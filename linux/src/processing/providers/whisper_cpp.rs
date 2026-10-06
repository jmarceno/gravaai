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
            // `-di` stereo diarization: speaker 0 = left = the local mic,
            // 1 = right = system audio (remote participants); "?" = unsure.
            let label = match seg.get("speaker").and_then(|v| v.as_str()) {
                Some("0") => "**Me:** ",
                Some("1") => "**Remote:** ",
                _ => "",
            };
            lines.push(format!("[{h:02}:{m:02}:{s:02}] {label}{text}"));
        }
    }
    lines.join("\n")
}

/// whisper-cli argv: JSON output to `<out_base>.json`, language
/// auto-detection (whisper defaults to English, which garbles or translates
/// every other language) and no console progress spam. Pure; unit-tested.
pub fn whisper_command(
    binary: &Path,
    model: &Path,
    wav: &Path,
    out_base: &Path,
    threads: usize,
) -> Vec<String> {
    vec![
        binary.to_string_lossy().into_owned(),
        "-m".into(),
        model.to_string_lossy().into_owned(),
        "-f".into(),
        wav.to_string_lossy().into_owned(),
        "-l".into(),
        "auto".into(),
        "-t".into(),
        threads.max(1).to_string(),
        "-np".into(),
        "-pp".into(),
        // Stereo recordings keep mic (left) and system audio (right) apart;
        // whisper labels each segment by the louder channel.
        "-di".into(),
        "-oj".into(),
        "-of".into(),
        out_base.to_string_lossy().into_owned(),
    ]
}

/// Decode any recording into the 16 kHz stereo WAV whisper.cpp expects
/// (stereo so `-di` can tell the mic from system audio), run
/// whisper-cli and return the JSON it wrote.
fn run_whisper_cli(
    binary: &Path,
    model: &Path,
    audio: &Path,
    on_status: Option<&dyn Fn(&str)>,
) -> anyhow::Result<String> {
    let work = tempfile::tempdir()?;
    let wav = work.path().join("audio.wav");
    let ffmpeg = crate::utils::exe::runtime_program("ffmpeg");
    let out = std::process::Command::new(&ffmpeg)
        .args(["-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i"])
        .arg(audio)
        .args(["-vn", "-ac", "2", "-ar", "16000", "-c:a", "pcm_s16le"])
        .arg(&wav)
        .output()
        .map_err(|e| anyhow::anyhow!("Could not run ffmpeg to decode the audio: {e}"))?;
    if !out.status.success() {
        anyhow::bail!(
            "ffmpeg could not decode {}: {}",
            audio.display(),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .min(16);
    let out_base = work.path().join("out");
    let cmd = whisper_command(binary, model, &wav, &out_base, threads);
    log::info!("Running whisper.cpp: {}", cmd.join(" "));
    if let Some(cb) = on_status {
        cb("Transcribing with whisper.cpp (this can take a while for long meetings)…");
    }
    // The prebuilt engine ships its own `.so` libraries next to the binary;
    // point the loader there.
    let lib_dir = binary
        .parent()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default();
    let mut child = std::process::Command::new(&cmd[0])
        .args(&cmd[1..])
        .env("LD_LIBRARY_PATH", &lib_dir)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| anyhow::anyhow!("Could not start whisper-cli: {e}"))?;
    // Stream stderr: `-pp` progress lines become job status updates so a
    // long meeting visibly advances instead of looking hung.
    let mut tail: std::collections::VecDeque<String> = std::collections::VecDeque::new();
    let mut last_pct = None;
    if let Some(err) = child.stderr.take() {
        use std::io::BufRead;
        for line in std::io::BufReader::new(err).lines().map_while(Result::ok) {
            if let Some(pct) = parse_progress(&line) {
                if last_pct != Some(pct) {
                    last_pct = Some(pct);
                    if let Some(cb) = on_status {
                        cb(&format!("Transcribing with whisper.cpp… {pct}%"));
                    }
                }
                continue;
            }
            tail.push_back(line);
            if tail.len() > 6 {
                tail.pop_front();
            }
        }
    }
    let status = child.wait()?;
    if !status.success() {
        let tail: Vec<String> = tail.into_iter().collect();
        anyhow::bail!("whisper-cli failed ({status}): {}", tail.join(" | "));
    }
    let json = out_base.with_extension("json");
    std::fs::read_to_string(&json).map_err(|e| {
        anyhow::anyhow!("whisper-cli produced no transcript ({}): {e}", json.display())
    })
}

/// Parse a whisper-cli `-pp` line (`…progress =  42%`) into a percentage.
pub fn parse_progress(line: &str) -> Option<u8> {
    let rest = line.split("progress =").nth(1)?;
    rest.trim().trim_end_matches('%').trim().parse().ok()
}

/// The language whisper detected (`{"result":{"language":"pt"}}`), if any.
pub fn parse_whisper_cpp_language(raw: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(raw).ok()?;
    let lang = v.pointer("/result/language")?.as_str()?.trim();
    (!lang.is_empty() && lang != "auto").then(|| lang.to_string())
}

pub type RunnerFn = Box<dyn Fn(&[String]) -> anyhow::Result<String> + Send>;

fn default_binary() -> PathBuf {
    crate::services::whisper_cpp_service::whisper_cpp_binary()
}

fn default_model_path(model: &str) -> PathBuf {
    crate::services::whisper_cpp_service::WhisperCppStatusChecker::model_path(model)
}

pub struct WhisperCppProvider {
    /// Language whisper detected on the last run (`result.language`).
    detected_language: std::sync::Mutex<Option<String>>,
    model_name: String,
    binary: Option<PathBuf>,
    model_path: Option<PathBuf>,
    runner: Option<RunnerFn>,
}

impl WhisperCppProvider {
    pub fn new(model: &str) -> Self {
        Self {
            detected_language: std::sync::Mutex::new(None),
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
        if self.runner.is_none() && !model_file.is_file() {
            anyhow::bail!(
                "whisper.cpp model '{}' is not downloaded. Download it from Settings → Models.",
                self.model_name
            );
        }
        let raw = match &self.runner {
            Some(r) => {
                let cmd = whisper_command(&binary, &model_file, audio_path, Path::new("out"), 4);
                r(&cmd)?
            }
            None => run_whisper_cli(&binary, &model_file, audio_path, on_status)?,
        };
        *self.detected_language.lock().unwrap() = parse_whisper_cpp_language(&raw);
        Ok(parse_whisper_cpp_output(&raw))
    }

    /// Language code whisper detected on the last `transcribe` (e.g. "pt").
    pub fn detected_language(&self) -> Option<String> {
        self.detected_language.lock().unwrap().clone()
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
    fn diarized_segments_get_speaker_labels() {
        let raw = r#"{"transcription":[
            {"offsets":{"from":0,"to":1},"text":" Oi","speaker":"0"},
            {"offsets":{"from":2000,"to":3},"text":" Olá","speaker":"1"},
            {"offsets":{"from":4000,"to":5},"text":" Hm","speaker":"?"}]}"#;
        assert_eq!(
            parse_whisper_cpp_output(raw),
            "[00:00:00] **Me:** Oi\n[00:00:02] **Remote:** Olá\n[00:00:04] Hm"
        );
    }

    #[test]
    fn detected_language_is_read_from_the_result() {
        let raw = r#"{"result":{"language":"pt"},"transcription":[]}"#;
        assert_eq!(parse_whisper_cpp_language(raw).as_deref(), Some("pt"));
        assert_eq!(parse_whisper_cpp_language("not json"), None);
        assert_eq!(parse_whisper_cpp_language(r#"{"transcription":[]}"#), None);
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

    #[test]
    fn command_auto_detects_language_and_writes_json_to_a_file() {
        let cmd = whisper_command(
            Path::new("/e/whisper-cli"),
            Path::new("/m/ggml-small.bin"),
            Path::new("/w/audio.wav"),
            Path::new("/w/out"),
            8,
        );
        let joined = cmd.join(" ");
        assert!(joined.contains("-l auto"), "{joined}");
        assert!(joined.contains("-oj -of /w/out"), "{joined}");
        assert!(joined.contains("-t 8"), "{joined}");
        assert!(cmd.iter().any(|a| a == "-di"), "{joined}");
        // Regression: `--output-file -` wrote a file literally named `-.json`.
        assert!(!cmd.iter().any(|a| a == "-"), "{joined}");
    }

    #[test]
    fn progress_lines_parse() {
        assert_eq!(
            parse_progress("whisper_print_progress_callback: progress =  93%"),
            Some(93)
        );
        assert_eq!(parse_progress("whisper_print_progress_callback: progress = 100%"), Some(100));
        assert_eq!(parse_progress("output_json: saving output to 'out.json'"), None);
    }

    #[test]
    fn missing_model_gives_download_guidance() {
        let dir = tempfile::tempdir().unwrap();
        let fake_bin = dir.path().join("whisper-cli");
        std::fs::write(&fake_bin, b"x").unwrap();
        let mut p = WhisperCppProvider::new("small").with_binary(fake_bin);
        p.model_path = Some(dir.path().join("ggml-small.bin"));
        let err = p.transcribe(Path::new("/tmp/x.mp3"), None).unwrap_err();
        assert!(format!("{err}").contains("not downloaded"), "{err}");
    }
}
