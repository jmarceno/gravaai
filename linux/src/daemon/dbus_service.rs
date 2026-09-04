//! The D-Bus Engine service: the daemon↔window boundary.
//!
//! Exposes `io.github.jmarceno.GravaAi.Engine` on the session bus. Method
//! calls from the window process are routed to the in-daemon [`Engine`];
//! state changes are pushed back as `SnapshotChanged` signals (plus `Error` /
//! `Output` / `PresentWindow` / install signals) by the daemon loop, which
//! observes engine mutations through the engine hooks. Also owns the window
//! child via [`WindowSupervisor`] (spawn on `OpenWindow`, present the
//! existing one otherwise).

use std::sync::{Arc, Mutex};

use crate::audio::recorder::Recorder;
use crate::config::defaults::APP_ID;
use crate::daemon::engine::Engine;
use crate::daemon::install_manager::InstallManager;
use crate::daemon::window_supervisor::WindowSupervisor;

pub const ENGINE_NAME: &str = APP_ID;
pub const ENGINE_PATH: &str = "/io/github/jmarceno/GravaAi";

/// Shared daemon state behind the D-Bus interface.
pub struct ServiceCtx {
    pub engine: tokio::sync::Mutex<Engine<Recorder>>,
    pub installs: tokio::sync::Mutex<InstallManager>,
    pub supervisor: Mutex<WindowSupervisor>,
    pub on_quit: Box<dyn Fn() + Send + Sync>,
    pub on_reload: Box<dyn Fn() + Send + Sync>,
}

pub struct EngineIface {
    ctx: Arc<ServiceCtx>,
}

impl EngineIface {
    pub fn new(ctx: Arc<ServiceCtx>) -> Self {
        Self { ctx }
    }
}

#[zbus::interface(name = "io.github.jmarceno.GravaAi.Engine")]
impl EngineIface {
    async fn start_recording(&self, mode: String) {
        let mut engine = self.ctx.engine.lock().await;
        engine.reload_config();
        engine.start_recording(&mode);
    }

    async fn set_title(&self, title: String) {
        self.ctx.engine.lock().await.set_title(&title);
    }

    async fn pause(&self) {
        self.ctx.engine.lock().await.pause();
    }

    async fn resume(&self) {
        self.ctx.engine.lock().await.resume();
    }

    async fn stop(&self) {
        let mut engine = self.ctx.engine.lock().await;
        engine.reload_config();
        engine.stop();
    }

    async fn cancel_countdown(&self) {
        self.ctx.engine.lock().await.cancel_countdown();
    }

    #[zbus(name = "CancelSave")]
    async fn cancel_save(&self) {
        self.ctx.engine.lock().await.cancel_and_save();
    }

    #[zbus(name = "Cancel")]
    async fn cancel(&self) {
        self.ctx.engine.lock().await.cancel_and_discard();
    }

    async fn import_existing(
        &self,
        audio: String,
        transcript: String,
        notes: String,
        label: String,
    ) {
        self.ctx
            .engine
            .lock()
            .await
            .import_existing(&audio, &transcript, &notes, &label);
    }

    async fn summarize_meeting(
        &self,
        audio: String,
        transcript: String,
        notes: String,
        label: String,
    ) -> String {
        self.ctx
            .engine
            .lock()
            .await
            .summarize_meeting(&audio, &transcript, &notes, &label)
            .unwrap_or_default()
    }

    async fn cancel_job(&self, id: i32) {
        self.ctx.engine.lock().await.cancel_job(id as i64);
    }

    async fn retry_job(&self, id: i32) {
        self.ctx.engine.lock().await.retry_job(id as i64);
    }

    async fn dismiss_job(&self, id: i32) {
        self.ctx.engine.lock().await.dismiss_job(id as i64);
    }

    async fn job_folder(&self, id: i32) -> String {
        self.ctx
            .engine
            .lock()
            .await
            .job_folder(id as i64)
            .unwrap_or_default()
    }

    async fn output_folder(&self) -> String {
        self.ctx.engine.lock().await.output_folder()
    }

    async fn reload_config(&self) {
        self.ctx.engine.lock().await.reload_config();
        (self.ctx.on_reload)();
    }

    async fn open_window(&self) {
        // Spawn-vs-present decision; the actual spawn/present primitives were
        // wired into the supervisor by the daemon app.
        self.ctx.supervisor.lock().unwrap().open();
    }

    async fn get_snapshot(&self) -> String {
        self.ctx.engine.lock().await.snapshot_json()
    }

    async fn start_install(&self, spec: String) {
        let mut installs = self.ctx.installs.lock().await;
        if let Err(e) = installs.start(&spec) {
            log::warn!("Rejected install request: {e:#}");
        }
    }

    async fn get_installs(&self) -> String {
        self.ctx.installs.lock().await.running_json()
    }

    async fn quit(&self) {
        (self.ctx.on_quit)();
    }

    // ------------------------------------------------------------------
    // Signals pushed to the window (emitted by the daemon loop through the
    // generated Signals trait on the InterfaceRef).
    // ------------------------------------------------------------------

    #[zbus(signal)]
    async fn snapshot_changed(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
        json: String,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn error(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
        msg: String,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn output(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
        text: String,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn open_use_existing(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn present_window(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn install_progress(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
        key: String,
        text: String,
    ) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn install_finished(
        signal_emitter: &zbus::object_server::SignalEmitter<'_>,
        key: String,
        ok: bool,
        message: String,
    ) -> zbus::Result<()>;
}
