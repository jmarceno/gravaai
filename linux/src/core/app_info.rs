//! App identity + installed-version resolution.

use std::path::PathBuf;

pub const DESCRIPTION: &str =
    "Records meetings and generates transcripts and structured notes using AI.";
pub const REPOSITORY: &str = "https://github.com/jmarceno/gravaai";
pub const ISSUE_URL: &str = "https://github.com/jmarceno/gravaai/issues";
pub const DEVELOPER_NAME: &str = "jmarceno";
pub const DEVELOPERS: &[&str] = &["jmarceno <https://github.com/jmarceno>"];
pub const COPYRIGHT: &str = "© 2026 jmarceno (hard fork of Dipak Yadav's meeting-recorder)";
pub const PACKAGE_NAME: &str = "meeting-recorder";

/// Read the installed pacman package version (`pacman -Q meeting-recorder`
/// prints `<name> <version>`). Returns None on a source checkout or when
/// pacman is unavailable. The command runner is injectable for tests.
pub fn resolve_version(run: &dyn Fn() -> Option<String>) -> Option<String> {
    let out = run()?;
    let mut parts = out.split_whitespace();
    let name = parts.next()?;
    let version = parts.next()?;
    // Must be exactly our package (pacman errors like "error: package ..."
    // have extra tokens and a foreign name).
    if name != PACKAGE_NAME || parts.next().is_some() {
        return None;
    }
    if version.trim().is_empty() {
        return None;
    }
    Some(version.to_string())
}

/// Version stamped into the AppImage at pack time
/// (`usr/share/meeting-recorder/VERSION`). Only consulted when we are
/// actually running from our own AppImage (host IDE AppImages are ignored).
fn version_from_appimage() -> Option<String> {
    let appdir = std::env::var_os("APPDIR").map(PathBuf::from)?;
    crate::utils::exe::own_appimage()?;
    let text = std::fs::read_to_string(appdir.join("usr/share/meeting-recorder/VERSION")).ok()?;
    let version = text.trim();
    if version.is_empty() {
        None
    } else {
        Some(version.to_string())
    }
}

pub fn installed_version() -> Option<String> {
    if let Some(v) = version_from_appimage() {
        return Some(v);
    }
    resolve_version(&|| {
        std::process::Command::new("pacman")
            .args(["-Q", PACKAGE_NAME])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pacman_output() {
        let v = resolve_version(&|| Some("meeting-recorder 1.2.0-1\n".to_string()));
        assert_eq!(v.as_deref(), Some("1.2.0-1"));
    }

    #[test]
    fn tolerates_garbage() {
        assert_eq!(resolve_version(&|| None), None);
        assert_eq!(resolve_version(&|| Some("".to_string())), None);
        assert_eq!(resolve_version(&|| Some("   \n".to_string())), None);
        assert_eq!(
            resolve_version(&|| Some("error: package not found\n".to_string())),
            None
        );
    }
}
