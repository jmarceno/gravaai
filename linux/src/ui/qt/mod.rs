//! Qt Quick window companion.

mod controller;
mod runtime;

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use cxx_qt_lib::{QGuiApplication, QQmlApplicationEngine, QQuickStyle, QString, QUrl};
use zbus::blocking::Connection;

use crate::config::defaults::{APP_ID, APP_NAME};
use crate::daemon::dbus_service::ENGINE_NAME;

const UI_INSTANCE_NAME: &str = "io.github.jmarceno.GravaAi.UI";

static READY_SEEN: AtomicBool = AtomicBool::new(false);

pub(crate) fn mark_ready_seen() {
    READY_SEEN.store(true, Ordering::Release);
}

/// Claim the single UI slot and prove that the daemon (which owns the tray)
/// is already present.  This prevents a user launching `gravaai-ui` directly
/// from getting a window without an icon and prevents a second window process
/// from appearing if the compatibility trampoline is invoked twice.
fn acquire_ui_instance() -> Result<Connection, String> {
    let conn = Connection::session().map_err(|e| format!("cannot connect to session bus: {e}"))?;
    let daemon_name = zbus::names::BusName::try_from(ENGINE_NAME)
        .map_err(|e| format!("invalid daemon bus name: {e}"))?;
    let dbus = zbus::blocking::fdo::DBusProxy::new(&conn)
        .map_err(|e| format!("cannot inspect daemon bus name: {e}"))?;
    if !dbus
        .name_has_owner(daemon_name)
        .map_err(|e| format!("cannot inspect daemon owner: {e}"))?
    {
        return Err(
            "the GravaAi daemon/tray is not running; launch the application entry point instead"
                .into(),
        );
    }

    let reply = conn
        .request_name_with_flags(
            UI_INSTANCE_NAME,
            zbus::fdo::RequestNameFlags::DoNotQueue.into(),
        )
        .map_err(|e| format!("cannot claim the single UI instance: {e}"))?;
    match reply {
        zbus::fdo::RequestNameReply::PrimaryOwner | zbus::fdo::RequestNameReply::AlreadyOwner => {
            Ok(conn)
        }
        other => Err(format!(
            "another GravaAi UI instance is already running ({other})"
        )),
    }
}

/// Run the Qt-only companion. It deliberately has no daemon ownership or
/// tray responsibilities; the core daemon remains the single authority.
pub fn run_window() -> i32 {
    crate::utils::logging::setup_logging("window-qt");
    let smoke_mode = std::env::var_os("GRAVAAI_QML_SMOKE").is_some();
    let _ui_instance = if smoke_mode {
        None
    } else {
        match acquire_ui_instance() {
            Ok(conn) => Some(conn),
            Err(message) => {
                log::error!("{message}");
                eprintln!("GravaAi UI unavailable: {message}");
                return 73;
            }
        }
    };
    // Qt messages otherwise go only to journald on some distributions,
    // hiding fatal QML load errors from the user and the supervisor log.
    // SAFETY: set before any Qt object is created.
    if std::env::var_os("QT_FORCE_STDERR_LOGGING").is_none() {
        unsafe { std::env::set_var("QT_FORCE_STDERR_LOGGING", "1") };
    }
    if std::env::var_os("QT_QUICK_CONTROLS_STYLE").is_none() {
        unsafe { std::env::set_var("QT_QUICK_CONTROLS_STYLE", "Basic") };
    }

    spawn_startup_watchdog();
    QQuickStyle::set_style(&QString::from("Basic"));

    let mut app = QGuiApplication::new();
    if let Some(mut app_ref) = app.as_mut() {
        app_ref
            .as_mut()
            .set_application_name(&QString::from(APP_NAME));
        app_ref
            .as_mut()
            .set_application_display_name(&QString::from(APP_NAME));
        app_ref
            .as_mut()
            .set_application_version(&QString::from(env!("CARGO_PKG_VERSION")));
        app_ref
            .as_mut()
            .set_organization_name(&QString::from("GravaAi"));
        app_ref
            .as_mut()
            .set_organization_domain(&QString::from("github.com"));
    } else {
        eprintln!("GravaAi failed to start: Qt application is null.");
        return 1;
    }

    let mut engine = QQmlApplicationEngine::new();
    if let Some(mut engine_ref) = engine.as_mut() {
        // cxx-qt embeds the module into the resource system. Keeping the URL
        // module-based prevents source/AppImage path drift.
        let url = if smoke_mode {
            QUrl::from("qrc:/qt/qml/io/github/jmarceno/gravaai/qml/SmokeHarness.qml")
        } else {
            QUrl::from("qrc:/qt/qml/io/github/jmarceno/gravaai/qml/Main.qml")
        };
        if !runtime::load_engine(engine_ref.as_mut(), &url) {
            eprintln!("GravaAi failed to load its QML root.");
            return 70;
        }
        if smoke_mode {
            // The harness intentionally has no D-Bus worker; loading its root
            // is the readiness condition for this isolated geometry test.
            mark_ready_seen();
        }
    } else {
        eprintln!("GravaAi failed to start: Qt QML engine is null.");
        return 1;
    }

    let code = app.as_mut().map(|app_ref| app_ref.exec()).unwrap_or(1);
    log::info!("Qt window exited with code {code} ({APP_ID})");
    code
}

fn spawn_startup_watchdog() {
    std::thread::Builder::new()
        .name("gravaai-qml-watchdog".into())
        .spawn(|| {
            std::thread::sleep(Duration::from_secs(5));
            if !READY_SEEN.load(Ordering::Acquire) {
                let message = "GravaAI Qt UI did not load within 5 seconds; check window-qt.log";
                log::error!("{message}");
                eprintln!("{message}");
                std::process::exit(70);
            }
        })
        .ok();
}
