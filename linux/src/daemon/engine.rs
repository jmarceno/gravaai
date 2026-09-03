//! The GTK-free recording engine that lives in the daemon.
//!
//!
//! Recording/job/processing logic that runs without a display. Owns the
//! [`RecordingController`] and [`JobManager`]; each processing job runs in a
//! short-lived `--process` child (see `daemon::processor`) so the heavy AI
//! stack never accumulates in the daemon. Keeps a plain snapshot (state,
//! status, timer, jobs) and fires `on_change` whenever it mutates; the daemon
//! wires that to the tray and the D-Bus service.
//!
//! Threading: public methods run on the daemon event-loop thread. Controller
//! callbacks fire synchronously inside those calls and are applied via an
//! internal event queue ([`Engine::drain_events`], auto-drained at the end of
//! every public method). Recorder worker threads and TaskRunner callbacks
//! never touch the engine directly — the daemon marshals them back as
//! [`DaemonEvent`]s (see `daemon::app`).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::config::defaults::Config;
use crate::config::settings;
use crate::core::job::{CancelToken, Job, JobStatus};
use crate::core::job_manager::JobManager;
use crate::core::recording_controller::{
    Callbacks, PendingRecording, RecorderBackend, RecordingController,
};
use crate::core::state_machine::State;
use crate::core::task_runner::TaskRunner;
use crate::core::wire::{snapshot_to_json, JobView};

use super::processor::ChildEvent;

fn state_names(state: State) -> &'static str {
    match state {
        // Tray shows idle art during the stop countdown.
        State::Countdown => "idle",
        other => other.as_str(),
    }
}

/// Controller-level callbacks queued for application on the event-loop thread.
#[derive(Debug)]
enum CtlEvent {
    State(State, String),
    Error(String),
    Commit(PendingRecording),
    Saved(PendingRecording),
    Discarded,
    Countdown(u64),
    Timer(u64),
    RecorderError(String),
    Stopped,
}

pub struct EngineHooks {
    pub on_change: Box<dyn Fn() + Send>,
    pub on_error: Box<dyn Fn(&str) + Send>,
    pub on_output: Box<dyn Fn(&str) + Send>,
}

/// Processing-child backend. The real one spawns `--process` children; tests
/// inject a fake that records launches.
pub trait ProcessorBackend: Send {
    fn launch(&mut self, job_id: i64, audio: &str, transcript: &str, notes: &str);
    fn cancel(&mut self, job_id: i64);
}

pub struct Engine<R> {
    controller: RecordingController<R>,
    jobs: JobManager,
    config: Config,
    status: String,
    elapsed: u64,
    countdown: u64,
    job_status_text: HashMap<i64, String>,
    /// Committed jobs waiting for the recorder file before the processor launches.
    awaiting_file: Vec<i64>,
    pending_title: Option<String>,
    backend: Box<dyn ProcessorBackend>,
    hooks: EngineHooks,
    queue: Arc<Mutex<Vec<CtlEvent>>>,
}

impl<R: RecorderBackend + Send> Engine<R> {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        config: Config,
        runner: TaskRunner,
        recorder_factory: impl Fn(PathBuf, String, String) -> anyhow::Result<R> + Send + 'static,
        validate_devices: impl Fn() -> Result<(), String> + Send + 'static,
        request_tick: impl Fn() + Send + 'static,
        job_manager: JobManager,
        backend: Box<dyn ProcessorBackend>,
        hooks: EngineHooks,
    ) -> Self {
        let queue: Arc<Mutex<Vec<CtlEvent>>> = Arc::new(Mutex::new(Vec::new()));
        let q = queue.clone();
        let controller = RecordingController::new(
            runner,
            Callbacks {
                on_state: Box::new(move |s: State, m: &str| {
                    q.lock().unwrap().push(CtlEvent::State(s, m.to_string()))
                }),
                on_error: {
                    let q = queue.clone();
                    Box::new(move |m| q.lock().unwrap().push(CtlEvent::Error(m.to_string())))
                },
                on_commit: {
                    let q = queue.clone();
                    Box::new(move |p| q.lock().unwrap().push(CtlEvent::Commit(p)))
                },
                on_saved: {
                    let q = queue.clone();
                    Box::new(move |p| q.lock().unwrap().push(CtlEvent::Saved(p)))
                },
                on_discarded: {
                    let q = queue.clone();
                    Box::new(move || q.lock().unwrap().push(CtlEvent::Discarded))
                },
                on_countdown: {
                    let q = queue.clone();
                    Box::new(move |r| q.lock().unwrap().push(CtlEvent::Countdown(r)))
                },
                on_stopped: {
                    let q = queue.clone();
                    Box::new(move || q.lock().unwrap().push(CtlEvent::Stopped))
                },
            },
            recorder_factory,
            validate_devices,
            request_tick,
        );
        Self {
            controller,
            jobs: job_manager,
            config,
            status: "Ready to record".to_string(),
            elapsed: 0,
            countdown: 0,
            job_status_text: HashMap::new(),
            awaiting_file: Vec::new(),
            pending_title: None,
            backend,
            hooks,
            queue,
        }
    }

    // ------------------------------------------------------------------
    // Snapshot / query surface
    // ------------------------------------------------------------------

    pub fn state(&self) -> State {
        self.controller.state()
    }

    pub fn state_name(&self) -> &'static str {
        state_names(self.controller.state())
    }

    pub fn processing_jobs(&self) -> Vec<&Job> {
        self.jobs
            .jobs()
            .iter()
            .filter(|j| j.status == JobStatus::Processing && !j.cancelled)
            .collect()
    }

    pub fn snapshot_json(&self) -> String {
        let state = self.controller.state();
        let wire_state = if state == State::Countdown {
            "countdown"
        } else {
            state_names(state)
        };
        let views: Vec<JobView> = self
            .jobs
            .jobs()
            .iter()
            .map(|j| JobView {
                job_id: j.job_id,
                label: j.label.clone(),
                status: j.status,
                error_msg: j.error_msg.clone(),
                audio_dir: j
                    .audio_path
                    .parent()
                    .map(|p| p.to_string_lossy().into_owned())
                    .unwrap_or_default(),
                status_text: self
                    .job_status_text
                    .get(&j.job_id)
                    .cloned()
                    .unwrap_or_default(),
            })
            .collect();
        snapshot_to_json(
            wire_state,
            &self.status,
            self.elapsed,
            self.countdown,
            &views,
        )
    }

    /// Re-offer jobs from the previous session (crash/quit recovery).
    pub fn restore_persisted_jobs(&mut self) {
        self.jobs.load_persisted();
        self.changed();
    }

    #[cfg(test)]
    pub fn status_text_for(&self, job_id: i64) -> String {
        self.job_status_text
            .get(&job_id)
            .cloned()
            .unwrap_or_default()
    }

    // ------------------------------------------------------------------
    // Recording commands (event-loop thread only)
    // ------------------------------------------------------------------

    pub fn start_recording(&mut self, mode: &str) {
        if self.controller.state() != State::Idle {
            return;
        }
        // NOTE: the daemon reloads config from disk before calling (startup,
        // ReloadConfig, and before start/stop); the engine itself keeps the
        // in-memory copy so headless tests stay hermetic.
        if let Some(err) = settings::api_key_error(&self.config) {
            self.emit_error(&err);
            return;
        }
        let title = self.pending_title.clone();
        let cfg = self.config.clone();
        self.controller.start(&cfg, mode, title.as_deref());
        if self.controller.backend_start().is_err() {
            // backend_start failed after start() prepared state: surface and reset.
            let msg = "Failed to start recording (audio backend error).";
            self.controller.abort_to_idle();
            self.emit_error(msg);
        }
        self.drain_events();
    }

    /// The window sends the meeting title with the start command; the last one
    /// is kept so a tray-initiated start still records without a title.
    pub fn set_title(&mut self, title: &str) {
        let t = title.trim();
        self.pending_title = if t.is_empty() {
            None
        } else {
            Some(t.to_string())
        };
    }

    pub fn pause(&mut self) {
        self.controller.pause();
        self.drain_events();
    }

    pub fn resume(&mut self) {
        self.controller.resume();
        self.drain_events();
    }

    pub fn stop(&mut self) {
        let countdown = self.config.processing_countdown_enabled;
        self.controller.stop(countdown);
        self.drain_events();
    }

    pub fn cancel_countdown(&mut self) {
        self.controller.cancel_countdown();
        self.drain_events();
    }

    pub fn cancel_and_save(&mut self) {
        self.controller.cancel_and_save();
        self.drain_events();
    }

    pub fn cancel_and_discard(&mut self) {
        self.controller.cancel_and_discard();
        self.drain_events();
    }

    /// Advance the stop countdown by one second (from the daemon tick loop).
    pub fn countdown_tick(&mut self) {
        self.controller.countdown_tick();
        self.drain_events();
    }

    /// Deliver a recorder timer tick marshalled from a worker thread.
    pub fn timer_tick(&mut self, elapsed: u64) {
        self.queue.lock().unwrap().push(CtlEvent::Timer(elapsed));
        self.drain_events();
    }

    /// Deliver a recorder error marshalled from a worker thread.
    pub fn recorder_error(&mut self, msg: &str) {
        self.queue
            .lock()
            .unwrap()
            .push(CtlEvent::RecorderError(msg.to_string()));
        self.drain_events();
    }

    /// Stop any active recording (keeping audio) before the daemon exits.
    pub fn prepare_quit(&mut self) {
        self.controller.cancel_and_save();
        self.drain_events();
    }

    /// Join background tasks with a bounded grace period during shutdown.
    pub fn shutdown_tasks(&self) {
        self.controller
            .runner()
            .shutdown(std::time::Duration::from_secs(10));
    }

    // ------------------------------------------------------------------
    // Job creation from the window (paths already resolved UI-side)
    // ------------------------------------------------------------------

    /// Process an existing audio file already resolved to meeting paths.
    pub fn import_existing(&mut self, audio: &str, transcript: &str, notes: &str, label: &str) {
        let id = self.jobs.create(
            audio.into(),
            transcript.into(),
            notes.into(),
            label.to_string(),
        );
        self.changed();
        self.launch_processor(id);
    }

    /// Process a library meeting; returns an error string or None on success.
    pub fn summarize_meeting(
        &mut self,
        audio: &str,
        transcript: &str,
        notes: &str,
        label: &str,
    ) -> Option<String> {
        if self
            .jobs
            .jobs()
            .iter()
            .any(|j| j.audio_path.as_os_str() == audio && j.status == JobStatus::Processing)
        {
            return Some("This meeting is already being processed.".to_string());
        }
        let id = self.jobs.create(
            audio.into(),
            transcript.into(),
            notes.into(),
            label.to_string(),
        );
        self.changed();
        self.launch_processor(id);
        None
    }

    // ------------------------------------------------------------------
    // Job row actions
    // ------------------------------------------------------------------

    pub fn cancel_job(&mut self, job_id: i64) {
        let Some(job) = self.jobs.find_mut(job_id) else {
            return;
        };
        job.cancelled = true;
        job.token.cancel();
        self.backend.cancel(job_id);
        self.awaiting_file.retain(|id| *id != job_id);
        self.jobs.remove(job_id);
        self.job_status_text.remove(&job_id);
        self.changed();
        log::info!("Job {job_id} cancelled by user");
    }

    pub fn retry_job(&mut self, job_id: i64) {
        let Some(job) = self.jobs.find_mut(job_id) else {
            return;
        };
        job.cancelled = false;
        job.token = CancelToken::new();
        self.jobs.mark_processing(job_id);
        self.changed();
        self.launch_processor(job_id);
    }

    pub fn dismiss_job(&mut self, job_id: i64) {
        if self.jobs.find(job_id).is_none() {
            return;
        }
        self.jobs.remove(job_id);
        self.job_status_text.remove(&job_id);
        self.changed();
    }

    pub fn job_folder(&self, job_id: i64) -> Option<String> {
        self.jobs
            .find(job_id)
            .and_then(|j| j.audio_path.parent())
            .map(|p| p.to_string_lossy().into_owned())
    }

    pub fn output_folder(&self) -> String {
        shellexpand(&self.config.output_folder)
    }

    pub fn reload_config(&mut self) {
        self.config = settings::load();
    }

    #[cfg(test)]
    pub fn set_config(&mut self, cfg: Config) {
        self.config = cfg;
    }

    // ------------------------------------------------------------------
    // Processing-child events (from the event loop)
    // ------------------------------------------------------------------

    pub fn handle_child_event(&mut self, job_id: i64, event: ChildEvent) {
        match event {
            ChildEvent::Status(msg) => {
                self.job_status_text.insert(job_id, msg);
                self.changed();
            }
            ChildEvent::Done(paths) => self.on_processing_done(job_id, paths),
            ChildEvent::Error(msg) => self.on_processing_error(job_id, msg),
        }
    }

    // ------------------------------------------------------------------
    // Controller event queue
    // ------------------------------------------------------------------

    /// Apply queued controller callbacks. Called at the end of every public
    /// method; the daemon loop also calls it after running marshalled worker
    /// callbacks.
    pub fn drain_events(&mut self) {
        loop {
            let batch: Vec<CtlEvent> = self.queue.lock().unwrap().drain(..).collect();
            if batch.is_empty() {
                break;
            }
            for ev in batch {
                self.apply(ev);
            }
        }
    }

    fn apply(&mut self, ev: CtlEvent) {
        match ev {
            CtlEvent::State(state, status) => {
                if !status.is_empty() {
                    self.status = status;
                }
                if state == State::Idle {
                    self.elapsed = 0;
                    self.countdown = 0;
                }
                self.changed();
            }
            CtlEvent::Error(msg) => self.emit_error(&msg),
            CtlEvent::Commit(pending) => {
                let id = self.jobs.create(
                    pending.audio_path,
                    pending.transcript_path,
                    pending.notes_path,
                    pending.label,
                );
                self.changed();
                // The recorder file may still be finalizing (ffmpeg concat on
                // the worker); launch now if it already finished, else queue.
                if self.controller.stop_finished() {
                    self.launch_processor(id);
                } else {
                    self.awaiting_file.push(id);
                }
            }
            CtlEvent::Saved(pending) => {
                let mut paths = Vec::new();
                if pending.transcript_path.exists() {
                    paths.push(format!("Transcript: {}", pending.transcript_path.display()));
                }
                if pending.notes_path.exists() {
                    paths.push(format!("Notes: {}", pending.notes_path.display()));
                }
                if pending.audio_path.exists() {
                    paths.push(format!("Audio: {}", pending.audio_path.display()));
                }
                if !paths.is_empty() {
                    (self.hooks.on_output)(&paths.join("\n"));
                }
            }
            CtlEvent::Discarded => {}
            CtlEvent::Countdown(remaining) => {
                self.countdown = remaining;
                self.status = format!("Starting transcription in {remaining}s…");
                self.changed();
            }
            CtlEvent::Timer(elapsed) => {
                self.elapsed = elapsed;
                self.changed();
            }
            CtlEvent::RecorderError(msg) => {
                self.controller.abort_to_idle();
                // abort_to_idle queues a State event; apply it now.
                self.drain_events();
                self.emit_error(&msg);
            }
            CtlEvent::Stopped => {
                let awaiting = std::mem::take(&mut self.awaiting_file);
                for id in awaiting {
                    let cancelled = self.jobs.find(id).map(|j| j.cancelled).unwrap_or(true);
                    if !cancelled {
                        self.launch_processor(id);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Pipeline plumbing
    // ------------------------------------------------------------------

    fn launch_processor(&mut self, job_id: i64) {
        let Some(job) = self.jobs.find(job_id) else {
            return;
        };
        if job.cancelled {
            return;
        }
        self.job_status_text
            .insert(job_id, "Transcribing…".to_string());
        let (a, t, n) = (
            job.audio_path.to_string_lossy().into_owned(),
            job.transcript_path.to_string_lossy().into_owned(),
            job.notes_path.to_string_lossy().into_owned(),
        );
        self.backend.launch(job_id, &a, &t, &n);
        self.changed();
    }

    fn on_processing_done(&mut self, job_id: i64, paths: Vec<Option<String>>) {
        if self.jobs.find(job_id).map(|j| j.cancelled).unwrap_or(true) {
            return;
        }
        // Auto-title may have moved the meeting directory — adopt the paths.
        self.adopt_paths(job_id, paths);
        self.jobs.persist();
        self.jobs.mark_done(job_id);
        self.job_status_text.remove(&job_id);
        self.changed();
        self.notify_complete(job_id);
    }

    fn adopt_paths(&mut self, job_id: i64, mut paths: Vec<Option<String>>) {
        // Child returns [audio, transcript, notes]; pad defensively.
        paths.resize_with(3, || None);
        let Some(job) = self.jobs.find_mut(job_id) else {
            return;
        };
        let mut it = paths.into_iter();
        if let Some(Some(a)) = it.next() {
            if !a.is_empty() {
                job.audio_path = PathBuf::from(a);
            }
        }
        if let Some(Some(t)) = it.next() {
            if !t.is_empty() {
                job.transcript_path = PathBuf::from(t);
            }
        }
        if let Some(Some(n)) = it.next() {
            if !n.is_empty() {
                job.notes_path = PathBuf::from(n);
            }
        }
    }

    fn on_processing_error(&mut self, job_id: i64, msg: String) {
        let Some(job) = self.jobs.find(job_id) else {
            return;
        };
        if job.cancelled {
            return;
        }
        self.jobs.mark_error(job_id, msg);
        self.changed();
    }

    fn notify_complete(&self, job_id: i64) {
        crate::ui::notifications::notify("Meeting Recorded", &notify_body(self, job_id));
    }

    fn emit_error(&self, msg: &str) {
        log::error!("Engine error: {msg}");
        (self.hooks.on_error)(msg);
    }

    fn changed(&self) {
        // Never let a listener break the engine.
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (self.hooks.on_change)()));
        if r.is_err() {
            log::error!("Engine on_change listener failed");
        }
    }
}

fn notify_body<R>(engine: &Engine<R>, job_id: i64) -> String {
    let Some(job) = engine.jobs.find(job_id) else {
        return "Processing complete.".to_string();
    };
    let mut parts = Vec::new();
    if !job.transcript_path.as_os_str().is_empty() {
        parts.push(job.transcript_path.to_string_lossy().into_owned());
    }
    if !job.notes_path.as_os_str().is_empty() {
        parts.push(job.notes_path.to_string_lossy().into_owned());
    }
    if parts.is_empty() {
        "Processing complete.".to_string()
    } else {
        parts.join("\n")
    }
}

fn shellexpand(p: &str) -> String {
    if let Some(rest) = p.strip_prefix("~/") {
        match dirs::home_dir() {
            Some(h) => h.join(rest).to_string_lossy().into_owned(),
            None => p.to_string(),
        }
    } else {
        p.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::recording_controller::RecorderBackend;
    use std::sync::mpsc;
    use std::time::Duration;

    struct FakeRecorder {
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
        fn stop(&mut self) {}
    }

    struct FakeBackend {
        launched: Vec<(i64, String, String, String)>,
        cancelled: Vec<i64>,
    }

    impl ProcessorBackend for FakeBackend {
        fn launch(&mut self, job_id: i64, audio: &str, transcript: &str, notes: &str) {
            self.launched
                .push((job_id, audio.into(), transcript.into(), notes.into()));
        }
        fn cancel(&mut self, job_id: i64) {
            self.cancelled.push(job_id);
        }
    }

    struct Fixture {
        engine: Engine<FakeRecorder>,
        changes: mpsc::Receiver<()>,
        errors: mpsc::Receiver<String>,
        outputs: mpsc::Receiver<String>,
        launched: Arc<Mutex<FakeBackend>>,
    }

    // The fake backend needs shared ownership to assert on launches; wrap it.
    struct SharedBackend(Arc<Mutex<FakeBackend>>);

    impl ProcessorBackend for SharedBackend {
        fn launch(&mut self, job_id: i64, audio: &str, transcript: &str, notes: &str) {
            self.0
                .lock()
                .unwrap()
                .launch(job_id, audio, transcript, notes);
        }
        fn cancel(&mut self, job_id: i64) {
            self.0.lock().unwrap().cancel(job_id);
        }
    }

    fn fixture() -> Fixture {
        let (ctx, crx) = mpsc::channel();
        let (etx, erx) = mpsc::channel();
        let (otx, orx) = mpsc::channel();
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("meetings");
        std::mem::forget(dir);
        let mut cfg = Config {
            transcription_service: "ollama".into(),
            summarization_service: "ollama".into(),
            ..Config::default()
        };
        cfg.output_folder = out.to_string_lossy().into_owned();
        let jm_dir = tempfile::tempdir().unwrap();
        let jm_path = jm_dir.path().to_path_buf();
        std::mem::forget(jm_dir);
        let backend = Arc::new(Mutex::new(FakeBackend {
            launched: Vec::new(),
            cancelled: Vec::new(),
        }));
        let engine = Engine::new(
            cfg,
            TaskRunner::default(),
            |_, _, _| Ok(FakeRecorder { fail_start: false }),
            || Ok(()),
            || {},
            JobManager::new(Some(jm_path)),
            Box::new(SharedBackend(backend.clone())),
            EngineHooks {
                on_change: Box::new(move || ctx.send(()).unwrap()),
                on_error: Box::new(move |m| etx.send(m.to_string()).unwrap()),
                on_output: Box::new(move |t| otx.send(t.to_string()).unwrap()),
            },
        );
        Fixture {
            engine,
            changes: crx,
            errors: erx,
            outputs: orx,
            launched: backend,
        }
    }

    fn drain(rx: &mpsc::Receiver<()>) {
        while rx.recv_timeout(Duration::from_millis(50)).is_ok() {}
    }

    #[test]
    fn snapshot_naming_and_job_lifecycle() {
        let mut f = fixture();
        assert_eq!(f.engine.state_name(), "idle");
        assert!(f.engine.snapshot_json().contains("\"state\":\"idle\""));

        f.engine.start_recording("headphones");
        assert_eq!(f.engine.state(), State::Recording);
        assert_eq!(f.engine.state_name(), "recording");
        drain(&f.changes);

        f.engine.stop();
        // FakeRecorder.stop runs inline (immediate scheduler) → Stopped queued;
        // commit (no countdown) created the job awaiting the file; the Stopped
        // event (queued after commit) launches the processor.
        let launched = f.launched.lock().unwrap();
        assert_eq!(launched.launched.len(), 1);
        drop(launched);
        let snap = f.engine.snapshot_json();
        assert!(snap.contains("processing"));

        // Status text tracking.
        let job_id = 0;
        f.engine
            .handle_child_event(job_id, ChildEvent::Status("Transcribing…".into()));
        assert_eq!(f.engine.status_text_for(job_id), "Transcribing…");

        // Done adopts returned paths (auto-title move).
        f.engine.handle_child_event(
            job_id,
            ChildEvent::Done(vec![
                Some("/m/new/recording.mp3".into()),
                Some("/m/new/transcript.md".into()),
                Some("/m/new/notes.md".into()),
            ]),
        );
        assert_eq!(f.engine.job_folder(job_id).as_deref(), Some("/m/new"));
        let snap = f.engine.snapshot_json();
        assert!(snap.contains("\"status\":\"done\""));
    }

    #[test]
    fn duplicate_and_error_guards() {
        let mut f = fixture();
        f.engine.import_existing("/a.mp3", "/t.md", "/n.md", "L");
        // Same audio already processing → refused.
        let err = f.engine.summarize_meeting("/a.mp3", "/t.md", "/n.md", "L");
        assert_eq!(
            err.as_deref(),
            Some("This meeting is already being processed.")
        );
        // Error the job, then retry relaunches.
        f.engine
            .handle_child_event(0, ChildEvent::Error("boom".into()));
        assert!(f.engine.snapshot_json().contains("boom"));
        f.engine.retry_job(0);
        assert_eq!(f.launched.lock().unwrap().launched.len(), 2);
        // Cancel kills backend handle and removes the job.
        f.engine.cancel_job(0);
        assert!(f.launched.lock().unwrap().cancelled.contains(&0));
        assert!(!f.engine.snapshot_json().contains("boom"));
        let _ = (&f.changes, &f.errors, &f.outputs);
    }

    #[test]
    fn api_key_guard_blocks_start() {
        let mut f = fixture();
        let cfg = Config {
            transcription_service: "openai".into(),
            summarization_service: "openai".into(),
            openai_api_key: String::new(),
            ..Config::default()
        };
        f.engine.set_config(cfg);
        f.engine.start_recording("headphones");
        assert_eq!(f.engine.state(), State::Idle);
        let err = f.errors.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(err.contains("API key"));
    }
}
