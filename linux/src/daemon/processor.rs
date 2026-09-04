//! One-shot AI-processing child + daemon-side launcher.
//!
//!
//! Each job runs in a short-lived `--process` child (same binary) that loads
//! the AI stack, does one job, writes transcript.md/notes.md, and exits — so
//! the long-lived daemon stays lean.
//!
//! Protocol (child → daemon, one line each on stdout):
//!   `STATUS:<text>` progress for the job row
//!   `RESULT:<json>` final `[audio, transcript, notes]` paths (auto-title may
//!                    have moved them); success
//!   `ERROR:<text>`  failure
//! Anything else on stdout is ignored. User cancellation is forceful so a
//! cancelled job cannot keep running; daemon shutdown uses the separate
//! graceful SIGTERM path below.

use std::io::BufRead;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;

use super::child_io::StderrTail;
use crate::config::defaults::{
    CHILD_ERROR_PREFIX, CHILD_RESULT_PREFIX, CHILD_STATUS_PREFIX, OPENAI_DEFAULT_BASE_URL,
};

/// Events decoded from one child's stdout protocol stream.
#[derive(Debug, Clone)]
pub enum ChildEvent {
    Status(String),
    Done(Vec<Option<String>>),
    Error(String),
}

/// Pure decoder for a single protocol line.
pub fn parse_child_line(line: &str) -> Option<ChildEvent> {
    if let Some(text) = line.strip_prefix(CHILD_STATUS_PREFIX) {
        return Some(ChildEvent::Status(text.to_string()));
    }
    if let Some(text) = line.strip_prefix(CHILD_RESULT_PREFIX) {
        let paths: Vec<Option<String>> = serde_json::from_str(text).unwrap_or_default();
        return Some(ChildEvent::Done(paths));
    }
    if let Some(text) = line.strip_prefix(CHILD_ERROR_PREFIX) {
        return Some(ChildEvent::Error(text.to_string()));
    }
    None
}

fn emit(prefix: &str, text: &str) {
    // One protocol line; collapse newlines so it stays single-line.
    println!("{prefix}{}", text.replace('\n', " "));
}

/// Entry for `gravaai --process [mode] <audio> <transcript> <notes>` where
/// `[mode]` is an optional `--transcribe-only` / `--summarize-only` flag
/// (default: full pipeline).
/// Internal daemon plumbing only: refuses to run unless spawned by the
/// daemon (see `core::run_mode::CHILD_ENV`). Returns the process exit code.
pub fn run_processor_child(args: &[String]) -> i32 {
    crate::utils::logging::setup_logging("process");
    if !crate::core::run_mode::child_allowed() {
        eprintln!("--process is internal daemon plumbing, not a user command.");
        eprintln!("Run the app normally (no flags) to open the graphical UI.");
        return 2;
    }
    let (mode, rest) = parse_process_args(args);
    let Some((audio, transcript, notes)) = rest else {
        emit(
            CHILD_ERROR_PREFIX,
            "processor: missing audio/transcript/notes arguments",
        );
        return 2;
    };
    let cfg = crate::config::settings::load();
    if cfg.openai_api_key.is_empty()
        && (cfg.transcription_service == "openai" || cfg.summarization_service == "openai")
        && cfg.openai_base_url == OPENAI_DEFAULT_BASE_URL
    {
        // Keep the child honest: without a key the provider calls would fail
        // deep inside with confusing errors. Summarize-only jobs only need
        // the chat service; transcribe-only jobs only need the STT service.
        let needs_key = match mode {
            crate::processing::pipeline::PipelineMode::SummarizeOnly => {
                cfg.summarization_service == "openai"
            }
            crate::processing::pipeline::PipelineMode::TranscribeOnly => {
                cfg.transcription_service == "openai"
            }
            crate::processing::pipeline::PipelineMode::Full => {
                cfg.transcription_service == "openai" || cfg.summarization_service == "openai"
            }
        };
        if needs_key {
            emit(
                CHILD_ERROR_PREFIX,
                "OpenAI-compatible API key is not configured. Please open Settings.",
            );
            return 1;
        }
    }
    let mut pipeline = crate::processing::pipeline::Pipeline::with_mode(
        cfg,
        Some(PathBuf::from(&audio)),
        Some(PathBuf::from(&transcript)),
        Some(PathBuf::from(&notes)),
        Some(Box::new(|msg: &str| emit(CHILD_STATUS_PREFIX, msg))),
        mode,
    );
    if let Err(e) = pipeline.run(None) {
        log::error!("Processor job failed: {e:#}");
        emit(CHILD_ERROR_PREFIX, &format!("{e:#}"));
        return 1;
    }
    let (a, t, n) = pipeline.output_paths();
    let opt = |p: Option<PathBuf>| p.map(|p| p.to_string_lossy().into_owned());
    emit(
        CHILD_RESULT_PREFIX,
        &serde_json::json!([opt(a), opt(t), opt(n)]).to_string(),
    );
    0
}

/// Pure CLI split for the `--process` child: an optional mode flag followed
/// by the three positional paths. Unit-tested.
pub fn parse_process_args(
    args: &[String],
) -> (
    crate::processing::pipeline::PipelineMode,
    Option<(String, String, String)>,
) {
    use crate::processing::pipeline::PipelineMode;
    let mut mode = PipelineMode::Full;
    let mut rest = args;
    match args.first().map(String::as_str) {
        Some("--transcribe-only") => {
            mode = PipelineMode::TranscribeOnly;
            rest = &args[1..];
        }
        Some("--summarize-only") => {
            mode = PipelineMode::SummarizeOnly;
            rest = &args[1..];
        }
        _ => {}
    }
    if rest.len() >= 3 {
        (
            mode,
            Some((rest[0].clone(), rest[1].clone(), rest[2].clone())),
        )
    } else {
        (mode, None)
    }
}

// ---------------------------------------------------------------------------
// Daemon side
// ---------------------------------------------------------------------------

/// A running processor child; `cancel()` kills it. Dropping without cancel
/// leaves the child running (the reader thread reaps it).
pub struct ProcessorHandle {
    child: Arc<std::sync::Mutex<Option<Child>>>,
    cancelled: Arc<AtomicBool>,
}

impl ProcessorHandle {
    pub fn cancel(&mut self) {
        self.cancelled.store(true, Ordering::SeqCst);
        if let Some(child) = self.child.lock().unwrap().as_mut() {
            let _ = child.kill();
        }
    }

    /// Ask a processor to exit as part of daemon shutdown.  The reader thread
    /// owns reaping; we only send SIGTERM and deliberately never escalate to
    /// SIGKILL during a normal application Quit.
    pub fn terminate_gracefully(&mut self) {
        self.cancelled.store(true, Ordering::SeqCst);
        if let Some(child) = self.child.lock().unwrap().as_ref() {
            request_pid_term(child.id());
        }
    }
}

#[cfg(unix)]
fn request_pid_term(pid: u32) {
    // SAFETY: this targets a child process whose pid is held by Child.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_pid_term(_pid: u32) {}

/// Spawns `--process` children and decodes their protocol stream on a reader
/// thread, forwarding [`ChildEvent`]s (tagged by job id) to the event loop.
pub struct ProcessorLauncher {
    tx: mpsc::UnboundedSender<(i64, ChildEvent)>,
}

impl ProcessorLauncher {
    pub fn new(tx: mpsc::UnboundedSender<(i64, ChildEvent)>) -> Self {
        Self { tx }
    }

    pub fn launch(
        &self,
        job_id: i64,
        audio: &str,
        transcript: &str,
        notes: &str,
        mode: crate::core::job::JobMode,
    ) -> ProcessorHandle {
        // Share the daemon's AppImage mount — do not re-exec $APPIMAGE.
        let exe = crate::utils::exe::internal_exe();
        let mut cmd = Command::new(&exe);
        cmd.env(crate::core::run_mode::CHILD_ENV, "1");
        match mode {
            crate::core::job::JobMode::TranscribeOnly => {
                cmd.arg("--transcribe-only");
            }
            crate::core::job::JobMode::SummarizeOnly => {
                cmd.arg("--summarize-only");
            }
            crate::core::job::JobMode::Full => {}
        }
        let mut child = cmd
            .args(["--process", audio, transcript, notes])
            // Capture stderr too: provider tooling (whisper-cli, TLS, ...) can
            // fail on stderr, so keep a tail to surface the real reason.
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .expect("spawn --process child");
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();
        let stderr_tail = StderrTail::new();
        let tail_clone = stderr_tail.clone();
        if let Some(err) = stderr {
            std::thread::Builder::new()
                .name(format!("process-{job_id}-stderr"))
                .spawn(move || {
                    for line in std::io::BufReader::new(err).lines().map_while(Result::ok) {
                        tail_clone.push(line);
                    }
                })
                .ok();
        }
        let child = Arc::new(std::sync::Mutex::new(Some(child)));
        let handle = ProcessorHandle {
            child: child.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
        };
        let cancelled = handle.cancelled.clone();
        let tx = self.tx.clone();
        std::thread::Builder::new()
            .name(format!("process-{job_id}-reader"))
            .spawn(move || {
                let mut done = false;
                let mut failed: Option<String> = None;
                if let Some(out) = stdout {
                    for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
                        match parse_child_line(&line) {
                            Some(ChildEvent::Done(paths)) => {
                                done = true;
                                let _ = tx.send((job_id, ChildEvent::Done(paths)));
                            }
                            Some(ChildEvent::Error(msg)) => {
                                failed = Some(msg.clone());
                                let _ = tx.send((job_id, ChildEvent::Error(msg)));
                            }
                            Some(ev) => {
                                let _ = tx.send((job_id, ev));
                            }
                            None => {}
                        }
                    }
                }
                // Reap.
                if let Some(mut c) = child.lock().unwrap().take() {
                    let _ = c.wait();
                }
                if cancelled.load(Ordering::SeqCst) {
                    return; // engine already handled the cancel
                }
                if done || failed.is_some() {
                    if failed.is_some() && !stderr_tail.lines().is_empty() {
                        log::warn!("Processor stderr: {}", stderr_tail.lines().join(" | "));
                    }
                    return;
                }
                let mut message = "processing exited without a result".to_string();
                if !stderr_tail.lines().is_empty() {
                    message = format!("{message} — {}", stderr_tail.tail());
                }
                let _ = tx.send((job_id, ChildEvent::Error(message)));
            })
            .ok();
        handle
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::job::JobMode;
    use crate::processing::pipeline::PipelineMode;

    #[test]
    fn protocol_lines() {
        assert!(matches!(
            parse_child_line("STATUS:hi"),
            Some(ChildEvent::Status(_))
        ));
        match parse_child_line(r#"RESULT:["a",null,"c"]"#) {
            Some(ChildEvent::Done(paths)) => {
                assert_eq!(
                    paths,
                    vec![Some("a".to_string()), None, Some("c".to_string())]
                );
            }
            other => panic!("unexpected {other:?}"),
        }
        assert!(matches!(
            parse_child_line("ERROR:boom"),
            Some(ChildEvent::Error(_))
        ));
        assert!(parse_child_line("random log line").is_none());
    }

    #[test]
    fn process_args_split_mode_flag() {
        let none = |s: &str| s.to_string();
        // Default (legacy) positional form.
        let (mode, rest) = parse_process_args(&[none("a"), none("t"), none("n")]);
        assert_eq!(mode, PipelineMode::Full);
        assert_eq!(rest.unwrap(), (none("a"), none("t"), none("n")));
        // Transcribe-only flag.
        let (mode, rest) =
            parse_process_args(&[none("--transcribe-only"), none("a"), none("t"), none("n")]);
        assert_eq!(mode, PipelineMode::TranscribeOnly);
        assert_eq!(rest.unwrap(), (none("a"), none("t"), none("n")));
        // Summarize-only flag.
        let (mode, rest) =
            parse_process_args(&[none("--summarize-only"), none("a"), none("t"), none("n")]);
        assert_eq!(mode, PipelineMode::SummarizeOnly);
        assert_eq!(rest.unwrap(), (none("a"), none("t"), none("n")));
        // Too few args → no paths.
        let (mode, rest) = parse_process_args(&[none("--transcribe-only"), none("a")]);
        assert_eq!(mode, PipelineMode::TranscribeOnly);
        assert!(rest.is_none());
        let (_, rest) = parse_process_args(&[]);
        assert!(rest.is_none());
        let _ = JobMode::Full;
    }

    #[test]
    fn job_mode_maps_to_pipeline_mode_flag() {
        // The launcher must forward every JobMode as the matching CLI flag.
        for (job_mode, flag) in [
            (JobMode::Full, None),
            (JobMode::TranscribeOnly, Some("--transcribe-only")),
            (JobMode::SummarizeOnly, Some("--summarize-only")),
        ] {
            let mut extra: Vec<String> = Vec::new();
            if let Some(flag) = flag {
                extra.push(flag.to_string());
            }
            extra.push("a".into());
            extra.push("t".into());
            extra.push("n".into());
            let (mode, rest) = parse_process_args(&extra);
            match job_mode {
                JobMode::Full => assert_eq!(mode, PipelineMode::Full),
                JobMode::TranscribeOnly => assert_eq!(mode, PipelineMode::TranscribeOnly),
                JobMode::SummarizeOnly => assert_eq!(mode, PipelineMode::SummarizeOnly),
            }
            assert!(rest.is_some(), "{job_mode:?} must still carry paths");
        }
    }
}
