//! Persistent application configuration.
//!
//! Config lives in
//! `~/.config/meeting-recorder/config.json` (`chmod 600`), written atomically
//! via tmp+rename. The OpenAI-compatible API key lives in the Secret Service
//! keyring when one is reachable; `config.json` then only carries the
//! `@keyring` sentinel.

use std::collections::HashMap;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;

use anyhow::Context;
use serde_json::Value;

use super::defaults::Config;
use super::keyring_store::KeyringStore;

/// Written to config.json in place of the real key when it lives in the keyring.
pub const KEYRING_SENTINEL: &str = "@keyring";

pub fn config_path() -> PathBuf {
    PathBuf::from(shellexpand_config_file())
}

fn shellexpand_config_file() -> String {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("~"));
    home.join(".config/meeting-recorder/config.json")
        .to_string_lossy()
        .into_owned()
}

fn config_dir() -> PathBuf {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("~"));
    home.join(".config/meeting-recorder")
}

/// Load config: defaults merged with stored values.
/// Unknown stored keys are ignored; corrupt files fall back to defaults.
pub fn load() -> Config {
    let mut cfg = Config::default();
    let path = config_path();
    let stored: HashMap<String, Value> = std::fs::read_to_string(&path)
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default();

    let mut merged = serde_json::to_value(&cfg).unwrap_or(Value::Null);
    if let Value::Object(ref mut map) = merged {
        for (k, v) in &stored {
            if map.contains_key(k) {
                map.insert(k.clone(), v.clone());
            }
        }
        cfg = serde_json::from_value(Value::Object(map.clone())).unwrap_or_default();
    }

    if cfg.openai_api_key == KEYRING_SENTINEL {
        let store = KeyringStore::new();
        cfg.openai_api_key = store.get().unwrap_or_default();
    }
    cfg
}

/// Save config with 600 permissions; the API key goes to the keyring when possible.
pub fn save(cfg: &Config) -> anyhow::Result<()> {
    let store = KeyringStore::new();
    let mut value = serde_json::to_value(cfg)?;
    let key = cfg.openai_api_key.clone();
    if !key.is_empty() && key != KEYRING_SENTINEL {
        if store.available() && store.set(&key) {
            value[KEY_API] = Value::String(KEYRING_SENTINEL.to_string());
        }
    } else if key.is_empty() {
        if store.available() {
            store.delete();
        } else if stored_key_is_sentinel() {
            // Keyring unreachable (locked / dismissed unlock): an empty key most
            // likely came from a failed load(), not from the user clearing it.
            // Keep the sentinel so the secret is not silently lost.
            value[KEY_API] = Value::String(KEYRING_SENTINEL.to_string());
        }
    }
    write_value(&value)
}

const KEY_API: &str = "openai_api_key";

fn stored_key_is_sentinel() -> bool {
    std::fs::read_to_string(config_path())
        .ok()
        .and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .and_then(|v| v.get(KEY_API).cloned())
        == Some(Value::String(KEYRING_SENTINEL.to_string()))
}

/// One-time startup migration: move a plaintext key into the keyring.
/// Returns true if a key was moved.
pub fn migrate_key_to_keyring() -> bool {
    let path = config_path();
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(_) => return false,
    };
    let mut stored: Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(_) => return false,
    };
    // Accept the current key.
    let key = stored
        .get(KEY_API)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if key.is_empty() || key == KEYRING_SENTINEL {
        return false;
    }
    let store = KeyringStore::new();
    if !(store.available() && store.set(&key)) {
        return false;
    }
    if let Value::Object(ref mut map) = stored {
        map.insert(
            KEY_API.to_string(),
            Value::String(KEYRING_SENTINEL.to_string()),
        );
    }
    if write_value(&stored).is_ok() {
        log::info!("Migrated API key from config.json into the Secret Service keyring");
        return true;
    }
    false
}

fn write_value(value: &Value) -> anyhow::Result<()> {
    let dir = config_dir();
    std::fs::create_dir_all(&dir).context("creating config dir")?;
    let path = config_path();
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_string_pretty(value)?)?;
    std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
    std::fs::rename(&tmp, &path)?;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
    Ok(())
}

/// Soft format check at Settings-save time. Pure and unit-testable.
/// Unlike the old Google `AIza` prefix check, any non-blank key is accepted —
/// OpenAI-compatible endpoints (Ollama, vLLM, LiteLLM, ...) use arbitrary tokens.
pub fn api_key_warning(cfg: &Config) -> Option<String> {
    let uses_openai =
        cfg.transcription_service == "openai" || cfg.summarization_service == "openai";
    if !uses_openai {
        return None;
    }
    if cfg.openai_api_key.trim().is_empty() {
        return Some("An OpenAI-compatible service is selected but no API key is set.".to_string());
    }
    if cfg.openai_base_url.trim().is_empty() {
        return Some("The OpenAI-compatible base URL is empty.".to_string());
    }
    None
}

/// Resolve the effective prompt: stored text, or the built-in default when empty.
pub fn effective_prompt(stored: &str, default: &str) -> String {
    if stored.trim().is_empty() {
        default.to_string()
    } else {
        stored.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::defaults::Config;

    #[test]
    fn no_warning_when_local_services() {
        let c = Config {
            transcription_service: "whisper_cpp".into(),
            summarization_service: "ollama".into(),
            ..Config::default()
        };
        assert_eq!(api_key_warning(&c), None);
    }

    #[test]
    fn warns_on_missing_key() {
        let c = Config::default();
        assert!(api_key_warning(&c).is_some());
    }

    #[test]
    fn accepts_any_nonblank_key_and_url() {
        let c = Config {
            openai_api_key: "sk-anything".into(),
            ..Config::default()
        };
        assert_eq!(api_key_warning(&c), None);
    }

    #[test]
    fn effective_prompt_falls_back() {
        assert_eq!(effective_prompt("", "D"), "D");
        assert_eq!(effective_prompt("  ", "D"), "D");
        assert_eq!(effective_prompt("C", "D"), "C");
    }
}
