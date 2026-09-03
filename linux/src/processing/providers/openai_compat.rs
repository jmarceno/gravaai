//! Single OpenAI-compatible AI provider (transcription + summarization).
//!
//! Any OpenAI-compatible `/v1` endpoint works: OpenAI, Azure OpenAI, Ollama's
//! OpenAI endpoint, llama.cpp server, vLLM, LiteLLM, OpenRouter, ...
//!
//! - Transcription: `POST {base_url}/audio/transcriptions`
//!   (multipart `file` + `model`, optional `prompt` carrying the transcription
//!   prompt and `response_format=text`).
//! - Summarization / titling: `POST {base_url}/chat/completions`
//!   (`{model, messages:[{role:"user",content}], temperature}`).

use std::path::Path;
use std::time::Duration;

use crate::config::defaults::{Config, SUMMARIZATION_PROMPT, TRANSCRIPTION_PROMPT};
use crate::config::settings::effective_prompt;
use crate::core::retry::retry_on_transient;

/// Fill `{transcript}` in a prompt template; if the user removed the
/// placeholder, append the transcript manually.
pub fn render_prompt(template: &str, transcript: &str) -> String {
    if template.contains("{transcript}") {
        template.replace("{transcript}", transcript)
    } else {
        format!("{template}\n\nTRANSCRIPT:\n{transcript}")
    }
}

pub struct OpenAiCompatProvider {
    api_key: String,
    base_url: String,
    stt_model: String,
    chat_model: String,
    transcription_prompt: String,
    summarization_prompt: String,
    timeout: Duration,
}

impl OpenAiCompatProvider {
    pub fn new(cfg: &Config) -> Self {
        let timeout_minutes = cfg.llm_request_timeout_minutes.clamp(1, 10);
        Self {
            api_key: cfg.openai_api_key.clone(),
            base_url: cfg.openai_base_url.trim_end_matches('/').to_string(),
            stt_model: cfg.openai_transcription_model.clone(),
            chat_model: cfg.openai_summarization_model.clone(),
            transcription_prompt: effective_prompt(&cfg.transcription_prompt, TRANSCRIPTION_PROMPT),
            summarization_prompt: effective_prompt(&cfg.summarization_prompt, SUMMARIZATION_PROMPT),
            timeout: Duration::from_secs(timeout_minutes * 60),
        }
    }

    fn client(&self) -> reqwest::blocking::Client {
        reqwest::blocking::Client::builder()
            .timeout(self.timeout)
            .build()
            .unwrap_or_else(|_| reqwest::blocking::Client::new())
    }

    fn timeout_minutes(&self) -> u64 {
        (self.timeout.as_secs() / 60).max(1)
    }

    // ------------------------------------------------------------------
    // Transcription
    // ------------------------------------------------------------------

    /// Transcribe an audio file via `/audio/transcriptions`.
    pub fn transcribe(
        &self,
        audio_path: &Path,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        if let Some(cb) = on_status {
            cb("Uploading audio for transcription…");
        }
        log::info!(
            "Transcribing {} via {}",
            audio_path.display(),
            self.base_url
        );
        let client = self.client();
        let url = format!("{}/audio/transcriptions", self.base_url);
        let file_name = audio_path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "recording.mp3".to_string());
        // Read once; rebuild the multipart body on each retry attempt
        // (blocking Form has no try_clone).
        let bytes = std::fs::read(audio_path)?;
        let mime = mime_for(audio_path);
        let api_key = self.api_key.clone();
        let stt_model = self.stt_model.clone();
        let prompt = self.transcription_prompt.clone();
        let minutes = self.timeout_minutes();
        if let Some(cb) = on_status {
            cb("Transcribing audio…");
        }
        let text = retry_on_transient(
            || -> anyhow::Result<String> {
                let part = reqwest::blocking::multipart::Part::bytes(bytes.clone())
                    .file_name(file_name.clone())
                    .mime_str(&mime)?;
                let form = reqwest::blocking::multipart::Form::new()
                    .text("model", stt_model.clone())
                    .text("prompt", prompt.clone())
                    .text("response_format", "verbose_json")
                    .part("file", part);
                let resp = client
                    .post(&url)
                    .bearer_auth(&api_key)
                    .multipart(form)
                    .send()
                    .map_err(|e| wrap_timeout(e, "transcription", minutes))?;
                if !resp.status().is_success() {
                    let status = resp.status();
                    let body = resp.text().unwrap_or_default();
                    return Err(classify_http_error(status.as_u16(), &body, "transcription"));
                }
                let body = resp.text().unwrap_or_default();
                extract_transcript_text(&body)
            },
            "transcription",
            2,
        )?;
        require_text(&text, "transcription")
    }

    // ------------------------------------------------------------------
    // Summarization
    // ------------------------------------------------------------------

    /// Summarize transcript text via `/chat/completions`.
    pub fn summarize(
        &self,
        transcript: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        self.summarize_with_prompt(transcript, &self.summarization_prompt, on_status)
    }

    pub fn summarize_with_prompt(
        &self,
        text: &str,
        prompt_template: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        if let Some(cb) = on_status {
            cb("Summarizing…");
        }
        let prompt = render_prompt(prompt_template, text);
        let client = self.client();
        let url = format!("{}/chat/completions", self.base_url);
        let body = serde_json::json!({
            "model": self.chat_model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.2,
        });
        let api_key = self.api_key.clone();
        let minutes = self.timeout_minutes();
        retry_on_transient(
            || -> anyhow::Result<String> {
                let resp = client
                    .post(&url)
                    .bearer_auth(&api_key)
                    .json(&body)
                    .send()
                    .map_err(|e| wrap_timeout(e, "summarization", minutes))?;
                if !resp.status().is_success() {
                    let status = resp.status();
                    let text = resp.text().unwrap_or_default();
                    return Err(classify_http_error(status.as_u16(), &text, "summarization"));
                }
                let data: serde_json::Value = resp.json().unwrap_or(serde_json::Value::Null);
                let text = data
                    .pointer("/choices/0/message/content")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                // Some gateways truncate with finish_reason=length.
                if text.is_empty() {
                    if data
                        .pointer("/choices/0/finish_reason")
                        .and_then(|v| v.as_str())
                        == Some("length")
                    {
                        anyhow::bail!(
                            "Output was truncated (summarization): the response hit the token limit. Try a shorter recording."
                        );
                    }
                    anyhow::bail!("The model returned no text for summarization.");
                }
                Ok(text)
            },
            "summarization",
            2,
        )
    }
}

fn mime_for(path: &Path) -> String {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_lowercase())
        .as_deref()
    {
        Some("wav") => "audio/wav",
        Some("m4a") => "audio/mp4",
        Some("ogg") => "audio/ogg",
        Some("flac") => "audio/flac",
        Some("webm") => "audio/webm",
        _ => "audio/mpeg",
    }
    .to_string()
}

/// Accept `verbose_json` (`{text, segments:[{start, text}]}` with
/// speaker-labeled `[HH:MM:SS]` lines), plain `{text}`, or raw text.
fn extract_transcript_text(body: &str) -> anyhow::Result<String> {
    let trimmed = body.trim();
    if trimmed.is_empty() {
        anyhow::bail!("The model returned no text for transcription.");
    }
    if !(trimmed.starts_with('{') || trimmed.starts_with('[')) {
        return Ok(trimmed.to_string());
    }
    let data: serde_json::Value = match serde_json::from_str(trimmed) {
        Ok(v) => v,
        // Not JSON after all (e.g. a transcript line starting with '[') —
        // treat the body as raw transcript text.
        Err(_) => return Ok(trimmed.to_string()),
    };
    if let Some(t) = data.get("text").and_then(|v| v.as_str()) {
        if !t.trim().is_empty() {
            // Prefer timestamped segments when present.
            if let Some(segs) = data.get("segments").and_then(|v| v.as_array()) {
                let lines: Vec<String> = segs
                    .iter()
                    .filter_map(|s| {
                        let start = s.get("start").and_then(|v| v.as_f64()).unwrap_or(0.0);
                        let text = s.get("text").and_then(|v| v.as_str())?.trim();
                        if text.is_empty() {
                            return None;
                        }
                        let (h, m, sec) = (
                            start as u64 / 3600,
                            start as u64 % 3600 / 60,
                            start as u64 % 60,
                        );
                        Some(format!("[{h:02}:{m:02}:{sec:02}] {text}"))
                    })
                    .collect();
                if !lines.is_empty() {
                    return Ok(lines.join("\n"));
                }
            }
            return Ok(t.trim().to_string());
        }
    }
    anyhow::bail!("The model returned no text for transcription.")
}

fn require_text(text: &str, context: &str) -> anyhow::Result<String> {
    let t = text.trim();
    if t.is_empty() {
        anyhow::bail!("The model returned no text for {context}.");
    }
    Ok(t.to_string())
}

fn wrap_timeout(err: reqwest::Error, context: &str, minutes: u64) -> anyhow::Error {
    if err.is_timeout() || err.is_connect() {
        anyhow::anyhow!(
            "The model did not respond within {minutes} minutes ({context}). The audio may be too long, or the service may be overloaded. Try again, or use a shorter recording."
        )
    } else if err.is_status() {
        err.into()
    } else {
        anyhow::anyhow!("request failed ({context}): {err}")
    }
}

/// Permanent errors (bad key, 4xx, model errors) fail immediately with a clear
/// message; transient statuses become retryable errors.
fn classify_http_error(status: u16, body: &str, context: &str) -> anyhow::Error {
    let detail = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| {
            v.pointer("/error/message")
                .or_else(|| v.get("error"))
                .and_then(|e| e.as_str().map(|s| s.to_string()))
        })
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| format!("HTTP {status}"));
    if status == 401 || status == 403 {
        anyhow::anyhow!(
            "Authentication failed ({context}): {detail}. Check the API key in Settings."
        )
    } else if status == 429 || (500..600).contains(&status) {
        // Transient: message carries the status so retry_on_transient retries.
        anyhow::anyhow!("transient HTTP {status} ({context}): {detail}")
    } else {
        anyhow::anyhow!("Request failed ({context}): {detail}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_rendering() {
        assert_eq!(render_prompt("Hi {transcript}!", "Bob"), "Hi Bob!");
        assert_eq!(
            render_prompt("Summarize:", "Bob"),
            "Summarize:\n\nTRANSCRIPT:\nBob"
        );
    }

    #[test]
    fn verbose_json_segments() {
        let body = r#"{"text":"hello world","segments":[{"start":5.0,"text":"hello"},{"start":9.0,"text":"world"}]}"#;
        assert_eq!(
            extract_transcript_text(body).unwrap(),
            "[00:00:05] hello\n[00:00:09] world"
        );
    }

    #[test]
    fn plain_text_passthrough() {
        assert_eq!(
            extract_transcript_text("[00:00:01] hi").unwrap(),
            "[00:00:01] hi"
        );
        assert!(extract_transcript_text("   ").is_err());
        assert!(extract_transcript_text(r#"{"text":""}"#).is_err());
    }

    #[test]
    fn auth_errors_are_clear() {
        let e = classify_http_error(401, r#"{"error":{"message":"bad key"}}"#, "summarization");
        assert!(format!("{e:#}").contains("API key"));
    }
}
