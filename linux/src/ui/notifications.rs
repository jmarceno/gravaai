//! Desktop notifications.
//!
//! The daemon is GTK-free, so notifications go through `notify-send`
//! (libnotify system package) with a log fallback — no display
//! or GTK required, works from every process role.

/// Show a transient desktop notification (best-effort, never fails).
pub fn notify(summary: &str, body: &str) {
    log::info!("notify: {summary} — {body}");
    let status = std::process::Command::new("notify-send")
        .args([
            "--app-name=Meeting Recorder",
            "--icon=meeting-recorder",
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
