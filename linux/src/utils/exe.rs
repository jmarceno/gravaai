//! Resolve portable launchers, companion binaries and bundled helpers.

use std::path::{Path, PathBuf};

use crate::config::defaults::APP_DIR_NAME;

const FALLBACK_NAME: &str = APP_DIR_NAME;

/// Pure check: is `portable_exe`/`portable_root` owned by the process whose exe is
/// `current_exe`? Injectable for tests — never mutates process environment.
pub fn own_portable_exe_from(
    portable_exe: Option<&Path>,
    portable_root: Option<&Path>,
    current_exe: &Path,
) -> Option<PathBuf> {
    let portable_exe = portable_exe?;
    let portable_root = portable_root?;
    if !portable_exe.is_absolute() || !portable_exe.is_file() {
        return None;
    }
    // Ignore inherited launcher exports when running from a source checkout.
    if !path_is_under(current_exe, &portable_root.join("bin")) {
        return None;
    }
    Some(portable_exe.to_path_buf())
}

/// Absolute path of the portable bundle that contains this process, if any.
pub fn own_portable_exe() -> Option<PathBuf> {
    let portable_exe = std::env::var_os("GRAVAAI_PORTABLE_EXE").map(PathBuf::from);
    let portable_root = std::env::var_os("GRAVAAI_PORTABLE_ROOT").map(PathBuf::from);
    let exe = std::env::current_exe().ok()?;
    own_portable_exe_from(portable_exe.as_deref(), portable_root.as_deref(), &exe)
}

/// Persistent extraction root of the bundle containing this process, if any. Only a
/// GRAVAAI_PORTABLE_ROOT that actually contains the running binary is accepted.
pub fn own_portable_root() -> Option<PathBuf> {
    let portable_root = std::env::var_os("GRAVAAI_PORTABLE_ROOT").map(PathBuf::from)?;
    let exe = std::env::current_exe().ok()?;
    path_is_under(&exe, &portable_root.join("bin")).then_some(portable_root)
}

/// Stable launcher for desktop entries and detached daemon launches.
pub fn persistent_exe() -> PathBuf {
    if let Some(portable_exe) = own_portable_exe() {
        return portable_exe;
    }
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME))
}

/// Path for internal children in the same persistent extraction tree.
pub fn internal_exe() -> PathBuf {
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME))
}

/// Resolve the Qt companion next to the daemon in the portable tree.
/// Source builds keep both
/// release/debug binaries side by side in Cargo's target directory.
pub fn internal_ui_exe() -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME));
    let portable_root = std::env::var_os("GRAVAAI_PORTABLE_ROOT").map(PathBuf::from);
    resolve_ui_exe(&exe, portable_root.as_deref()).unwrap_or_else(|| {
        exe.parent()
            .map(|p| p.join("gravaai-ui"))
            .unwrap_or_else(|| PathBuf::from("gravaai-ui"))
    })
}

/// Resolve a helper executable from the current portable bundle before consulting
/// the host PATH. The portable launcher puts this directory first, but
/// resolving here keeps source runs, direct daemon launches and contaminated
/// IDE environments consistent.
pub fn runtime_program(name: &str) -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(FALLBACK_NAME));
    let portable_root = std::env::var_os("GRAVAAI_PORTABLE_ROOT").map(PathBuf::from);
    resolve_runtime_program(&exe, portable_root.as_deref(), name)
        .or_else(|| crate::services::system_installer::which(name).map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(name))
}

/// Pure helper resolver used by runtime code and unit tests.
pub fn resolve_runtime_program(
    exe: &Path,
    portable_root: Option<&Path>,
    name: &str,
) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(root) = portable_root {
        if path_is_under(exe, root) {
            candidates.push(root.join("bin").join(name));
        }
    }
    if let Some(parent) = exe.parent() {
        candidates.push(parent.join(name));
    }
    candidates.into_iter().find(|path| path.is_file())
}

/// Pure UI companion resolver used by the daemon and tests.
pub fn resolve_ui_exe(exe: &Path, portable_root: Option<&Path>) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(root) = portable_root {
        // Only accept a GRAVAAI_PORTABLE_ROOT that actually contains the current binary.
        if path_is_under(exe, root) {
            candidates.push(root.join("bin/gravaai-ui"));
        }
    }
    if let Some(parent) = exe.parent() {
        candidates.push(parent.join("gravaai-ui"));
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
    fn own_portable_exe_ignores_host_when_exe_outside_portable_root() {
        let dir = tempfile::tempdir().unwrap();
        let host_portable_root = dir.path().join("cursor-mount");
        let host_portable_exe = dir.path().join("Host.run");
        fs::create_dir_all(&host_portable_root).unwrap();
        fs::write(&host_portable_exe, b"x").unwrap();
        let our_exe = dir.path().join(format!("elsewhere/{APP_DIR_NAME}"));
        fs::create_dir_all(our_exe.parent().unwrap()).unwrap();
        fs::write(&our_exe, b"x").unwrap();

        assert!(
            own_portable_exe_from(
                Some(&host_portable_exe),
                Some(&host_portable_root),
                &our_exe
            )
            .is_none(),
            "host IDE portable bundle must not be treated as ours"
        );
    }

    #[test]
    fn own_portable_exe_accepts_when_exe_under_portable_root() {
        let dir = tempfile::tempdir().unwrap();
        let portable_root = dir.path().join("mr-mount");
        let bin_dir = portable_root.join("bin");
        fs::create_dir_all(&bin_dir).unwrap();
        let fake_exe = bin_dir.join(APP_DIR_NAME);
        fs::write(&fake_exe, b"x").unwrap();
        let portable_exe = dir.path().join("gravaai.run");
        fs::write(&portable_exe, b"x").unwrap();

        let got = own_portable_exe_from(Some(&portable_exe), Some(&portable_root), &fake_exe);
        assert_eq!(got.as_deref(), Some(portable_exe.as_path()));
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
    fn resolves_portable_exe_companion_only_inside_owned_mount() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("mount");
        let bin = root.join("bin");
        let ui = root.join("bin");
        fs::create_dir_all(&bin).unwrap();
        fs::create_dir_all(&ui).unwrap();
        let exe = bin.join(APP_DIR_NAME);
        let ui_exe = ui.join("gravaai-ui");
        fs::write(&exe, b"x").unwrap();
        fs::write(&ui_exe, b"x").unwrap();
        assert_eq!(resolve_ui_exe(&exe, Some(&root)), Some(ui_exe.clone()));

        let host = dir.path().join("host-mount");
        fs::create_dir_all(host.join("bin")).unwrap();
        fs::write(host.join("bin/gravaai-ui"), b"x").unwrap();
        assert_ne!(
            resolve_ui_exe(&exe, Some(&host)),
            Some(host.join("bin/gravaai-ui"))
        );
    }

    #[test]
    fn resolves_runtime_helper_inside_owned_mount() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("mount");
        let bin = root.join("bin");
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
