//! Application-wide constants and default configuration.
//!
//! The cloud provider here is a single
//! **OpenAI-compatible** service (any `/v1`-style endpoint: OpenAI, Azure
//! OpenAI, Ollama, llama.cpp server, vLLM, LiteLLM, ...).

/// D-Bus / desktop application id.
pub const APP_ID: &str = "io.github.jmarceno.GravaAi";
/// User-facing display name.
pub const APP_NAME: &str = "GravaAi";
/// Filesystem / binary / icon / XDG directory basename.
pub const APP_DIR_NAME: &str = "gravaai";
pub const DEFAULT_OUTPUT_FOLDER: &str = "~/meetings";

pub fn whisper_cpp_model_info(model: &str) -> (&'static str, &'static str) {
    match model {
        "small" => ("~470 MB", "Fast, lower accuracy"),
        "medium" => ("~1.5 GB", "Good balance"),
        "large-v3-turbo" => ("~1.6 GB", "High quality, fast"),
        "large-v3" => ("~3 GB", "Best accuracy, slower"),
        _ => ("", ""),
    }
}

/// Transcription backends selectable in Settings → General.
///
/// NOTE: the retired `"whisper"` (faster-whisper) backend is gone; legacy
/// configs using it resolve to the whisper.cpp engine.
pub const TRANSCRIPTION_SERVICES: &[&str] = &["openai", "whisper_cpp"];
/// Summarization backends selectable in Settings → General.
pub const SUMMARIZATION_SERVICES: &[&str] = &["openai", "ollama"];

/// Allowed LLM request timeout values (minutes).
pub const LLM_TIMEOUT_OPTIONS: &[u64] = &[1, 2, 3, 5, 8, 10];

/// Suggested OpenAI-compatible chat models for summarization / titling.
pub const OPENAI_CHAT_MODELS: &[&str] = &[
    "gpt-5.6-luna",
    "gpt-4o-mini",
    "gpt-4o",
    "gpt-4.1-mini",
    "gpt-4.1",
    "whisper-1",
];

/// Suggested OpenAI-compatible speech-to-text models for transcription.
pub const OPENAI_STT_MODELS: &[&str] =
    &["whisper-1", "gpt-4o-mini-transcribe", "gpt-4o-transcribe"];

pub const OPENAI_DEFAULT_BASE_URL: &str = "https://api.openai.com/v1";
pub const OPENAI_DEFAULT_CHAT_MODEL: &str = "gpt-5.6-luna";
pub const OPENAI_DEFAULT_STT_MODEL: &str = "whisper-1";

// --- Local-engine model catalogues ------------------------------------------

pub const WHISPER_CPP_MODELS: &[&str] = &["large-v3-turbo", "large-v3", "medium", "small"];

pub const WHISPER_CPP_BACKENDS: &[&str] = &["auto", "cuda", "cpu"];

pub const OLLAMA_MODELS: &[&str] = &[
    "phi4-mini",
    "gemma3:4b",
    "qwen2.5:7b",
    "llama3.1:8b",
    "gemma3:12b",
];

pub const OLLAMA_DEFAULT_HOST: &str = "http://localhost:11434";

/// Burst-collapse window for call-detection notifications (seconds).
pub const CALL_DETECTION_DEDUP_WINDOW_SECS: u64 = 10;

/// Countdown before processing starts after Stop (seconds).
pub const COUNTDOWN_SECONDS: u64 = 5;

/// Processing-child stdout protocol prefixes (shared with daemon child readers).
/// Used by `daemon::processor` / `daemon::installer` when emitting and parsing.
pub const CHILD_STATUS_PREFIX: &str = "STATUS:";
pub const CHILD_RESULT_PREFIX: &str = "RESULT:";
pub const CHILD_ERROR_PREFIX: &str = "ERROR:";

pub fn whisper_cpp_ggml_file(model: &str) -> Option<&'static str> {
    match model {
        "small" => Some("ggml-small.bin"),
        "medium" => Some("ggml-medium.bin"),
        "large-v3" => Some("ggml-large-v3.bin"),
        "large-v3-turbo" => Some("ggml-large-v3-turbo.bin"),
        _ => None,
    }
}

pub const WHISPER_CPP_GGML_BASE_URL: &str =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

// --- whisper.cpp engine (prebuilt, no source builds) ------------------------
// The engine is fetched as an official upstream binary release — the user
// machine never needs a compiler. Pinned tag + per-asset SHA-256 so installs
// are reproducible and verified; bump all three together.
pub const WHISPER_CPP_RELEASE: &str = "b4938";
pub const WHISPER_CPP_RELEASE_BASE_URL: &str =
    "https://github.com/ggml-org/whisper.cpp/releases/download";

pub struct EngineAsset {
    pub filename: &'static str,
    pub sha256: &'static str,
    /// "tar.gz" (extract with the Rust tar/flate2 crates) or "zip"
    /// (extract in-process).
    pub format: &'static str,
}

/// Prebuilt engine binary for (`arch`, `backend`).
/// `arch` is `std::env::consts::ARCH` (`"x86_64"` / `"aarch64"`).
/// Returns None when no prebuilt exists.
///
/// NOTE: upstream publishes CUDA bundles for **Windows only**
/// (`whisper-cublas-*-bin-x64.zip` = `Release/*.exe` + `*.dll` — verified
/// against the b4938 asset listing). There is no prebuilt CUDA engine for
/// Linux, so `"cuda"` deliberately has no asset: `resolve_backend()` in
/// `services/whisper_cpp_service.rs` routes `auto` to `cpu` and rejects an
/// explicit `cuda` with an actionable message instead of downloading 670 MB
/// of unusable Windows binaries.
pub fn whisper_cpp_engine_asset(arch: &str, backend: &str) -> Option<EngineAsset> {
    match (arch, backend) {
        ("x86_64", "cpu") => Some(EngineAsset {
            filename: "whisper-bin-ubuntu-x64.tar.gz",
            sha256: "f4cfc1f969a13805908fb72043ce7cc896eb42e0b8afbe841dc8e7298923b061",
            format: "tar.gz",
        }),
        ("aarch64", "cpu") => Some(EngineAsset {
            filename: "whisper-bin-ubuntu-arm64.tar.gz",
            sha256: "94a33318650c57cc3d9a91439e0e3f0b94ba96bacd34203a06db395cf9204e40",
            format: "tar.gz",
        }),
        _ => None,
    }
}

pub fn whisper_cpp_engine_url(asset: &EngineAsset) -> String {
    format!(
        "{WHISPER_CPP_RELEASE_BASE_URL}/{WHISPER_CPP_RELEASE}/{}",
        asset.filename
    )
}

pub fn ollama_model_info(model: &str) -> (&'static str, &'static str) {
    match model {
        "phi4-mini" => ("~3 GB", "Lightest, good quality"),
        "gemma3:4b" => ("~4 GB", "Good quality"),
        "qwen2.5:7b" => ("~5 GB", "Very capable"),
        "llama3.1:8b" => ("~5 GB", "Very capable"),
        "gemma3:12b" => ("~8 GB", "Best quality, high RAM required"),
        _ => ("", ""),
    }
}

pub fn recording_quality_label(quality: &str) -> (&'static str, &'static str) {
    match quality {
        "very_high" => ("Very High Quality (~190kbps)", "2"),
        "high" => ("High Quality (~130kbps)", "5"),
        "medium" => ("Medium Quality (~100kbps)", "7"),
        "low" => ("Low Quality (~64kbps)", "9"),
        _ => ("High Quality (~130kbps)", "5"),
    }
}

pub const SUMMARIZATION_PROMPT: &str = "You are a meeting assistant. Given the following meeting transcript, produce concise, well-structured meeting notes in Markdown format.\n\nThe transcript may include speaker labels (e.g. **Speaker 1:**, **John:**). Where speaker labels are present, reference speakers by name or label when attributing decisions and key points.\n\nStructure the notes as follows:\n1. A brief summary of the meeting (2-4 sentences).\n2. Key discussion points and decisions, attributed to speakers where identifiable.\n3. If and only if there are clear action items mentioned in the meeting, add an ## Action Items section at the very end. List each item as a checkbox with the owner if known (e.g. `- [ ] John to send the report by Friday`). If there are no action items, omit this section entirely — do not write \"None\".\n\nTRANSCRIPT:\n{transcript}\n";

pub const TITLE_PROMPT: &str = "Generate a concise 3-6 word title for this meeting based on the content below. Return only the title text, nothing else.\n\n{transcript}";

pub const TRANSCRIPTION_PROMPT: &str = "Transcribe this audio recording exactly as spoken.\n\nLabel each speaker turn with a timestamp and speaker label on a new line, for example:\n\n[00:00:05] **Alice:** Hello, can everyone hear me?\n[00:00:09] **Bob:** Yes, loud and clear.\n\nRules:\n- Try to infer each speaker's name from the conversation (e.g. if someone is addressed by name or introduces themselves). Use that name as their label.\n- If a name cannot be determined, label speakers as **Person 1:**, **Person 2:**, etc., assigned in the order they first speak. Use the same label consistently for the same speaker.\n- Start each new speaker turn on a new line.\n- Timestamps should be in [HH:MM:SS] format, incremented roughly every turn.\n- Transcribe faithfully in whatever language is spoken; do not translate.\n";

/// Full default configuration. Empty string for any prompt key means
/// "use the built-in default" (see `effective_prompt` in settings).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct Config {
    pub transcription_service: String,
    pub summarization_service: String,
    pub openai_api_key: String,
    pub openai_base_url: String,
    pub openai_transcription_model: String,
    pub openai_summarization_model: String,
    pub output_folder: String,
    pub recording_quality: String,
    pub call_detection_enabled: bool,
    pub start_at_startup: bool,
    pub auto_title: bool,
    pub processing_countdown_enabled: bool,
    /// When false, stopping a recording saves the audio only — transcription
    /// and summarization never auto-start (the user runs them manually from
    /// Jobs / Library). True preserves the historical auto-process behavior.
    #[serde(default = "default_true")]
    pub auto_process_enabled: bool,
    pub low_memory_mode: bool,
    pub llm_request_timeout_minutes: u64,
    pub whisper_cpp_model: String,
    pub whisper_cpp_backend: String,
    pub ollama_model: String,
    pub ollama_host: String,
    pub transcription_prompt: String,
    pub summarization_prompt: String,
    pub title_prompt: String,
}

fn default_true() -> bool {
    true
}

impl Default for Config {
    fn default() -> Self {
        Self {
            transcription_service: "whisper_cpp".to_string(),
            summarization_service: "openai".to_string(),
            openai_api_key: String::new(),
            openai_base_url: OPENAI_DEFAULT_BASE_URL.to_string(),
            openai_transcription_model: OPENAI_DEFAULT_STT_MODEL.to_string(),
            openai_summarization_model: OPENAI_DEFAULT_CHAT_MODEL.to_string(),
            output_folder: DEFAULT_OUTPUT_FOLDER.to_string(),
            recording_quality: "high".to_string(),
            call_detection_enabled: false,
            start_at_startup: false,
            auto_title: true,
            processing_countdown_enabled: false,
            auto_process_enabled: true,
            low_memory_mode: false,
            llm_request_timeout_minutes: 5,
            whisper_cpp_model: "large-v3-turbo".to_string(),
            whisper_cpp_backend: "auto".to_string(),
            ollama_model: "phi4-mini".to_string(),
            ollama_host: OLLAMA_DEFAULT_HOST.to_string(),
            transcription_prompt: String::new(),
            summarization_prompt: String::new(),
            title_prompt: String::new(),
        }
    }
}
