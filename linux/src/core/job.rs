//! Background-job model (includes the cooperative cancellation token).

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum JobStatus {
    Processing,
    Done,
    Error,
}

impl JobStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            JobStatus::Processing => "processing",
            JobStatus::Done => "done",
            JobStatus::Error => "error",
        }
    }
}

/// Which pipeline stages a job runs. Serialized with jobs.json; older
/// persisted jobs without the field restore as [`JobMode::Full`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub enum JobMode {
    /// Transcribe, then summarize (the normal pipeline).
    #[default]
    #[serde(rename = "full")]
    Full,
    /// Transcribe only — never write notes.
    #[serde(rename = "transcribe")]
    TranscribeOnly,
    /// Summarize an existing transcript — never re-transcribe.
    #[serde(rename = "summarize")]
    SummarizeOnly,
}

impl JobMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            JobMode::Full => "full",
            JobMode::TranscribeOnly => "transcribe",
            JobMode::SummarizeOnly => "summarize",
        }
    }
}

/// Cooperative cancellation token, checked between pipeline stages.
#[derive(Debug, Clone, Default)]
pub struct CancelToken {
    cancelled: Arc<AtomicBool>,
}

impl CancelToken {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }
}

#[derive(Debug, Clone)]
pub struct Job {
    pub job_id: i64,
    pub audio_path: PathBuf,
    pub transcript_path: PathBuf,
    pub notes_path: PathBuf,
    pub label: String,
    pub status: JobStatus,
    pub error_msg: Option<String>,
    pub cancelled: bool,
    pub mode: JobMode,
    pub token: CancelToken,
}

impl Job {
    pub fn new(
        job_id: i64,
        audio_path: PathBuf,
        transcript_path: PathBuf,
        notes_path: PathBuf,
        label: String,
    ) -> Self {
        Self::with_mode(
            job_id,
            audio_path,
            transcript_path,
            notes_path,
            label,
            JobMode::Full,
        )
    }

    pub fn with_mode(
        job_id: i64,
        audio_path: PathBuf,
        transcript_path: PathBuf,
        notes_path: PathBuf,
        label: String,
        mode: JobMode,
    ) -> Self {
        Self {
            job_id,
            audio_path,
            transcript_path,
            notes_path,
            label,
            status: JobStatus::Processing,
            error_msg: None,
            cancelled: false,
            mode,
            token: CancelToken::new(),
        }
    }
}

/// Which action buttons a job row shows for `status`.
/// Returns identifiers, not widgets, so the policy is testable headless:
/// "cancel" | "open_folder" | "retry" | "dismiss".
pub fn actions_for_status(status: JobStatus) -> &'static [&'static str] {
    match status {
        JobStatus::Processing => &["cancel"],
        JobStatus::Done => &["open_folder", "dismiss"],
        JobStatus::Error => &["retry", "dismiss"],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn row_policy() {
        assert_eq!(actions_for_status(JobStatus::Processing), &["cancel"]);
        assert_eq!(
            actions_for_status(JobStatus::Done),
            &["open_folder", "dismiss"]
        );
        assert_eq!(actions_for_status(JobStatus::Error), &["retry", "dismiss"]);
    }

    #[test]
    fn token_cancel() {
        let t = CancelToken::new();
        assert!(!t.is_cancelled());
        t.cancel();
        assert!(t.is_cancelled());
        assert!(t.clone().is_cancelled());
    }
}
