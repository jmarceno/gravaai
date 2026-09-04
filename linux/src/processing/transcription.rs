//! Transcription provider factory.

use std::path::Path;

use crate::config::defaults::Config;

use super::providers::crisp_asr::CrispAsrProvider;
use super::providers::ollama::get_loaded_models;
use super::providers::openai_compat::OpenAiCompatProvider;
use super::providers::whisper_cpp::WhisperCppProvider;

pub trait TranscriptionProvider {
    fn transcribe(&self, audio: &Path, on_status: Option<&dyn Fn(&str)>) -> anyhow::Result<String>;
    fn unload(&self) {}
}

struct OpenAiTranscription(OpenAiCompatProvider);

impl TranscriptionProvider for OpenAiTranscription {
    fn transcribe(&self, audio: &Path, on_status: Option<&dyn Fn(&str)>) -> anyhow::Result<String> {
        self.0.transcribe(audio, on_status)
    }
}

struct WhisperCppTranscription {
    inner: WhisperCppProvider,
    ollama_host: String,
}

impl TranscriptionProvider for WhisperCppTranscription {
    fn transcribe(&self, audio: &Path, on_status: Option<&dyn Fn(&str)>) -> anyhow::Result<String> {
        if !get_loaded_models(&self.ollama_host).is_empty() {
            if let Some(cb) = on_status {
                cb("Freeing GPU memory (unloading ollama models)…");
            }
            super::providers::ollama::unload_all_models(&self.ollama_host);
        }
        self.inner.transcribe(audio, on_status)
    }
}

struct CrispAsrTranscription {
    inner: CrispAsrProvider,
    ollama_host: String,
}

impl TranscriptionProvider for CrispAsrTranscription {
    fn transcribe(&self, audio: &Path, on_status: Option<&dyn Fn(&str)>) -> anyhow::Result<String> {
        // Free GPU memory the same way the whisper.cpp path does — a loaded
        // Ollama model would otherwise starve the ASR backend on small VRAM.
        if !get_loaded_models(&self.ollama_host).is_empty() {
            if let Some(cb) = on_status {
                cb("Freeing GPU memory (unloading ollama models)…");
            }
            super::providers::ollama::unload_all_models(&self.ollama_host);
        }
        self.inner.transcribe(audio, on_status)
    }
}

pub fn create_transcription_provider(cfg: &Config) -> Box<dyn TranscriptionProvider> {
    match cfg.transcription_service.as_str() {
        "whisper_cpp" => Box::new(WhisperCppTranscription {
            ollama_host: cfg.ollama_host.clone(),
            inner: WhisperCppProvider::new(&cfg.whisper_cpp_model),
        }),
        "crisp_asr" => Box::new(CrispAsrTranscription {
            ollama_host: cfg.ollama_host.clone(),
            inner: CrispAsrProvider::new(&cfg.crisp_asr_model, &cfg.crisp_asr_backend),
        }),
        _ => Box::new(OpenAiTranscription(OpenAiCompatProvider::new(cfg))),
    }
}
