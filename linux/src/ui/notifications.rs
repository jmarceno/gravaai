//! Desktop notifications through the freedesktop D-Bus interface.
//!
//! Notifications are deliberately best-effort and dispatched on a short-lived
//! worker. The daemon never shells out to an external notification helper, and a missing or
//! stopped notification service cannot block recording or shutdown.

use std::sync::atomic::{AtomicBool, Ordering};

use crate::config::defaults::{APP_DIR_NAME, APP_NAME};

/// Notifications are part of the graphical daemon contract.  Keep this gate
/// separate from the notification service itself: a desktop notification
/// service can be present even when the StatusNotifier host (and therefore the
/// application's only visible entry point) has disappeared.
static GRAPHICAL_READY: AtomicBool = AtomicBool::new(false);

pub fn set_graphical_ready(ready: bool) {
    GRAPHICAL_READY.store(ready, Ordering::Release);
}

pub fn graphical_ready() -> bool {
    GRAPHICAL_READY.load(Ordering::Acquire)
}

/// Show a transient desktop notification (best-effort, never fails).
pub fn notify(summary: &str, body: &str) {
    if !graphical_ready() {
        log::debug!("notification suppressed while the GravaAi tray is offline");
        return;
    }
    log::info!("notify: {summary} — {body}");
    let summary = summary.to_owned();
    let body = body.to_owned();
    std::thread::Builder::new()
        .name("gravaai-notification".into())
        .spawn(move || {
            // The tray may have gone away after notify() queued this worker.
            // Re-check immediately before touching the session bus so a late
            // engine/call-detector callback cannot create a headless alert.
            if !graphical_ready() {
                log::debug!("notification worker dropped after the tray went offline");
                return;
            }
            let connection = match zbus::blocking::Connection::session() {
                Ok(connection) => connection,
                Err(err) => {
                    log::debug!("notification service unavailable: {err}");
                    return;
                }
            };
            let hints: std::collections::HashMap<&str, zbus::zvariant::Value<'_>> =
                std::collections::HashMap::new();
            let actions: Vec<&str> = Vec::new();
            let body_args = (
                APP_NAME,
                0u32,
                APP_DIR_NAME,
                summary.as_str(),
                body.as_str(),
                actions,
                hints,
                5000i32,
            );
            if let Err(err) = connection.call_method(
                Some("org.freedesktop.Notifications"),
                "/org/freedesktop/Notifications",
                Some("org.freedesktop.Notifications"),
                "Notify",
                &body_args,
            ) {
                log::debug!("notification request failed: {err}");
            }
        })
        .ok();
}
