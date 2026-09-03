//! End-to-end AI processing pipeline.
//!
//! Transcription then summarization run as **separate** calls (a single
//! dual-prompt call was removed upstream because the model would cut
//! transcription short to save output budget for notes).

use std::path::PathBuf;

use chrono::Local;
use regex::Regex;

use crate::config::defaults::{Config, TITLE_PROMPT};
use crate::config::settings::effective_prompt;
use crate::core::job::CancelToken;
use crate::utils::meeting_scanner::{rename_meeting_path, write_metadata};

use super::summarization::{create_prompt_provider, create_summarization_provider};
use super::transcription::create_transcription_provider;

#[derive(Debug)]
pub struct PipelineCancelled;

impl std::fmt::Display for PipelineCancelled {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "pipeline cancelled")
    }
}

impl std::error::Error for PipelineCancelled {}

pub type StatusCallback = Box<dyn Fn(&str) + Send>;

pub struct Pipeline {
    config: Config,
    audio_path: Option<PathBuf>,
    transcript_path: Option<PathBuf>,
    notes_path: Option<PathBuf>,
    on_status: Option<StatusCallback>,
}

impl Pipeline {
    pub fn new(
        config: Config,
        audio_path: Option<PathBuf>,
        transcript_path: Option<PathBuf>,
        notes_path: Option<PathBuf>,
        on_status: Option<StatusCallback>,
    ) -> Self {
        Self {
            config,
            audio_path,
            transcript_path,
            notes_path,
            on_status,
        }
    }

    fn status(&self, msg: &str) {
        if let Some(cb) = &self.on_status {
            cb(msg);
        }
    }

    /// Execute the pipeline. Raises on failure, `PipelineCancelled` on cancel.
    /// Cancellation is cooperative: the token is checked between stages (an
    /// in-flight network call still completes, but no further stage starts
    /// and nothing is written).
    pub fn run(&mut self, cancel_token: Option<&CancelToken>) -> anyhow::Result<()> {
        let audio_path = self
            .audio_path
            .clone()
            .ok_or_else(|| anyhow::anyhow!("Pipeline requires an audio path to transcribe"))?;
        check_cancelled(cancel_token)?;

        // Transcription.
        let ts_provider = create_transcription_provider(&self.config);
        let status_cb = |m: &str| self.status(m);
        let transcript = ts_provider.transcribe(&audio_path, Some(&status_cb))?;
        ts_provider.unload();
        check_cancelled(cancel_token)?;

        // Summarization.
        let ss_provider = create_summarization_provider(&self.config);
        let notes = ss_provider.summarize(&transcript, Some(&status_cb))?;
        ss_provider.unload();
        check_cancelled(cancel_token)?;

        self.write_results(&transcript, &notes);
        if self.config.auto_title {
            self.auto_title(&notes);
        }
        Ok(())
    }

    pub fn output_paths(&self) -> (Option<PathBuf>, Option<PathBuf>, Option<PathBuf>) {
        (
            self.audio_path.clone(),
            self.transcript_path.clone(),
            self.notes_path.clone(),
        )
    }

    fn auto_title(&mut self, notes: &str) {
        let (Some(notes_path), Some(audio_path)) =
            (self.notes_path.clone(), self.audio_path.clone())
        else {
            return;
        };
        let meeting_dir = match audio_path.parent() {
            Some(p) => p.to_path_buf(),
            None => return,
        };
        let name = meeting_dir
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        // Only auto-title untitled timestamp dirs (user-titled dirs are left alone).
        if !Regex::new(r"^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}(?:_\d+)?$")
            .unwrap()
            .is_match(&name)
        {
            return;
        }
        self.status("Generating title…");
        let template = effective_prompt(&self.config.title_prompt, TITLE_PROMPT);
        let cfg = self.config.clone();
        let provider = create_prompt_provider(&cfg, &template);
        let title = match provider.summarize(notes, None) {
            Ok(t) => t
                .trim()
                .trim_matches('"')
                .trim_matches('\'')
                .trim()
                .to_string(),
            Err(e) => {
                log::warn!("Auto-title failed: {e:#}");
                return;
            }
        };
        if title.is_empty() {
            return;
        }
        let mut meta = std::collections::HashMap::new();
        meta.insert("title".to_string(), serde_json::json!(title));
        meta.insert(
            "generated_at".to_string(),
            serde_json::json!(Local::now().to_rfc3339()),
        );
        write_metadata(&meeting_dir, meta);
        match rename_meeting_path(&meeting_dir, &title) {
            Ok(new_path) => {
                log::info!(
                    "Auto-titled meeting: {} -> {}",
                    meeting_dir.display(),
                    new_path.display()
                );
                let audio_name = audio_path.file_name().map(|s| s.to_owned());
                let transcript_name = self
                    .transcript_path
                    .as_ref()
                    .and_then(|p| p.file_name())
                    .map(|s| s.to_owned());
                let notes_name = notes_path.file_name().map(|s| s.to_owned());
                if let Some(n) = audio_name {
                    self.audio_path = Some(new_path.join(n));
                }
                if let Some(n) = transcript_name {
                    self.transcript_path = Some(new_path.join(n));
                }
                if let Some(n) = notes_name {
                    self.notes_path = Some(new_path.join(n));
                }
            }
            Err(e) => log::warn!("Auto-title failed: {e:#}"),
        }
    }

    fn write_results(&self, transcript: &str, notes: &str) {
        self.status("Saving results…");
        if let Some(p) = &self.transcript_path {
            if let Some(parent) = p.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if std::fs::write(p, transcript).is_ok() {
                log::info!("Transcript saved: {}", p.display());
            }
        }
        if let Some(p) = &self.notes_path {
            if let Some(parent) = p.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if std::fs::write(p, notes).is_ok() {
                log::info!("Notes saved: {}", p.display());
            }
        }
    }
}

fn check_cancelled(token: Option<&CancelToken>) -> anyhow::Result<()> {
    if token.map(|t| t.is_cancelled()).unwrap_or(false) {
        return Err(anyhow::Error::new(PipelineCancelled));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fails_fast_without_audio() {
        let mut p = Pipeline::new(Config::default(), None, None, None, None);
        assert!(p.run(None).is_err());
    }

    #[test]
    fn cancel_before_start() {
        let dir = tempfile::tempdir().unwrap();
        let audio = dir.path().join("recording.mp3");
        std::fs::write(&audio, b"x").unwrap();
        let mut p = Pipeline::new(Config::default(), Some(audio), None, None, None);
        let token = CancelToken::new();
        token.cancel();
        let err = p.run(Some(&token)).unwrap_err();
        assert!(err.is::<PipelineCancelled>());
    }
}
