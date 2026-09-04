//! Call-start event watching over `pactl subscribe`.
//!

use std::io::BufRead;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::utils::exe::runtime_program;

/// Pure matcher: a new mic-capture stream appearing means a call may start.
pub fn is_call_start_event(line: &str) -> bool {
    let lower = line.to_lowercase();
    lower.contains("new") && lower.contains("source-output")
}

/// Runs `pactl subscribe` on its own thread and invokes the callback on new
/// mic-capture streams. If pactl dies it is restarted with exponential backoff
/// (1s → 60s cap, reset after a healthy minute).
pub struct AudioWatcher {
    callback: Arc<dyn Fn() + Send + Sync>,
    running: Arc<AtomicBool>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl AudioWatcher {
    pub fn new(callback: impl Fn() + Send + Sync + 'static) -> Self {
        Self {
            callback: Arc::new(callback),
            running: Arc::new(AtomicBool::new(false)),
            handle: None,
        }
    }

    pub fn start(&mut self) {
        if self.running.swap(true, Ordering::SeqCst) {
            return;
        }
        let running = self.running.clone();
        let callback = self.callback.clone();
        self.handle = std::thread::Builder::new()
            .name("audio-watcher".into())
            .spawn(move || watch_loop(running, callback))
            .ok();
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl Drop for AudioWatcher {
    fn drop(&mut self) {
        self.stop();
    }
}

fn watch_loop(running: Arc<AtomicBool>, callback: Arc<dyn Fn() + Send + Sync>) {
    let mut backoff = Duration::from_secs(1);
    while running.load(Ordering::SeqCst) {
        let healthy_since = Instant::now();
        match spawn_pactl() {
            Ok(mut child) => {
                read_events(&mut child, &running, &callback);
                request_term(child.id());
                let _ = child.wait();
                // Healthy for over a minute → reset backoff.
                if healthy_since.elapsed() >= Duration::from_secs(60) {
                    backoff = Duration::from_secs(1);
                }
            }
            Err(e) => {
                log::warn!("pactl subscribe failed: {e:#}");
            }
        }
        if !running.load(Ordering::SeqCst) {
            break;
        }
        std::thread::sleep(backoff);
        backoff = (backoff * 2).min(Duration::from_secs(60));
    }
}

#[cfg(unix)]
fn request_term(pid: u32) {
    // SAFETY: pid belongs to the pactl child owned by this watcher.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_term(_pid: u32) {}

fn spawn_pactl() -> anyhow::Result<Child> {
    Ok(Command::new(runtime_program("pactl"))
        .arg("subscribe")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?)
}

fn read_events(
    child: &mut Child,
    running: &Arc<AtomicBool>,
    callback: &Arc<dyn Fn() + Send + Sync>,
) {
    let stdout = match child.stdout.take() {
        Some(s) => s,
        None => return,
    };
    let reader = std::io::BufReader::new(stdout);
    for line in reader.lines().map_while(Result::ok) {
        if !running.load(Ordering::SeqCst) {
            break;
        }
        if is_call_start_event(&line) {
            callback();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matcher() {
        assert!(is_call_start_event("Event 'new' on source-output #5"));
        assert!(is_call_start_event("EVENT 'NEW' ON SOURCE-OUTPUT #1"));
        assert!(!is_call_start_event("Event 'remove' on source-output #5"));
        assert!(!is_call_start_event("Event 'new' on sink #3"));
        assert!(!is_call_start_event(""));
    }
}
