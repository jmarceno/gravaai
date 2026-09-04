//! Meeting library scanning + metadata.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use chrono::NaiveDateTime;
use regex::Regex;

use super::filename::sanitize_title;

const AUDIO_EXTENSIONS: &[&str] = &[".mp3", ".wav", ".m4a", ".ogg", ".flac", ".webm"];

#[derive(Debug, Clone)]
pub struct Meeting {
    pub path: PathBuf,
    pub time_label: String,
    pub date: NaiveDateTime,
    pub title: Option<String>,
    pub has_notes: bool,
    pub has_transcript: bool,
    pub duration_seconds: Option<u64>,
}

fn folder_pattern() -> Regex {
    Regex::new(r"^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})(?:_.*)?$").unwrap()
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

pub fn find_audio_file(meeting_path: &Path) -> Option<PathBuf> {
    meeting_path
        .read_dir()
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .find(|p| {
            p.is_file()
                && p.extension()
                    .and_then(|e| e.to_str())
                    .map(|e| AUDIO_EXTENSIONS.contains(&format!(".{e}").as_str()))
                    .unwrap_or(false)
        })
}

/// Walk the output folder and return all meetings, newest first.
/// Flat structure: `<output_folder>/<YYYY-MM-DD_HH-MM[_title]>/`.
pub fn scan_meetings(output_folder: &str) -> Vec<Meeting> {
    let root = expanduser(output_folder);
    let entries: Vec<PathBuf> = match root.read_dir() {
        Ok(rd) => rd
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.is_dir())
            .collect(),
        Err(_) => return Vec::new(),
    };
    let pat = folder_pattern();
    let mut meetings = Vec::new();
    for dir in entries {
        let name = dir
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        let Some(caps) = pat.captures(&name) else {
            continue;
        };
        // Skip active recordings / in-progress processing.
        if dir.join(".recording").exists() {
            continue;
        }
        let dt = NaiveDateTime::parse_from_str(
            &format!(
                "{}-{}-{} {}:{}:00",
                &caps[1], &caps[2], &caps[3], &caps[4], &caps[5]
            ),
            "%Y-%m-%d %H:%M:%S",
        );
        let Ok(dt) = dt else { continue };
        let meta = read_metadata(&dir);
        let audio_files: Vec<PathBuf> = dir
            .read_dir()
            .map(|rd| {
                rd.filter_map(|e| e.ok().map(|e| e.path()))
                    .filter(|p| {
                        p.is_file()
                            && p.extension()
                                .and_then(|e| e.to_str())
                                .map(|e| AUDIO_EXTENSIONS.contains(&format!(".{e}").as_str()))
                                .unwrap_or(false)
                    })
                    .collect()
            })
            .unwrap_or_default();
        let mut duration: Option<u64> = meta.get("duration_seconds").and_then(|v| {
            v.as_u64()
                .or_else(|| v.as_i64().and_then(|i| u64::try_from(i).ok()))
        });
        if duration.is_none() {
            if let Some(first) = audio_files.first() {
                if let Some(d) = probe_audio_duration(first) {
                    duration = Some(d);
                    write_metadata(
                        &dir,
                        [("duration_seconds".to_string(), serde_json::json!(d))]
                            .into_iter()
                            .collect(),
                    );
                }
            }
        }
        meetings.push(Meeting {
            time_label: name,
            title: meta
                .get("title")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
            has_notes: dir.join("notes.md").exists(),
            has_transcript: dir.join("transcript.md").exists(),
            duration_seconds: duration,
            date: dt,
            path: dir,
        });
    }
    meetings.sort_by_key(|m| std::cmp::Reverse(m.date));
    meetings
}

fn probe_audio_duration(audio_path: &Path) -> Option<u64> {
    let out = std::process::Command::new(crate::utils::exe::runtime_program("ffprobe"))
        .args([
            "-v",
            "quiet",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            &audio_path.to_string_lossy(),
        ])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    String::from_utf8_lossy(&out.stdout)
        .trim()
        .parse::<f64>()
        .ok()
        .map(|f| f as u64)
}

/// Read meeting.json. Returns empty map if missing/malformed.
pub fn read_metadata(meeting_path: &Path) -> HashMap<String, serde_json::Value> {
    let file = meeting_path.join("meeting.json");
    let text = match std::fs::read_to_string(&file) {
        Ok(t) => t,
        Err(_) => return HashMap::new(),
    };
    serde_json::from_str::<HashMap<String, serde_json::Value>>(&text).unwrap_or_default()
}

/// Write/merge metadata into meeting.json.
pub fn write_metadata(meeting_path: &Path, metadata: HashMap<String, serde_json::Value>) {
    let mut existing = read_metadata(meeting_path);
    existing.extend(metadata);
    let _ = std::fs::write(
        meeting_path.join("meeting.json"),
        serde_json::to_string_pretty(&existing).unwrap_or_default(),
    );
}

/// Delete meeting directories. Returns (succeeded, failures with message).
pub fn delete_meetings(meetings: &[Meeting]) -> (Vec<PathBuf>, Vec<(PathBuf, String)>) {
    let mut ok = Vec::new();
    let mut failed = Vec::new();
    for m in meetings {
        match std::fs::remove_dir_all(&m.path) {
            Ok(_) => ok.push(m.path.clone()),
            Err(e) => failed.push((m.path.clone(), e.to_string())),
        }
    }
    (ok, failed)
}

/// Rename a meeting directory to `{YYYY-MM-DD_HH-MM}_{sanitized_title}`
/// (with `_2`, `_3`, ... on collision). Returns the new path.
pub fn rename_meeting_path(meeting_dir: &Path, new_title: &str) -> anyhow::Result<PathBuf> {
    let name = meeting_dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let pat = folder_pattern();
    let caps = pat
        .captures(&name)
        .ok_or_else(|| anyhow::anyhow!("Cannot parse date-time from folder name: {name}"))?;
    let date_time_part = format!(
        "{}-{}-{}_{}-{}",
        &caps[1], &caps[2], &caps[3], &caps[4], &caps[5]
    );
    let new_name = format!("{date_time_part}_{}", sanitize_title(new_title));
    let parent = meeting_dir
        .parent()
        .ok_or_else(|| anyhow::anyhow!("meeting dir has no parent"))?;
    let mut new_path = parent.join(&new_name);
    if new_path.exists() && new_path != meeting_dir {
        let mut counter = 2u32;
        loop {
            let candidate = parent.join(format!("{new_name}_{counter}"));
            if !candidate.exists() {
                new_path = candidate;
                break;
            }
            counter += 1;
        }
    }
    std::fs::rename(meeting_dir, &new_path)?;
    Ok(new_path)
}

pub fn rename_meeting_dir(meeting: &Meeting, new_title: &str) -> anyhow::Result<PathBuf> {
    rename_meeting_path(&meeting.path, new_title)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_meeting(root: &Path, name: &str, with_notes: bool) -> PathBuf {
        let d = root.join(name);
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("recording.mp3"), b"fake").unwrap();
        std::fs::write(d.join("transcript.md"), "t").unwrap();
        if with_notes {
            std::fs::write(d.join("notes.md"), "n").unwrap();
        }
        d
    }

    #[test]
    fn scan_newest_first_and_skips() {
        let root = tempfile::tempdir().unwrap();
        make_meeting(root.path(), "2026-03-01_14-30", true);
        make_meeting(root.path(), "2026-03-02_09-00_Standup", false);
        std::fs::create_dir_all(root.path().join("random-dir")).unwrap();
        let active = make_meeting(root.path(), "2026-03-03_10-00", false);
        std::fs::write(active.join(".recording"), b"").unwrap();
        let meetings = scan_meetings(&root.path().to_string_lossy());
        assert_eq!(meetings.len(), 2);
        assert_eq!(meetings[0].time_label, "2026-03-02_09-00_Standup");
        assert!(meetings[1].has_notes);
        assert!(!meetings[0].has_notes);
    }

    #[test]
    fn rename_with_collision() {
        let root = tempfile::tempdir().unwrap();
        let a = make_meeting(root.path(), "2026-03-01_14-30", false);
        make_meeting(root.path(), "2026-03-01_14-30_Title", false);
        let new_path = rename_meeting_path(&a, "Title").unwrap();
        assert!(new_path
            .file_name()
            .unwrap()
            .to_string_lossy()
            .ends_with("_Title_2"));
    }

    #[test]
    fn metadata_round_trip() {
        let root = tempfile::tempdir().unwrap();
        let d = make_meeting(root.path(), "2026-03-01_14-30", false);
        write_metadata(
            &d,
            [("title".to_string(), serde_json::json!("Standup"))]
                .into_iter()
                .collect(),
        );
        let meta = read_metadata(&d);
        assert_eq!(meta.get("title").and_then(|v| v.as_str()), Some("Standup"));
    }
}
