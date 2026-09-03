//! HTTP client for the Ollama local API.

use std::io::BufRead;
use std::time::Duration;

use crate::config::defaults::OLLAMA_DEFAULT_HOST;

/// Socket-read timeout for streaming responses. Applies per read, not to the
/// whole download: a healthy pull keeps data flowing, while a stalled server
/// errors instead of hanging the worker thread forever.
pub const STREAM_READ_TIMEOUT_SECS: u64 = 300;

pub struct OllamaClient {
    inner: reqwest::blocking::Client,
}

impl OllamaClient {
    pub fn new() -> Self {
        Self {
            inner: reqwest::blocking::Client::builder()
                .timeout(Duration::from_secs(STREAM_READ_TIMEOUT_SECS))
                .build()
                .unwrap_or_else(|_| reqwest::blocking::Client::new()),
        }
    }

    fn base(host: &str) -> String {
        if host.trim().is_empty() {
            OLLAMA_DEFAULT_HOST.to_string()
        } else {
            host.trim_end_matches('/').to_string()
        }
    }

    /// Installed model names, or None if Ollama is unreachable.
    pub fn get_installed_models(&self, host: &str) -> Option<Vec<String>> {
        let data: serde_json::Value = self
            .inner
            .get(format!("{}/api/tags", Self::base(host)))
            .timeout(Duration::from_secs(3))
            .send()
            .ok()?
            .json()
            .ok()?;
        Some(
            data.get("models")
                .and_then(|m| m.as_array())
                .cloned()
                .unwrap_or_default()
                .iter()
                .filter_map(|m| {
                    m.get("name")
                        .and_then(|n| n.as_str())
                        .map(|s| s.to_string())
                })
                .collect(),
        )
    }

    pub fn is_model_installed(&self, model: &str, installed: &[String]) -> bool {
        installed
            .iter()
            .any(|n| n == model || n.starts_with(&format!("{model}:")))
    }

    /// Stream-pull `model` from Ollama, reporting human-readable progress.
    /// Returns true when the server confirms success. Raises on network error
    /// or when the server reports an error mid-stream.
    pub fn pull_model(
        &self,
        model: &str,
        host: &str,
        on_progress: &dyn Fn(&str),
    ) -> anyhow::Result<bool> {
        let body = serde_json::json!({"name": model, "stream": true});
        let resp = self
            .inner
            .post(format!("{}/api/pull", Self::base(host)))
            .json(&body)
            .send()?;
        let reader = std::io::BufReader::new(resp);
        for line in reader.lines().map_while(Result::ok) {
            if line.trim().is_empty() {
                continue;
            }
            let data: serde_json::Value = match serde_json::from_str(&line) {
                Ok(d) => d,
                Err(_) => continue,
            };
            if !data.is_object() {
                continue;
            }
            if let Some(err) = data.get("error").and_then(|e| e.as_str()) {
                if !err.is_empty() {
                    anyhow::bail!("Ollama failed to pull {model:?}: {err}");
                }
            }
            let mut status_text = data
                .get("status")
                .and_then(|s| s.as_str())
                .unwrap_or("")
                .to_string();
            let total = data.get("total").and_then(|v| v.as_u64()).unwrap_or(0);
            let completed = data.get("completed").and_then(|v| v.as_u64()).unwrap_or(0);
            if total > 0 && completed > 0 {
                status_text = format!("{status_text} {}%", completed * 100 / total);
            }
            on_progress(&status_text);
            if data.get("status").and_then(|s| s.as_str()) == Some("success") {
                return Ok(true);
            }
        }
        // Stream ended without explicit "success" — do one final check.
        Ok(self
            .get_installed_models(host)
            .map(|installed| self.is_model_installed(model, &installed))
            .unwrap_or(false))
    }
}

impl Default for OllamaClient {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefix_match() {
        let c = OllamaClient::new();
        let installed = vec!["phi4-mini:latest".to_string(), "qwen2.5:7b".to_string()];
        assert!(c.is_model_installed("phi4-mini", &installed));
        assert!(c.is_model_installed("qwen2.5:7b", &installed));
        assert!(!c.is_model_installed("llama3.1:8b", &installed));
    }

    #[test]
    fn unreachable_returns_none() {
        let c = OllamaClient::new();
        assert!(c.get_installed_models("http://127.0.0.1:1").is_none());
    }
}
