//! Call-detection notification dedup.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use super::audio_watcher::AudioWatcher;
use crate::config::defaults::CALL_DETECTION_DEDUP_WINDOW_SECS;

/// Wraps [`AudioWatcher`] with a notification dedup window: a burst of
/// source-output events (browser tabs, virtual devices) collapses into a
/// single notification per window.
pub struct CallDetector {
    watcher: Option<AudioWatcher>,
    last_notified: Arc<Mutex<Option<Instant>>>,
}

impl CallDetector {
    pub fn new(notify: impl Fn() + Send + Sync + 'static) -> Self {
        let last: Arc<Mutex<Option<Instant>>> = Arc::new(Mutex::new(None));
        let last_clone = last.clone();
        let window = Duration::from_secs(CALL_DETECTION_DEDUP_WINDOW_SECS);
        let watcher = AudioWatcher::new(move || {
            let mut slot = last_clone.lock().unwrap();
            let now = Instant::now();
            if slot
                .map(|t| now.duration_since(t) >= window)
                .unwrap_or(true)
            {
                *slot = Some(now);
                drop(slot);
                notify();
            }
        });
        Self {
            watcher: Some(watcher),
            last_notified: last,
        }
    }

    pub fn start(&mut self) {
        if let Some(w) = self.watcher.as_mut() {
            w.start();
        }
    }

    pub fn stop(&mut self) {
        if let Some(w) = self.watcher.as_mut() {
            w.stop();
        }
    }

    #[allow(dead_code)]
    pub fn last_notified(&self) -> Option<Instant> {
        *self.last_notified.lock().unwrap()
    }
}
