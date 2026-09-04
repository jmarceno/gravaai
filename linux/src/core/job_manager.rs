//! Job list + persistence.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::job::{CancelToken, Job, JobStatus};

pub const INTERRUPTED_MSG: &str = "Interrupted — the app exited while this job was running";

const FORMAT_VERSION: u32 = 1;

/// Pure policy: how a persisted job status is restored at startup.
/// Returns `(status, error_msg)` for jobs to re-offer, or None to drop.
pub fn restore_status(persisted: &str) -> Option<(JobStatus, Option<String>)> {
    match persisted {
        "processing" => Some((JobStatus::Error, Some(INTERRUPTED_MSG.to_string()))),
        "error" => Some((JobStatus::Error, None)),
        _ => None, // done (or unknown) — nothing to re-offer
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct PersistedJob {
    job_id: i64,
    audio_path: String,
    transcript_path: String,
    notes_path: String,
    label: String,
    status: String,
    error_msg: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
struct PersistedState {
    version: u32,
    next_id: i64,
    jobs: Vec<PersistedJob>,
}

pub fn default_state_dir() -> PathBuf {
    if let Ok(base) = std::env::var("XDG_STATE_HOME") {
        if !base.is_empty() {
            return PathBuf::from(base).join(crate::config::defaults::APP_DIR_NAME);
        }
    }
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(format!(
            ".local/state/{}",
            crate::config::defaults::APP_DIR_NAME
        ))
}

/// Job list + jobs.json persistence. All methods are main-thread only.
pub struct JobManager {
    file: PathBuf,
    jobs: Vec<Job>,
    next_id: i64,
}

impl JobManager {
    pub fn new(state_dir: Option<PathBuf>) -> Self {
        let dir = state_dir.unwrap_or_else(default_state_dir);
        let file = dir.join("jobs.json");
        Self {
            file,
            jobs: Vec::new(),
            next_id: 0,
        }
    }

    pub fn jobs(&self) -> &[Job] {
        &self.jobs
    }
    pub fn find(&self, job_id: i64) -> Option<&Job> {
        self.jobs.iter().find(|j| j.job_id == job_id)
    }

    pub fn find_mut(&mut self, job_id: i64) -> Option<&mut Job> {
        self.jobs.iter_mut().find(|j| j.job_id == job_id)
    }

    /// Reserve a job id (used for pending jobs not yet committed).
    pub fn allocate_id(&mut self) -> i64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    pub fn create(
        &mut self,
        audio_path: PathBuf,
        transcript_path: PathBuf,
        notes_path: PathBuf,
        label: String,
    ) -> i64 {
        let job = Job::new(
            self.allocate_id(),
            audio_path,
            transcript_path,
            notes_path,
            label,
        );
        let id = job.job_id;
        self.jobs.push(job);
        self.persist();
        id
    }
    pub fn remove(&mut self, job_id: i64) {
        self.jobs.retain(|j| j.job_id != job_id);
        self.persist();
    }

    pub fn mark_done(&mut self, job_id: i64) {
        if let Some(j) = self.find_mut(job_id) {
            j.status = JobStatus::Done;
            j.error_msg = None;
        }
        self.persist();
    }

    pub fn mark_error(&mut self, job_id: i64, msg: String) {
        if let Some(j) = self.find_mut(job_id) {
            j.status = JobStatus::Error;
            j.error_msg = Some(msg);
        }
        self.persist();
    }

    pub fn mark_processing(&mut self, job_id: i64) {
        if let Some(j) = self.find_mut(job_id) {
            j.status = JobStatus::Processing;
            j.error_msg = None;
        }
        self.persist();
    }

    /// Explicit persistence hook (e.g. after path updates from auto-title).
    pub fn persist(&self) {
        if let Some(dir) = self.file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        // Cancelled jobs are excluded from disk.
        let jobs: Vec<PersistedJob> = self
            .jobs
            .iter()
            .filter(|j| !j.cancelled)
            .map(|j| PersistedJob {
                job_id: j.job_id,
                audio_path: j.audio_path.to_string_lossy().into_owned(),
                transcript_path: j.transcript_path.to_string_lossy().into_owned(),
                notes_path: j.notes_path.to_string_lossy().into_owned(),
                label: j.label.clone(),
                status: j.status.as_str().to_string(),
                error_msg: j.error_msg.clone(),
            })
            .collect();
        let state = PersistedState {
            version: FORMAT_VERSION,
            next_id: self.next_id,
            jobs,
        };
        let tmp = self.file.with_extension("json.tmp");
        let payload = match serde_json::to_string_pretty(&state) {
            Ok(p) => p,
            Err(e) => {
                log::warn!("Could not serialize jobs: {e}");
                return;
            }
        };
        // Best-effort: in-memory queue keeps working if the disk write fails.
        if std::fs::write(&tmp, payload).is_err() {
            return;
        }
        let _ = std::fs::rename(&tmp, &self.file);
    }

    /// Load jobs.json, restore re-offerable jobs, return their ids.
    /// Interrupted (processing) jobs come back as ERROR + Retry; ERROR jobs as-is;
    /// DONE jobs are dropped. Corrupt or missing state starts empty.
    pub fn load_persisted(&mut self) -> Vec<i64> {
        let text = match std::fs::read_to_string(&self.file) {
            Ok(t) => t,
            Err(_) => return Vec::new(),
        };
        let data: serde_json::Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(e) => {
                log::warn!("Could not read jobs state ({}); starting with no jobs", e);
                return Vec::new();
            }
        };
        let obj = match data.as_object() {
            Some(o) => o,
            None => {
                log::warn!("Persisted jobs state is not an object; starting with no jobs");
                return Vec::new();
            }
        };
        let mut restored = Vec::new();
        if let Some(entries) = obj.get("jobs").and_then(|j| j.as_array()) {
            for entry in entries {
                let status = entry.get("status").and_then(|s| s.as_str()).unwrap_or("");
                let Some((new_status, forced_msg)) = restore_status(status) else {
                    continue;
                };
                let parsed = (|| {
                    Some(Job {
                        job_id: entry.get("job_id")?.as_i64()?,
                        audio_path: PathBuf::from(entry.get("audio_path")?.as_str()?),
                        transcript_path: PathBuf::from(entry.get("transcript_path")?.as_str()?),
                        notes_path: PathBuf::from(entry.get("notes_path")?.as_str()?),
                        label: entry.get("label")?.as_str()?.to_string(),
                        status: new_status,
                        error_msg: forced_msg.or_else(|| {
                            entry
                                .get("error_msg")
                                .and_then(|m| m.as_str())
                                .map(|s| s.to_string())
                        }),
                        cancelled: false,
                        token: CancelToken::new(),
                    })
                })();
                match parsed {
                    Some(j) => restored.push(j),
                    None => log::warn!("Skipping malformed persisted job: {}", entry),
                }
            }
        }
        let next_id = obj.get("next_id").and_then(|n| n.as_i64()).unwrap_or(0);
        self.jobs = restored;
        let max_seen = self.jobs.iter().map(|j| j.job_id + 1).max().unwrap_or(0);
        self.next_id = next_id.max(max_seen);
        self.persist(); // drop DONE entries from disk right away
        self.jobs.iter().map(|j| j.job_id).collect()
    }
}

#[allow(dead_code)]
pub fn _path_ref(_p: &Path) {}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_mgr() -> (tempfile::TempDir, JobManager) {
        let d = tempfile::tempdir().unwrap();
        let m = JobManager::new(Some(d.path().to_path_buf()));
        (d, m)
    }

    #[test]
    fn restore_policy() {
        assert_eq!(
            restore_status("processing"),
            Some((JobStatus::Error, Some(INTERRUPTED_MSG.to_string())))
        );
        assert_eq!(restore_status("error"), Some((JobStatus::Error, None)));
        assert_eq!(restore_status("done"), None);
        assert_eq!(restore_status("bogus"), None);
    }

    #[test]
    fn round_trip_and_recovery() {
        let (_d, mut m) = tmp_mgr();
        let id = m.create("a.mp3".into(), "t.md".into(), "n.md".into(), "L".into());
        // Simulate a crash while processing: reload from disk.
        let mut m2 = JobManager::new(Some(_d.path().to_path_buf()));
        // next_id persisted so ids don't collide
        m2.next_id = 0;
        let ids = m2.load_persisted();
        assert_eq!(ids, vec![id]);
        assert_eq!(m2.find(id).unwrap().status, JobStatus::Error);
        assert_eq!(
            m2.find(id).unwrap().error_msg.as_deref(),
            Some(INTERRUPTED_MSG)
        );
        // New ids don't collide.
        let id2 = m2.create("b.mp3".into(), "t.md".into(), "n.md".into(), "L2".into());
        assert_ne!(id, id2);
    }

    #[test]
    fn done_pruned_and_corrupt_tolerated() {
        let (_d, mut m) = tmp_mgr();
        let id = m.create("a.mp3".into(), "t.md".into(), "n.md".into(), "L".into());
        m.mark_done(id);
        let mut m2 = JobManager::new(Some(_d.path().to_path_buf()));
        assert!(m2.load_persisted().is_empty());

        std::fs::write(_d.path().join("jobs.json"), "not json{{").unwrap();
        let mut m3 = JobManager::new(Some(_d.path().to_path_buf()));
        assert!(m3.load_persisted().is_empty());
    }

    #[test]
    fn cancelled_excluded() {
        let (_d, mut m) = tmp_mgr();
        let id = m.create("a.mp3".into(), "t.md".into(), "n.md".into(), "L".into());
        m.find_mut(id).unwrap().cancelled = true;
        m.persist();
        let mut m2 = JobManager::new(Some(_d.path().to_path_buf()));
        assert!(m2.load_persisted().is_empty());
    }
}
