//! D-Bus client for the Engine service.
//!
//! The window process renders snapshots fetched over D-Bus (`GetSnapshot`,
//! kept fresh by `SnapshotChanged`) and forwards clicks back as method calls.
//! Fire-and-forget for commands; getters await replies.

use crate::core::daemon_watch::should_exit_on_owner_change;
use crate::daemon::dbus_service::ENGINE_NAME;

/// Generated proxy struct is `EngineProxy` (from trait `Engine`).
#[zbus::proxy(
    interface = "io.github.jmarceno.GravaAi.Engine",
    default_service = "io.github.jmarceno.GravaAi",
    default_path = "/io/github/jmarceno/GravaAi"
)]
pub trait Engine {
    fn start_recording(&self, mode: &str) -> zbus::Result<()>;
    fn start_custom_recording(&self, devices_json: &str) -> zbus::Result<String>;
    fn set_title(&self, title: &str) -> zbus::Result<()>;
    fn pause(&self) -> zbus::Result<()>;
    fn resume(&self) -> zbus::Result<()>;
    fn stop(&self) -> zbus::Result<()>;
    fn cancel_countdown(&self) -> zbus::Result<()>;
    #[zbus(name = "CancelSave")]
    fn cancel_save(&self) -> zbus::Result<()>;
    #[zbus(name = "Cancel")]
    fn cancel(&self) -> zbus::Result<()>;
    fn import_existing(
        &self,
        audio: &str,
        transcript: &str,
        notes: &str,
        label: &str,
    ) -> zbus::Result<()>;
    fn summarize_meeting(
        &self,
        audio: &str,
        transcript: &str,
        notes: &str,
        label: &str,
    ) -> zbus::Result<String>;
    fn transcribe_meeting(
        &self,
        audio: &str,
        transcript: &str,
        notes: &str,
        label: &str,
    ) -> zbus::Result<String>;
    fn cancel_job(&self, id: i32) -> zbus::Result<()>;
    fn retry_job(&self, id: i32) -> zbus::Result<()>;
    fn dismiss_job(&self, id: i32) -> zbus::Result<()>;
    fn job_folder(&self, id: i32) -> zbus::Result<String>;
    fn output_folder(&self) -> zbus::Result<String>;
    fn reload_config(&self) -> zbus::Result<()>;
    fn open_window(&self) -> zbus::Result<()>;
    fn get_snapshot(&self) -> zbus::Result<String>;
    fn start_install(&self, spec: &str) -> zbus::Result<()>;
    fn get_installs(&self) -> zbus::Result<String>;
    fn quit(&self) -> zbus::Result<()>;

    #[zbus(signal)]
    fn snapshot_changed(&self, json: String) -> zbus::Result<()>;

    #[zbus(signal)]
    fn error(&self, msg: String) -> zbus::Result<()>;

    #[zbus(signal)]
    fn output(&self, text: String) -> zbus::Result<()>;

    #[zbus(signal)]
    fn open_use_existing(&self) -> zbus::Result<()>;

    #[zbus(signal)]
    fn present_window(&self) -> zbus::Result<()>;

    #[zbus(signal)]
    fn install_progress(&self, key: String, text: String) -> zbus::Result<()>;

    #[zbus(signal)]
    fn install_finished(&self, key: String, ok: bool, message: String) -> zbus::Result<()>;
}

// The macro above generates the `EngineProxy` D-Bus client struct.

// ---------------------------------------------------------------------------
// UI-friendly handle: fire-and-forget commands + async getters, shared
// across UI callbacks. The zbus connection is leaked ('static) since it lives
// as long as the window process.
// ---------------------------------------------------------------------------

/// Install progress/finished events forwarded to the open Models page.
#[derive(Debug, Clone)]
pub enum InstallUiEvent {
    Progress(String, String),
    Finished(String, bool, String),
}

#[derive(Clone)]
pub struct ProxyHandle {
    proxy: EngineProxy<'static>,
    rt: tokio::runtime::Handle,
}

impl ProxyHandle {
    pub fn new(conn: &'static zbus::Connection, rt: tokio::runtime::Handle) -> zbus::Result<Self> {
        let rt_c = rt.clone();
        Ok(Self {
            proxy: rt.block_on(async move { EngineProxy::new(conn).await })?,
            rt: rt_c,
        })
    }

    fn spawn<F>(&self, fut: F)
    where
        F: std::future::Future<Output = ()> + Send + 'static,
    {
        self.rt.spawn(fut);
    }

    // --- recording lifecycle (fire-and-forget) ---
    pub fn start_recording(&self, mode: &str) {
        let (p, mode) = (self.proxy.clone(), mode.to_string());
        self.spawn(async move {
            if let Err(e) = p.start_recording(&mode).await {
                log::error!("Engine.StartRecording failed: {e:#}");
            }
        });
    }

    pub fn set_title(&self, title: &str) {
        let (p, title) = (self.proxy.clone(), title.to_string());
        self.spawn(async move {
            if let Err(e) = p.set_title(&title).await {
                log::error!("Engine.SetTitle failed: {e:#}");
            }
        });
    }

    pub fn pause(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.pause().await {
                log::error!("Engine.Pause failed: {e:#}");
            }
        });
    }

    pub fn resume(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.resume().await {
                log::error!("Engine.Resume failed: {e:#}");
            }
        });
    }

    pub fn stop(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.stop().await {
                log::error!("Engine.Stop failed: {e:#}");
            }
        });
    }

    pub fn cancel_countdown(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.cancel_countdown().await {
                log::error!("Engine.CancelCountdown failed: {e:#}");
            }
        });
    }

    pub fn cancel_save(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.cancel_save().await {
                log::error!("Engine.CancelSave failed: {e:#}");
            }
        });
    }

    pub fn cancel(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.cancel().await {
                log::error!("Engine.Cancel failed: {e:#}");
            }
        });
    }

    // --- jobs ---
    pub fn import_existing(&self, audio: &str, transcript: &str, notes: &str, label: &str) {
        let (p, a, t, n, l) = (
            self.proxy.clone(),
            audio.to_string(),
            transcript.to_string(),
            notes.to_string(),
            label.to_string(),
        );
        self.spawn(async move {
            if let Err(e) = p.import_existing(&a, &t, &n, &l).await {
                log::error!("Engine.ImportExisting failed: {e:#}");
            }
        });
    }

    pub fn cancel_job(&self, job_id: i64) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.cancel_job(job_id as i32).await {
                log::error!("Engine.CancelJob failed: {e:#}");
            }
        });
    }

    pub fn retry_job(&self, job_id: i64) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.retry_job(job_id as i32).await {
                log::error!("Engine.RetryJob failed: {e:#}");
            }
        });
    }

    pub fn dismiss_job(&self, job_id: i64) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.dismiss_job(job_id as i32).await {
                log::error!("Engine.DismissJob failed: {e:#}");
            }
        });
    }

    // --- installs ---
    pub fn start_install(&self, spec_json: &str) {
        let (p, spec) = (self.proxy.clone(), spec_json.to_string());
        self.spawn(async move {
            if let Err(e) = p.start_install(&spec).await {
                log::error!("Engine.StartInstall failed: {e:#}");
            }
        });
    }

    pub fn reload_config(&self) {
        let p = self.proxy.clone();
        self.spawn(async move {
            if let Err(e) = p.reload_config().await {
                log::error!("Engine.ReloadConfig failed: {e:#}");
            }
        });
    }

    // --- async getters (called by the worker, never the Qt thread) ---
    fn block_on<F, T>(&self, fut: F) -> Option<T>
    where
        F: std::future::Future<Output = zbus::Result<T>>,
    {
        match self.rt.block_on(fut) {
            Ok(v) => Some(v),
            Err(e) => {
                log::error!("Engine call failed: {e:#}");
                None
            }
        }
    }

    pub fn get_snapshot(&self) -> String {
        let p = self.proxy.clone();
        self.block_on(async move { p.get_snapshot().await })
            .unwrap_or_default()
    }

    pub fn output_folder(&self) -> String {
        let p = self.proxy.clone();
        self.block_on(async move { p.output_folder().await })
            .unwrap_or_default()
    }

    pub fn summarize_meeting(
        &self,
        audio: &str,
        transcript: &str,
        notes: &str,
        label: &str,
    ) -> String {
        let (p, a, t, n, l) = (
            self.proxy.clone(),
            audio.to_string(),
            transcript.to_string(),
            notes.to_string(),
            label.to_string(),
        );
        self.block_on(async move { p.summarize_meeting(&a, &t, &n, &l).await })
            .unwrap_or_default()
    }

    pub fn get_installs(&self) -> String {
        let p = self.proxy.clone();
        self.block_on(async move { p.get_installs().await })
            .unwrap_or_else(|| "[]".to_string())
    }

    pub fn proxy(&self) -> &EngineProxy<'static> {
        &self.proxy
    }
}

/// Watch the Engine bus name; invoke `on_gone` when the daemon vanishes —
/// but only after the name was seen owned, so a startup race can't kill the
/// window early (see [`should_exit_on_owner_change`]).
pub async fn watch_daemon_owner(conn: zbus::Connection, on_gone: impl FnOnce() + Send + 'static) {
    let mut seen_owned = false;
    let proxy = match zbus::fdo::DBusProxy::new(&conn).await {
        Ok(p) => p,
        Err(_) => return,
    };
    // Poll (simple + robust across zbus versions; 2s cadence is plenty for a
    // daemon-lifetime watch).
    loop {
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        let Ok(bus_name) = zbus::names::BusName::try_from(ENGINE_NAME) else {
            return;
        };
        let has_owner: bool = proxy.name_has_owner(bus_name).await.unwrap_or(true);
        if has_owner {
            seen_owned = true;
        } else if should_exit_on_owner_change(seen_owned, has_owner) {
            on_gone();
            return;
        }
    }
}
