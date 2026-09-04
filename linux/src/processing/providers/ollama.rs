//! Local LLM summarization via Ollama's HTTP API.
//!

use std::time::Duration;

use crate::config::defaults::{OLLAMA_DEFAULT_HOST, SUMMARIZATION_PROMPT};
use crate::config::settings::effective_prompt;
use crate::core::retry::retry_on_transient;
use crate::processing::providers::openai_compat::render_prompt;

pub fn get_loaded_models(host: &str) -> Vec<String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(3))
        .build();
    let Ok(client) = client else {
        return Vec::new();
    };
    client
        .get(format!("{}/api/ps", host.trim_end_matches('/')))
        .send()
        .ok()
        .and_then(|r| r.json::<serde_json::Value>().ok())
        .and_then(|v| v.get("models").cloned())
        .and_then(|m| m.as_array().cloned())
        .map(|arr| {
            arr.iter()
                .filter_map(|m| {
                    m.get("name")
                        .and_then(|n| n.as_str())
                        .map(|s| s.to_string())
                })
                .collect()
        })
        .unwrap_or_default()
}

pub fn unload_model(host: &str, model: &str) {
    let body = serde_json::json!({"model": model, "keep_alive": 0});
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build();
    let Ok(client) = client else { return };
    match client
        .post(format!("{}/api/generate", host.trim_end_matches('/')))
        .json(&body)
        .send()
    {
        Ok(_) => log::info!("Unloaded ollama model from memory: {model}"),
        Err(e) => log::warn!("Failed to unload ollama model {model}: {e:#}"),
    }
}

pub fn unload_all_models(host: &str) {
    for model in get_loaded_models(host) {
        unload_model(host, &model);
    }
}

pub struct OllamaProvider {
    model: String,
    host: String,
    summarization_prompt: String,
    timeout: Duration,
}

impl OllamaProvider {
    pub fn new(model: &str, host: &str, summarization_prompt: &str, timeout_minutes: u64) -> Self {
        let host = if host.trim().is_empty() {
            OLLAMA_DEFAULT_HOST
        } else {
            host
        };
        Self {
            model: model.to_string(),
            host: host.trim_end_matches('/').to_string(),
            summarization_prompt: effective_prompt(summarization_prompt, SUMMARIZATION_PROMPT),
            timeout: Duration::from_secs(timeout_minutes.clamp(1, 30) * 60),
        }
    }

    pub fn summarize(
        &self,
        transcript: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        if let Some(cb) = on_status {
            cb(&format!("Summarizing with Ollama ({})…", self.model));
        }
        let prompt = render_prompt(&self.summarization_prompt, transcript);
        let body = serde_json::json!({"model": self.model, "prompt": prompt, "stream": false});
        let url = format!("{}/api/generate", self.host);
        let timeout = self.timeout;
        let timeout_minutes = timeout.as_secs() / 60;
        let host = self.host.clone();
        let model = self.model.clone();
        let data: serde_json::Value = retry_on_transient(
            || -> anyhow::Result<serde_json::Value> {
                let client = reqwest::blocking::Client::builder()
                    .timeout(timeout)
                    .build()?;
                let resp = client.post(&url).json(&body).send().map_err(|e| {
                    if e.is_timeout() {
                        anyhow::anyhow!(
                            "Ollama did not respond within {timeout_minutes} minutes. The transcript may be too long, or the model may be overloaded."
                        )
                    } else if e.is_connect() {
                        anyhow::anyhow!(
                            "Cannot reach Ollama at {host}. Make sure ollama is running: ollama serve"
                        )
                    } else if let Some(status) = e.status() {
                        anyhow::anyhow!("Ollama error: HTTP {}", status.as_u16())
                    } else {
                        anyhow::anyhow!("Ollama request failed: {e}")
                    }
                })?;
                if !resp.status().is_success() {
                    let status = resp.status().as_u16();
                    let text = resp.text().unwrap_or_default();
                    let detail = serde_json::from_str::<serde_json::Value>(&text)
                        .ok()
                        .and_then(|v| {
                            v.get("error")
                                .and_then(|e| e.as_str())
                                .map(|s| s.to_string())
                        })
                        .filter(|s| !s.is_empty())
                        .unwrap_or_else(|| format!("HTTP {status}"));
                    if status == 429 || (500..600).contains(&status) {
                        if detail.to_lowercase().contains("unable to load model") {
                            anyhow::bail!(
                                "Ollama cannot load model {model:?} ({detail}). The download may be corrupt — remove it (`ollama rm {model}`) and re-download — or the model may be unsupported by this Ollama version, in which case pick another model in Settings → Models."
                            );
                        }
                        anyhow::bail!("transient HTTP {status}: {detail}");
                    }
                    anyhow::bail!("Ollama error: {detail}");
                }
                Ok(resp.json().unwrap_or(serde_json::Value::Null))
            },
            "Ollama summarization",
            2,
        )?;
        if !data.is_object() {
            anyhow::bail!("Ollama returned an invalid response format.");
        }
        if let Some(err) = data.get("error").and_then(|e| e.as_str()) {
            if !err.is_empty() {
                anyhow::bail!("Ollama error: {err}");
            }
        }
        // "response": null must read as empty (not the truthy string "None").
        let response = data
            .get("response")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        if response.is_empty() {
            anyhow::bail!("Ollama returned an empty response for model {model:?}.");
        }
        Ok(response.to_string())
    }

    pub fn unload(&self) {
        unload_model(&self.host, &self.model);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unreachable_host_gives_empty_list() {
        // Nothing listens here; must not panic or block long.
        assert!(get_loaded_models("http://127.0.0.1:1").is_empty());
    }
}
