//! Import-existing-recording decision.

use std::path::{Path, PathBuf};

/// Decide how to import an audio file the user picked.
///
/// If `selected` already lives inside a meeting subdirectory of
/// `output_folder` it is reused in place (sibling transcript/notes returned).
/// Otherwise the file is external and the caller should copy it into a fresh
/// meeting directory. Returns `(reuse_in_place, paths)`.
pub fn resolve_existing_recording_target(
    selected: &Path,
    output_folder: &Path,
) -> (bool, Option<(PathBuf, PathBuf, PathBuf)>) {
    let selected_abs = absolutize(selected);
    let folder_abs = absolutize(output_folder);
    let inside_subdir = selected_abs
        .parent()
        .map(|p| p != folder_abs)
        .unwrap_or(false)
        && selected_abs.starts_with(&folder_abs);
    if inside_subdir {
        let session_dir = selected_abs.parent().unwrap().to_path_buf();
        return (
            true,
            Some((
                selected_abs,
                session_dir.join("transcript.md"),
                session_dir.join("notes.md"),
            )),
        );
    }
    (false, None)
}

/// Resolve the `(audio, transcript, notes)` paths for importing `selected`.
///
/// A file already inside a meeting directory is processed in place; an
/// external file is copied into a fresh `<YYYY-MM-DD_HH-MM>_<stem>` meeting
/// directory (suffixed `_2`, `_3`, … if that name is taken) so results land
/// in the library next to the audio.
pub fn prepare_import(
    selected: &Path,
    output_folder: &Path,
    now: &chrono::DateTime<chrono::Local>,
) -> anyhow::Result<(PathBuf, PathBuf, PathBuf)> {
    if !selected.is_file() {
        anyhow::bail!("Recording not found: {}", selected.display());
    }
    if let (true, Some(paths)) = resolve_existing_recording_target(selected, output_folder) {
        return Ok(paths);
    }
    let stem = selected
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let title = crate::utils::filename::sanitize_title(&stem);
    let mut base = now.format("%Y-%m-%d_%H-%M").to_string();
    if !title.is_empty() {
        base = format!("{base}_{title}");
    }
    let mut session = output_folder.join(&base);
    let mut n = 2;
    while session.exists() {
        session = output_folder.join(format!("{base}_{n}"));
        n += 1;
    }
    std::fs::create_dir_all(&session)?;
    let ext = selected
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_else(|| "mp3".to_string());
    let audio = session.join(format!("recording.{ext}"));
    if let Err(e) = std::fs::copy(selected, &audio) {
        let _ = std::fs::remove_dir_all(&session);
        anyhow::bail!("Could not copy {} into the library: {e}", selected.display());
    }
    Ok((audio, session.join("transcript.md"), session.join("notes.md")))
}

fn absolutize(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(p)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reuses_in_tree() {
        let folder = PathBuf::from("/meetings");
        let sel = PathBuf::from("/meetings/2026-03-01_14-30/recording.mp3");
        let (reuse, paths) = resolve_existing_recording_target(&sel, &folder);
        assert!(reuse);
        let (a, t, n) = paths.unwrap();
        assert_eq!(a, sel);
        assert_eq!(t, PathBuf::from("/meetings/2026-03-01_14-30/transcript.md"));
        assert_eq!(n, PathBuf::from("/meetings/2026-03-01_14-30/notes.md"));
    }

    #[test]
    fn prepare_import_copies_external_files_into_a_new_meeting() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("meetings");
        let ext = tmp.path().join("Weekly Sync.WAV");
        std::fs::write(&ext, b"audio").unwrap();
        let now = chrono::Local::now();
        let (a, t, n) = prepare_import(&ext, &out, &now).unwrap();
        let dir = a.parent().unwrap().to_path_buf();
        assert!(dir.starts_with(&out));
        let name = dir.file_name().unwrap().to_string_lossy().into_owned();
        assert!(name.ends_with("_Weekly_Sync"), "{name}");
        assert_eq!(a.file_name().unwrap(), "recording.wav");
        assert_eq!(std::fs::read(&a).unwrap(), b"audio");
        assert_eq!(t, dir.join("transcript.md"));
        assert_eq!(n, dir.join("notes.md"));
        assert!(ext.exists(), "the original must be left untouched");
        // A second import in the same minute gets its own directory.
        let (a2, _, _) = prepare_import(&ext, &out, &now).unwrap();
        assert_ne!(a2.parent(), a.parent());
        assert!(a2.parent().unwrap().to_string_lossy().ends_with("_2"));
    }

    #[test]
    fn prepare_import_reuses_files_already_in_the_library() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("2026-03-01_14-30");
        std::fs::create_dir_all(&dir).unwrap();
        let audio = dir.join("recording.mp3");
        std::fs::write(&audio, b"a").unwrap();
        let (a, t, _) = prepare_import(&audio, tmp.path(), &chrono::Local::now()).unwrap();
        assert_eq!(a, audio);
        assert_eq!(t, dir.join("transcript.md"));
        assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 1);
    }

    #[test]
    fn prepare_import_rejects_missing_files() {
        let tmp = tempfile::tempdir().unwrap();
        let err = prepare_import(&tmp.path().join("nope.mp3"), tmp.path(), &chrono::Local::now())
            .unwrap_err();
        assert!(format!("{err}").contains("not found"));
    }

    #[test]
    fn external_needs_copy() {
        let folder = PathBuf::from("/meetings");
        assert_eq!(
            resolve_existing_recording_target(&PathBuf::from("/tmp/call.mp3"), &folder),
            (false, None)
        );
        // Directly inside the output folder root is not a meeting subdir.
        assert_eq!(
            resolve_existing_recording_target(&PathBuf::from("/meetings/call.mp3"), &folder),
            (false, None)
        );
    }
}
