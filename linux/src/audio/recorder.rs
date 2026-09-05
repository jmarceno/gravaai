//! ffmpeg-backed audio recorder with pause/resume segments.
//!

use std::io::BufRead;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::devices::{
    get_default_sink, get_default_source, list_sources, missing_sources, monitor_of_sink,
};
use super::levels::LevelMonitor;
use super::mixer::{
    build_ffmpeg_command, build_ffmpeg_command_mic_only, build_ffmpeg_command_multi,
};
use crate::core::recording_controller::RecorderBackend;
use crate::utils::exe::runtime_program;

pub fn segment_path_for(output_path: &std::path::Path, index: u64) -> PathBuf {
    let stem = output_path
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let ext = output_path
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    output_path.with_file_name(format!("{stem}_seg{index:03}{ext}"))
}

type TickCb = Arc<Mutex<Option<Box<dyn Fn(u64) + Send>>>>;
type ErrorCb = Arc<Mutex<Option<Box<dyn Fn(String) + Send>>>>;
type LevelCb = Arc<Mutex<Option<Box<dyn Fn(f32) + Send>>>>;

fn emit_tick(cb: &TickCb, elapsed: u64) {
    if let Some(f) = cb.lock().unwrap().as_ref() {
        f(elapsed);
    }
}

fn emit_error(cb: &ErrorCb, msg: String) {
    if let Some(f) = cb.lock().unwrap().as_ref() {
        f(msg);
    }
}

struct Flags {
    paused: AtomicBool,
    stop: AtomicBool,
    elapsed: AtomicU64,
}

/// Deduplicate a Custom-mode selection, preserving the user's order.
/// Pure so double-toggled devices never spawn duplicate ffmpeg inputs.
pub fn dedupe_sources(selected: &[String]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    selected
        .iter()
        .filter(|s| seen.insert((*s).clone()))
        .cloned()
        .collect()
}

/// Full recording lifecycle over a single ffmpeg subprocess per segment.
///
/// Pause terminates ffmpeg cleanly (saving the segment), resume spawns a new
/// ffmpeg writing the next segment, and stop concatenates all segments with
/// ffmpeg's concat demuxer so paused intervals are excluded.
///
/// Modes: `headphones` (default mic + default sink monitor),
/// `speaker` (default mic only) and `custom` (the explicit
/// [`Recorder::custom_sources`] list — every selected device is recorded).
pub struct Recorder {
    output_path: PathBuf,
    mode: String,
    custom_sources: Vec<String>,
    quality: String,
    on_tick: TickCb,
    on_error: ErrorCb,
    on_level: LevelCb,
    child: Arc<Mutex<Option<Child>>>,
    level_monitor: Option<LevelMonitor>,
    segments: Vec<PathBuf>,
    segment_index: u64,
    sources: Vec<String>,
    flags: Arc<Flags>,
}

impl Recorder {
    pub fn new(
        output_path: PathBuf,
        mode: String,
        custom_sources: Vec<String>,
        quality: String,
        on_tick: Option<Box<dyn Fn(u64) + Send>>,
        on_error: Option<Box<dyn Fn(String) + Send>>,
    ) -> Self {
        Self {
            output_path,
            mode,
            custom_sources,
            quality,
            on_tick: Arc::new(Mutex::new(on_tick)),
            on_error: Arc::new(Mutex::new(on_error)),
            on_level: Arc::new(Mutex::new(None)),
            child: Arc::new(Mutex::new(None)),
            level_monitor: None,
            segments: Vec::new(),
            segment_index: 0,
            sources: Vec::new(),
            flags: Arc::new(Flags {
                paused: AtomicBool::new(false),
                stop: AtomicBool::new(false),
                elapsed: AtomicU64::new(0),
            }),
        }
    }

    /// Live-level callback for the recording meter (0.0–1.0, ~10 Hz).
    ///
    /// Wired by the daemon factory to the event loop; unit tests and other
    /// embeds simply leave it unset. Monitor failures never surface here —
    /// the meter just stays silent at 0.
    pub fn set_on_level(&mut self, cb: impl Fn(f32) + Send + 'static) {
        *self.on_level.lock().unwrap() = Some(Box::new(cb));
    }

    fn start_level_monitor(&mut self) {
        let cb = self.on_level.clone();
        let sources = self.sources.clone();
        self.level_monitor = Some(LevelMonitor::start(sources, move |level| {
            if let Some(f) = cb.lock().unwrap().as_ref() {
                f(level);
            }
        }));
    }

    fn stop_level_monitor(&mut self) {
        if let Some(mut monitor) = self.level_monitor.take() {
            monitor.stop();
        } else if let Some(f) = self.on_level.lock().unwrap().as_ref() {
            f(0.0);
        }
    }

    fn start_ffmpeg_segment(&mut self) -> anyhow::Result<()> {
        let seg = segment_path_for(&self.output_path, self.segment_index);
        if let Some(parent) = seg.parent() {
            std::fs::create_dir_all(parent)?;
        }
        if self.sources.is_empty() {
            anyhow::bail!("Audio devices are not resolved — cannot start a segment");
        }
        // Custom mode records the explicit multi-source list; the fixed modes
        // keep their exact historical commands.
        let cmd: Vec<String> = if self.mode == "custom" {
            build_ffmpeg_command_multi(&self.sources, &seg, &self.quality)
        } else if self.mode == "speaker" {
            let mic = self.sources.first().ok_or_else(|| {
                anyhow::anyhow!("Audio devices are not resolved — cannot start a segment")
            })?;
            build_ffmpeg_command_mic_only(mic, &seg, &self.quality)
        } else {
            let (mic, mon) = match self.sources.as_slice() {
                [mic, mon, ..] => (mic, mon),
                _ => anyhow::bail!("Audio devices are not resolved — cannot start a segment"),
            };
            build_ffmpeg_command(mic, mon, &seg, &self.quality)
        };
        let mut child = Command::new(runtime_program(&cmd[0]))
            .args(&cmd[1..])
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::NotFound {
                    anyhow::anyhow!("ffmpeg not found. Please install ffmpeg.")
                } else {
                    anyhow::anyhow!("failed to start ffmpeg: {e}")
                }
            })?;
        // Drain stderr on a thread (avoids pipe backpressure) at debug level.
        if let Some(stderr) = child.stderr.take() {
            let idx = self.segment_index;
            std::thread::Builder::new()
                .name(format!("ffmpeg-stderr-{idx}"))
                .spawn(move || {
                    let reader = std::io::BufReader::new(stderr);
                    for line in reader.lines().map_while(Result::ok) {
                        if !line.trim().is_empty() {
                            log::debug!("ffmpeg[seg{idx}]: {line}");
                        }
                    }
                })
                .ok();
        }
        self.segments.push(seg.clone());
        *self.child.lock().unwrap() = Some(child);
        log::info!("ffmpeg segment {} → {}", self.segment_index, seg.display());

        // Monitor thread: report unexpected ffmpeg death (device loss etc.).
        // Intentional exits (pause sets paused, stop sets stop) are ignored.
        let child_ref = self.child.clone();
        let flags = self.flags.clone();
        let on_error = self.on_error.clone();
        let idx = self.segment_index;
        std::thread::Builder::new()
            .name(format!("ffmpeg-monitor-{idx}"))
            .spawn(move || {
                let code: Option<i32> = loop {
                    // Poll: try_wait needs the lock only briefly.
                    let done = match child_ref.lock().unwrap().as_mut() {
                        Some(c) => match c.try_wait() {
                            Ok(Some(status)) => Some(status.code().unwrap_or(-1)),
                            Ok(None) => None,
                            Err(_) => Some(-1),
                        },
                        None => return, // reaped by stop/pause — intentional
                    };
                    if let Some(code) = done {
                        break Some(code);
                    }
                    if flags.stop.load(Ordering::SeqCst) || flags.paused.load(Ordering::SeqCst) {
                        return; // intentional exit in progress — stop/pause reaps
                    }
                    std::thread::sleep(Duration::from_millis(500));
                };
                if flags.stop.load(Ordering::SeqCst) || flags.paused.load(Ordering::SeqCst) {
                    return;
                }
                if code != Some(0) {
                    let msg = format!(
                        "ffmpeg exited unexpectedly on segment {idx} (code {})",
                        code.unwrap_or(-1)
                    );
                    log::error!("{msg}");
                    flags.stop.store(true, Ordering::SeqCst);
                    emit_error(&on_error, msg);
                }
            })
            .ok();
        Ok(())
    }

    /// Terminate the current ffmpeg process with SIGTERM and wait for its
    /// trailer to be flushed.  Normal application shutdown must never use a
    /// force-kill here: an abrupt exit can corrupt the current segment.
    fn stop_ffmpeg_segment(&mut self) {
        // Take the child out from under the monitor thread so only we reap it.
        let child = self.child.lock().unwrap().take();
        let Some(mut child) = child else { return };
        match child.try_wait() {
            Ok(None) => {
                terminate(&mut child);
                let deadline = std::time::Instant::now() + Duration::from_secs(30);
                let mut warned = false;
                loop {
                    match child.try_wait() {
                        Ok(Some(_)) => break,
                        Ok(None) => {
                            if !warned && std::time::Instant::now() >= deadline {
                                log::warn!(
                                    "ffmpeg did not exit after SIGTERM; continuing to wait for a clean trailer"
                                );
                                warned = true;
                            }
                            std::thread::sleep(Duration::from_millis(100));
                        }
                        Err(err) => {
                            log::warn!("Could not poll ffmpeg after SIGTERM: {err}");
                            let _ = child.wait();
                            break;
                        }
                    }
                }
            }
            _ => {
                let _ = child.wait();
            }
        }
    }

    fn concatenate_segments(&self) {
        let stem = self
            .output_path
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        let list_path = self
            .output_path
            .with_file_name(format!("{stem}_concat.txt"));
        let body: String = self
            .segments
            .iter()
            .map(|s| format!("file '{}'\n", s.to_string_lossy().replace('\'', "'\\''")))
            .collect();
        if std::fs::write(&list_path, body).is_err() {
            return;
        }
        let status = Command::new(runtime_program("ffmpeg"))
            .args([
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                &list_path.to_string_lossy(),
                "-c",
                "copy",
                &self.output_path.to_string_lossy(),
            ])
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .output();
        match status {
            Ok(out) if out.status.success() => {
                log::info!("Segments concatenated → {}", self.output_path.display());
                for seg in &self.segments {
                    let _ = std::fs::remove_file(seg);
                }
            }
            Ok(out) => log::error!("ffmpeg concat failed (code {})", out.status),
            Err(e) => log::error!("Failed to concatenate segments: {e}"),
        }
        let _ = std::fs::remove_file(&list_path);
    }
}

/// Ask ffmpeg to exit cleanly (SIGTERM writes a valid trailer; SIGKILL would
/// corrupt the segment). `Child` has no terminate API on stable, so signal the
/// pid directly (Linux-only app).
#[cfg(unix)]
fn terminate(child: &mut Child) {
    let pid = child.id() as i32;
    // SAFETY: kill(2) with SIGTERM is async-signal-safe and takes a pid.
    unsafe {
        libc_kill(pid, 15);
    }
}

#[cfg(unix)]
unsafe fn libc_kill(pid: i32, sig: i32) {
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        kill(pid, sig);
    }
}

#[cfg(not(unix))]
fn terminate(child: &mut Child) {
    let _ = child.kill();
}

impl RecorderBackend for Recorder {
    fn start(&mut self) -> anyhow::Result<()> {
        // Custom mode never touches the defaults: non-standard setups may not
        // even have one, and the user already picked explicit sources.
        if self.mode == "custom" {
            let selected = dedupe_sources(&self.custom_sources);
            if selected.is_empty() {
                anyhow::bail!(
                    "No custom audio devices selected. Open the Recorder and choose devices for Custom mode."
                );
            }
            // Fail fast on a stale selection (e.g. an unplugged USB mic)
            // instead of dying mid-segment inside ffmpeg.
            let available = list_sources();
            if !available.is_empty() {
                let missing = missing_sources(&selected, &available);
                if !missing.is_empty() {
                    anyhow::bail!(
                        "Selected audio devices not found: {}. Refresh the device list and try again.",
                        missing.join(", ")
                    );
                }
            }
            self.sources = selected;
        } else if self.mode == "speaker" {
            let mic = get_default_source()
                .ok_or_else(|| anyhow::anyhow!("No microphone found. Check audio setup."))?;
            self.sources = vec![mic];
        } else {
            let mic = get_default_source()
                .ok_or_else(|| anyhow::anyhow!("No microphone found. Check audio setup."))?;
            let sink = get_default_sink().ok_or_else(|| {
                anyhow::anyhow!("No audio output device found. Check audio setup.")
            })?;
            self.sources = vec![mic, monitor_of_sink(&sink)];
        }
        self.flags.stop.store(false, Ordering::SeqCst);
        self.flags.paused.store(false, Ordering::SeqCst);
        self.flags.elapsed.store(0, Ordering::SeqCst);
        self.segments.clear();
        self.segment_index = 0;
        self.start_ffmpeg_segment()?;
        self.start_level_monitor(); // Timer thread: +1s ticks while neither paused nor stopped.
        let flags = self.flags.clone();
        let on_tick = self.on_tick.clone();
        std::thread::Builder::new()
            .name("recorder-timer".into())
            .spawn(move || {
                while !flags.stop.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_secs(1));
                    if flags.stop.load(Ordering::SeqCst) || flags.paused.load(Ordering::SeqCst) {
                        continue;
                    }
                    let e = flags.elapsed.fetch_add(1, Ordering::SeqCst) + 1;
                    emit_tick(&on_tick, e);
                }
            })
            .ok();
        log::info!("Recording started → {}", self.output_path.display());
        Ok(())
    }

    fn pause(&mut self) {
        if self.flags.paused.swap(true, Ordering::SeqCst) {
            return;
        }
        self.stop_ffmpeg_segment();
        self.stop_level_monitor();
        log::info!("Recording paused — segment {} saved", self.segment_index);
    }

    fn resume(&mut self) {
        if !self.flags.paused.swap(false, Ordering::SeqCst) {
            return;
        }
        self.segment_index += 1;
        if let Err(e) = self.start_ffmpeg_segment() {
            log::error!("Failed to resume segment: {e:#}");
            self.flags.stop.store(true, Ordering::SeqCst);
            emit_error(&self.on_error, format!("{e:#}"));
            return;
        }
        self.start_level_monitor();
        log::info!("Recording resumed — segment {} started", self.segment_index);
    }

    fn stop(&mut self) {
        log::info!("Stopping recording...");
        self.flags.stop.store(true, Ordering::SeqCst);
        self.stop_level_monitor();
        self.stop_ffmpeg_segment();
        if self.segments.is_empty() {
            log::warn!("No segments recorded.");
        } else if self.segments.len() == 1 {
            let seg = &self.segments[0];
            if seg != &self.output_path {
                let _ = std::fs::rename(seg, &self.output_path);
            }
        } else {
            self.concatenate_segments();
        }
        log::info!("Recording stopped. File: {}", self.output_path.display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segment_naming() {
        let out = std::path::PathBuf::from("/m/2026-03-01_14-30/recording.mp3");
        assert_eq!(
            segment_path_for(&out, 2),
            std::path::PathBuf::from("/m/2026-03-01_14-30/recording_seg002.mp3")
        );
    }

    #[test]
    fn dedupe_preserves_order() {
        let in_list = vec!["b".to_string(), "a".to_string(), "b".to_string()];
        assert_eq!(dedupe_sources(&in_list), vec!["b", "a"]);
        assert!(dedupe_sources(&[]).is_empty());
    }

    #[test]
    fn custom_start_rejects_empty_selection_without_touching_hardware() {
        // No ffmpeg is spawned: start() bails during source resolution.
        let mut r = Recorder::new(
            std::path::PathBuf::from("/tmp/x.mp3"),
            "custom".to_string(),
            Vec::new(),
            "5".to_string(),
            None,
            None,
        );
        let err = r.start().unwrap_err().to_string();
        assert!(err.contains("No custom audio devices selected"));
    }
}
