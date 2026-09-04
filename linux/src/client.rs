//! Client mode (`meeting-recorder` with no role flag).
//!
//!
//! What the app-menu launcher invokes: make sure the daemon is running
//! (starting it detached if not), then ask it to open a window. Loads no GTK;
//! the daemon spawns the GTK window child.

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::daemon::dbus_service::{ENGINE_NAME, ENGINE_PATH};

fn daemon_exe() -> std::path::PathBuf {
    // Detached daemon must outlive this client — and, under AppImage, the
    // client's FUSE mount. Prefer our own AppImage file when applicable;
    // never trust a host IDE's APPIMAGE (see utils::exe::own_appimage).
    crate::utils::exe::persistent_exe()
}

/// Spawn the daemon detached (own session) so it outlives this transient
/// client. Fork+exec of a fresh binary — never a bare fork.
fn spawn_daemon() {
    #[cfg(unix)]
    {
        // Detach into a new session (setsid) pre-exec.
        use std::os::unix::process::CommandExt as _;
        let mut cmd = Command::new(daemon_exe());
        cmd.arg("--daemon")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        unsafe {
            cmd.pre_exec(|| {
                unsafe extern "C" {
                    fn setsid() -> i32;
                }
                setsid();
                Ok(())
            });
        }
        let _ = cmd.spawn();
    }
    #[cfg(not(unix))]
    {
        let _ = Command::new(daemon_exe())
            .arg("--daemon")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
    }
}

async fn name_has_owner(conn: &zbus::Connection, name: &str) -> bool {
    let bus_name = match zbus::names::BusName::try_from(name) {
        Ok(n) => n,
        Err(_) => return false,
    };
    match zbus::fdo::DBusProxy::new(conn).await {
        Ok(proxy) => proxy.name_has_owner(bus_name).await.unwrap_or(false),
        Err(_) => false,
    }
}

async fn wait_for_daemon(conn: &zbus::Connection, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if name_has_owner(conn, ENGINE_NAME).await {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    false
}

async fn open_window(conn: &zbus::Connection) -> zbus::Result<()> {
    let proxy = crate::ui::engine_proxy::EngineProxy::new(conn).await?;
    proxy.open_window().await
}

pub fn run_client() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");
    std::process::exit(rt.block_on(async_main()));
}

async fn async_main() -> i32 {
    let conn = match zbus::Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to connect to session bus: {e:#}");
            return 1;
        }
    };
    if !name_has_owner(&conn, ENGINE_NAME).await {
        log::info!("Daemon not running — starting it");
        spawn_daemon();
        if !wait_for_daemon(&conn, Duration::from_secs(8)).await {
            eprintln!("Daemon did not come up in time");
            return 1;
        }
    }
    if let Err(e) = open_window(&conn).await {
        eprintln!("Failed to open window: {e:#}");
        return 1;
    }
    let _ = ENGINE_PATH;
    0
}
