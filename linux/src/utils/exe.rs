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
}
