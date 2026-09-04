//! Desktop notifications.
//!
//! The daemon is GTK-free, so notifications go through `notify-send`
//! (libnotify system package) with a log fallback — no display
//! or GTK required, works from every process role.

use crate::config::defaults::{APP_DIR_NAME, APP_NAME};

/// Show a transient desktop notification (best-effort, never fails).
pub fn notify(summary: &str, body: &str) {
    log::info!("notify: {summary} — {body}");
    let status = std::process::Command::new("notify-send")
        .args([
            &format!("--app-name={APP_NAME}"),
            &format!("--icon={APP_DIR_NAME}"),
            summary,
            body,
        ])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status();
    if let Err(e) = status {
        log::debug!("notify-send unavailable: {e}");
    }
}
