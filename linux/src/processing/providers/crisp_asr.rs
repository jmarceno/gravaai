//! Local speech-to-text via a CrispASR `crispasr` binary.
//!
//! Experimental third transcription backend (Nemotron 3.5 ASR 0.6B Q8 by
//! default). Output matches the shared `[HH:MM:SS] text` transcript format.
//!
//! Lifecycle mirrors whisper.cpp: each transcription is one short-lived CLI
//! invocation inside the `--process` child — the "service" starts on
//! transcribe and exits on completion, so there is no persistent daemon to
//! manage. GPU memory is freed the same way (the factory wrapper unloads
//! Ollama models first).

use std::path::{Path, PathBuf};

/// Convert CrispASR JSON (`-ojf` full JSON) into the shared `[HH:MM:SS] text`
/// transcript format. Pure and unit-testable.
///
/// The JSON shape is `{"transcription": [{"offsets": {"from": ms, "to": ms},
/// "text": "..."}, ...]}` with millisecond offsets — the same shape the
/// whisper.cpp provider parses. Falls back to the trimmed raw text (minus
/// `crispasr:` log lines) when it is not valid JSON.
pub fn parse_crisp_asr_output(raw: &str) -> String {
    if let Ok(data) = serde_json::from_str::<serde_json::Value>(raw) {
        if data
            .get("transcription")
            .and_then(|v| v.as_array())
            .is_some()
        {
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
                let text = strip_language_tags(text);
                if !text.is_empty() {
                    lines.push(format!("[{h:02}:{m:02}:{s:02}] {text}"));
                }
            }
            return lines.join("\n");
        }
    }
    // Plain-text stdout: drop progress/log lines, keep transcript lines.
    let kept: Vec<String> = raw
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("crispasr:"))
        .map(strip_language_tags)
        .filter(|l| !l.is_empty())
        .collect();
    kept.join("\n")
}

pub type RunnerFn = Box<dyn Fn(&[String]) -> anyhow::Result<String> + Send>;

/// Strip model-emitted language-tag tokens (e.g. `<en-US>`) from transcript
/// text. The Nemotron decoder emits these inline; they are noise in meeting
/// notes. Pure and unit-testable.
fn strip_language_tags(text: &str) -> String {
    text.split_whitespace()
        .filter(|tok| {
            let t = tok.trim_matches(|c: char| ".,!?;:\"'()[]".contains(c));
            !(t.starts_with('<')
                && t.ends_with('>')
                && t.contains('-')
                && t.len() < 16
                && t[1..t.len() - 1]
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-'))
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn default_binary() -> PathBuf {
    crate::services::crisp_asr_service::crisp_asr_binary()
}

fn default_model_path(model: &str) -> PathBuf {
    crate::services::crisp_asr_service::CrispAsrStatusChecker::model_path(model)
}

pub struct CrispAsrProvider {
    model_name: String,
    backend: String,
    binary: Option<PathBuf>,
    model_path: Option<PathBuf>,
    runner: Option<RunnerFn>,
}

impl CrispAsrProvider {
    pub fn new(model: &str, backend: &str) -> Self {
        Self {
            model_name: model.to_string(),
            backend: backend.to_string(),
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

    /// Test seam: point at a fixture model file.
    #[cfg(test)]
    pub fn with_model_path(mut self, path: PathBuf) -> Self {
        self.model_path = Some(path);
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

    /// Map the configured backend to crispasr's `--gpu-backend` flag.
    /// `auto` (and anything unresolvable) omits the flag and lets crispasr
    /// pick; otherwise the concrete `cpu`/`vulkan`/`cuda` is passed through.
    /// Pure and unit-testable.
    pub fn gpu_backend_flag(backend: &str) -> Option<String> {
        match crate::services::crisp_asr_service::resolve_backend(backend) {
            Ok(flavor) if flavor == "cpu" || flavor == "vulkan" || flavor == "cuda" => {
                if backend == "auto" {
                    None
                } else {
                    Some(flavor)
                }
            }
            Ok(_) => None,
            Err(_) => None,
        }
    }

    pub fn transcribe(
        &self,
        audio_path: &Path,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        if let Some(cb) = on_status {
            cb(&format!(
                "Transcribing with CrispASR ({})…",
                self.model_name
            ));
        }
        let binary = self.binary.clone().unwrap_or_else(default_binary);
        if !binary.is_file() {
            anyhow::bail!("CrispASR engine is not installed. Install it from Settings → Models.");
        }
        let model_file = self
            .model_path
            .clone()
            .unwrap_or_else(|| default_model_path(&self.model_name));
        if !model_file.is_file() {
            anyhow::bail!("CrispASR model is not downloaded. Download it from Settings → Models.");
        }
        // JSON sidecar base inside a temp dir (removed after the read).
        let workdir = tempfile::tempdir()?;
        let out_base = workdir.path().join("out");
        let out_json = out_base.with_extension("json");
        // NOTE: `-ojf` writes `<base>.json`; keep the base extensionless so
        // the sidecar lands exactly at `out.json`.
        let mut cmd = vec![
            binary.to_string_lossy().into_owned(),
            "--backend".into(),
            "nemotron".into(),
            "-m".into(),
            model_file.to_string_lossy().into_owned(),
            "-f".into(),
            audio_path.to_string_lossy().into_owned(),
            "-ojf".into(),
            "-of".into(),
            out_base.to_string_lossy().into_owned(),
        ];
        if let Some(flavor) = Self::gpu_backend_flag(&self.backend) {
            cmd.push("--gpu-backend".into());
            cmd.push(flavor);
        }
        log::info!("Running CrispASR: {}", cmd.join(" "));
        let stdout = match &self.runner {
            Some(r) => r(&cmd)?,
            None => {
                let lib_dir = binary
                    .parent()
                    .map(|p| p.to_string_lossy().into_owned())
                    .unwrap_or_default();
                let out = std::process::Command::new(&cmd[0])
                    .args(&cmd[1..])
                    .env("LD_LIBRARY_PATH", &lib_dir)
                    .output()?;
                if !out.status.success() {
                    let stderr = String::from_utf8_lossy(&out.stderr);
                    let tail: String = stderr.trim().chars().take(500).collect();
                    if tail.is_empty() {
                        anyhow::bail!("crispasr failed (code {})", out.status);
                    }
                    anyhow::bail!("crispasr failed (code {}): {tail}", out.status);
                }
                String::from_utf8_lossy(&out.stdout).into_owned()
            }
        };
        // Prefer the JSON sidecar; fall back to stdout text.
        let raw = match std::fs::read_to_string(&out_json) {
            Ok(text) => text,
            Err(_) => stdout,
        };
        Ok(parse_crisp_asr_output(&raw))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_segments() {
        let raw = r#"{"transcription":[{"offsets":{"from":5000,"to":9000},"text":" Hello "},{"timestamps":{"from":"00:00:09,000","to":"00:00:12,000"},"offsets":{"from":9000,"to":12000},"text":"world"}]}"#;
        assert_eq!(
            parse_crisp_asr_output(raw),
            "[00:00:05] Hello\n[00:00:09] world"
        );
    }

    #[test]
    fn falls_back_to_raw_text_without_log_lines() {
        assert_eq!(parse_crisp_asr_output("  plain text \n"), "plain text");
        assert_eq!(parse_crisp_asr_output(""), "");
        let mixed =
            "crispasr: transcribed 3.2s audio in 0.32s (10.1x realtime)\nThe quick brown fox\n";
        assert_eq!(parse_crisp_asr_output(mixed), "The quick brown fox");
    }

    #[test]
    fn strips_language_tags() {
        let raw = r#"{"transcription":[{"offsets":{"from":1000,"to":2000},"text":"Hello <en-US> world"}]}"#;
        assert_eq!(parse_crisp_asr_output(raw), "[00:00:01] Hello world");
        assert_eq!(
            parse_crisp_asr_output("line one <de-DE>\n<en-US>\nline two\n"),
            "line one\nline two"
        );
        // Real words in angle brackets-ish form are kept.
        assert_eq!(strip_language_tags("a < b and c > d"), "a < b and c > d");
        assert_eq!(strip_language_tags(""), "");
    }

    #[test]
    fn skips_empty_segments() {
        let raw = r#"{"transcription":[{"offsets":{"from":0,"to":1},"text":"  "}]}"#;
        assert_eq!(parse_crisp_asr_output(raw), "");
    }

    #[test]
    fn gpu_flag_mapping() {
        // Explicit flavors pass through; auto lets crispasr decide.
        assert_eq!(
            CrispAsrProvider::gpu_backend_flag("cpu"),
            Some("cpu".into())
        );
        assert_eq!(
            CrispAsrProvider::gpu_backend_flag("vulkan"),
            Some("vulkan".into())
        );
        assert_eq!(
            CrispAsrProvider::gpu_backend_flag("cuda"),
            Some("cuda".into())
        );
        assert_eq!(CrispAsrProvider::gpu_backend_flag("auto"), None);
        assert_eq!(CrispAsrProvider::gpu_backend_flag("bogus"), None);
    }

    #[test]
    fn transcribe_flow_with_fake_runner() {
        let dir = tempfile::tempdir().unwrap();
        let fake_bin = dir.path().join("crispasr");
        std::fs::write(&fake_bin, b"x").unwrap();
        let fake_model = dir.path().join("model.gguf");
        std::fs::write(&fake_model, b"m").unwrap();
        let p = CrispAsrProvider::new("nemotron-3.5-asr-0.6b-q8_0", "cpu")
            .with_binary(fake_bin)
            .with_model_path(fake_model)
            .with_runner(|cmd| {
                // The provider must request full JSON plus an output base and
                // an explicit nemotron backend.
                assert!(cmd.iter().any(|a| a == "-ojf"), "missing -ojf: {cmd:?}");
                assert!(
                    cmd.iter().any(|a| a == "nemotron"),
                    "missing backend: {cmd:?}"
                );
                let base = cmd
                    .windows(2)
                    .find(|w| w[0] == "-of")
                    .map(|w| w[1].clone())
                    .expect("missing -of");
                std::fs::write(
                    format!("{base}.json"),
                    r#"{"transcription":[{"offsets":{"from":1000,"to":2000},"text":"hi"}]}"#,
                )
                .unwrap();
                Ok("crispasr: transcribed 1s audio".to_string())
            });
        let t = p.transcribe(Path::new("/tmp/x.mp3"), None).unwrap();
        assert_eq!(t, "[00:00:01] hi");
        // The GPU flag must be forwarded for explicit backends.
        let cmd_seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let cmd_clone = cmd_seen.clone();
        let dir2 = tempfile::tempdir().unwrap();
        let fake_bin2 = dir2.path().join("crispasr");
        std::fs::write(&fake_bin2, b"x").unwrap();
        let fake_model2 = dir2.path().join("model.gguf");
        std::fs::write(&fake_model2, b"m").unwrap();
        let p2 = CrispAsrProvider::new("m", "vulkan")
            .with_binary(fake_bin2)
            .with_model_path(fake_model2)
            .with_runner(move |cmd: &[String]| {
                *cmd_clone.lock().unwrap() = cmd.to_vec();
                Ok("hello".to_string())
            });
        assert_eq!(
            p2.transcribe(Path::new("/tmp/x.mp3"), None).unwrap(),
            "hello"
        );
        let cmd = cmd_seen.lock().unwrap().clone();
        let pos = cmd
            .iter()
            .position(|a| a == "--gpu-backend")
            .expect("gpu flag");
        assert_eq!(cmd[pos + 1], "vulkan");
    }

    #[test]
    fn missing_engine_and_model_fail_with_guidance() {
        let dir = tempfile::tempdir().unwrap();
        let p = CrispAsrProvider::new("m", "cpu")
            .with_binary(dir.path().join("nope-crispasr"))
            .with_model_path(dir.path().join("model.gguf"));
        let err = p.transcribe(Path::new("/tmp/x.mp3"), None).unwrap_err();
        assert!(format!("{err:#}").contains("Settings → Models"));
        let fake_bin = dir.path().join("crispasr");
        std::fs::write(&fake_bin, b"x").unwrap();
        let p2 = CrispAsrProvider::new("m", "cpu")
            .with_binary(fake_bin)
            .with_model_path(dir.path().join("missing.gguf"));
        let err = p2.transcribe(Path::new("/tmp/x.mp3"), None).unwrap_err();
        assert!(format!("{err:#}").contains("Settings → Models"));
    }
}
