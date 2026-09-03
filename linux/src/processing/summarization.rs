//! Summarization provider factory.

use crate::config::defaults::Config;

use super::providers::ollama::OllamaProvider;
use super::providers::openai_compat::OpenAiCompatProvider;

pub trait SummarizationProvider {
    fn summarize(
        &self,
        transcript: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String>;
    fn unload(&self) {}
}

struct OpenAiSummarization(OpenAiCompatProvider);

impl SummarizationProvider for OpenAiSummarization {
    fn summarize(
        &self,
        transcript: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        self.0.summarize(transcript, on_status)
    }
}

struct OllamaSummarization(OllamaProvider);

impl SummarizationProvider for OllamaSummarization {
    fn summarize(
        &self,
        transcript: &str,
        on_status: Option<&dyn Fn(&str)>,
    ) -> anyhow::Result<String> {
        self.0.summarize(transcript, on_status)
    }

    fn unload(&self) {
        self.0.unload();
    }
}

pub fn create_summarization_provider(cfg: &Config) -> Box<dyn SummarizationProvider> {
    match cfg.summarization_service.as_str() {
        "ollama" => Box::new(OllamaSummarization(OllamaProvider::new(
            &cfg.ollama_model,
            &cfg.ollama_host,
            &cfg.summarization_prompt,
            cfg.llm_request_timeout_minutes.max(10),
        ))),
        _ => Box::new(OpenAiSummarization(OpenAiCompatProvider::new(cfg))),
    }
}

/// Build a summarization provider that uses `prompt_template` instead of the
/// configured summarization prompt (used for auto-titling).
pub fn create_prompt_provider(
    cfg: &Config,
    prompt_template: &str,
) -> Box<dyn SummarizationProvider> {
    if cfg.summarization_service == "ollama" {
        // Ollama titling reuses the stock prompt path; title quality stays good
        // because the title prompt is short and explicit.
        let _ = prompt_template;
        Box::new(OllamaSummarization(OllamaProvider::new(
            &cfg.ollama_model,
            &cfg.ollama_host,
            prompt_template,
            cfg.llm_request_timeout_minutes.max(10),
        )))
    } else {
        // Clone the config with the template installed as the summarization prompt.
        let mut cfg2 = cfg.clone();
        cfg2.summarization_prompt = prompt_template.to_string();
        Box::new(OpenAiSummarization(OpenAiCompatProvider::new(&cfg2)))
    }
}
