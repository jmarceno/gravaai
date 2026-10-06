//! User-scoped menu integration for a stable portable launcher.
use std::path::Path;

use crate::config::defaults::{APP_DIR_NAME, APP_ID, APP_NAME};

pub fn desktop_entry(launcher: &Path) -> String {
    let exec = crate::utils::autostart::desktop_exec_path(launcher);
    format!(
        "[Desktop Entry]\nVersion=1.0\nType=Application\nName={APP_NAME}\n\
         Comment=Record, transcribe and summarize meetings\nExec={exec}\n\
         Icon={APP_DIR_NAME}\nTerminal=false\nCategories=AudioVideo;Audio;Recorder;\n\
         Keywords=meeting;record;transcribe;notes;audio;\nStartupNotify=true\nStartupWMClass={APP_ID}\n"
    )
}

/// Installing menu entries never changes config, credentials or recordings.
pub fn install_at(home: &Path, data: &Path, launcher: &Path, root: &Path) -> anyhow::Result<()> {
    anyhow::ensure!(
        launcher.is_absolute() && launcher.is_file(),
        "a stable launcher is required"
    );
    let apps = data.join("applications");
    std::fs::create_dir_all(&apps)?;
    let entry = apps.join(format!("{APP_ID}.desktop"));
    let temporary = entry.with_extension("desktop.new");
    std::fs::write(&temporary, desktop_entry(launcher))?;
    std::fs::rename(temporary, &entry)?;
    // Both historical desktop IDs describe this same application.
    let legacy = apps.join(format!("{APP_DIR_NAME}.desktop"));
    if legacy.exists() {
        std::fs::remove_file(&legacy)?;
    }
    let legacy_home = home
        .join(".local/share/applications")
        .join(format!("{APP_DIR_NAME}.desktop"));
    if legacy_home != legacy && legacy_home.exists() {
        std::fs::remove_file(legacy_home)?;
    }
    for size in [
        "16x16", "24x24", "32x32", "48x48", "64x64", "128x128", "256x256", "scalable",
    ] {
        let suffix = if size == "scalable" { "svg" } else { "png" };
        let relative = format!("icons/hicolor/{size}/apps/{APP_DIR_NAME}.{suffix}");
        let source = root.join("share").join(&relative);
        let dest = data.join(&relative);
        std::fs::create_dir_all(dest.parent().unwrap())?;
        std::fs::copy(source, dest)?;
    }
    Ok(())
}

pub fn run_install(autostart: bool) -> i32 {
    let result = (|| -> anyhow::Result<()> {
        let launcher = crate::utils::exe::own_portable_exe()
            .ok_or_else(|| anyhow::anyhow!("Run install from the portable .run launcher"))?;
        let root = crate::utils::exe::own_portable_root()
            .ok_or_else(|| anyhow::anyhow!("Portable payload missing"))?;
        let home = dirs::home_dir().ok_or_else(|| anyhow::anyhow!("Home directory unavailable"))?;
        let data =
            dirs::data_local_dir().ok_or_else(|| anyhow::anyhow!("Data directory unavailable"))?;
        install_at(&home, &data, &launcher, &root)?;
        // Preserve an existing enabled login entry during an upgrade.
        if autostart || crate::utils::autostart::is_autostart_enabled() {
            crate::utils::autostart::update_autostart(true);
        }
        println!("Installed {APP_NAME} menu entry: {}", launcher.display());
        Ok(())
    })();
    match result {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("Installation failed: {e:#}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn install_replaces_entry_and_keeps_user_data() {
        let temp = tempfile::tempdir().unwrap();
        let home = temp.path().join("home");
        let data = home.join(".local/share");
        let root = temp.path().join("payload");
        let launcher = home.join("Grava Ai.run");
        std::fs::create_dir_all(data.join("applications")).unwrap();
        std::fs::write(&launcher, "launcher").unwrap();
        let config = home.join("config.json");
        std::fs::write(&config, "keep credentials").unwrap();
        std::fs::write(data.join("applications/gravaai.desktop"), "old").unwrap();
        for size in [
            "16x16", "24x24", "32x32", "48x48", "64x64", "128x128", "256x256", "scalable",
        ] {
            let suffix = if size == "scalable" { "svg" } else { "png" };
            let source = root.join(format!("share/icons/hicolor/{size}/apps/gravaai.{suffix}"));
            std::fs::create_dir_all(source.parent().unwrap()).unwrap();
            std::fs::write(source, "icon").unwrap();
        }
        install_at(&home, &data, &launcher, &root).unwrap();
        let entry =
            std::fs::read_to_string(data.join(format!("applications/{APP_ID}.desktop"))).unwrap();
        assert!(entry.contains(&format!(
            "Exec={}\n",
            crate::utils::autostart::desktop_exec_path(&launcher)
        )));
        assert!(!entry.contains("payload/bin"));
        assert!(!data.join("applications/gravaai.desktop").exists());
        assert_eq!(std::fs::read_to_string(config).unwrap(), "keep credentials");
    }
}
