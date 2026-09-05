//! D-Bus wire format.
//!
//! The daemon owns the authoritative recording state and job queue; the window
//! renders a copy fetched over D-Bus (`GetSnapshot`) and kept fresh by
//! `SnapshotChanged` signals. Parsing is tolerant of missing keys so a schema
//! addition on the daemon side never hard-crashes an older window.

use serde::{Deserialize, Serialize};

use super::job::JobStatus;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JobView {
    #[serde(default)]
    pub job_id: i64,
    #[serde(default)]
    pub label: String,
    #[serde(default = "default_processing")]
    pub status: JobStatus,
    #[serde(default)]
    pub error_msg: Option<String>,
    #[serde(default)]
    pub audio_dir: String,
    #[serde(default)]
    pub status_text: String,
}

fn default_processing() -> JobStatus {
    JobStatus::Processing
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Snapshot {
    #[serde(default = "default_idle")]
    pub state: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub elapsed: u64,
    #[serde(default)]
    pub countdown: u64,
    /// Live capture level, 0.0–1.0 (~10 Hz while recording, 0 otherwise).
    /// Missing in older payloads → 0 (tolerant parsing keeps old windows alive).
    #[serde(default)]
    pub audio_level: f32,
    #[serde(default)]
    pub jobs: Vec<JobView>,
}

fn default_idle() -> String {
    "idle".to_string()
}

impl Default for Snapshot {
    fn default() -> Self {
        Self {
            state: "idle".to_string(),
            status: String::new(),
            elapsed: 0,
            countdown: 0,
            audio_level: 0.0,
            jobs: Vec::new(),
        }
    }
}

pub fn snapshot_to_json(
    state: &str,
    status: &str,
    elapsed: u64,
    countdown: u64,
    audio_level: f32,
    jobs: &[JobView],
) -> String {
    serde_json::json!({
        "state": state,
        "status": status,
        "elapsed": elapsed,
        "countdown": countdown,
        "audio_level": audio_level.clamp(0.0, 1.0),
        "jobs": jobs,
    })
    .to_string()
}

pub fn snapshot_from_json(payload: &str) -> Snapshot {
    if payload.trim().is_empty() {
        return Snapshot::default();
    }
    serde_json::from_str(payload).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let jobs = vec![JobView {
            job_id: 3,
            label: "Standup".into(),
            status: JobStatus::Processing,
            error_msg: None,
            audio_dir: "/tmp/x".into(),
            status_text: "Transcribing…".into(),
        }];
        let json = snapshot_to_json("recording", "REC", 42, 0, 0.5, &jobs);
        let snap = snapshot_from_json(&json);
        assert_eq!(snap.state, "recording");
        assert_eq!(snap.elapsed, 42);
        assert!((snap.audio_level - 0.5).abs() < 1e-6);
        assert_eq!(snap.jobs.len(), 1);
        assert_eq!(snap.jobs[0].job_id, 3);
        assert_eq!(snap.jobs[0].status_text, "Transcribing…");
    }

    #[test]
    fn tolerant_parsing() {
        assert_eq!(snapshot_from_json(""), Snapshot::default());
        assert_eq!(snapshot_from_json("garbage"), Snapshot::default());
        assert_eq!(snapshot_from_json("null"), Snapshot::default());
        let s = snapshot_from_json("{}");
        assert_eq!(s.state, "idle");
        assert_eq!(s.audio_level, 0.0);
        // Pre-level payloads (older daemons) parse with a silent meter.
        let s = snapshot_from_json(r#"{"state":"recording","elapsed":7}"#);
        assert_eq!(s.audio_level, 0.0);
        // Missing status defaults to processing.
        let s = snapshot_from_json(r#"{"jobs": [{"job_id": 1}]}"#);
        assert_eq!(s.jobs[0].status, JobStatus::Processing);
    }

    #[test]
    fn level_is_clamped_to_unit_range() {
        let snap = snapshot_from_json(&snapshot_to_json("recording", "", 0, 0, 9.5, &[]));
        assert_eq!(snap.audio_level, 1.0);
        let snap = snapshot_from_json(&snapshot_to_json("recording", "", 0, 0, -2.0, &[]));
        assert_eq!(snap.audio_level, 0.0);
    }
}
