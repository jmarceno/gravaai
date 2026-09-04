//! Non-blocking Rust ↔ QML controller for the GravaAI window.
//!
//! The Qt thread owns only this QObject and presentation state. A dedicated
//! Tokio task owns the D-Bus proxy and all filesystem/network work, sending
//! owned event payloads through a standard channel which `poll_input` drains.

use std::path::Path;
use std::pin::Pin;
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};

use cxx_qt::CxxQtType;
use cxx_qt_lib::QString;
use futures_lite::StreamExt;

use crate::config::{defaults::Config, settings};
use crate::core::errors::{error_presentation, Presentation};
use crate::core::window_close::{resolve_close_action, CloseAction};
use crate::ui::engine_proxy::EngineProxy;
use crate::utils::meeting_scanner::{delete_meetings, rename_meeting_dir, scan_meetings};

use super::runtime;

type CommandTx = tokio::sync::mpsc::UnboundedSender<Command>;

#[derive(Debug)]
enum Command {
    StartRecording(String),
    SetTitle(String),
    Pause,
    Resume,
    Stop,
    CancelCountdown,
    CancelSave,
    CancelDiscard,
    CancelJob(i64),
    RetryJob(i64),
    DismissJob(i64),
    OpenJobFolder(i64),
    ImportExisting {
        audio: String,
        transcript: String,
        notes: String,
        label: String,
    },
    SummarizeMeeting {
        audio: String,
        transcript: String,
        notes: String,
        label: String,
    },
    RefreshMeetings,
    RenameMeeting {
        path: String,
        title: String,
    },
    DeleteMeetings(String),
    OpenMeetingFolder(String),
    OpenOutputFolder,
    LoadSettings,
    SaveSettings {
        json: String,
        confirm: bool,
    },
    ConfirmSaveSettings,
    RefreshInstalls,
    StartInstall(String),
    RequestClose,
    LaunchLepramim,
    Shutdown,
}

#[derive(Debug)]
enum Event {
    Ready,
    Snapshot(String),
    Settings(String),
    Meetings(String),
    Installs(String),
    Toast(String),
    Dialog { message: String, confirm: bool },
    Present,
    OpenUseExisting,
    CloseAction(CloseAction),
    Lepramim(bool),
    DaemonGone,
    Fatal(String),
}

struct UiRuntime {
    tx: CommandTx,
    rx: Receiver<Event>,
}

fn command_channel() -> (CommandTx, tokio::sync::mpsc::UnboundedReceiver<Command>) {
    tokio::sync::mpsc::unbounded_channel()
}

fn start_worker() -> UiRuntime {
    let (tx, mut cmd_rx) = command_channel();
    let (event_tx, event_rx) = mpsc::channel();
    std::thread::Builder::new()
        .name("gravaai-qt-dbus".into())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(err) => {
                    let _ = event_tx.send(Event::Fatal(format!(
                        "Could not start the UI worker: {err:#}"
                    )));
                    return;
                }
            };
            runtime.block_on(async move {
                if let Err(err) = worker_loop(&mut cmd_rx, event_tx.clone()).await {
                    let _ = event_tx.send(Event::Fatal(err));
                }
            });
        })
        .expect("spawn Qt D-Bus worker");
    UiRuntime { tx, rx: event_rx }
}

async fn worker_loop(
    cmd_rx: &mut tokio::sync::mpsc::UnboundedReceiver<Command>,
    event_tx: Sender<Event>,
) -> Result<(), String> {
    let connection = zbus::Connection::session()
        .await
        .map_err(|e| format!("Cannot connect to the session bus: {e:#}"))?;
    // The UI process lives only as long as this connection, so leaking the
    // connection is safe and allows signal tasks to own a 'static proxy.
    let connection: &'static zbus::Connection = Box::leak(Box::new(connection));
    let proxy = EngineProxy::new(connection)
        .await
        .map_err(|e| format!("Cannot connect to GravaAI daemon: {e:#}"))?;

    spawn_signal_tasks(proxy.clone(), event_tx.clone());
    let owner_conn = connection.clone();
    let gone_tx = event_tx.clone();
    tokio::spawn(crate::ui::engine_proxy::watch_daemon_owner(
        owner_conn,
        move || {
            let _ = gone_tx.send(Event::DaemonGone);
        },
    ));

    // A proxy can be constructed even when the well-known name is absent.
    // Perform one real daemon call before declaring the UI ready so a cold
    // start fails visibly and the supervisor can restart it.
    let initial_snapshot = proxy
        .get_snapshot()
        .await
        .map_err(|e| format!("Cannot read the GravaAI daemon snapshot: {e:#}"))?;
    let initial_installs = proxy
        .get_installs()
        .await
        .map_err(|e| format!("Cannot read the GravaAI install state: {e:#}"))?;
    let _ = event_tx.send(Event::Ready);
    let _ = event_tx.send(Event::Snapshot(initial_snapshot));
    let _ = event_tx.send(Event::Installs(initial_installs));
    send_settings(&event_tx);
    refresh_meetings_sync(&event_tx);
    send_lepramim_status(&event_tx).await;

    let mut pending_settings: Option<Config> = None;
    while let Some(command) = cmd_rx.recv().await {
        if matches!(command, Command::Shutdown) {
            break;
        }
        handle_command(command, &proxy, &event_tx, &mut pending_settings).await;
    }
    Ok(())
}

fn spawn_signal_tasks(proxy: EngineProxy<'static>, tx: Sender<Event>) {
    let p = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_snapshot_changed().await else {
            return;
        };
        while let Some(signal) = stream.next().await {
            if let Ok(args) = signal.args() {
                let _ = out.send(Event::Snapshot(args.json.clone()));
            }
        }
    });

    let p = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_error().await else {
            return;
        };
        while let Some(signal) = stream.next().await {
            if let Ok(args) = signal.args() {
                let _ = out.send(Event::Toast(args.msg.clone()));
            }
        }
    });

    let p = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_output().await else {
            return;
        };
        while let Some(signal) = stream.next().await {
            if let Ok(args) = signal.args() {
                let _ = out.send(Event::Toast(args.text.clone()));
            }
        }
    });

    let p = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_open_use_existing().await else {
            return;
        };
        while stream.next().await.is_some() {
            let _ = out.send(Event::OpenUseExisting);
        }
    });

    let p = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_present_window().await else {
            return;
        };
        while stream.next().await.is_some() {
            let _ = out.send(Event::Present);
        }
    });

    let p = proxy.clone();
    let refresh_proxy = proxy.clone();
    let out = tx.clone();
    tokio::spawn(async move {
        let Ok(mut stream) = p.receive_install_progress().await else {
            return;
        };
        while let Some(signal) = stream.next().await {
            if let Ok(args) = signal.args() {
                let _ = out.send(Event::Toast(format!("{}: {}", args.key, args.text)));
                if let Ok(json) = refresh_proxy.get_installs().await {
                    let _ = out.send(Event::Installs(json));
                }
            }
        }
    });

    let refresh_proxy = proxy.clone();
    let out = tx;
    tokio::spawn(async move {
        let Ok(mut stream) = proxy.receive_install_finished().await else {
            return;
        };
        while let Some(signal) = stream.next().await {
            if let Ok(args) = signal.args() {
                if args.ok {
                    let _ = out.send(Event::Toast(format!("Install complete: {}", args.key)));
                } else {
                    let _ = out.send(Event::Toast(format!(
                        "Install failed ({}): {}",
                        args.key, args.message
                    )));
                }
                if let Ok(json) = refresh_proxy.get_installs().await {
                    let _ = out.send(Event::Installs(json));
                }
            }
        }
    });
}

async fn handle_command(
    command: Command,
    proxy: &EngineProxy<'static>,
    tx: &Sender<Event>,
    pending_settings: &mut Option<Config>,
) {
    match command {
        Command::StartRecording(mode) => {
            call_unit(tx, proxy.start_recording(&mode).await, "StartRecording")
        }
        Command::SetTitle(title) => call_unit(tx, proxy.set_title(&title).await, "SetTitle"),
        Command::Pause => call_unit(tx, proxy.pause().await, "Pause"),
        Command::Resume => call_unit(tx, proxy.resume().await, "Resume"),
        Command::Stop => call_unit(tx, proxy.stop().await, "Stop"),
        Command::CancelCountdown => {
            call_unit(tx, proxy.cancel_countdown().await, "CancelCountdown")
        }
        Command::CancelSave => call_unit(tx, proxy.cancel_save().await, "CancelSave"),
        Command::CancelDiscard => call_unit(tx, proxy.cancel().await, "Cancel"),
        Command::CancelJob(id) => call_unit(tx, proxy.cancel_job(id as i32).await, "CancelJob"),
        Command::RetryJob(id) => call_unit(tx, proxy.retry_job(id as i32).await, "RetryJob"),
        Command::DismissJob(id) => call_unit(tx, proxy.dismiss_job(id as i32).await, "DismissJob"),
        Command::OpenJobFolder(id) => match proxy.job_folder(id as i32).await {
            Ok(path) if !path.trim().is_empty() => {
                open_path(&path, tx).await;
            }
            Ok(_) => {
                let _ = tx.send(Event::Toast("Job folder is not available yet.".into()));
            }
            Err(err) => send_error(tx, format!("Could not open job folder: {err:#}")),
        },
        Command::ImportExisting {
            audio,
            transcript,
            notes,
            label,
        } => call_unit(
            tx,
            proxy
                .import_existing(&audio, &transcript, &notes, &label)
                .await,
            "ImportExisting",
        ),
        Command::SummarizeMeeting {
            audio,
            transcript,
            notes,
            label,
        } => match proxy
            .summarize_meeting(&audio, &transcript, &notes, &label)
            .await
        {
            Ok(message) if !message.trim().is_empty() => {
                let _ = tx.send(Event::Toast(message));
            }
            Ok(_) => {}
            Err(err) => send_error(tx, format!("Could not summarize meeting: {err:#}")),
        },
        Command::RefreshMeetings => refresh_meetings_async(tx.clone()).await,
        Command::RenameMeeting { path, title } => {
            rename_meeting(&path, &title, tx);
        }
        Command::DeleteMeetings(paths_json) => delete_meeting_paths(&paths_json, tx),
        Command::OpenMeetingFolder(path) => {
            let cfg = settings::load();
            let valid = scan_meetings(&cfg.output_folder)
                .into_iter()
                .find(|meeting| meeting.path == Path::new(&path));
            match valid {
                Some(meeting) => {
                    open_path(&meeting.path.to_string_lossy(), tx).await;
                }
                None => {
                    let _ = tx.send(Event::Toast("Meeting is no longer in the library.".into()));
                }
            }
        }
        Command::OpenOutputFolder => match proxy.output_folder().await {
            Ok(path) if !path.trim().is_empty() => {
                open_path(&path, tx).await;
            }
            Ok(_) => {
                let _ = tx.send(Event::Toast("Output folder is not configured.".into()));
            }
            Err(err) => send_error(tx, format!("Could not read output folder: {err:#}")),
        },
        Command::LoadSettings => send_settings(tx),
        Command::SaveSettings { json, confirm } => {
            save_settings(&json, confirm, proxy, tx, pending_settings).await;
        }
        Command::ConfirmSaveSettings => {
            if let Some(cfg) = pending_settings.take() {
                persist_settings(cfg, proxy, tx).await;
            }
        }
        Command::RefreshInstalls => refresh_installs(proxy, tx).await,
        Command::StartInstall(spec) => {
            call_unit(tx, proxy.start_install(&spec).await, "StartInstall")
        }
        Command::RequestClose => {
            let cfg = settings::load();
            let _ = tx.send(Event::CloseAction(resolve_close_action(&cfg)));
        }
        Command::LaunchLepramim => launch_lepramim(tx).await,
        Command::Shutdown => {}
    }
}

/// Open a local folder through the Freedesktop portal.  No GUI-thread I/O or
/// shell helper is involved: the worker owns the D-Bus call and sends only a
/// toast result back to QML.
async fn open_path(path: &str, tx: &Sender<Event>) {
    let path = Path::new(path);
    let Some(uri) = file_uri(path) else {
        send_error(
            tx,
            format!("Could not open folder: invalid path {}", path.display()),
        );
        return;
    };
    let conn = match zbus::Connection::session().await {
        Ok(conn) => conn,
        Err(err) => {
            send_error(
                tx,
                format!("Could not open folder through the desktop portal: {err:#}"),
            );
            return;
        }
    };
    let portal = match zbus::Proxy::new(
        &conn,
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.OpenURI",
    )
    .await
    {
        Ok(proxy) => proxy,
        Err(err) => {
            send_error(
                tx,
                format!("The desktop file portal is unavailable: {err:#}"),
            );
            return;
        }
    };
    let options: std::collections::HashMap<&str, zbus::zvariant::Value<'_>> =
        std::collections::HashMap::new();
    let result: zbus::Result<zbus::zvariant::OwnedObjectPath> =
        portal.call("OpenURI", &("", uri.as_str(), options)).await;
    match result {
        Ok(_) => {
            let _ = tx.send(Event::Toast("Opened folder.".into()));
        }
        Err(err) => send_error(tx, format!("Could not open folder: {err:#}")),
    }
}

fn file_uri(path: &Path) -> Option<String> {
    let path = path
        .canonicalize()
        .ok()
        .or_else(|| path.is_absolute().then(|| path.to_path_buf()))?;
    let raw = path.to_string_lossy();
    let mut encoded = String::with_capacity(raw.len() + 7);
    for byte in raw.as_bytes() {
        match *byte {
            b'%' => encoded.push_str("%25"),
            b' ' => encoded.push_str("%20"),
            b'#' => encoded.push_str("%23"),
            b'?' => encoded.push_str("%3F"),
            b'\\' => encoded.push_str("%5C"),
            _ => encoded.push(*byte as char),
        }
    }
    Some(format!("file://{encoded}"))
}

fn call_unit(tx: &Sender<Event>, result: zbus::Result<()>, operation: &str) {
    if let Err(err) = result {
        send_error(tx, format!("Engine.{operation} failed: {err:#}"));
    }
}

fn send_error(tx: &Sender<Event>, message: String) {
    log::error!("{message}");
    match error_presentation(&message) {
        Presentation::Dialog => {
            let _ = tx.send(Event::Dialog {
                message,
                confirm: false,
            });
        }
        Presentation::Toast => {
            let _ = tx.send(Event::Toast(message));
        }
    }
}

async fn refresh_installs(proxy: &EngineProxy<'static>, tx: &Sender<Event>) {
    match proxy.get_installs().await {
        Ok(json) => {
            let _ = tx.send(Event::Installs(json));
        }
        Err(err) => send_error(tx, format!("Could not read installs: {err:#}")),
    }
}

fn send_settings(tx: &Sender<Event>) {
    match serde_json::to_string(&settings::load()) {
        Ok(json) => {
            let _ = tx.send(Event::Settings(json));
        }
        Err(err) => send_error(tx, format!("Could not serialize settings: {err:#}")),
    }
}

fn meeting_json() -> String {
    let cfg = settings::load();
    let rows: Vec<serde_json::Value> = scan_meetings(&cfg.output_folder)
        .into_iter()
        .map(|m| {
            serde_json::json!({
                "path": m.path.to_string_lossy(),
                "time_label": m.time_label,
                "title": m.title.unwrap_or_default(),
                "has_notes": m.has_notes,
                "has_transcript": m.has_transcript,
                "duration_seconds": m.duration_seconds.unwrap_or(0),
            })
        })
        .collect();
    serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into())
}

fn refresh_meetings_sync(tx: &Sender<Event>) {
    let _ = tx.send(Event::Meetings(meeting_json()));
}

async fn refresh_meetings_async(tx: Sender<Event>) {
    match tokio::task::spawn_blocking(meeting_json).await {
        Ok(json) => {
            let _ = tx.send(Event::Meetings(json));
        }
        Err(err) => send_error(&tx, format!("Could not scan meetings: {err:#}")),
    }
}

fn rename_meeting(path: &str, title: &str, tx: &Sender<Event>) {
    let cfg = settings::load();
    let meetings = scan_meetings(&cfg.output_folder);
    let Some(meeting) = meetings.iter().find(|m| m.path == Path::new(path)) else {
        let _ = tx.send(Event::Toast("Meeting is no longer in the library.".into()));
        return;
    };
    match rename_meeting_dir(meeting, title) {
        Ok(_) => {
            let _ = tx.send(Event::Meetings(meeting_json()));
        }
        Err(err) => send_error(tx, format!("Could not rename meeting: {err:#}")),
    }
}

fn delete_meeting_paths(paths_json: &str, tx: &Sender<Event>) {
    let paths: Vec<String> = serde_json::from_str(paths_json).unwrap_or_default();
    let cfg = settings::load();
    let meetings = scan_meetings(&cfg.output_folder);
    let selected: Vec<_> = meetings
        .into_iter()
        .filter(|m| paths.iter().any(|p| Path::new(p) == m.path))
        .collect();
    let (_ok, failures) = delete_meetings(&selected);
    if let Some((path, message)) = failures.first() {
        send_error(
            tx,
            format!("Could not delete {}: {message}", path.display()),
        );
    }
    let _ = tx.send(Event::Meetings(meeting_json()));
}

async fn save_settings(
    json: &str,
    confirm: bool,
    proxy: &EngineProxy<'static>,
    tx: &Sender<Event>,
    pending: &mut Option<Config>,
) {
    let cfg: Config = match serde_json::from_str(json) {
        Ok(cfg) => cfg,
        Err(err) => {
            send_error(tx, format!("Settings are invalid: {err:#}"));
            return;
        }
    };
    if !confirm {
        if let Some(warning) = settings::api_key_warning(&cfg) {
            *pending = Some(cfg);
            let _ = tx.send(Event::Dialog {
                message: warning,
                confirm: true,
            });
            return;
        }
    }
    persist_settings(cfg, proxy, tx).await;
}

async fn persist_settings(cfg: Config, proxy: &EngineProxy<'static>, tx: &Sender<Event>) {
    if let Err(err) = settings::save(&cfg) {
        send_error(tx, format!("Could not save settings: {err:#}"));
        return;
    }
    crate::utils::autostart::update_autostart(cfg.start_at_startup);
    if let Err(err) = proxy.reload_config().await {
        send_error(
            tx,
            format!("Settings saved but daemon reload failed: {err:#}"),
        );
    }
    send_settings(tx);
    let _ = tx.send(Event::Toast("Settings saved.".into()));
}

async fn send_lepramim_status(tx: &Sender<Event>) {
    let active = lepramim_is_active().await;
    let installed = crate::utils::desktop::find_lepramim_launch().is_some();
    let _ = tx.send(Event::Lepramim(active || installed));
}

async fn launch_lepramim(tx: &Sender<Event>) {
    if lepramim_is_active().await {
        let _ = tx.send(Event::Lepramim(true));
        let _ = tx.send(Event::Toast("Lepramim is already running.".into()));
        return;
    }
    let Some(launch) = crate::utils::desktop::find_lepramim_launch() else {
        let _ = tx.send(Event::Toast(
            "Lepramim is not installed (no desktop entry or executable was found).".into(),
        ));
        return;
    };
    match crate::utils::desktop::spawn_launch(&launch) {
        Ok(_) => {
            let _ = tx.send(Event::Lepramim(true));
            let _ = tx.send(Event::Toast("Opening Lepramim…".into()));
        }
        Err(err) => send_error(tx, format!("Could not open Lepramim: {err:#}")),
    }
}

async fn lepramim_is_active() -> bool {
    let Ok(conn) = zbus::Connection::session().await else {
        return false;
    };
    let Ok(proxy) = zbus::fdo::DBusProxy::new(&conn).await else {
        return false;
    };
    let Ok(name) = zbus::names::BusName::try_from("org.lepramim.App") else {
        return false;
    };
    proxy.name_has_owner(name).await.unwrap_or(false)
}

#[cxx_qt::bridge]
mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[qml_element]
        #[qproperty(bool, ready)]
        #[qproperty(bool, daemon_alive)]
        #[qproperty(bool, busy)]
        #[qproperty(bool, lepramim_ready)]
        #[qproperty(QString, selected_page)]
        #[qproperty(QString, snapshot_json)]
        #[qproperty(QString, settings_json)]
        #[qproperty(QString, meetings_json)]
        #[qproperty(QString, installs_json)]
        #[qproperty(QString, toast_message)]
        #[qproperty(i32, toast_serial)]
        #[qproperty(QString, dialog_message)]
        #[qproperty(bool, dialog_confirm)]
        #[qproperty(i32, dialog_serial)]
        #[qproperty(i32, present_serial)]
        #[qproperty(i32, import_serial)]
        #[qproperty(QString, close_action)]
        #[qproperty(i32, close_serial)]
        #[qproperty(QString, fatal_message)]
        type AppController = super::AppControllerRust;

        // Typed presentation signals are kept separate from the JSON/state
        // properties so QML pages can react without polling or serial-field
        // conventions.  The serial properties remain for compatibility with
        // simple bindings and diagnostics.
        #[qsignal]
        #[cxx_name = "toast"]
        fn toast(self: Pin<&mut Self>, message: QString);
        #[qsignal]
        #[cxx_name = "dialog"]
        fn dialog(self: Pin<&mut Self>, message: QString, confirm: bool);
        #[qsignal]
        #[cxx_name = "presentWindow"]
        fn present_window(self: Pin<&mut Self>);
        #[qsignal]
        #[cxx_name = "openImport"]
        fn open_import(self: Pin<&mut Self>);
        #[qsignal]
        #[cxx_name = "closeAction"]
        fn close_action_signal(self: Pin<&mut Self>, action: QString);
        #[qsignal]
        #[cxx_name = "fatalError"]
        fn fatal_error(self: Pin<&mut Self>, message: QString);

        #[qinvokable]
        fn bootstrap(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "pollInput"]
        fn poll_input(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "selectPage"]
        fn select_page(self: Pin<&mut Self>, page: QString);
        #[qinvokable]
        #[cxx_name = "setTitle"]
        fn set_title(self: Pin<&mut Self>, title: QString);
        #[qinvokable]
        #[cxx_name = "startRecording"]
        fn start_recording(self: Pin<&mut Self>, mode: QString);
        #[qinvokable]
        #[cxx_name = "pauseRecording"]
        fn pause_recording(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "resumeRecording"]
        fn resume_recording(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "stopRecording"]
        fn stop_recording(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "cancelCountdown"]
        fn cancel_countdown(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "cancelAndSave"]
        fn cancel_and_save(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "cancelAndDiscard"]
        fn cancel_and_discard(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "cancelJob"]
        fn cancel_job(self: Pin<&mut Self>, id: i64);
        #[qinvokable]
        #[cxx_name = "retryJob"]
        fn retry_job(self: Pin<&mut Self>, id: i64);
        #[qinvokable]
        #[cxx_name = "dismissJob"]
        fn dismiss_job(self: Pin<&mut Self>, id: i64);
        #[qinvokable]
        #[cxx_name = "openJobFolder"]
        fn open_job_folder(self: Pin<&mut Self>, id: i64);
        #[qinvokable]
        #[cxx_name = "importExisting"]
        fn import_existing(
            self: Pin<&mut Self>,
            audio: QString,
            transcript: QString,
            notes: QString,
            label: QString,
        );
        #[qinvokable]
        #[cxx_name = "summarizeMeeting"]
        fn summarize_meeting(
            self: Pin<&mut Self>,
            audio: QString,
            transcript: QString,
            notes: QString,
            label: QString,
        );
        #[qinvokable]
        #[cxx_name = "refreshMeetings"]
        fn refresh_meetings(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "renameMeeting"]
        fn rename_meeting(self: Pin<&mut Self>, path: QString, title: QString);
        #[qinvokable]
        #[cxx_name = "deleteMeetings"]
        fn delete_meetings(self: Pin<&mut Self>, paths_json: QString);
        #[qinvokable]
        #[cxx_name = "openMeetingFolder"]
        fn open_meeting_folder(self: Pin<&mut Self>, path: QString);
        #[qinvokable]
        #[cxx_name = "openOutputFolder"]
        fn open_output_folder(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "loadSettings"]
        fn load_settings(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "saveSettings"]
        fn save_settings(self: Pin<&mut Self>, json: QString, confirm: bool);
        #[qinvokable]
        #[cxx_name = "confirmSaveSettings"]
        fn confirm_save_settings(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "refreshInstalls"]
        fn refresh_installs(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "startInstall"]
        fn start_install(self: Pin<&mut Self>, spec: QString);
        #[qinvokable]
        #[cxx_name = "requestClose"]
        fn request_close(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "launchLepramim"]
        fn launch_lepramim(self: Pin<&mut Self>);
        #[qinvokable]
        #[cxx_name = "requestAppQuit"]
        fn request_app_quit(self: Pin<&mut Self>);
    }
}

pub struct AppControllerRust {
    ready: bool,
    daemon_alive: bool,
    busy: bool,
    lepramim_ready: bool,
    selected_page: QString,
    snapshot_json: QString,
    settings_json: QString,
    meetings_json: QString,
    installs_json: QString,
    toast_message: QString,
    toast_serial: i32,
    dialog_message: QString,
    dialog_confirm: bool,
    dialog_serial: i32,
    present_serial: i32,
    import_serial: i32,
    close_action: QString,
    close_serial: i32,
    fatal_message: QString,
    runtime: Option<UiRuntime>,
}

impl Default for AppControllerRust {
    fn default() -> Self {
        Self {
            ready: false,
            daemon_alive: false,
            busy: false,
            lepramim_ready: false,
            selected_page: QString::from("recorder"),
            snapshot_json: QString::from("{}"),
            settings_json: QString::from("{}"),
            meetings_json: QString::from("[]"),
            installs_json: QString::from("[]"),
            toast_message: QString::default(),
            toast_serial: 0,
            dialog_message: QString::default(),
            dialog_confirm: false,
            dialog_serial: 0,
            present_serial: 0,
            import_serial: 0,
            close_action: QString::from("hide"),
            close_serial: 0,
            fatal_message: QString::default(),
            runtime: None,
        }
    }
}

impl AppControllerRust {
    fn send(&self, command: Command) {
        if let Some(runtime) = &self.runtime {
            let _ = runtime.tx.send(command);
        }
    }
}

impl Drop for AppControllerRust {
    fn drop(&mut self) {
        if let Some(runtime) = &self.runtime {
            let _ = runtime.tx.send(Command::Shutdown);
        }
    }
}

impl qobject::AppController {
    fn apply_event(mut self: Pin<&mut Self>, event: Event) {
        match event {
            Event::Ready => {
                super::mark_ready_seen();
                self.as_mut().set_ready(true);
                self.as_mut().set_daemon_alive(true);
            }
            Event::Snapshot(json) => self.as_mut().set_snapshot_json(QString::from(&json)),
            Event::Settings(json) => self.as_mut().set_settings_json(QString::from(&json)),
            Event::Meetings(json) => self.as_mut().set_meetings_json(QString::from(&json)),
            Event::Installs(json) => self.as_mut().set_installs_json(QString::from(&json)),
            Event::Toast(message) => {
                self.as_mut().set_toast_message(QString::from(&message));
                let serial = *self.toast_serial() + 1;
                self.as_mut().set_toast_serial(serial);
                self.as_mut().toast(QString::from(&message));
            }
            Event::Dialog { message, confirm } => {
                self.as_mut().set_dialog_message(QString::from(&message));
                self.as_mut().set_dialog_confirm(confirm);
                let serial = *self.dialog_serial() + 1;
                self.as_mut().set_dialog_serial(serial);
                self.as_mut().dialog(QString::from(&message), confirm);
            }
            Event::Present => {
                let serial = *self.present_serial() + 1;
                self.as_mut().set_present_serial(serial);
                self.as_mut().present_window();
            }
            Event::OpenUseExisting => {
                let serial = *self.import_serial() + 1;
                self.as_mut().set_import_serial(serial);
                self.as_mut().open_import();
            }
            Event::CloseAction(action) => {
                let action = match action {
                    CloseAction::Hide => "hide",
                    CloseAction::Exit => "exit",
                };
                self.as_mut().set_close_action(QString::from(action));
                let serial = *self.close_serial() + 1;
                self.as_mut().set_close_serial(serial);
                self.as_mut().close_action_signal(QString::from(action));
            }
            Event::Lepramim(ready) => self.as_mut().set_lepramim_ready(ready),
            Event::DaemonGone => {
                self.as_mut().set_daemon_alive(false);
                self.as_mut().set_ready(false);
                runtime::request_quit();
            }
            Event::Fatal(message) => {
                self.as_mut().set_fatal_message(QString::from(&message));
                self.as_mut().set_dialog_message(QString::from(&message));
                self.as_mut().set_dialog_confirm(false);
                let serial = *self.dialog_serial() + 1;
                self.as_mut().set_dialog_serial(serial);
                self.as_mut().set_busy(false);
                self.as_mut().fatal_error(QString::from(&message));
                runtime::request_exit(70);
            }
        }
    }

    fn bootstrap(mut self: Pin<&mut Self>) {
        if self.rust().runtime.is_some() {
            return;
        }
        self.as_mut().rust_mut().runtime = Some(start_worker());
        self.as_mut().set_busy(true);
    }

    fn poll_input(mut self: Pin<&mut Self>) {
        let events = {
            let mut rust = self.as_mut().rust_mut();
            let Some(runtime) = rust.runtime.as_mut() else {
                return;
            };
            let mut events = Vec::new();
            for _ in 0..256 {
                match runtime.rx.try_recv() {
                    Ok(event) => events.push(event),
                    Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
                }
            }
            events
        };
        for event in events {
            self.as_mut().apply_event(event);
        }
        if *self.ready() {
            self.as_mut().set_busy(false);
        }
    }

    fn select_page(mut self: Pin<&mut Self>, page: QString) {
        self.as_mut().set_selected_page(page);
    }

    fn set_title(self: Pin<&mut Self>, title: QString) {
        self.rust().send(Command::SetTitle(String::from(title)));
    }

    fn start_recording(self: Pin<&mut Self>, mode: QString) {
        self.rust()
            .send(Command::StartRecording(String::from(mode)));
    }

    fn pause_recording(self: Pin<&mut Self>) {
        self.rust().send(Command::Pause);
    }
    fn resume_recording(self: Pin<&mut Self>) {
        self.rust().send(Command::Resume);
    }
    fn stop_recording(self: Pin<&mut Self>) {
        self.rust().send(Command::Stop);
    }
    fn cancel_countdown(self: Pin<&mut Self>) {
        self.rust().send(Command::CancelCountdown);
    }
    fn cancel_and_save(self: Pin<&mut Self>) {
        self.rust().send(Command::CancelSave);
    }
    fn cancel_and_discard(self: Pin<&mut Self>) {
        self.rust().send(Command::CancelDiscard);
    }
    fn cancel_job(self: Pin<&mut Self>, id: i64) {
        self.rust().send(Command::CancelJob(id));
    }
    fn retry_job(self: Pin<&mut Self>, id: i64) {
        self.rust().send(Command::RetryJob(id));
    }
    fn dismiss_job(self: Pin<&mut Self>, id: i64) {
        self.rust().send(Command::DismissJob(id));
    }
    fn open_job_folder(self: Pin<&mut Self>, id: i64) {
        self.rust().send(Command::OpenJobFolder(id));
    }

    fn import_existing(
        self: Pin<&mut Self>,
        audio: QString,
        transcript: QString,
        notes: QString,
        label: QString,
    ) {
        self.rust().send(Command::ImportExisting {
            audio: String::from(audio),
            transcript: String::from(transcript),
            notes: String::from(notes),
            label: String::from(label),
        });
    }

    fn summarize_meeting(
        self: Pin<&mut Self>,
        audio: QString,
        transcript: QString,
        notes: QString,
        label: QString,
    ) {
        self.rust().send(Command::SummarizeMeeting {
            audio: String::from(audio),
            transcript: String::from(transcript),
            notes: String::from(notes),
            label: String::from(label),
        });
    }

    fn refresh_meetings(self: Pin<&mut Self>) {
        self.rust().send(Command::RefreshMeetings);
    }
    fn rename_meeting(self: Pin<&mut Self>, path: QString, title: QString) {
        self.rust().send(Command::RenameMeeting {
            path: String::from(path),
            title: String::from(title),
        });
    }
    fn delete_meetings(self: Pin<&mut Self>, paths_json: QString) {
        self.rust()
            .send(Command::DeleteMeetings(String::from(paths_json)));
    }
    fn open_meeting_folder(self: Pin<&mut Self>, path: QString) {
        self.rust()
            .send(Command::OpenMeetingFolder(String::from(path)));
    }
    fn open_output_folder(self: Pin<&mut Self>) {
        self.rust().send(Command::OpenOutputFolder);
    }
    fn load_settings(self: Pin<&mut Self>) {
        self.rust().send(Command::LoadSettings);
    }
    fn save_settings(self: Pin<&mut Self>, json: QString, confirm: bool) {
        self.rust().send(Command::SaveSettings {
            json: String::from(json),
            confirm,
        });
    }
    fn confirm_save_settings(self: Pin<&mut Self>) {
        self.rust().send(Command::ConfirmSaveSettings);
    }
    fn refresh_installs(self: Pin<&mut Self>) {
        self.rust().send(Command::RefreshInstalls);
    }
    fn start_install(self: Pin<&mut Self>, spec: QString) {
        self.rust().send(Command::StartInstall(String::from(spec)));
    }
    fn request_close(self: Pin<&mut Self>) {
        self.rust().send(Command::RequestClose);
    }
    fn launch_lepramim(self: Pin<&mut Self>) {
        self.rust().send(Command::LaunchLepramim);
    }
    fn request_app_quit(self: Pin<&mut Self>) {
        runtime::request_quit();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn meeting_json_is_an_array() {
        let json = meeting_json();
        assert!(json.starts_with('['));
        assert!(json.ends_with(']'));
    }

    #[test]
    fn close_policy_is_not_inverted() {
        assert_eq!(resolve_close_action(&Config::default()), CloseAction::Hide);
        assert_eq!(
            resolve_close_action(&Config {
                low_memory_mode: true,
                ..Config::default()
            }),
            CloseAction::Exit
        );
    }
}
