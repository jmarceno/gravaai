//! Login autostart entry.
//!
//! The login entry launches the **daemon** (tray only) — its `Exec` carries
//! `--daemon`.
//!
//! The persisted `Exec` path must stay valid across reboots: a transient
//! AppImage FUSE mount (`/tmp/.mount_*`) disappears at shutdown, so writing
//! one produces an entry the desktop shows but never launches. `find_exec`
//! therefore only returns stable paths, and enabling autostart rewrites a
//! stale entry instead of keeping it.

use std::path::{Path, PathBuf};

use crate::config::defaults::{APP_DIR_NAME, APP_ID, APP_NAME};
use crate::core::run_mode::DAEMON_FLAG;
use crate::utils::exe::own_appimage;

pub const DESKTOP_FILENAME: &str = "gravaai.desktop";

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
        if !is_transient_runtime_path(&appimage) {
            return desktop_exec_path(&appimage);
        }
    }
    // Prefer known install locations, then PATH, then the running binary when
    // it lives at a stable location, then bare name at login.
    let mut candidates = vec![PathBuf::from(format!("/usr/bin/{APP_DIR_NAME}"))];
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(format!(".local/bin/{APP_DIR_NAME}")));
    }
    if let Ok(path) = std::env::var("PATH") {
        candidates.extend(std::env::split_paths(&path).map(|dir| dir.join(APP_DIR_NAME)));
    }
    if let Ok(exe) = std::env::current_exe() {
        candidates.push(exe);
    }
    match pick_stable(&candidates) {
        Some(path) => desktop_exec_path(path),
        None => {
            log::warn!(
                "No stable executable found for autostart; falling back to bare {APP_DIR_NAME}"
            );
            APP_DIR_NAME.to_string()
        }
    }
}

/// Transient AppImage runtime locations (FUSE mounts, extract-and-run dirs)
/// that vanish on reboot/unmount and must never be persisted into the login
/// entry.
fn is_transient_runtime_path(path: &Path) -> bool {
    let s = path.to_string_lossy();
    s.starts_with("/tmp/.mount_") || s.starts_with("/tmp/appimage-extract_")
}

/// First absolute, existing, non-transient candidate wins. Pure for tests;
/// the caller falls back to the bare binary name when this yields nothing.
fn pick_stable(candidates: &[PathBuf]) -> Option<&Path> {
    candidates
        .iter()
        .map(PathBuf::as_path)
        .find(|p| p.is_absolute() && !is_transient_runtime_path(p) && p.is_file())
}

fn desktop_template(exec_path: &str) -> String {
    format!(
        "[Desktop Entry]\nVersion=1.0\nType=Application\nName={APP_NAME}\n\
         Comment=Record, transcribe and summarize meetings\nExec={exec_path} {DAEMON_FLAG}\n\
         Icon={APP_DIR_NAME}\nTerminal=false\nCategories=AudioVideo;Audio;Recorder;\n\
         Keywords=meeting;record;transcribe;notes;audio;\nStartupNotify=true\nStartupWMClass={APP_ID}\n"
    )
}

pub fn update_autostart(enabled: bool) {
    let file = autostart_file();
    if enabled {
        // Reconcile: rewrite a missing entry, and repair a stale one (e.g. a
        // transient AppImage mount path persisted by an older build) instead
        // of keeping a login entry that never launches.
        let desired = desktop_template(&find_exec());
        match std::fs::read_to_string(&file) {
            Ok(existing) if existing == desired => return,
            Ok(_) => log::info!("Repairing stale autostart entry: {}", file.display()),
            Err(_) => {}
        }
        if std::fs::create_dir_all(autostart_dir()).is_err() {
            return;
        }
        match std::fs::write(&file, desired) {
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
            desktop_exec_path(Path::new("/opt/GravaAi AppImage.AppImage")),
            "\"/opt/GravaAi AppImage.AppImage\""
        );
        assert_eq!(
            desktop_exec_path(Path::new("/opt/gravaai.AppImage")),
            "/opt/gravaai.AppImage"
        );
    }

    #[test]
    fn desktop_entry_launches_daemon() {
        let content = desktop_template("/opt/gravaai.AppImage");
        assert!(content.contains("Exec=/opt/gravaai.AppImage --daemon\n"));
    }

    #[test]
    fn transient_runtime_paths_rejected() {
        assert!(is_transient_runtime_path(Path::new(
            "/tmp/.mount_gravaaABC123/usr/bin/gravaai"
        )));
        assert!(is_transient_runtime_path(Path::new(
            "/tmp/appimage-extract_abc/usr/bin/gravaai"
        )));
        assert!(!is_transient_runtime_path(Path::new(
            "/home/u/Software/AppImages/gravaai.appimage"
        )));
        assert!(!is_transient_runtime_path(Path::new("/usr/bin/gravaai")));
        assert!(!is_transient_runtime_path(Path::new(
            "/tmp/gravaai.AppImage"
        )));
    }

    #[test]
    fn pick_stable_skips_missing_transient_and_relative() {
        let dir = tempfile::tempdir().unwrap();
        let real = dir.path().join(APP_DIR_NAME);
        std::fs::write(&real, b"x").unwrap();
        let candidates = vec![
            PathBuf::from("/tmp/.mount_deadbeef/usr/bin/gravaai"),
            dir.path().join("does-not-exist"),
            PathBuf::from("relative/gravaai"),
            real.clone(),
        ];
        assert_eq!(pick_stable(&candidates), Some(real.as_path()));
    }

    #[test]
    fn pick_stable_skips_existing_transient_mount() {
        // A live FUSE mount path sorts first when AppRun prepends it to PATH;
        // it must still lose to a stable on-disk binary.
        let mount = tempfile::Builder::new()
            .prefix(".mount_gravaai-test-")
            .tempdir()
            .unwrap();
        let nested = mount.path().join("usr/bin/gravaai");
        std::fs::create_dir_all(nested.parent().unwrap()).unwrap();
        std::fs::write(&nested, b"x").unwrap();
        let stable_dir = tempfile::tempdir().unwrap();
        let stable = stable_dir.path().join(APP_DIR_NAME);
        std::fs::write(&stable, b"x").unwrap();

        let candidates = vec![nested.clone(), stable.clone()];
        assert_eq!(pick_stable(&candidates), Some(stable.as_path()));
        // With no stable candidate there is nothing safe to persist.
        assert_eq!(pick_stable(&[nested]), None);
    }
}
