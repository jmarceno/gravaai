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
