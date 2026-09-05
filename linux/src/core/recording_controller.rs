//! Recording lifecycle orchestration.
//!
//! The controller owns the recorder instance, the stop countdown and the
//! authoritative lifecycle [`State`](crate::core::state_machine::State).
//! Threading contract: all public methods are main-thread only; the recorder's
//! `stop()` runs on a worker thread. UI scheduler specifics (countdown timer,
//! recorder factory, device validation) are injected so the whole lifecycle is
//! unit-testable headless.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::config::defaults::{self, Config};
use crate::core::state_machine::{can_transition, State};
use crate::core::task_runner::TaskRunner;
use crate::utils::filename::{make_job_label, output_paths};

pub const COUNTDOWN_SECONDS: u64 = defaults::COUNTDOWN_SECONDS;

#[derive(Debug, Clone)]
pub struct PendingRecording {
    pub audio_path: PathBuf,
    pub transcript_path: PathBuf,
    pub notes_path: PathBuf,
    pub label: String,
}

/// Recorder backend (real ffmpeg recorder or a test fake).
pub trait RecorderBackend: Send + 'static {
    fn start(&mut self) -> anyhow::Result<()>;
    fn pause(&mut self);
    fn resume(&mut self);
    /// Blocking stop: terminates ffmpeg and merges segments.
    fn stop(&mut self);
}

pub type StateCallback = Box<dyn Fn(State, &str) + Send>;
pub type TextCallback = Box<dyn Fn(&str) + Send>;
pub type CommitCallback = Box<dyn Fn(PendingRecording) + Send>;
pub type SavedCallback = Box<dyn Fn(PendingRecording) + Send>;
pub type VoidCallback = Box<dyn Fn() + Send>;
pub type CountdownCallback = Box<dyn Fn(u64) + Send>;

/// Builds a recorder backend: output path, mode
/// (`headphones`/`speaker`/`custom`), Custom-mode device list, quality value.
#[allow(clippy::type_complexity)]
pub type RecorderFactory<R> =
    Box<dyn Fn(PathBuf, String, Vec<String>, String) -> anyhow::Result<R> + Send>;

pub struct Callbacks {
    pub on_state: StateCallback,
    pub on_error: TextCallback,
    pub on_commit: CommitCallback,
    pub on_saved: SavedCallback,
    pub on_discarded: VoidCallback,
    pub on_countdown: CountdownCallback,
    /// Fired on a worker thread once the blocking recorder stop (ffmpeg exit +
    /// segment concat) has finished. The engine uses it to delay the processor
    /// launch until the file is fully written.
    pub on_stopped: VoidCallback,
}

struct Inner {
    state: State,
    pending: Option<PendingRecording>,
    countdown_gen: u64,
    countdown_remaining: u64,
    has_recorder: bool,
    /// Set once the worker-thread recorder stop has finished (stop path).
    stop_done: bool,
}

pub struct RecordingController<R> {
    inner: Arc<Mutex<Inner>>,
    recorder: Option<R>,
    runner: TaskRunner,
    callbacks: Arc<Mutex<Callbacks>>,
    recorder_factory: RecorderFactory<R>,
    validate_devices: Box<dyn Fn() -> Result<(), String> + Send>,
    /// Request a countdown tick in ~1s. The owner (daemon event loop) delivers
    /// it by calling [`Self::countdown_tick`]; headless tests call the tick
    /// directly. Kept as a plain callback (not a scheduled closure) so the
    /// controller never needs shared ownership of itself.
    request_tick: Box<dyn Fn() + Send>,
}

impl<R: RecorderBackend> RecordingController<R> {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        runner: TaskRunner,
        callbacks: Callbacks,
        recorder_factory: impl Fn(PathBuf, String, Vec<String>, String) -> anyhow::Result<R>
            + Send
            + 'static,
        validate_devices: impl Fn() -> Result<(), String> + Send + 'static,
        request_tick: impl Fn() + Send + 'static,
    ) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                state: State::Idle,
                pending: None,
                countdown_gen: 0,
                countdown_remaining: 0,
                has_recorder: false,
                stop_done: false,
            })),
            recorder: None,
            runner,
            callbacks: Arc::new(Mutex::new(callbacks)),
            recorder_factory: Box::new(recorder_factory),
            validate_devices: Box::new(validate_devices),
            request_tick: Box::new(request_tick),
        }
    }

    pub fn state(&self) -> State {
        self.inner.lock().unwrap().state
    }

    fn set_state(&self, new_state: State, status: &str) {
        let cur = self.inner.lock().unwrap().state;
        if !can_transition(cur, new_state) {
            log::error!("Illegal state transition {cur:?} -> {new_state:?}");
        }
        self.inner.lock().unwrap().state = new_state;
        (self.callbacks.lock().unwrap().on_state)(new_state, status);
    }

    /// Validate and start a recording. Emits on_error instead of raising.
    pub fn start(&mut self, cfg: &Config, mode: &str, title: Option<&str>) {
        if self.state() != State::Idle {
            return;
        }
        if mode == "custom" {
            // Custom mode records the user's explicit device list, so the
            // default-device check must not gate it — non-standard setups may
            // not even have defaults. An empty selection is a usage error.
            if cfg.custom_devices.is_empty() {
                (self.callbacks.lock().unwrap().on_error)(
                    "No custom audio devices selected. Open the Recorder and choose devices for Custom mode.",
                );
                return;
            }
        } else if let Err(e) = (self.validate_devices)() {
            (self.callbacks.lock().unwrap().on_error)(&format!("Audio device error: {e}"));
            return;
        }
        let (audio, transcript, notes) = output_paths(&cfg.output_folder, title);
        let label = make_job_label(&audio, title);
        self.inner.lock().unwrap().pending = Some(PendingRecording {
            audio_path: audio.clone(),
            transcript_path: transcript,
            notes_path: notes,
            label,
        });
        let (_, q_val) = defaults::recording_quality_label(&cfg.recording_quality);
        let recorder = match (self.recorder_factory)(
            audio,
            mode.to_string(),
            cfg.custom_devices.clone(),
            q_val.to_string(),
        ) {
            Ok(r) => r,
            Err(e) => {
                self.inner.lock().unwrap().pending = None;
                (self.callbacks.lock().unwrap().on_error)(&format!("{e:#}"));
                return;
            }
        };
        // Wire timer/error callbacks: the real recorder calls these from worker
        // threads; the daemon/window wrap them onto the main loop at construction.
        self.recorder = Some(recorder);
        self.inner.lock().unwrap().has_recorder = true;
        // NOTE: start() on the backend may fail (e.g. ffmpeg missing).
        let mode_label = match mode {
            "headphones" => "headphones",
            "speaker" => "speaker",
            "custom" => "custom",
            _ => "headphones",
        };
        self.set_state(State::Recording, &format!("Recording… ({mode_label} mode)"));
    }

    /// Start the backend after `start()` prepared it (split so tests can fail it).
    pub fn backend_start(&mut self) -> anyhow::Result<()> {
        match self.recorder.as_mut() {
            Some(r) => r.start(),
            None => Ok(()),
        }
    }

    pub fn pause(&mut self) {
        if self.state() != State::Recording || self.recorder.is_none() {
            return;
        }
        if let Some(r) = self.recorder.as_mut() {
            r.pause();
        }
        self.set_state(State::Paused, "Paused");
    }

    pub fn resume(&mut self) {
        if self.state() != State::Paused || self.recorder.is_none() {
            return;
        }
        if let Some(r) = self.recorder.as_mut() {
            r.resume();
        }
        self.set_state(State::Recording, "Recording…");
    }

    /// Stop recording; commit the pending job now or after a countdown.
    ///
    /// The blocking recorder stop (ffmpeg exit + segment concat) runs on a
    /// worker thread; [`Self::wait_until_stopped`] lets the engine delay the
    /// processor launch until the file is fully written.
    pub fn stop(&mut self, countdown_enabled: bool)
    where
        R: Send,
    {
        let st = self.state();
        if !matches!(st, State::Recording | State::Paused) || self.recorder.is_none() {
            return;
        }
        {
            let mut inner = self.inner.lock().unwrap();
            inner.countdown_gen += 1;
            inner.has_recorder = false;
            inner.stop_done = false;
        }
        if let Some(mut recorder) = self.recorder.take() {
            let inner = self.inner.clone();
            let cbs = self.callbacks.clone();
            self.runner.submit(
                move || {
                    recorder.stop();
                    inner.lock().unwrap().stop_done = true;
                    (cbs.lock().unwrap().on_stopped)();
                    Ok(())
                },
                "stop recorder",
                None::<fn(())>,
                None::<fn(anyhow::Error)>,
            );
        }
        if countdown_enabled {
            {
                let mut inner = self.inner.lock().unwrap();
                inner.countdown_remaining = COUNTDOWN_SECONDS;
            }
            self.set_state(
                State::Countdown,
                &format!("Starting transcription in {COUNTDOWN_SECONDS}s…"),
            );
            self.schedule_next_tick();
        } else {
            self.commit();
        }
    }

    /// True once the worker-thread recorder stop has finished.
    pub fn stop_finished(&self) -> bool {
        self.inner.lock().unwrap().stop_done
    }

    /// Access the task runner (e.g. for bounded shutdown).
    pub fn runner(&self) -> &TaskRunner {
        &self.runner
    }

    fn schedule_next_tick(&self) {
        // The owner delivers the tick by calling countdown_tick(); the
        // generation counter drops stale ticks after cancel/supersede.
        (self.request_tick)();
    }

    /// Advance the countdown by one second. Returns true while it continues.
    /// Called on the main thread by the owner's tick loop.
    pub fn countdown_tick(&mut self) -> bool {
        let remaining = {
            let mut inner = self.inner.lock().unwrap();
            if inner.state != State::Countdown {
                return false;
            }
            if inner.countdown_remaining > 0 {
                inner.countdown_remaining -= 1;
            }
            inner.countdown_remaining
        };
        if remaining > 0 {
            (self.callbacks.lock().unwrap().on_countdown)(remaining);
            true
        } else {
            self.commit();
            false
        }
    }

    pub fn cancel_countdown(&mut self) {
        if self.state() != State::Countdown {
            return;
        }
        {
            let mut inner = self.inner.lock().unwrap();
            inner.countdown_gen += 1;
            inner.pending = None;
        }
        self.set_state(State::Idle, "Transcription cancelled.");
        log::info!("Transcription cancelled during countdown.");
    }

    /// Stop recording, keep the audio, skip transcription.
    pub fn cancel_and_save(&mut self)
    where
        R: Send,
    {
        let st = self.state();
        if !matches!(st, State::Recording | State::Paused) || self.recorder.is_none() {
            return;
        }
        let recorder = self.recorder.take();
        let pending = self.inner.lock().unwrap().pending.take();
        self.inner.lock().unwrap().has_recorder = false;
        self.set_state(State::Idle, "Stopping recording…");
        let cbs = self.callbacks.clone();
        self.runner.submit(
            move || {
                if let Some(mut r) = recorder {
                    r.stop();
                }
                Ok(())
            },
            "stop recorder (cancel + save)",
            Some(move |_| {
                (cbs.lock().unwrap().on_state)(State::Idle, "Recording saved (no transcription).");
                if let Some(p) = pending {
                    (cbs.lock().unwrap().on_saved)(p);
                }
            }),
            None::<fn(anyhow::Error)>,
        );
    }

    /// Stop recording and delete the audio.
    pub fn cancel_and_discard(&mut self)
    where
        R: Send,
    {
        let st = self.state();
        if !matches!(st, State::Recording | State::Paused) || self.recorder.is_none() {
            return;
        }
        let recorder = self.recorder.take();
        let pending = self.inner.lock().unwrap().pending.take();
        self.inner.lock().unwrap().has_recorder = false;
        let audio_path = pending.as_ref().map(|p| p.audio_path.clone());
        self.set_state(State::Idle, "Cancelling…");
        let cbs = self.callbacks.clone();
        self.runner.submit(
            move || {
                if let Some(mut r) = recorder {
                    r.stop();
                }
                if let Some(a) = audio_path {
                    if a.exists() {
                        if let Err(e) = std::fs::remove_file(&a) {
                            log::warn!("Could not delete audio file: {e}");
                        }
                    }
                    // Remove the meeting dir if it is now empty.
                    if let Some(parent) = a.parent() {
                        let _ = std::fs::remove_dir(parent);
                    }
                }
                Ok(())
            },
            "stop recorder (discard)",
            Some(move |_| {
                (cbs.lock().unwrap().on_state)(State::Idle, "Recording discarded.");
                (cbs.lock().unwrap().on_discarded)();
            }),
            None::<fn(anyhow::Error)>,
        );
    }

    /// Recorder died mid-recording (device loss etc.) — reset the lifecycle.
    pub fn abort_to_idle(&mut self) {
        self.recorder = None;
        {
            let mut inner = self.inner.lock().unwrap();
            inner.pending = None;
            inner.countdown_gen += 1;
            inner.has_recorder = false;
        }
        self.set_state(State::Idle, "");
    }

    fn commit(&mut self) {
        let pending = self.inner.lock().unwrap().pending.take();
        self.set_state(State::Idle, "");
        if let Some(p) = pending {
            (self.callbacks.lock().unwrap().on_commit)(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::Duration;

    struct FakeRecorder {
        stopped: bool,
        fail_start: bool,
    }

    impl RecorderBackend for FakeRecorder {
        fn start(&mut self) -> anyhow::Result<()> {
            if self.fail_start {
                anyhow::bail!("ffmpeg not found");
            }
            Ok(())
        }
        fn pause(&mut self) {}
        fn resume(&mut self) {}
        fn stop(&mut self) {
            self.stopped = true;
        }
    }

    struct Events {
        states: mpsc::Receiver<(State, String)>,
        errors: mpsc::Receiver<String>,
        commits: mpsc::Receiver<PendingRecording>,
        saved: mpsc::Receiver<PendingRecording>,
        discarded: mpsc::Receiver<()>,
        stopped: mpsc::Receiver<()>,
    }

    /// (mode, custom device list) pairs the factory was called with.
    type SeenModes = std::sync::Arc<std::sync::Mutex<Vec<(String, Vec<String>)>>>;

    fn harness(fail_start: bool) -> (RecordingController<FakeRecorder>, Events) {
        let (stx, srx) = mpsc::channel();
        let (etx, erx) = mpsc::channel();
        let (ctx, crx) = mpsc::channel();
        let (svtx, svrx) = mpsc::channel();
        let (dtx, drx) = mpsc::channel();
        let (sptx, sprx) = mpsc::channel();
        let cbs = Callbacks {
            on_state: Box::new(move |s: State, m: &str| stx.send((s, m.to_string())).unwrap()),
            on_error: Box::new(move |m| etx.send(m.to_string()).unwrap()),
            on_commit: Box::new(move |p| ctx.send(p).unwrap()),
            on_saved: Box::new(move |p| svtx.send(p).unwrap()),
            on_discarded: Box::new(move || dtx.send(()).unwrap()),
            on_countdown: Box::new(|_| {}),
            on_stopped: Box::new(move || sptx.send(()).unwrap()),
        };
        let runner = TaskRunner::default();
        let c = RecordingController::new(
            runner,
            cbs,
            move |_, _, _, _| {
                Ok(FakeRecorder {
                    stopped: false,
                    fail_start,
                })
            },
            || Ok(()),
            || {},
        );
        (
            c,
            Events {
                states: srx,
                errors: erx,
                commits: crx,
                saved: svrx,
                discarded: drx,
                stopped: sprx,
            },
        )
    }

    fn test_cfg() -> Config {
        let dir = tempfile::tempdir().unwrap();
        // Leak the tempdir path intentionally for the test lifetime via env-independent cfg.
        let path = dir.path().to_string_lossy().into_owned();
        std::mem::forget(dir);
        Config {
            output_folder: path,
            ..Config::default()
        }
    }

    #[test]
    fn start_pause_resume_stop_no_countdown() {
        let (mut c, ev) = harness(false);
        let cfg = test_cfg();
        c.start(&cfg, "headphones", Some("Standup"));
        c.backend_start().unwrap();
        assert_eq!(c.state(), State::Recording);
        assert!(ev.states.recv_timeout(Duration::from_secs(2)).is_ok());
        c.pause();
        assert_eq!(c.state(), State::Paused);
        c.resume();
        assert_eq!(c.state(), State::Recording);
        // Stop without countdown commits immediately; the recorder stop runs
        // on the worker (inline here) and reports via on_stopped.
        c.stop(false);
        ev.stopped.recv_timeout(Duration::from_secs(2)).unwrap();
        let pending = ev.commits.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(pending.label.contains("Standup"));
        assert_eq!(c.state(), State::Idle);
    }

    #[test]
    fn start_failure_reports_error() {
        let (mut c, ev) = harness(true);
        let cfg = test_cfg();
        c.start(&cfg, "headphones", None);
        // backend_start fails -> emulate window behavior: surface error, abort.
        let r = c.backend_start();
        assert!(r.is_err());
        c.abort_to_idle();
        assert_eq!(c.state(), State::Idle);
        let _ = ev.errors.recv_timeout(Duration::from_secs(1));
    }

    #[test]
    fn countdown_commit_and_cancel() {
        let (mut c, ev) = harness(false);
        let cfg = test_cfg();
        c.start(&cfg, "headphones", None);
        c.backend_start().unwrap();
        c.stop(true);
        assert_eq!(c.state(), State::Countdown);
        // Tick down to zero -> commit.
        for _ in 0..COUNTDOWN_SECONDS {
            if !c.countdown_tick() {
                break;
            }
        }
        assert!(ev.commits.recv_timeout(Duration::from_secs(2)).is_ok());
        assert_eq!(c.state(), State::Idle);

        // Second round: cancel the countdown.
        c.start(&cfg, "headphones", None);
        c.backend_start().unwrap();
        c.stop(true);
        assert_eq!(c.state(), State::Countdown);
        c.cancel_countdown();
        assert_eq!(c.state(), State::Idle);
        assert!(ev.commits.recv_timeout(Duration::from_millis(200)).is_err());
    }

    #[test]
    fn cancel_save_and_discard() {
        let (mut c, ev) = harness(false);
        let cfg = test_cfg();
        c.start(&cfg, "headphones", None);
        c.backend_start().unwrap();
        c.cancel_and_save();
        assert!(ev.saved.recv_timeout(Duration::from_secs(5)).is_ok());

        c.start(&cfg, "headphones", None);
        c.backend_start().unwrap();
        c.cancel_and_discard();
        assert!(ev.discarded.recv_timeout(Duration::from_secs(5)).is_ok());
    }

    fn harness_with_capture() -> (RecordingController<FakeRecorder>, Events, SeenModes) {
        let (stx, srx) = mpsc::channel();
        let (etx, erx) = mpsc::channel();
        let (ctx, crx) = mpsc::channel();
        let (svtx, svrx) = mpsc::channel();
        let (dtx, drx) = mpsc::channel();
        let (sptx, sprx) = mpsc::channel();
        let cbs = Callbacks {
            on_state: Box::new(move |s: State, m: &str| stx.send((s, m.to_string())).unwrap()),
            on_error: Box::new(move |m| etx.send(m.to_string()).unwrap()),
            on_commit: Box::new(move |p| ctx.send(p).unwrap()),
            on_saved: Box::new(move |p| svtx.send(p).unwrap()),
            on_discarded: Box::new(move || dtx.send(()).unwrap()),
            on_countdown: Box::new(|_| {}),
            on_stopped: Box::new(move || sptx.send(()).unwrap()),
        };
        let seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen_c = seen.clone();
        let c = RecordingController::new(
            TaskRunner::default(),
            cbs,
            move |_, mode: String, custom: Vec<String>, _| {
                seen_c.lock().unwrap().push((mode, custom));
                Ok(FakeRecorder {
                    stopped: false,
                    fail_start: false,
                })
            },
            || Ok(()),
            || {},
        );
        (
            c,
            Events {
                states: srx,
                errors: erx,
                commits: crx,
                saved: svrx,
                discarded: drx,
                stopped: sprx,
            },
            seen,
        )
    }

    #[test]
    fn custom_without_devices_errors_and_never_builds_a_recorder() {
        let (mut c, ev, seen) = harness_with_capture();
        let cfg = test_cfg();
        assert!(cfg.custom_devices.is_empty());
        c.start(&cfg, "custom", None);
        assert_eq!(c.state(), State::Idle);
        let err = ev.errors.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(err.contains("No custom audio devices selected"));
        assert!(seen.lock().unwrap().is_empty());
    }

    #[test]
    fn custom_forwards_the_device_list_to_the_recorder() {
        let (mut c, ev, seen) = harness_with_capture();
        let mut cfg = test_cfg();
        cfg.custom_devices = vec!["mic-a".to_string(), "sink.monitor".to_string()];
        c.start(&cfg, "custom", None);
        c.backend_start().unwrap();
        assert_eq!(c.state(), State::Recording);
        let (state, status) = ev.states.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(state, State::Recording);
        assert!(status.contains("custom"));
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].0, "custom");
        assert_eq!(seen[0].1, vec!["mic-a", "sink.monitor"]);
    }
}
