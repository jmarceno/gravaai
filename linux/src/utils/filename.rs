//! Output path generation.

use std::path::{Path, PathBuf};

use chrono::Local;
use regex::Regex;

/// Remove characters unsafe for filenames, collapse whitespace to `_`,
/// truncate to 50 chars.
pub fn sanitize_title(title: &str) -> String {
    let unsafe_chars = Regex::new(r#"[/\\:*?"<>|]"#).unwrap();
    let sanitized = unsafe_chars.replace_all(title, "");
    let ws = Regex::new(r"\s+").unwrap();
    let collapsed = ws.replace_all(sanitized.trim(), "_");
    collapsed.chars().take(50).collect()
}

fn expanduser(p: &str) -> PathBuf {
    if let Some(rest) = p.strip_prefix("~/") {
        match dirs::home_dir() {
            Some(h) => h.join(rest),
            None => PathBuf::from(p),
        }
    } else {
        PathBuf::from(p)
    }
}

/// Return (audio_path, transcript_path, notes_path) for a recording session.
///
/// Structure: `<output_folder>/<YYYY-MM-DD_HH-MM[_title]>/` holding
/// `recording.mp3 + transcript.md + notes.md`.
pub fn output_paths(output_folder: &str, title: Option<&str>) -> (PathBuf, PathBuf, PathBuf) {
    output_paths_at(output_folder, title, &Local::now())
}

pub fn output_paths_at(
    output_folder: &str,
    title: Option<&str>,
    dt: &chrono::DateTime<Local>,
) -> (PathBuf, PathBuf, PathBuf) {
    let date_time_part = dt.format("%Y-%m-%d_%H-%M").to_string();
    let folder_name = match title.map(str::trim).filter(|t| !t.is_empty()) {
        Some(t) => format!("{date_time_part}_{}", sanitize_title(t)),
        None => date_time_part,
    };
    let session_dir = expanduser(output_folder).join(folder_name);
    let _ = std::fs::create_dir_all(&session_dir);
    (
        session_dir.join("recording.mp3"),
        session_dir.join("transcript.md"),
        session_dir.join("notes.md"),
    )
}

/// Human label for a job row: the meeting dir's time part plus the title.
pub fn make_job_label(audio_path: &Path, title: Option<&str>) -> String {
    let time_part = audio_path
        .parent()
        .and_then(|p| p.file_name())
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| "recording".to_string());
    match title.map(str::trim).filter(|t| !t.is_empty()) {
        Some(t) => format!("{time_part} {t}").trim().to_string(),
        None => time_part,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize() {
        // '&' is legal in filenames and is preserved.
        assert_eq!(
            sanitize_title("Standup / Q&A: \"plan\"?"),
            "Standup_Q&A_plan"
        );
        assert_eq!(sanitize_title("  a  b\tc "), "a_b_c");
        assert_eq!(sanitize_title(&"x".repeat(100)).len(), 50);
    }

    #[test]
    fn layout() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("meetings");
        let out_s = out.to_string_lossy().into_owned();
        let (a, t, n) = output_paths(&out_s, Some("Standup"));
        assert!(a.parent().unwrap().is_dir());
        assert_eq!(a.file_name().unwrap(), "recording.mp3");
        assert_eq!(t.file_name().unwrap(), "transcript.md");
        assert_eq!(n.file_name().unwrap(), "notes.md");
        assert!(a
            .parent()
            .unwrap()
            .file_name()
            .unwrap()
            .to_string_lossy()
            .ends_with("_Standup"));
        let (a2, _, _) = output_paths(&out_s, None);
        let name = a2
            .parent()
            .unwrap()
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned();
        assert!(regex::Regex::new(r"^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}$")
            .unwrap()
            .is_match(&name));
    }

    #[test]
    fn label() {
        let p = PathBuf::from("/m/2026-03-01_14-30/recording.mp3");
        assert_eq!(
            make_job_label(&p, Some("Standup")),
            "2026-03-01_14-30 Standup"
        );
        assert_eq!(make_job_label(&p, None), "2026-03-01_14-30");
    }
}
