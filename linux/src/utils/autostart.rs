//! Login autostart entry.
//!
//! The login entry launches the **daemon** (tray only) — its `Exec` carries
//! `--daemon`.

use std::path::{Path, PathBuf};

use crate::config::defaults::APP_ID;
use crate::core::run_mode::DAEMON_FLAG;
use crate::utils::exe::own_appimage;

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

/// Quote a path for a desktop-file `Exec=` key when it contains whitespace.
fn desktop_exec_path(path: &Path) -> String {
    let s = path.to_string_lossy();
    if s.chars().any(|c| c.is_whitespace()) {
        format!("\"{s}\"")
    } else {
        s.into_owned()
    }
}

fn find_exec() -> String {
    // Prefer our own AppImage (never a host IDE's APPIMAGE — see own_appimage).
    if let Some(appimage) = own_appimage() {
        return desktop_exec_path(&appimage);
    }
    // Prefer known install locations, then PATH, then bare name at login.
    for candidate in ["/usr/bin/meeting-recorder"] {
        if Path::new(candidate).exists() {
            return candidate.to_string();
        }
    }
    if let Some(home) = dirs::home_dir() {
        let local = home.join(".local/bin/meeting-recorder");
        if local.exists() {
            return desktop_exec_path(&local);
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join(APP_NAME);
            if c.is_file() {
                return desktop_exec_path(&c);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_exec_quotes_whitespace() {
        assert_eq!(
            desktop_exec_path(Path::new("/opt/Meeting Recorder.AppImage")),
            "\"/opt/Meeting Recorder.AppImage\""
        );
        assert_eq!(
            desktop_exec_path(Path::new("/opt/MeetingRecorder.AppImage")),
            "/opt/MeetingRecorder.AppImage"
        );
    }
}
