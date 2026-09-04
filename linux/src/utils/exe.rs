//! Resolve the on-disk path used to re-invoke this binary.
//!
//! Under AppImage the FUSE mount disappears when the launching process exits,
//! so a detached daemon must be started from `$APPIMAGE` (a fresh mount) rather
//! than from `current_exe()` inside the caller's mount. Short-lived children
//! that share the daemon's lifetime keep using the mounted binary.
//!
//! `$APPIMAGE` / `$APPDIR` are only trusted when `current_exe()` lives under
//! `$APPDIR`. Nested AppImage hosts (e.g. an IDE packaged as an AppImage)
//! export those variables for *themselves*; without this check a cargo-run
//! or standalone binary would incorrectly re-exec the host AppImage.

use std::path::{Path, PathBuf};

use crate::config::defaults::APP_DIR_NAME;

const FALLBACK_NAME: &str = APP_DIR_NAME;

/// Pure check: is `appimage`/`appdir` owned by the process whose exe is
/// `current_exe`? Injectable for tests — never mutates process environment.
pub fn own_appimage_from(
    appimage: Option<&Path>,
    appdir: Option<&Path>,
    current_exe: &Path,
) -> Option<PathBuf> {
    let appimage = appimage?;
    let appdir = appdir?;
    if !appimage.is_file() {
        return None;
    }
    // Require the running binary to sit inside this AppImage's mount. A host
    // AppImage (Cursor, etc.) sets APPIMAGE/APPDIR, but our exe is elsewhere.
    if !path_is_under(current_exe, appdir) {
        return None;
    }
    Some(appimage.to_path_buf())
}

/// Absolute path of the AppImage that contains this process, if any.
pub fn own_appimage() -> Option<PathBuf> {
    let appimage = std::env::var_os("APPIMAGE").map(PathBuf::from);
    let appdir = std::env::var_os("APPDIR").map(PathBuf::from);
    let exe = std::env::current_exe().ok()?;
    own_appimage_from(appimage.as_deref(), appdir.as_deref(), &exe)
}

/// Path for a process that must outlive the caller (and its AppImage mount).
/// Prefer the owning AppImage file; otherwise `current_exe()`.
pub fn persistent_exe() -> PathBuf {
    if let Some(appimage) = own_appimage() {
        return appimage;
    }
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME))
}

/// Path for short-lived children that share this process's lifetime/mount.
pub fn internal_exe() -> PathBuf {
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME))
}

/// Resolve the Qt companion executable without trusting a host AppImage's
/// environment. The daemon binary lives in `usr/bin`, while the UI lives in
/// `usr/libexec/gravaai` inside the same AppImage. Source builds keep both
/// release/debug binaries side by side in Cargo's target directory.
pub fn internal_ui_exe() -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME));
    let appdir = std::env::var_os("APPDIR").map(PathBuf::from);
    resolve_ui_exe(&exe, appdir.as_deref()).unwrap_or_else(|| {
        exe.parent()
            .map(|p| p.join("gravaai-ui"))
            .unwrap_or_else(|| PathBuf::from("gravaai-ui"))
    })
}

/// Resolve a helper executable from the current AppImage before consulting
/// the host PATH. The AppRun script also puts this directory first, but
/// resolving here keeps source runs, direct daemon launches and contaminated
/// IDE environments consistent.
pub fn runtime_program(name: &str) -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME));
    let appdir = std::env::var_os("APPDIR").map(PathBuf::from);
    resolve_runtime_program(&exe, appdir.as_deref(), name)
        .or_else(|| crate::services::system_installer::which(name).map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(name))
}

/// Pure helper resolver used by runtime code and unit tests.
pub fn resolve_runtime_program(exe: &Path, appdir: Option<&Path>, name: &str) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(root) = appdir {
        if path_is_under(exe, root) {
            candidates.push(root.join("usr/bin").join(name));
        }
    }
    if let Some(parent) = exe.parent() {
        candidates.push(parent.join(name));
        candidates.push(parent.join("../libexec/gravaai").join(name));
    }
    candidates.into_iter().find(|path| path.is_file())
}

/// Pure UI companion resolver used by the daemon and tests.
pub fn resolve_ui_exe(exe: &Path, appdir: Option<&Path>) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(root) = appdir {
        // Only accept an APPDIR that actually contains the current binary.
        if path_is_under(exe, root) {
            candidates.push(root.join("usr/libexec/gravaai/gravaai-ui"));
        }
    }
    if let Some(parent) = exe.parent() {
        candidates.push(parent.join("gravaai-ui"));
        candidates.push(parent.join("../libexec/gravaai/gravaai-ui"));
        candidates.push(parent.join("../share/gravaai/gravaai-ui"));
    }
    candidates.into_iter().find(|p| p.is_file())
}

/// Compatibility implementation of the historical `gravaai --window` role.
/// The core process never loads Qt; it simply replaces itself with the
/// companion executable and forwards all arguments except the role marker.
pub fn run_ui_trampoline() -> i32 {
    let ui = internal_ui_exe();
    let args: Vec<String> = std::env::args()
        .skip(1)
        .filter(|arg| arg != "--window")
        .collect();
    let mut cmd = std::process::Command::new(&ui);
    cmd.args(args);
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt as _;
        let err = cmd.exec();
        eprintln!("Failed to start Qt UI {}: {err}", ui.display());
        127
    }
    #[cfg(not(unix))]
    {
        match cmd.status() {
            Ok(status) => status.code().unwrap_or(1),
            Err(err) => {
                eprintln!("Failed to start Qt UI {}: {err}", ui.display());
                127
            }
        }
    }
}

fn path_is_under(path: &Path, root: &Path) -> bool {
    let Ok(path) = path.canonicalize() else {
        return false;
    };
    let Ok(root) = root.canonicalize() else {
        return false;
    };
    path.starts_with(root)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn own_appimage_ignores_host_when_exe_outside_appdir() {
        let dir = tempfile::tempdir().unwrap();
        let host_appdir = dir.path().join("cursor-mount");
        let host_appimage = dir.path().join("Cursor.AppImage");
        fs::create_dir_all(&host_appdir).unwrap();
        fs::write(&host_appimage, b"x").unwrap();
        let our_exe = dir.path().join(format!("elsewhere/{APP_DIR_NAME}"));
        fs::create_dir_all(our_exe.parent().unwrap()).unwrap();
        fs::write(&our_exe, b"x").unwrap();

        assert!(
            own_appimage_from(Some(&host_appimage), Some(&host_appdir), &our_exe).is_none(),
            "host IDE AppImage must not be treated as ours"
        );
    }

    #[test]
    fn own_appimage_accepts_when_exe_under_appdir() {
        let dir = tempfile::tempdir().unwrap();
        let appdir = dir.path().join("mr-mount");
        let bin_dir = appdir.join("usr/bin");
        fs::create_dir_all(&bin_dir).unwrap();
        let fake_exe = bin_dir.join(APP_DIR_NAME);
        fs::write(&fake_exe, b"x").unwrap();
        let appimage = dir.path().join("gravaai.AppImage");
        fs::write(&appimage, b"x").unwrap();

        let got = own_appimage_from(Some(&appimage), Some(&appdir), &fake_exe);
        assert_eq!(got.as_deref(), Some(appimage.as_path()));
    }

    #[test]
    fn path_is_under_rejects_sibling() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("root");
        let sibling = dir.path().join("sibling");
        fs::create_dir_all(&root).unwrap();
        fs::create_dir_all(&sibling).unwrap();
        let file = sibling.join("bin");
        fs::write(&file, b"x").unwrap();
        assert!(!path_is_under(&file, &root));
    }

    #[test]
    fn resolves_appimage_companion_only_inside_owned_mount() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("mount");
        let bin = root.join("usr/bin");
        let ui = root.join("usr/libexec/gravaai");
        fs::create_dir_all(&bin).unwrap();
        fs::create_dir_all(&ui).unwrap();
        let exe = bin.join(APP_DIR_NAME);
        let ui_exe = ui.join("gravaai-ui");
        fs::write(&exe, b"x").unwrap();
        fs::write(&ui_exe, b"x").unwrap();
        assert_eq!(resolve_ui_exe(&exe, Some(&root)), Some(ui_exe.clone()));

        let host = dir.path().join("host-mount");
        fs::create_dir_all(host.join("usr/libexec/gravaai")).unwrap();
        fs::write(host.join("usr/libexec/gravaai/gravaai-ui"), b"x").unwrap();
        assert_ne!(
            resolve_ui_exe(&exe, Some(&host)),
            Some(host.join("usr/libexec/gravaai/gravaai-ui"))
        );
    }

    #[test]
    fn resolves_runtime_helper_inside_owned_mount() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("mount");
        let bin = root.join("usr/bin");
        std::fs::create_dir_all(&bin).unwrap();
        let exe = bin.join(APP_DIR_NAME);
        let helper = bin.join("ffmpeg");
        std::fs::write(&exe, b"x").unwrap();
        std::fs::write(&helper, b"x").unwrap();
        assert_eq!(
            resolve_runtime_program(&exe, Some(&root), "ffmpeg"),
            Some(helper)
        );
    }
}
