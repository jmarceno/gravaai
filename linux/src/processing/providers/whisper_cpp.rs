//! Local speech-to-text via a whisper.cpp `whisper-cli` binary.
//!
//! Output matches the shared `[HH:MM:SS] text` transcript format.

use std::path::{Path, PathBuf};

/// Convert whisper.cpp JSON (`--output-json-full` to stdout) into the shared
/// `[HH:MM:SS] text` transcript format. Pure and unit-testable.
///
/// The JSON shape is `{"transcription": [{"offsets": {"from": ms, "to": ms},
/// "text": "..."}, ...]}` with millisecond offsets. Falls back to the trimmed
/// raw text when it is not valid JSON.
pub fn parse_whisper_cpp_output(raw: &str) -> String {
    let data: serde_json::Value = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return raw.trim().to_string(),
    };
    let segments = data
        .get("transcription")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let mut lines = Vec::new();
    for seg in &segments {
        let start_ms = seg
            .get("offsets")
            .and_then(|o| o.get("from"))
            .and_then(|v| {
                v.as_u64()
                    .or_else(|| v.as_i64().and_then(|i| u64::try_from(i).ok()))
            })
            .unwrap_or(0);
        let start = start_ms / 1000;
        let (h, m, s) = (start / 3600, start % 3600 / 60, start % 60);
        let text = seg
            .get("text")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        if !text.is_empty() {
            lines.push(format!("[{h:02}:{m:02}:{s:02}] {text}"));
        }
    }
    lines.join("\n")
}

pub type RunnerFn = Box<dyn Fn(&[String]) -> anyhow::Result<String> + Send>;

fn default_binary() -> PathBuf {
    crate::services::whisper_cpp_service::whisper_cpp_binary()
}

fn default_model_path(model: &str) -> PathBuf {
    crate::services::whisper_cpp_service::WhisperCppStatusChecker::model_path(model)
}

pub struct WhisperCppProvider {
    model_name: String,
    binary: Option<PathBuf>,
    model_path: Option<PathBuf>,
    runner: Option<RunnerFn>,
}

impl WhisperCppProvider {
    pub fn new(model: &str) -> Self {
        Self {
            model_name: model.to_string(),
            binary: None,
            model_path: None,
            runner: None,
        }
    }

    /// Test seam: point at a fixture binary.
    #[cfg(test)]
    pub fn with_binary(mut self, path: PathBuf) -> Self {
        self.binary = Some(path);
        self
    }

    /// Test seam mirroring the injected `runner` used by unit tests.
    #[cfg(test)]
    pub fn with_runner(
        mut self,
        runner: impl Fn(&[String]) -> anyhow::Result<String> + Send + 'static,
    ) -> Self {
        self.runner = Some(Box::new(runner));
        self
    }

    pub fn transcribe(
        &self,
        audio_path: &Path,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        if let Some(cb) = on_status {
            cb(&format!(
                "Transcribing with whisper.cpp ({})…",
                self.model_name
            ));
        }
        let binary = self.binary.clone().unwrap_or_else(default_binary);
        if !binary.is_file() {
            anyhow::bail!(
                "whisper.cpp engine is not installed. Install it from Settings → Models."
            );
        }
        let model_file = self
            .model_path
            .clone()
            .unwrap_or_else(|| default_model_path(&self.model_name));
        let cmd = vec![
            binary.to_string_lossy().into_owned(),
            "-m".into(),
            model_file.to_string_lossy().into_owned(),
            "-f".into(),
            audio_path.to_string_lossy().into_owned(),
            "--output-json-full".into(),
            "--output-file".into(),
            "-".into(),
        ];
        log::info!("Running whisper.cpp: {}", cmd.join(" "));
        let raw = match &self.runner {
            Some(r) => r(&cmd)?,
            None => {
                // The prebuilt engine ships its own `.so` libraries next to
                // the binary; point the loader there.
                let lib_dir = binary
                    .parent()
                    .map(|p| p.to_string_lossy().into_owned())
                    .unwrap_or_default();
                let out = std::process::Command::new(&cmd[0])
                    .args(&cmd[1..])
                    .env("LD_LIBRARY_PATH", &lib_dir)
                    .output()?;
                if !out.status.success() {
                    anyhow::bail!("whisper-cli failed (code {})", out.status);
                }
                String::from_utf8_lossy(&out.stdout).into_owned()
            }
        };
        Ok(parse_whisper_cpp_output(&raw))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_segments() {
        let raw = r#"{"transcription":[{"offsets":{"from":5000,"to":9000},"text":" Hello "},{"offsets":{"from":9000,"to":12000},"text":"world"}]}"#;
        assert_eq!(
            parse_whisper_cpp_output(raw),
            "[00:00:05] Hello\n[00:00:09] world"
        );
    }

    #[test]
    fn falls_back_to_raw() {
        assert_eq!(parse_whisper_cpp_output("  plain text \n"), "plain text");
        assert_eq!(parse_whisper_cpp_output(""), "");
    }

    #[test]
    fn skips_empty_segments() {
        let raw = r#"{"transcription":[{"offsets":{"from":0,"to":1},"text":"  "}]}"#;
        assert_eq!(parse_whisper_cpp_output(raw), "");
    }

    #[test]
    fn transcribe_flow_with_fake_runner() {
        let dir = tempfile::tempdir().unwrap();
        let fake_bin = dir.path().join("whisper-cli");
        std::fs::write(&fake_bin, b"x").unwrap();
        let p = WhisperCppProvider::new("small")
            .with_binary(fake_bin)
            .with_runner(|_| {
                Ok(
                    r#"{"transcription":[{"offsets":{"from":1000,"to":2000},"text":"hi"}]}"#
                        .to_string(),
                )
            });
        let t = p.transcribe(Path::new("/tmp/x.mp3"), None).unwrap();
        assert_eq!(t, "[00:00:01] hi");
    }
}
