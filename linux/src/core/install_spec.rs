//! Install request model.
//!
//! Install kinds: engine/runtime installers (`ollama`, `whisper_cpp_engine`,
//! the latter carrying a `backend`) and per-model downloads (`whisper_cpp_model`
//! with `model`, `ollama_model` with `model` + `host`).

use serde::{Deserialize, Serialize};

pub const KIND_OLLAMA: &str = "ollama";
pub const KIND_WHISPER_CPP_ENGINE: &str = "whisper_cpp_engine";
pub const KIND_WHISPER_CPP_MODEL: &str = "whisper_cpp_model";
pub const KIND_OLLAMA_MODEL: &str = "ollama_model";

pub const KINDS: &[&str] = &[
    KIND_OLLAMA,
    KIND_WHISPER_CPP_ENGINE,
    KIND_WHISPER_CPP_MODEL,
    KIND_OLLAMA_MODEL,
];

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct InstallSpec {
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub backend: String,
    #[serde(default)]
    pub host: String,
}

/// Stable id for dedup + UI mapping: per-model installs get a scoped key so
/// different models install concurrently while the same request dedups.
pub fn install_key(spec: &InstallSpec) -> String {
    if matches!(spec.kind.as_str(), "whisper_cpp_model" | "ollama_model") {
        return format!("{}:{}", spec.kind, spec.model);
    }
    spec.kind.clone()
}

pub fn spec_to_json(spec: &InstallSpec) -> String {
    serde_json::to_string(spec).unwrap_or_else(|_| "{}".to_string())
}

pub fn spec_from_json(payload: &str) -> anyhow::Result<InstallSpec> {
    let spec: InstallSpec = serde_json::from_str(payload)?;
    if !KINDS.contains(&spec.kind.as_str()) {
        anyhow::bail!("invalid install spec: {payload:?}");
    }
    Ok(spec)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys() {
        let e = InstallSpec {
            kind: "whisper_cpp_engine".into(),
            backend: "cpu".into(),
            ..Default::default()
        };
        assert_eq!(install_key(&e), "whisper_cpp_engine");
        let m = InstallSpec {
            kind: "ollama_model".into(),
            model: "phi4-mini".into(),
            ..Default::default()
        };
        assert_eq!(install_key(&m), "ollama_model:phi4-mini");
        let o = InstallSpec {
            kind: "ollama".into(),
            ..Default::default()
        };
        assert_eq!(install_key(&o), "ollama");
    }

    #[test]
    fn json_round_trip_and_validation() {
        let s = InstallSpec {
            kind: "whisper_cpp_engine".into(),
            backend: "cpu".into(),
            ..Default::default()
        };
        let back = spec_from_json(&spec_to_json(&s)).unwrap();
        assert_eq!(back, s);
        assert!(spec_from_json(r#"{"kind":"nope"}"#).is_err());
        assert!(spec_from_json("garbage").is_err());
    }
}
