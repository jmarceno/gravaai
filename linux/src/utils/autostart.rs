//! Login autostart entry.
//!
//! The login entry launches the **daemon** (tray only) — its `Exec` carries
//! `--daemon`.

use std::path::PathBuf;

use crate::config::defaults::APP_ID;
use crate::core::run_mode::DAEMON_FLAG;

pub const APP_NAME: &str = "meeting-recorder";
pub const DESKTOP_FILENAME: &str = "meeting-recorder.desktop";

fn autostart_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(".config/autostart")
}

fn autostart_file() -> PathBuf {
    autostart_dir().join(DESKTOP_FILENAME)
}

fn find_exec() -> String {
    // Prefer PATH resolution, then known locations, then PATH fallback at login.
    for candidate in ["/usr/bin/meeting-recorder"] {
        if std::path::Path::new(candidate).exists() {
            return candidate.to_string();
        }
    }
    if let Some(home) = dirs::home_dir() {
        let local = home.join(".local/bin/meeting-recorder");
        if local.exists() {
            return local.to_string_lossy().into_owned();
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join(APP_NAME);
            if c.is_file() {
                return c.to_string_lossy().into_owned();
            }
        }
    }
    APP_NAME.to_string()
}

fn desktop_template(exec_path: &str) -> String {
    format!(
        "[Desktop Entry]\nVersion=1.0\nType=Application\nName=Meeting Recorder\n\
         Comment=Record, transcribe and summarize meetings\nExec={exec_path} {DAEMON_FLAG}\n\
         Icon=meeting-recorder\nTerminal=false\nCategories=AudioVideo;Audio;Recorder;\n\
         Keywords=meeting;record;transcribe;notes;audio;\nStartupNotify=true\nStartupWMClass={APP_ID}\n"
    )
}

pub fn update_autostart(enabled: bool) {
    let file = autostart_file();
    if enabled {
        if file.exists() {
            return;
        }
        if std::fs::create_dir_all(autostart_dir()).is_err() {
            return;
        }
        match std::fs::write(&file, desktop_template(&find_exec())) {
            Ok(_) => log::info!("Enabled autostart: wrote {}", file.display()),
            Err(e) => log::error!("Failed to enable autostart: {e}"),
        }
    } else if file.exists() {
        match std::fs::remove_file(&file) {
            Ok(_) => log::info!("Disabled autostart: removed {}", file.display()),
            Err(e) => log::error!("Failed to disable autostart: {e}"),
        }
    }
}

pub fn is_autostart_enabled() -> bool {
    autostart_file().exists()
}
