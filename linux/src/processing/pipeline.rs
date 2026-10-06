//! End-to-end AI processing pipeline.
//!
//! Transcription then summarization run as **separate** calls (a single
//! dual-prompt call was removed upstream because the model would cut
//! transcription short to save output budget for notes).

use std::path::PathBuf;

use chrono::Local;
use regex::Regex;

use crate::config::defaults::{Config, SUMMARIZATION_PROMPT, TITLE_PROMPT};
use crate::config::settings::effective_prompt;
use crate::core::job::CancelToken;
use crate::utils::meeting_scanner::{read_metadata, rename_meeting_path, write_metadata};

use super::summarization::{create_prompt_provider, create_summarization_provider};
use super::transcription::create_transcription_provider;

/// Which stages the pipeline runs (mirrors `core::job::JobMode`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PipelineMode {
    /// Transcribe, then summarize (the normal pipeline).
    #[default]
    Full,
    /// Transcribe only — write transcript.md, never notes.md.
    TranscribeOnly,
    /// Summarize an existing transcript — never re-transcribe.
    SummarizeOnly,
}

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
    mode: PipelineMode,
    /// Spoken language (whisper code, e.g. "pt"), from this run's
    /// transcription or the meeting's `meeting.json`.
    language: Option<String>,
}

impl Pipeline {
    pub fn new(
        config: Config,
        audio_path: Option<PathBuf>,
        transcript_path: Option<PathBuf>,
        notes_path: Option<PathBuf>,
        on_status: Option<StatusCallback>,
    ) -> Self {
        Self::with_mode(
            config,
            audio_path,
            transcript_path,
            notes_path,
            on_status,
            PipelineMode::Full,
        )
    }

    pub fn with_mode(
        config: Config,
        audio_path: Option<PathBuf>,
        transcript_path: Option<PathBuf>,
        notes_path: Option<PathBuf>,
        on_status: Option<StatusCallback>,
        mode: PipelineMode,
    ) -> Self {
        Self {
            config,
            audio_path,
            transcript_path,
            notes_path,
            on_status,
            mode,
            language: None,
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
        check_cancelled(cancel_token)?;
        match self.mode {
            PipelineMode::TranscribeOnly => {
                let transcript = self.transcribe(cancel_token)?;
                self.status("Saving transcript…");
                self.write_results(&transcript, None);
                Ok(())
            }
            PipelineMode::Full => {
                let transcript = self.transcribe(cancel_token)?;
                let notes = self.summarize(cancel_token, &transcript)?;
                self.write_results(&transcript, Some(&notes));
                if self.config.auto_title {
                    self.auto_title(&notes);
                }
                Ok(())
            }
            PipelineMode::SummarizeOnly => {
                // The transcript already exists on disk; never re-transcribe.
                let transcript = self.summarize_existing_transcript(cancel_token)?;
                let notes = self.summarize(cancel_token, &transcript)?;
                self.write_results(&transcript, Some(&notes));
                if self.config.auto_title {
                    self.auto_title(&notes);
                }
                Ok(())
            }
        }
    }

    /// Transcription stage. Returns the transcript text.
    fn transcribe(&mut self, cancel_token: Option<&CancelToken>) -> anyhow::Result<String> {
        let audio_path = self
            .audio_path
            .clone()
            .ok_or_else(|| anyhow::anyhow!("Pipeline requires an audio path to transcribe"))?;
        check_cancelled(cancel_token)?;

        let ts_provider = create_transcription_provider(&self.config);
        let status_cb = |m: &str| self.status(m);
        let transcript = ts_provider.transcribe(&audio_path, Some(&status_cb))?;
        ts_provider.unload();
        if let Some(lang) = ts_provider.detected_language() {
            if let Some(dir) = audio_path.parent() {
                let mut meta = std::collections::HashMap::new();
                meta.insert("language".to_string(), serde_json::json!(lang));
                write_metadata(dir, meta);
            }
            self.language = Some(lang);
        }
        check_cancelled(cancel_token)?;
        Ok(transcript)
    }

    /// Summarize-only stage: read the existing transcript file instead of
    /// re-transcribing the audio. Fails fast with an actionable message when
    /// the transcript is missing so the user is told to transcribe first.
    fn summarize_existing_transcript(
        &mut self,
        cancel_token: Option<&CancelToken>,
    ) -> anyhow::Result<String> {
        let path = self
            .transcript_path
            .clone()
            .ok_or_else(|| anyhow::anyhow!("No transcript was provided to summarize"))?;
        if !path.is_file() {
            anyhow::bail!(
                "Transcript not found at {}. Transcribe this meeting first, then summarize.",
                path.display()
            );
        }
        check_cancelled(cancel_token)?;
        self.language = path
            .parent()
            .and_then(|dir| read_metadata(dir).get("language").cloned())
            .and_then(|v| v.as_str().map(str::to_string));
        self.status("Reading existing transcript…");
        std::fs::read_to_string(&path)
            .map_err(|e| anyhow::anyhow!("Could not read {}: {e:#}", path.display()))
    }

    /// Summarization stage (skipped for transcribe-only runs).
    fn summarize(
        &mut self,
        cancel_token: Option<&CancelToken>,
        transcript: &str,
    ) -> anyhow::Result<String> {
        if self.config.summarization_service == "ollama" {
            // The server may be down between meetings — start it automatically
            // (binary present, local host) instead of failing the job.
            let status_cb = |m: &str| self.status(m);
            crate::services::ollama_service::ensure_ollama_serving(
                &self.config.ollama_host,
                &status_cb,
            )?;
        }
        let mut cfg = self.config.clone();
        cfg.summarization_prompt = with_language_instruction(
            &effective_prompt(&cfg.summarization_prompt, SUMMARIZATION_PROMPT),
            self.language.as_deref(),
        );
        let ss_provider = create_summarization_provider(&cfg);
        let status_cb = |m: &str| self.status(m);
        let notes = drop_empty_sections(&ss_provider.summarize(transcript, Some(&status_cb))?);
        ss_provider.unload();
        check_cancelled(cancel_token)?;
        Ok(notes)
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
        let template = with_language_instruction(
            &effective_prompt(&self.config.title_prompt, TITLE_PROMPT),
            self.language.as_deref(),
        );
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

    /// Write results. `notes` is None for transcribe-only runs (transcript
    /// rewrite is idempotent: the summarize-only path passes the file's own
    /// content back).
    fn write_results(&self, transcript: &str, notes: Option<&str>) {
        self.status("Saving results…");
        if let Some(p) = &self.transcript_path {
            if let Some(parent) = p.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if std::fs::write(p, transcript).is_ok() {
                log::info!("Transcript saved: {}", p.display());
            }
        }
        if let Some(notes) = notes {
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
}

/// Remove Markdown `##` sections whose body is only a placeholder such as
/// "N/A", "None" or "Nenhum" — small local models write those even when told
/// to omit empty sections. Pure; unit-tested.
pub fn drop_empty_sections(notes: &str) -> String {
    fn is_placeholder(body: &str) -> bool {
        let t = body
            .trim()
            .trim_start_matches(['-', '*', ' '])
            .trim_end_matches('.')
            .trim()
            .to_lowercase();
        matches!(
            t.as_str(),
            "" | "n/a" | "na" | "none" | "nenhum" | "nenhuma" | "nada" | "ninguno" | "ninguna"
                | "aucun" | "aucune" | "keine" | "-" | "—"
        )
    }
    let mut out: Vec<String> = Vec::new();
    let mut section: Vec<&str> = Vec::new();
    let flush = |section: &mut Vec<&str>, out: &mut Vec<String>| {
        if let Some((head, body)) = section.split_first() {
            if !(head.starts_with("## ") && is_placeholder(&body.join("\n"))) {
                out.extend(section.iter().map(|l| l.to_string()));
            }
        }
        section.clear();
    };
    for line in notes.lines() {
        if line.starts_with("## ") || line.starts_with("# ") {
            flush(&mut section, &mut out);
        }
        section.push(line);
    }
    flush(&mut section, &mut out);
    let joined = out.join("\n");
    // Collapse the blank-line runs left behind by removed sections.
    let mut cleaned = String::with_capacity(joined.len());
    let mut blank = 0;
    for line in joined.lines() {
        if line.trim().is_empty() {
            blank += 1;
            if blank > 1 {
                continue;
            }
        } else {
            blank = 0;
        }
        cleaned.push_str(line);
        cleaned.push('\n');
    }
    cleaned.trim().to_string() + "\n"
}

/// English name for a whisper language code (common meeting languages).
pub fn language_name(code: &str) -> Option<&'static str> {
    Some(match code {
        "pt" => "Portuguese",
        "en" => "English",
        "es" => "Spanish",
        "fr" => "French",
        "de" => "German",
        "it" => "Italian",
        "nl" => "Dutch",
        "pl" => "Polish",
        "ru" => "Russian",
        "uk" => "Ukrainian",
        "tr" => "Turkish",
        "ja" => "Japanese",
        "ko" => "Korean",
        "zh" => "Chinese",
        "hi" => "Hindi",
        "ar" => "Arabic",
        "sv" => "Swedish",
        "da" => "Danish",
        "no" => "Norwegian",
        "fi" => "Finnish",
        "cs" => "Czech",
        "ro" => "Romanian",
        "el" => "Greek",
        "he" => "Hebrew",
        "id" => "Indonesian",
        "vi" => "Vietnamese",
        _ => return None,
    })
}

/// Append an explicit output-language instruction to a prompt template.
/// Small local models ignore an in-prompt "same language" rule but follow a
/// closing instruction naming the language. Unknown/absent language leaves
/// the template unchanged. Pure; unit-tested.
pub fn with_language_instruction(template: &str, language: Option<&str>) -> String {
    let Some(name) = language.and_then(language_name) else {
        return template.to_string();
    };
    let template = if template.contains("{transcript}") {
        template.to_string()
    } else {
        // Keep the transcript before the closing instruction.
        format!("{template}\n\n{{transcript}}")
    };
    format!(
        "{}\n\nIMPORTANT: The meeting was held in {name}. Write your entire answer in {name}.",
        template.trim_end()
    )
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
    fn placeholder_sections_are_dropped() {
        let notes = "## Summary\nWe met.\n\n## Decisions\nN/A\n\n## Action items\n- [ ] Ana: send it\n\n## Open questions\nNenhuma.\n";
        assert_eq!(
            drop_empty_sections(notes),
            "## Summary\nWe met.\n\n## Action items\n- [ ] Ana: send it\n"
        );
        // Real content is never touched.
        let keep = "# Notes\n\n## Decisions\n- None of the options was chosen yet\n";
        assert_eq!(drop_empty_sections(keep), keep);
    }

    #[test]
    fn language_instruction_closes_the_prompt() {
        let p = with_language_instruction("Notes:\n{transcript}\n", Some("pt"));
        assert!(p.starts_with("Notes:\n{transcript}"));
        assert!(p.ends_with("Write your entire answer in Portuguese."), "{p}");
        // Unknown or missing language → unchanged.
        assert_eq!(with_language_instruction("X {transcript}", None), "X {transcript}");
        assert_eq!(with_language_instruction("X {transcript}", Some("xx")), "X {transcript}");
        // A template without the placeholder still gets the transcript
        // before the instruction.
        let p = with_language_instruction("Summarize", Some("en"));
        assert!(p.find("{transcript}").unwrap() < p.find("IMPORTANT").unwrap());
    }

    #[test]
    fn summarize_only_reads_the_language_from_meeting_json() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("transcript.md"), "[00:00:01] oi").unwrap();
        std::fs::write(dir.path().join("meeting.json"), r#"{"language":"pt"}"#).unwrap();
        let mut p = Pipeline::with_mode(
            Config::default(),
            None,
            Some(dir.path().join("transcript.md")),
            Some(dir.path().join("notes.md")),
            None,
            PipelineMode::SummarizeOnly,
        );
        let text = p.summarize_existing_transcript(None).unwrap();
        assert_eq!(text, "[00:00:01] oi");
        assert_eq!(p.language.as_deref(), Some("pt"));
    }

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

    #[test]
    fn summarize_only_requires_an_existing_transcript_file() {
        // Summarize-only must fail fast with an actionable message when the
        // transcript is missing — and must not require or touch the audio.
        let dir = tempfile::tempdir().unwrap();
        let transcript = dir.path().join("transcript.md");
        let mut p = Pipeline::with_mode(
            Config::default(),
            None,
            Some(transcript),
            Some(dir.path().join("notes.md")),
            None,
            PipelineMode::SummarizeOnly,
        );
        let err = p.run(None).unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("Transcribe this meeting first"), "{msg}");
    }

    #[test]
    fn summarize_only_fails_without_a_transcript_path() {
        let mut p = Pipeline::with_mode(
            Config::default(),
            None,
            None,
            None,
            None,
            PipelineMode::SummarizeOnly,
        );
        let err = p.run(None).unwrap_err();
        assert!(format!("{err:#}").contains("No transcript was provided"));
    }

    #[test]
    fn transcribe_only_maps_to_the_transcription_path() {
        // Transcribe-only without audio must report the transcription
        // requirement (not the summarize-only transcript requirement) —
        // proving the mode routes to the transcribe stage before any
        // provider is ever constructed.
        let dir = tempfile::tempdir().unwrap();
        let mut p = Pipeline::with_mode(
            Config::default(),
            None,
            Some(dir.path().join("transcript.md")),
            Some(dir.path().join("notes.md")),
            None,
            PipelineMode::TranscribeOnly,
        );
        let err = p.run(None).unwrap_err();
        assert!(
            format!("{err:#}").contains("requires an audio path"),
            "unexpected error: {err:#}"
        );
    }

    #[test]
    fn transcribe_only_cancelled_before_start_writes_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let audio = dir.path().join("recording.mp3");
        std::fs::write(&audio, b"x").unwrap();
        let transcript = dir.path().join("transcript.md");
        let mut p = Pipeline::with_mode(
            Config::default(),
            Some(audio),
            Some(transcript.clone()),
            Some(dir.path().join("notes.md")),
            None,
            PipelineMode::TranscribeOnly,
        );
        let token = CancelToken::new();
        token.cancel();
        assert!(p.run(Some(&token)).is_err());
        assert!(!transcript.exists(), "cancelled run must not write files");
    }
}
