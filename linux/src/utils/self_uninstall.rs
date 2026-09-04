//! Self-uninstall (`gravaai --uninstall`).
//!
//! The binary removes everything it ever installed or created, so no shell
//! script is needed and nothing is left behind: the running daemon/window,
//! the installed binary copy, desktop entries, the autostart entry, hicolor
//! icons, `~/.local/share/gravaai` (assets, engines, models, logs),
//! the config dir, the keyring credential, the state dir, and the system log
//! dir (best-effort — needs root, skipped gracefully otherwise).
//!
//! Recordings (`~/meetings` by default) are user data and are kept; the
//! summary tells the user where they are.

use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::config::defaults::{APP_DIR_NAME, APP_ID};
use crate::config::keyring_store::KeyringStore;
use crate::core::job_manager::default_state_dir;
use crate::daemon::dbus_service::ENGINE_PATH;

const ICON_SIZES: &[u32] = &[16, 24, 32, 48, 64, 128, 256];

/// Every file or dir uninstall removes, derived from `home` (plus the
/// already-resolved `state_dir`). Pure and unit-testable; [`remove_all`]
/// deletes them with the running binary itself removed last.
pub fn plan(home: &Path, state_dir: &Path) -> Vec<PathBuf> {
    let mut targets = Vec::new();
    // Installed binary copy.
    targets.push(home.join(".local/bin").join(APP_DIR_NAME));
    // App data: tray artwork, icons copy, whisper.cpp engine + models, logs.
    targets.push(home.join(".local/share").join(APP_DIR_NAME));
    // Desktop entries.
    let apps = home.join(".local/share/applications");
    targets.push(apps.join(format!("{APP_ID}.desktop")));
    targets.push(apps.join(format!("{APP_DIR_NAME}.desktop")));
    // Autostart entry.
    targets.push(
        home.join(".config/autostart")
            .join(format!("{APP_DIR_NAME}.desktop")),
    );
    // Hicolor icons.
    let theme = home.join(".local/share/icons/hicolor");
    for size in ICON_SIZES {
        let dir = theme.join(format!("{size}x{size}/apps"));
        targets.push(dir.join(format!("{APP_DIR_NAME}.png")));
    }
    let scalable = theme.join("scalable/apps");
    targets.push(scalable.join(format!("{APP_DIR_NAME}.svg")));
    // Config (incl. plaintext API key when no keyring is in use).
    targets.push(home.join(".config").join(APP_DIR_NAME));
    // Job/state dir.
    targets.push(state_dir.to_path_buf());
    targets
}

fn remove_path(path: &Path) -> bool {
    if path.is_dir() && !path.is_symlink() {
        std::fs::remove_dir_all(path).is_ok()
    } else {
        std::fs::remove_file(path).is_ok()
    }
}

/// Remove newly-emptied parent dirs up to (not including) `home`.
fn prune_empty_parents(path: &Path, home: &Path) {
    let mut dir = path.parent();
    while let Some(d) = dir {
        if d == home || !d.starts_with(home) {
            break;
        }
        if std::fs::remove_dir(d).is_err() {
            break; // not empty (or otherwise kept) — stop climbing
        }
        dir = d.parent();
    }
}

/// Delete every planned target under `home`, removing `exe` itself last.
/// Returns the paths actually removed (for the summary).
pub fn remove_all(home: &Path, state_dir: &Path, exe: &Path) -> Vec<PathBuf> {
    let mut removed = Vec::new();
    for target in plan(home, state_dir) {
        if target == exe {
            continue; // self last
        }
        if target.exists() && remove_path(&target) {
            prune_empty_parents(&target, home);
            removed.push(target);
        }
    }
    // Stored API key, if any.
    KeyringStore::new().delete();
    // Self (works on Linux even while running).
    if exe.exists() && remove_path(exe) {
        if let Ok(canonical_home) = home.canonicalize() {
            prune_empty_parents(exe, &canonical_home);
        }
        removed.push(exe.to_path_buf());
    }
    removed
}

/// Return process IDs that are demonstrably one of our daemon/UI roles.
///
/// Process matching is restricted to validated GravaAI executable paths and
/// role arguments so an unrelated user's process can never be terminated.
/// `/proc/<pid>/cmdline` is inspected, the executable basename is constrained,
/// and this process is excluded.
fn owned_process_ids(exe: &Path) -> Vec<u32> {
    let expected = exe
        .canonicalize()
        .ok()
        .map(|p| p.to_string_lossy().into_owned());
    let own_pid = std::process::id();
    let mut result = Vec::new();
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return result;
    };
    for entry in entries.filter_map(Result::ok) {
        let name = entry.file_name();
        let Ok(pid) = name.to_string_lossy().parse::<u32>() else {
            continue;
        };
        if pid == own_pid {
            continue;
        }
        let bytes = match std::fs::read(entry.path().join("cmdline")) {
            Ok(bytes) => bytes,
            Err(_) => continue,
        };
        let args: Vec<String> = bytes
            .split(|b| *b == 0)
            .filter(|part| !part.is_empty())
            .map(|part| String::from_utf8_lossy(part).into_owned())
            .collect();
        let Some(program) = args.first() else {
            continue;
        };
        let program_path = Path::new(program);
        let same_binary = expected
            .as_deref()
            .and_then(|wanted| {
                program_path
                    .canonicalize()
                    .ok()
                    .map(|got| got.to_string_lossy() == wanted)
            })
            .unwrap_or(false);
        let program_name = program_path.file_name().and_then(|n| n.to_str());
        let daemon_name = program_name == Some(APP_DIR_NAME);
        let ui_name = program_name == Some("gravaai-ui");
        let role = args
            .iter()
            .any(|arg| arg == "--daemon" || arg == "--window");
        if ui_name || ((same_binary || daemon_name) && role) {
            result.push(pid);
        }
    }
    result
}

#[cfg(unix)]
fn send_term(pid: u32) {
    // SAFETY: the pid was validated from /proc and is not this process.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn send_term(_pid: u32) {}

/// Stop our running daemon/window (best-effort, never fails uninstall).
/// Prefer the Engine Quit method so the daemon can flush state; `/proc` is a
/// validated SIGTERM fallback for a crashed or bus-less session.
fn stop_running(exe: &Path) {
    #[cfg(unix)]
    if let Ok(conn) = zbus::blocking::Connection::session() {
        if let Ok(proxy) = zbus::blocking::Proxy::new(
            &conn,
            APP_ID,
            ENGINE_PATH,
            "io.github.jmarceno.GravaAi.Engine",
        ) {
            let _: Result<(), _> = proxy.call("Quit", &());
        }
    }

    for attempt in 0..30 {
        let pids = owned_process_ids(exe);
        if pids.is_empty() {
            return;
        }
        if attempt == 0 {
            for pid in pids {
                send_term(pid);
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let remaining = owned_process_ids(exe);
    if !remaining.is_empty() {
        println!("Waiting for GravaAI processes to exit: {:?}", remaining);
    }
}

/// Run the full uninstall. Always succeeds from the user's perspective;
/// individual misses are reported, never fatal.
pub fn run_uninstall() -> i32 {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("~"));
    // Prefer our own AppImage file (never a host IDE's APPIMAGE). The mounted
    // squashfs binary is not deletable; removing the .AppImage is.
    let exe = crate::utils::exe::own_appimage().unwrap_or_else(|| {
        std::env::current_exe().unwrap_or_else(|_| home.join(".local/bin").join(APP_DIR_NAME))
    });

    println!("Stopping {APP_DIR_NAME}…");
    stop_running(&exe);

    let state_dir = default_state_dir();
    let removed = remove_all(&home, &state_dir, &exe);
    for path in &removed {
        println!("Removed {}", path.display());
    }

    // System log dir: only removable as root; never escalate, just try.
    let system_log = Path::new("/var/log").join(APP_DIR_NAME);
    if system_log.exists() {
        if remove_path(&system_log) {
            println!("Removed {}", system_log.display());
        } else {
            println!("Kept {} (needs root to remove)", system_log.display());
        }
    }

    println!();
    println!("Uninstall complete. Your recordings were kept.");
    println!("(Delete ~/meetings yourself if you want them gone too.)");
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fake_home() -> tempfile::TempDir {
        tempfile::tempdir().unwrap()
    }

    #[test]
    fn plan_covers_install_artifacts() {
        let home = PathBuf::from("/home/tester");
        let state = PathBuf::from(format!("/home/tester/.local/state/{APP_DIR_NAME}"));
        let targets = plan(&home, &state);
        let has = |suffix: &str| {
            targets
                .iter()
                .any(|p| p.to_string_lossy().ends_with(suffix))
        };
        assert!(has(&format!(".local/bin/{APP_DIR_NAME}")));
        assert!(has(&format!("applications/{APP_ID}.desktop")));
        assert!(has(&format!(".config/autostart/{APP_DIR_NAME}.desktop")));
        assert!(has(&format!("hicolor/48x48/apps/{APP_DIR_NAME}.png")));
        assert!(has(&format!("hicolor/scalable/apps/{APP_DIR_NAME}.svg")));
        assert!(has(&format!(".local/share/{APP_DIR_NAME}")));
        assert!(has(&format!(".config/{APP_DIR_NAME}")));
        assert!(targets.contains(&state));
    }

    #[test]
    fn remove_all_clears_fake_home() {
        let dir = fake_home();
        let home = dir.path();
        let state = home.join(format!(".local/state/{APP_DIR_NAME}"));
        let exe = home.join(".local/bin").join(APP_DIR_NAME);
        // Plant every planned target.
        for target in plan(home, &state) {
            if target == exe {
                continue;
            }
            if target.extension().is_some() {
                std::fs::create_dir_all(target.parent().unwrap()).unwrap();
                std::fs::write(&target, b"x").unwrap();
            } else {
                std::fs::create_dir_all(&target).unwrap();
            }
        }
        std::fs::create_dir_all(exe.parent().unwrap()).unwrap();
        std::fs::write(&exe, b"bin").unwrap();
        // A sibling file outside the plan keeps its dir alive.
        let keeper = home.join(".local/share/applications/mimeinfo.cache");
        std::fs::write(&keeper, b"keep").unwrap();

        let removed = remove_all(home, &state, &exe);
        assert!(!exe.exists(), "running binary removes itself last");
        assert!(removed.contains(&exe));
        for target in plan(home, &state) {
            assert!(!target.exists(), "leftover: {}", target.display());
        }
        // Emptied parents are pruned, non-empty ones stay.
        assert!(!home.join(".local/bin").exists());
        assert!(keeper.exists());
        assert!(home.exists());
    }
}
