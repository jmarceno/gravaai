//! Live audio-level monitoring for the recording indicator.
//!
//! The Recorder page and the recording-pill overlay must show whether audio
//! is *really* being captured — not a decorative animation. A second,
//! lightweight ffmpeg process reads the **same** PulseAudio/PipeWire source
//! names the recording uses, mixes them down, and reports momentary loudness
//! through the `ebur128` filter (`framelog=info`, ~10 lines/s on stderr).
//! Each line's `M:` (momentary LUFS) value is mapped to a 0.0–1.0 level.
//!
//! The monitor never touches the recording pipeline: if it fails (ffmpeg
//! missing, source busy) the recording continues and levels simply stay at 0.

use std::io::BufRead;
use std::process::{Child, Command, Stdio};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::Duration;

use crate::utils::exe::runtime_program;

/// Map momentary loudness (LUFS) to a 0.0–1.0 meter level.
///
/// Silence sits around −70 LUFS or lower (`-inf`, `-120.7`); conversational
/// speech lands roughly between −35 and −15 LUFS. The mapping is deliberately
/// coarse — the meter is an activity indicator, not a measurement instrument.
pub fn lufs_to_level(lufs: f32) -> f32 {
    if !lufs.is_finite() || lufs <= -70.0 {
        return 0.0;
    }
    ((lufs + 60.0) / 50.0).clamp(0.0, 1.0)
}

/// Extract the momentary loudness (`M:` value in LUFS) from one `ebur128`
/// `framelog=info` stderr line. Returns `None` for unrelated lines.
///
/// Sample line:
/// `[Parsed_ebur128_0 @ 0x…] t: 0.49 TARGET:-23 LUFS M: -21.8 S:-120.7 …`
pub fn parse_ebur128_momentary(line: &str) -> Option<f32> {
    let m_pos = line.find("M:")?;
    let rest = line[m_pos + 2..].trim_start();
    let end = rest.find(|c: char| c.is_whitespace()).unwrap_or(rest.len());
    let token = &rest[..end];
    if token.eq_ignore_ascii_case("-inf") {
        return Some(f32::NEG_INFINITY);
    }
    token.parse::<f32>().ok()
}

/// Build the level-monitor ffmpeg command for `sources`.
///
/// All sources are mixed down with `amix` so the reported level reflects
/// every captured source combined — the same set the recording reads. Output
/// is `-f null -`; loudness is reported on stderr via `ebur128` frame logs.
pub fn build_level_command(sources: &[String]) -> Vec<String> {
    let mut cmd = vec![
        "ffmpeg".to_string(),
        "-hide_banner".into(),
        "-loglevel".into(),
        "info".into(),
        "-nostats".into(),
        "-y".into(),
    ];
    for source in sources {
        cmd.extend([
            "-thread_queue_size".to_string(),
            "1024".into(),
            "-f".into(),
            "pulse".into(),
            "-i".into(),
            source.clone(),
        ]);
    }
    let filter = if sources.len() <= 1 {
        "ebur128=peak=true:framelog=info".to_string()
    } else {
        format!(
            "amix=inputs={}:duration=longest:dropout_transition=0:normalize=0,ebur128=peak=true:framelog=info",
            sources.len()
        )
    };
    cmd.extend(["-filter_complex".to_string(), filter]);
    cmd.extend(["-f".to_string(), "null".to_string(), "-".to_string()]);
    cmd
}

type LevelCb = Arc<Mutex<Option<Box<dyn Fn(f32) + Send>>>>;

fn emit_level(cb: &LevelCb, level: f32) {
    if let Some(f) = cb.lock().unwrap().as_ref() {
        f(level);
    }
}

/// Background ffmpeg process reporting live levels for `sources`.
///
/// Spawned by the [`Recorder`](crate::audio::recorder::Recorder) alongside
/// the recording so the UI meter reflects the real captured audio. Stops its
/// child on [`Self::stop`]/drop; failures are logged and surface as silence
/// (level 0) rather than recording errors.
pub struct LevelMonitor {
    child: Arc<Mutex<Option<Child>>>,
    stop_flag: Arc<AtomicBool>,
    callback: LevelCb,
}

impl LevelMonitor {
    /// Start monitoring `sources`, invoking `on_level` at ~10 Hz.
    /// Never fails the recording: spawn errors are logged and ignored.
    pub fn start(sources: Vec<String>, on_level: impl Fn(f32) + Send + 'static) -> Self {
        let callback: LevelCb = Arc::new(Mutex::new(Some(
            Box::new(on_level) as Box<dyn Fn(f32) + Send>
        )));
        let child: Arc<Mutex<Option<Child>>> = Arc::new(Mutex::new(None));
        let stop_flag = Arc::new(AtomicBool::new(false));
        if sources.is_empty() {
            return Self {
                child,
                stop_flag,
                callback,
            };
        }
        let cmd = build_level_command(&sources);
        let spawned = Command::new(runtime_program(&cmd[0]))
            .args(&cmd[1..])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn();
        let mut proc = match spawned {
            Ok(p) => p,
            Err(e) => {
                log::warn!("Audio level monitor could not start ({e:#}); meter stays silent");
                return Self {
                    child,
                    stop_flag,
                    callback,
                };
            }
        };
        if let Some(stderr) = proc.stderr.take() {
            let cb = callback.clone();
            let flag = stop_flag.clone();
            std::thread::Builder::new()
                .name("audio-level-monitor".into())
                .spawn(move || {
                    let reader = std::io::BufReader::new(stderr);
                    for line in reader.lines().map_while(Result::ok) {
                        if flag.load(Ordering::SeqCst) {
                            break;
                        }
                        if let Some(lufs) = parse_ebur128_momentary(&line) {
                            emit_level(&cb, lufs_to_level(lufs));
                        }
                    }
                })
                .ok();
        }
        *child.lock().unwrap() = Some(proc);
        Self {
            child,
            stop_flag,
            callback,
        }
    }

    /// Stop the monitor child and report silence once so the meter falls.
    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::SeqCst);
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let _ = child.kill();
            // Bounded reap so no zombie lingers; the reader thread exits on
            // EOF once the child is gone.
            let deadline = std::time::Instant::now() + Duration::from_secs(2);
            loop {
                match child.try_wait() {
                    Ok(Some(_)) | Err(_) => break,
                    Ok(None) if std::time::Instant::now() >= deadline => {
                        log::debug!("Level monitor did not exit promptly; leaving it to reap");
                        break;
                    }
                    Ok(None) => std::thread::sleep(Duration::from_millis(50)),
                }
            }
        }
        emit_level(&self.callback, 0.0);
    }
}

impl Drop for LevelMonitor {
    fn drop(&mut self) {
        self.stop_flag.store(true, Ordering::SeqCst);
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_real_ebur128_lines() {
        let line = "[Parsed_ebur128_0 @ 0x7fe484003b80] t: 0.499977 TARGET:-23 LUFS M: -21.8 S:-120.7 I: -21.8 LUFS LRA: 0.0 LU FTPK: -18.1 dBFS TPK: -18.1 dBFS";
        assert_eq!(parse_ebur128_momentary(line), Some(-21.8));
        let silence = "[Parsed_ebur128_0 @ 0x7f] t: 0.09 TARGET:-23 LUFS M:-120.7 S:-120.7 I: -70.0 LUFS LRA: 0.0 LU FTPK: -inf dBFS TPK: -inf dBFS";
        assert_eq!(parse_ebur128_momentary(silence), Some(-120.7));
        let neg_inf = "[Parsed_ebur128_0 @ 0x7f] t: 0.09 M: -inf S:-inf I: -70.0 LUFS";
        assert_eq!(parse_ebur128_momentary(neg_inf), Some(f32::NEG_INFINITY));
    }

    #[test]
    fn ignores_unrelated_ffmpeg_lines() {
        assert_eq!(parse_ebur128_momentary(""), None);
        assert_eq!(
            parse_ebur128_momentary("Input #0, pulse, from 'alsa_input.usb':"),
            None
        );
        assert_eq!(
            parse_ebur128_momentary("Press [q] to stop, [?] for help"),
            None
        );
        // A "MODE:" token must not be mistaken for the "M:" loudness field.
        assert_eq!(parse_ebur128_momentary("MODE: something"), None);
    }

    #[test]
    fn lufs_mapping_is_coarse_but_monotonic() {
        assert_eq!(lufs_to_level(f32::NEG_INFINITY), 0.0);
        assert_eq!(lufs_to_level(-120.7), 0.0);
        assert_eq!(lufs_to_level(-70.0), 0.0);
        let quiet = lufs_to_level(-45.0);
        let speech = lufs_to_level(-22.0);
        let loud = lufs_to_level(-12.0);
        assert!(quiet > 0.0 && quiet < speech);
        assert!(speech > quiet && speech < loud);
        assert_eq!(lufs_to_level(0.0), 1.0);
        assert_eq!(lufs_to_level(5.0), 1.0);
        assert!(lufs_to_level(f32::NAN) == 0.0);
    }

    #[test]
    fn level_command_covers_all_sources() {
        let one = build_level_command(&["mic".to_string()]);
        assert_eq!(one.iter().filter(|a| *a == "-i").count(), 1);
        assert!(one.iter().any(|a| a.contains("ebur128")));
        assert!(!one.iter().any(|a| a.contains("amix")));
        assert_eq!(one.last().unwrap(), "-");

        let two = build_level_command(&["mic".to_string(), "sink.monitor".to_string()]);
        assert_eq!(two.iter().filter(|a| *a == "-i").count(), 2);
        assert!(two.iter().any(|a| a.contains("amix=inputs=2")));
        assert!(two.iter().any(|a| a.contains("ebur128")));

        let three = build_level_command(&["a".to_string(), "b".to_string(), "c".to_string()]);
        assert_eq!(three.iter().filter(|a| *a == "-i").count(), 3);
        assert!(three.iter().any(|a| a.contains("amix=inputs=3")));
    }
}
