//! The daemon entry point (`gravaai --daemon`).
//!
//!
//! Runs the toolkit-free engine, tray, call detector and D-Bus service on a
//! single-threaded event design: one async loop task owns all engine mutation;
//! recorder worker threads, processing
//! children, install children and tray callbacks marshal back as messages.

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::mpsc;

use crate::audio::recorder::Recorder;
use crate::config::settings;
use crate::core::task_runner::{MainCallback, TaskRunner};
use crate::daemon::dbus_service::{self, ENGINE_NAME, ENGINE_PATH};
use crate::daemon::engine::{Engine, EngineHooks, ProcessorBackend};
use crate::daemon::install_manager::InstallManager;
use crate::daemon::installer::InstallEvent;
use crate::daemon::processor::{ChildEvent, ProcessorHandle, ProcessorLauncher};
use crate::daemon::window_supervisor::WindowSupervisor;
use crate::detection::call_detector::CallDetector;
use crate::ui::notifications::{notify, set_graphical_ready};
use crate::ui::tray::{tick_tray_animation, update_tray, AppTray};
use ksni::TrayMethods as _;

/// Messages marshalled onto the daemon loop (the "main thread").
enum DaemonMsg {
    EngineChanged,
    EngineError(String),
    EngineOutput(String),
    TrayCommand(String),
    CountdownTick,
    TimerTick(u64),
    RecorderError(String),
    EmitPresent,
    EmitUseExisting,
    WindowExited,
    ConfigReloaded,
    Quit,
}

struct LauncherBackend {
    launcher: ProcessorLauncher,
    handles: HashMap<i64, ProcessorHandle>,
}

impl ProcessorBackend for LauncherBackend {
    fn launch(&mut self, job_id: i64, audio: &str, transcript: &str, notes: &str) {
        let handle = self.launcher.launch(job_id, audio, transcript, notes);
        self.handles.insert(job_id, handle);
    }

    fn cancel(&mut self, job_id: i64) {
        if let Some(mut h) = self.handles.remove(&job_id) {
            h.cancel();
        }
    }
}

impl Drop for LauncherBackend {
    fn drop(&mut self) {
        // A tray Quit is a graceful application shutdown.  Do not use the
        // user-cancel path here: it sends SIGKILL and can leave a processor's
        // partial output in an indeterminate state. The child reader threads
        // continue to reap after receiving SIGTERM.
        for (_, mut handle) in self.handles.drain() {
            handle.terminate_gracefully();
        }
    }
}

/// Ask the window child to exit cleanly before the daemon exits.
///
/// A kept-in-memory (hidden) window would otherwise be orphaned and linger
/// after the daemon quits. Qt handles SIGTERM through its native event loop;
/// we wait for it instead of using `Child::kill` (SIGKILL), which could lose
/// pending settings or leave a corrupted log.
fn shutdown_window(slot: &Arc<Mutex<Option<Child>>>, ctx: &Arc<dbus_service::ServiceCtx>) {
    let mut guard = slot.lock().unwrap();
    if let Some(mut child) = guard.take() {
        request_child_term(&child);
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => break,
                Ok(None) if std::time::Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(50));
                }
                Ok(None) => {
                    log::warn!(
                        "Qt window did not exit after SIGTERM; leaving it untouched (no SIGKILL)"
                    );
                    break;
                }
                Err(err) => {
                    log::warn!("Could not reap Qt window after SIGTERM: {err}");
                    break;
                }
            }
        }
    }
    ctx.supervisor.lock().unwrap().on_child_exit();
}

#[cfg(unix)]
fn request_child_term(child: &Child) {
    // SAFETY: sending SIGTERM to the pid owned by this Child is the same
    // process-control boundary used by ffmpeg's graceful recorder stop.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(child.id() as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_child_term(child: &Child) {
    // Windows has no portable SIGTERM equivalent in std; this path is not
    // used by the Linux release, but still avoids an unconditional force kill.
    let _ = child.id();
}

/// Return whether a well-known name is currently owned on this session bus.
async fn name_has_owner(conn: &zbus::Connection, name: &str) -> bool {
    let Ok(bus_name) = zbus::names::BusName::try_from(name) else {
        return false;
    };
    let Ok(proxy) = zbus::fdo::DBusProxy::new(conn).await else {
        return false;
    };
    proxy.name_has_owner(bus_name).await.unwrap_or(false)
}

/// True when our connection owns the engine well-known name.
async fn name_is_ours(conn: &zbus::Connection) -> bool {
    let Ok(bus_name) = zbus::names::BusName::try_from(ENGINE_NAME) else {
        return false;
    };
    let Ok(proxy) = zbus::fdo::DBusProxy::new(conn).await else {
        return false;
    };
    let owner = proxy.get_name_owner(bus_name).await.ok();
    let unique = conn.unique_name().map(|n| n.to_string());
    match (owner, unique) {
        (Some(o), Some(u)) => o.to_string() == u,
        _ => false,
    }
}

pub fn run_daemon() -> i32 {
    crate::utils::logging::setup_logging("daemon");
    if settings::migrate_key_to_keyring() {
        log::info!("Migrated API key into the keyring");
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");
    rt.block_on(async_main())
}

/// A graphical host/tray is part of the application's contract.  Returning a
/// distinct non-zero status lets a direct invocation and a client supervisor
/// distinguish "no usable desktop" from a clean duplicate-launch no-op.
const DAEMON_STARTUP_FAILURE: i32 = 74;

async fn async_main() -> i32 {
    let (msg_tx, mut msg_rx) = mpsc::unbounded_channel::<DaemonMsg>();
    let (child_tx, mut child_rx) = mpsc::unbounded_channel::<(i64, ChildEvent)>();
    let (install_tx, mut install_rx) = mpsc::unbounded_channel::<(String, InstallEvent)>();
    let (install_prog_tx, mut install_prog_rx) = mpsc::unbounded_channel::<(String, String)>();
    let (install_done_tx, mut install_done_rx) =
        mpsc::unbounded_channel::<(String, bool, String)>();

    // --- engine -----------------------------------------------------------
    let msg = msg_tx.clone();
    let scheduler: crate::core::task_runner::Scheduler = Arc::new(move |cb: MainCallback| {
        let msg = msg.clone();
        std::thread::Builder::new()
            .name("task-callback".into())
            .spawn(move || {
                cb();
                let _ = msg.send(DaemonMsg::EngineChanged);
            })
            .ok();
    });
    let runner = TaskRunner::new(Some(scheduler));

    let msg2 = msg_tx.clone();
    let msg3 = msg_tx.clone();
    let factory =
        move |output: PathBuf, mode: String, quality: String| -> anyhow::Result<Recorder> {
            let t = msg2.clone();
            let e = msg3.clone();
            Ok(Recorder::new(
                output,
                mode,
                quality,
                Some(Box::new(move |elapsed| {
                    let _ = t.send(DaemonMsg::TimerTick(elapsed));
                })),
                Some(Box::new(move |msg| {
                    let _ = e.send(DaemonMsg::RecorderError(msg));
                })),
            ))
        };
    let tick_tx = msg_tx.clone();
    let request_tick = move || {
        let tick_tx = tick_tx.clone();
        std::thread::Builder::new()
            .name("countdown-tick".into())
            .spawn(move || {
                std::thread::sleep(Duration::from_secs(1));
                let _ = tick_tx.send(DaemonMsg::CountdownTick);
            })
            .ok();
    };

    let hooks = EngineHooks {
        on_change: {
            let tx = msg_tx.clone();
            Box::new(move || {
                let _ = tx.send(DaemonMsg::EngineChanged);
            })
        },
        on_error: {
            let tx = msg_tx.clone();
            Box::new(move |m| {
                let _ = tx.send(DaemonMsg::EngineError(m.to_string()));
            })
        },
        on_output: {
            let tx = msg_tx.clone();
            Box::new(move |t| {
                let _ = tx.send(DaemonMsg::EngineOutput(t.to_string()));
            })
        },
    };

    let backend = LauncherBackend {
        launcher: ProcessorLauncher::new(child_tx),
        handles: HashMap::new(),
    };
    let cfg = settings::load();
    let call_detection_enabled = cfg.call_detection_enabled;
    let mut engine = Engine::new(
        cfg,
        runner,
        factory,
        crate::audio::devices::validate_devices,
        request_tick,
        crate::core::job_manager::JobManager::new(None),
        Box::new(backend),
        hooks,
    );
    engine.restore_persisted_jobs();

    // --- installs ----------------------------------------------------------
    let installs = InstallManager::new(install_tx.clone(), install_prog_tx, install_done_tx);

    // --- window child slot + supervisor ------------------------------------
    let child_slot: Arc<Mutex<Option<Child>>> = Arc::new(Mutex::new(None));
    let slot_clone = child_slot.clone();
    let tx_clone = msg_tx.clone();
    let spawn_fn = move || {
        // Share the daemon's AppImage mount — do not re-exec $APPIMAGE. The
        // Qt process is a separate companion so this daemon remains free of
        // Qt dependencies in both the linker and the runtime.
        let exe = crate::utils::exe::internal_ui_exe();
        let stderr = crate::utils::logging::open_window_stderr();
        let mut command = Command::new(&exe);
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(stderr.map(Stdio::from).unwrap_or_else(|_| Stdio::null()));
        match command.spawn() {
            Ok(child) => {
                *slot_clone.lock().unwrap() = Some(child);
                // Reaper: poll until exit, then report back (no zombies).
                let slot2 = slot_clone.clone();
                let tx2 = tx_clone.clone();
                std::thread::Builder::new()
                    .name("window-reaper".into())
                    .spawn(move || {
                        let born = std::time::Instant::now();
                        loop {
                            std::thread::sleep(Duration::from_millis(500));
                            let status = slot2
                                .lock()
                                .unwrap()
                                .as_mut()
                                .and_then(|c| c.try_wait().ok().flatten());
                            if let Some(status) = status {
                                if let Some(mut c) = slot2.lock().unwrap().take() {
                                    let _ = c.wait();
                                }
                                if born.elapsed() < Duration::from_secs(10)
                                    && status.code().is_some_and(|code| code != 0)
                                {
                                    let detail = format!(
                                        "Window exited during startup (code {}).",
                                        status.code().unwrap_or_default()
                                    );
                                    let _ = tx2.send(DaemonMsg::EngineError(format!(
                                        "{detail} Check the window log at {}.",
                                        crate::utils::logging::window_stderr_path().display()
                                    )));
                                } else if born.elapsed() < Duration::from_secs(10) {
                                    log::info!(
                                        "Qt window ended during startup without an application error"
                                    );
                                }
                                let _ = tx2.send(DaemonMsg::WindowExited);
                                break;
                            }
                        }
                    })
                    .ok();
            }
            Err(e) => {
                let msg = format!(
                    "Failed to start the Qt window: {e:#}. Check that the AppImage is complete."
                );
                log::error!("{msg}");
                let _ = tx_clone.send(DaemonMsg::EngineError(msg));
                let _ = tx_clone.send(DaemonMsg::WindowExited);
            }
        }
    };
    let tx_present = msg_tx.clone();
    let supervisor = WindowSupervisor::new(spawn_fn, move || {
        let _ = tx_present.send(DaemonMsg::EmitPresent);
    });

    // --- service context ----------------------------------------------------
    let quit_tx = msg_tx.clone();
    let reload_tx = msg_tx.clone();
    let ctx = Arc::new(dbus_service::ServiceCtx {
        engine: tokio::sync::Mutex::new(engine),
        installs: tokio::sync::Mutex::new(installs),
        supervisor: Mutex::new(supervisor),
        on_quit: Box::new(move || {
            let _ = quit_tx.send(DaemonMsg::Quit);
        }),
        on_reload: Box::new(move || {
            let _ = reload_tx.send(DaemonMsg::ConfigReloaded);
        }),
    });

    // --- D-Bus singleton guard ----------------------------------------------
    // Claim a private name before creating the tray.  Two simultaneous
    // launches must not both register a visible StatusNotifierItem while
    // racing for the public Engine name.
    let conn = match zbus::Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            log::error!("Failed to connect to session bus: {e:#}");
            return DAEMON_STARTUP_FAILURE;
        }
    };
    let lock_reply = conn
        .request_name_with_flags(
            dbus_service::DAEMON_LOCK_NAME,
            zbus::fdo::RequestNameFlags::DoNotQueue.into(),
        )
        .await;
    match lock_reply {
        Ok(zbus::fdo::RequestNameReply::PrimaryOwner)
        | Ok(zbus::fdo::RequestNameReply::AlreadyOwner) => {}
        Ok(reply) => {
            log::info!(
                "Another GravaAi daemon owns {}; exiting before tray startup ({reply})",
                dbus_service::DAEMON_LOCK_NAME
            );
            return 0;
        }
        Err(e) => {
            log::error!("Failed to claim daemon singleton: {e:#}");
            return DAEMON_STARTUP_FAILURE;
        }
    }
    if name_has_owner(&conn, ENGINE_NAME).await {
        log::info!(
            "Another GravaAi daemon already owns {ENGINE_NAME}; exiting before tray startup"
        );
        return 0;
    }

    // --- tray -----------------------------------------------------------------
    // A daemon without a registered tray is not a usable GravaAi instance.
    // Fail before exporting the Engine service so clients cannot open a window
    // or receive notifications when no graphical host can show our icon.
    let tray_tx = msg_tx.clone();
    let tray = AppTray::new(Arc::new(move |cmd: String| {
        let _ = tray_tx.send(DaemonMsg::TrayCommand(cmd));
    }));
    let tray_handle = match tray.spawn().await {
        Ok(h) => Some(h),
        Err(e) => {
            log::error!(
                "GravaAi requires a graphical StatusNotifier host; tray startup failed: {e:#}"
            );
            return DAEMON_STARTUP_FAILURE;
        }
    };

    // --- public D-Bus service ------------------------------------------------
    let iface = dbus_service::EngineIface::new(ctx.clone());
    if let Err(e) = conn.object_server().at(ENGINE_PATH, iface).await {
        log::error!("Failed to export engine interface: {e:#}");
        if let Some(h) = tray_handle.as_ref() {
            h.shutdown().await;
        }
        return DAEMON_STARTUP_FAILURE;
    }
    let engine_reply = conn
        .request_name_with_flags(ENGINE_NAME, zbus::fdo::RequestNameFlags::DoNotQueue.into())
        .await;
    match engine_reply {
        Ok(zbus::fdo::RequestNameReply::PrimaryOwner)
        | Ok(zbus::fdo::RequestNameReply::AlreadyOwner) => {
            // Confirm we actually own it — a second daemon must not fight for
            // the name (log and exit cleanly instead).
            if !name_is_ours(&conn).await {
                log::warn!("Bus name {ENGINE_NAME} already owned — another daemon is running");
                if let Some(h) = tray_handle.as_ref() {
                    h.shutdown().await;
                }
                return 0;
            }
        }
        Ok(reply) => {
            log::warn!("Could not become the Engine owner ({reply}); another daemon is running");
            if let Some(h) = tray_handle.as_ref() {
                h.shutdown().await;
            }
            return DAEMON_STARTUP_FAILURE;
        }
        Err(e) => {
            log::error!("Failed to own bus name: {e:#}");
            if let Some(h) = tray_handle.as_ref() {
                h.shutdown().await;
            }
            return DAEMON_STARTUP_FAILURE;
        }
    }
    log::info!("Engine service registered at {ENGINE_PATH}");
    let iface_ref = match conn
        .object_server()
        .interface::<_, dbus_service::EngineIface>(ENGINE_PATH)
        .await
    {
        Ok(r) => r,
        Err(e) => {
            log::error!("Failed to get interface ref: {e:#}");
            if let Some(h) = tray_handle.as_ref() {
                h.shutdown().await;
            }
            return DAEMON_STARTUP_FAILURE;
        }
    };
    // The tray and Engine are both live now. Only from this point may any
    // engine/call-detector callback enqueue a desktop notification.
    set_graphical_ready(true);

    // --- call detector ----------------------------------------------------------
    let mut detector: Option<CallDetector> = None;
    if call_detection_enabled {
        detector = start_detector(msg_tx.clone(), ctx.clone());
    }

    // --- signals ------------------------------------------------------------------
    #[cfg(unix)]
    let mut sigterm =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).ok();
    #[cfg(unix)]
    let mut sigint = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt()).ok();

    #[cfg(unix)]
    async fn wait_unix_signal(sig: Option<&mut tokio::signal::unix::Signal>) {
        match sig {
            Some(s) => {
                s.recv().await;
            }
            None => std::future::pending::<()>().await,
        }
    }

    log::info!("Daemon started");

    // Not tied to any distro: never install anything here — tell the user
    // what is missing instead (tray-only users would otherwise get silence).
    if let Some(msg) = crate::utils::dependencies::describe_missing_required() {
        log::error!("{msg}");
        notify("GravaAi", &msg);
    }

    // Initial paint.
    refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;

    // Tray recording/processing effects: recompose pixmap only (~12 fps).
    let mut tray_anim = tokio::time::interval(Duration::from_millis(80));
    tray_anim.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        enum Wake {
            Msg(DaemonMsg),
            Child((i64, ChildEvent)),
            Install((String, InstallEvent)),
            InstallProg((String, String)),
            InstallDone((String, bool, String)),
            TrayAnim,
            Signal,
        }
        let wake = {
            #[cfg(unix)]
            {
                tokio::select! {
                    biased;
                    Some(m) = msg_rx.recv() => Wake::Msg(m),
                    Some(c) = child_rx.recv() => Wake::Child(c),
                    Some(i) = install_rx.recv() => Wake::Install(i),
                    Some(p) = install_prog_rx.recv() => Wake::InstallProg(p),
                    Some(d) = install_done_rx.recv() => Wake::InstallDone(d),
                    _ = tray_anim.tick() => Wake::TrayAnim,
                    _ = wait_unix_signal(sigterm.as_mut()) => Wake::Signal,
                    _ = wait_unix_signal(sigint.as_mut()) => Wake::Signal,
                    else => break,
                }
            }
            #[cfg(not(unix))]
            {
                tokio::select! {
                    biased;
                    Some(m) = msg_rx.recv() => Wake::Msg(m),
                    Some(c) = child_rx.recv() => Wake::Child(c),
                    Some(i) = install_rx.recv() => Wake::Install(i),
                    Some(p) = install_prog_rx.recv() => Wake::InstallProg(p),
                    Some(d) = install_done_rx.recv() => Wake::InstallDone(d),
                    _ = tray_anim.tick() => Wake::TrayAnim,
                    else => break,
                }
            }
        };
        match wake {
            Wake::TrayAnim => {
                // Pixmap-only; never push a D-Bus snapshot on anim ticks.
                if let Some(h) = tray_handle.as_ref() {
                    if h.is_closed() {
                        set_graphical_ready(false);
                        log::error!(
                            "StatusNotifier host disconnected; GravaAi is stopping because the tray is required"
                        );
                        break;
                    }
                    tick_tray_animation(h).await;
                }
            }
            Wake::Signal => break,
            Wake::Msg(DaemonMsg::Quit) => break,
            Wake::Msg(DaemonMsg::EngineChanged) => {
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::EngineError(m)) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.error(m.clone()).await;
                // Tray-only users have no window to show the error in —
                // surface it as a desktop notification instead of silence.
                notify("GravaAi", &m);
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::EngineOutput(t)) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.output(t.clone()).await;
                notify("GravaAi", &t);
            }
            Wake::Msg(DaemonMsg::TrayCommand(cmd)) => {
                if handle_tray_command(&ctx, &cmd).await {
                    break;
                }
                if cmd == crate::core::commands::USE_EXISTING {
                    // The window may still be spawning; give it a moment to
                    // subscribe before asking it to open the file picker.
                    let tx = msg_tx.clone();
                    let alive = ctx.supervisor.lock().unwrap().is_alive();
                    std::thread::Builder::new()
                        .name("use-existing-delay".into())
                        .spawn(move || {
                            if !alive {
                                std::thread::sleep(Duration::from_secs(2));
                            }
                            let _ = tx.send(DaemonMsg::EmitUseExisting);
                        })
                        .ok();
                }
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::CountdownTick) => {
                ctx.engine.lock().await.countdown_tick();
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::TimerTick(elapsed)) => {
                ctx.engine.lock().await.timer_tick(elapsed);
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::RecorderError(m)) => {
                ctx.engine.lock().await.recorder_error(&m);
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.error(m.clone()).await;
                notify("GravaAi", &m);
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Msg(DaemonMsg::EmitPresent) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.present_window().await;
            }
            Wake::Msg(DaemonMsg::EmitUseExisting) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.open_use_existing().await;
            }
            Wake::Msg(DaemonMsg::WindowExited) => {
                ctx.supervisor.lock().unwrap().on_child_exit();
            }
            Wake::InstallProg((k, t)) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.install_progress(k, t).await;
            }
            Wake::InstallDone((k, ok, m)) => {
                use dbus_service::EngineIfaceSignals as _;
                let _ = iface_ref.install_finished(k.clone(), ok, m.clone()).await;
                if !ok {
                    // Installs survive the Settings window closing, so a bare
                    // D-Bus signal may have no listener — notify as well so a
                    // failure (e.g. whisper.cpp download) never fails silently.
                    let detail = if m.trim().is_empty() {
                        "Install failed. Check the logs for details.".to_string()
                    } else {
                        format!("Install {k} failed: {}", m.trim())
                    };
                    log::error!("{detail}");
                    notify("GravaAi", &detail);
                }
            }
            Wake::Msg(DaemonMsg::ConfigReloaded) => {
                let cfg = settings::load();
                let want_detector = cfg.call_detection_enabled;
                let has_detector = detector.is_some();
                if want_detector && !has_detector {
                    detector = start_detector(msg_tx.clone(), ctx.clone());
                } else if !want_detector && has_detector {
                    if let Some(mut d) = detector.take() {
                        d.stop();
                    }
                }
            }
            Wake::Child((job_id, ev)) => {
                ctx.engine.lock().await.handle_child_event(job_id, ev);
                refresh(&ctx, &iface_ref, tray_handle.as_ref()).await;
            }
            Wake::Install((key, ev)) => {
                ctx.installs.lock().await.handle_event(key, ev);
            }
        }
    }

    // Make the graphical invariant visible to every late worker callback
    // before beginning child/task teardown.
    set_graphical_ready(false);
    log::info!("Daemon shutting down");
    // Ask the window child to exit first so a hidden window doesn't outlive us.
    shutdown_window(&child_slot, &ctx);
    if let Some(mut d) = detector.take() {
        d.stop();
    }
    // Finish any active recording (keep audio) and let jobs drain with a
    // bounded grace period.
    {
        let mut engine = ctx.engine.lock().await;
        engine.prepare_quit();
        engine.shutdown_tasks();
    }
    ctx.installs.lock().await.shutdown();
    // Stop an Ollama server this app auto-started (recorded pid only — a
    // server that was already running has no record and is left alone).
    // Shutdown is intentionally quiet: once the tray is being torn down there
    // is no icon through which an informational notification could be paired.
    let _ = crate::services::ollama_service::shutdown_owned_server();
    if let Some(h) = tray_handle {
        h.shutdown().await;
    }
    0
}

/// Re-render tray + push snapshot to the window.
/// Drains queued controller callbacks first (worker threads queue them and
/// re-notify through `EngineChanged`).
async fn refresh(
    ctx: &Arc<dbus_service::ServiceCtx>,
    iface: &zbus::object_server::InterfaceRef<dbus_service::EngineIface>,
    tray: Option<&ksni::Handle<AppTray>>,
) {
    use dbus_service::EngineIfaceSignals as _;
    let mut engine = ctx.engine.lock().await;
    engine.drain_events();
    let snapshot = engine.snapshot_json();
    let state = engine.state_name().to_string();
    let processing: Vec<(i64, String)> = engine
        .processing_jobs()
        .iter()
        .map(|j| (j.job_id, j.label.clone()))
        .collect();
    drop(engine);
    let _ = iface.snapshot_changed(snapshot).await;
    if let Some(h) = tray {
        update_tray(h, state, processing).await;
    }
}

/// Returns true when the daemon should quit (tray Quit).
async fn handle_tray_command(ctx: &Arc<dbus_service::ServiceCtx>, cmd: &str) -> bool {
    use crate::core::commands::*;
    let mut engine = ctx.engine.lock().await;
    engine.reload_config();
    match cmd {
        RECORD_HEADPHONES => engine.start_recording("headphones"),
        RECORD_SPEAKER => engine.start_recording("speaker"),
        PAUSE => engine.pause(),
        RESUME => engine.resume(),
        STOP => engine.stop(),
        CANCEL_SAVE => engine.cancel_and_save(),
        CANCEL => engine.cancel_and_discard(),
        CANCEL_COUNTDOWN => engine.cancel_countdown(),
        SHOW_WINDOW | USE_EXISTING => {
            drop(engine);
            ctx.supervisor.lock().unwrap().open();
        }
        QUIT => return true,
        _ if cmd.starts_with("cancel_job:") => {
            if let Ok(id) = cmd["cancel_job:".len()..].parse::<i64>() {
                engine.cancel_job(id);
            }
        }
        _ => {}
    }
    false
}

fn start_detector(
    msg_tx: mpsc::UnboundedSender<DaemonMsg>,
    ctx: Arc<dbus_service::ServiceCtx>,
) -> Option<CallDetector> {
    let mut detector = CallDetector::new(move || {
        // Suppress while the engine is already active.
        let active = ctx
            .engine
            .try_lock()
            .map(|e| e.state() != crate::core::state_machine::State::Idle)
            .unwrap_or(false);
        if active {
            log::debug!("Call detected but engine already active — suppressing notification");
            return;
        }
        crate::ui::notifications::notify(
            "Call Detected",
            "A call may have started. Open GravaAi to start recording.",
        );
        let _ = msg_tx.send(DaemonMsg::EngineChanged);
    });
    detector.start();
    Some(detector)
}
