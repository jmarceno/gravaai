//! The GTK window process (`meeting-recorder --window`).
//!
//!
//! A short-lived [`adw::Application`] showing the recorder window and talking
//! to the always-on daemon through [`ProxyHandle`]. Uses `NON_UNIQUE` so it
//! does not own the engine bus name (the daemon owns it); the daemon
//! guarantees a single window by tracking the child and emitting
//! PresentWindow instead of spawning a second one. The app id is still
//! `APP_ID` so the shell maps the window to the app icon via StartupWMClass.
//!
//! Threading: GTK runs its main loop on the main thread; a background tokio
//! runtime serves D-Bus. Signal tasks forward daemon events to the GTK thread
//! over a glib channel.

use std::path::PathBuf;

use adw::prelude::*;

use crate::config::defaults::APP_ID;
use crate::ui::engine_proxy::{watch_daemon_owner, InstallUiEvent, ProxyHandle};
use crate::ui::main_window::MainWindow;

enum UiMsg {
    Snapshot(String),
    Error(String),
    Output(String),
    OpenUseExisting,
    Present,
    Install(InstallUiEvent),
    DaemonGone,
}

pub fn run_window() {
    crate::utils::logging::setup_logging("window");

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");
    let rt_handle = rt.handle().clone();
    // The runtime must outlive the GTK main loop; the window process is
    // short-lived, so leaking it is equivalent to joining at exit.
    std::mem::forget(rt);

    let app = adw::Application::builder()
        .application_id(APP_ID)
        .flags(gtk::gio::ApplicationFlags::NON_UNIQUE)
        .build();

    app.connect_activate(move |app| {
        activate(app, &rt_handle);
    });

    // Strip the --window role flag so GApplication doesn't try to parse it.
    let args: Vec<String> = std::env::args().filter(|a| a != "--window").collect();
    app.run_with_args(&args);
}

fn activate(app: &adw::Application, rt: &tokio::runtime::Handle) {
    setup_app_icon();

    let conn: &'static zbus::Connection = match rt.block_on(zbus::Connection::session()) {
        Ok(c) => Box::leak(Box::new(c)),
        Err(e) => {
            eprintln!("window: cannot reach session bus: {e:#}");
            app.quit();
            return;
        }
    };
    let proxy = match ProxyHandle::new(conn, rt.clone()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("window: cannot talk to daemon: {e:#}");
            app.quit();
            return;
        }
    };

    let window = MainWindow::new(app, proxy.clone());
    window.finish_init();

    // Daemon → GTK fan-in: a plain mpsc bus polled from the main loop.
    // (glib 0.20 has no MainContext::channel; Rc/Weak widgets are !Send so
    // worker threads can only ship owned data, never widget handles.)
    let (tx, rx) = std::sync::mpsc::channel::<UiMsg>();
    {
        let window_c = window.clone();
        glib::timeout_add_local(std::time::Duration::from_millis(50), move || {
            while let Ok(msg) = rx.try_recv() {
                match msg {
                    UiMsg::Snapshot(payload) => window_c.apply_snapshot_json(&payload),
                    UiMsg::Error(text) => window_c.show_error(&text),
                    UiMsg::Output(text) => window_c.show_output(&text),
                    UiMsg::OpenUseExisting => window_c.open_use_existing(),
                    UiMsg::Present => window_c.present_window(),
                    UiMsg::Install(event) => window_c.on_install_event(&event),
                    UiMsg::DaemonGone => {
                        window_c.window.destroy();
                        if let Some(app) = window_c.window.application() {
                            app.quit();
                        }
                    }
                }
            }
            glib::ControlFlow::Continue
        });
    }

    spawn_signal_tasks(rt, proxy, tx.clone());

    // A spawned window must never outlive its daemon.
    rt.spawn(watch_daemon_owner(conn.clone(), move || {
        let _ = tx.send(UiMsg::DaemonGone);
    }));

    window.present_window();
}

fn spawn_signal_tasks(
    rt: &tokio::runtime::Handle,
    proxy: ProxyHandle,
    tx: std::sync::mpsc::Sender<UiMsg>,
) {
    use futures_lite::StreamExt as _;

    // SnapshotChanged.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_snapshot_changed().await else {
                return;
            };
            while let Some(signal) = stream.next().await {
                if let Ok(args) = signal.args() {
                    let _ = tx.send(UiMsg::Snapshot(args.json.clone()));
                }
            }
        });
    }
    // Error.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_error().await else {
                return;
            };
            while let Some(signal) = stream.next().await {
                if let Ok(args) = signal.args() {
                    let _ = tx.send(UiMsg::Error(args.msg.clone()));
                }
            }
        });
    }
    // Output.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_output().await else {
                return;
            };
            while let Some(signal) = stream.next().await {
                if let Ok(args) = signal.args() {
                    let _ = tx.send(UiMsg::Output(args.text.clone()));
                }
            }
        });
    }
    // OpenUseExisting.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_open_use_existing().await else {
                return;
            };
            while stream.next().await.is_some() {
                let _ = tx.send(UiMsg::OpenUseExisting);
            }
        });
    }
    // PresentWindow.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_present_window().await else {
                return;
            };
            while stream.next().await.is_some() {
                let _ = tx.send(UiMsg::Present);
            }
        });
    }
    // InstallProgress.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_install_progress().await else {
                return;
            };
            while let Some(signal) = stream.next().await {
                if let Ok(args) = signal.args() {
                    let _ = tx.send(UiMsg::Install(InstallUiEvent::Progress(
                        args.key.clone(),
                        args.text.clone(),
                    )));
                }
            }
        });
    }
    // InstallFinished.
    {
        let p = proxy.proxy().clone();
        let tx = tx.clone();
        rt.spawn(async move {
            let Ok(mut stream) = p.receive_install_finished().await else {
                return;
            };
            while let Some(signal) = stream.next().await {
                if let Ok(args) = signal.args() {
                    let _ = tx.send(UiMsg::Install(InstallUiEvent::Finished(
                        args.key.clone(),
                        args.ok,
                        args.message.clone(),
                    )));
                }
            }
        });
    }
}

fn setup_app_icon() {
    let display = match gtk::gdk::Display::default() {
        Some(d) => d,
        None => return,
    };
    for dir in icon_search_dirs() {
        if dir.is_dir() {
            gtk::IconTheme::for_display(&display).add_search_path(dir);
        }
    }
    gtk::Window::set_default_icon_name("meeting-recorder");
}

fn icon_search_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            dirs.push(dir.join("assets/icons"));
            dirs.push(dir.join("../share/meeting-recorder/icons"));
        }
    }
    dirs.push(PathBuf::from("/usr/share/meeting-recorder/icons"));
    if let Some(home) = dirs::home_dir() {
        dirs.push(home.join(".local/share/meeting-recorder/icons"));
    }
    // Source-checkout fallbacks (dev).
    dirs.push(PathBuf::from("linux/assets/icons"));
    dirs.push(PathBuf::from("assets/icons"));
    dirs
}
