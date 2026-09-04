//! Downloaded-payload inventory + local engine/service status.
//!
//! Pure inventory logic over a base data dir (`~/.local/share/gravaai`).
//! The Qt window worker calls [`service_status_json`] on a blocking thread;
//! the QML Models page renders the status section and the Downloads page
//! renders the payload list from the same JSON. All filesystem walking is
//! done here so it stays unit-testable headless.

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::config::defaults::{APP_DIR_NAME, WHISPER_CPP_GGML_BASE_URL};

/// One downloaded payload shown in the Downloads tab.
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct PayloadRow {
    /// Human-readable payload name ("whisper.cpp engine", "ggml-small.bin",
    /// an Ollama model tag like "phi4-mini:latest").
    pub name: String,
    /// `"engine"`, `"model"` or `"binary"`.
    pub kind: String,
    /// Absolute location on disk ("" when the store path is unknown).
    pub path: String,
    /// True when `path` is a directory itself (engine/runtime/store rows) —
    /// the UI opens it directly instead of its parent.
    pub path_is_dir: bool,
    /// Size in bytes (0 when unknown).
    pub size_bytes: u64,
    /// Whether the payload is actually present/downloaded.
    pub present: bool,
}

/// Recursive directory size (bytes). Symlinks are not followed; unreadable
/// entries count as 0 so one bad entry never hides the rest.
pub fn dir_size(path: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(path) else {
        return 0;
    };
    let mut total = 0u64;
    for entry in entries.filter_map(|e| e.ok()) {
        let Ok(file_type) = entry.file_type() else {
            continue;
        };
        let p = entry.path();
        if file_type.is_dir() {
            total += dir_size(&p);
        } else if file_type.is_symlink() {
            // Versioned .so symlinks point inside the same dir; their target
            // is already counted — don't double-count.
        } else {
            total += std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
        }
    }
    total
}

/// Single-file size, 0 when missing/unreadable.
pub fn file_size(path: &Path) -> u64 {
    std::fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

fn row(
    name: &str,
    kind: &str,
    path: &Path,
    path_is_dir: bool,
    present: bool,
    size_bytes: u64,
) -> PayloadRow {
    PayloadRow {
        name: name.to_string(),
        kind: kind.to_string(),
        path: path.to_string_lossy().into_owned(),
        path_is_dir,
        size_bytes,
        present,
    }
}

/// GGML models actually on disk: every file in the models dir.
pub fn ggml_model_rows(models_dir: &Path) -> Vec<PayloadRow> {
    let Ok(entries) = std::fs::read_dir(models_dir) else {
        return Vec::new();
    };
    let mut rows: Vec<PayloadRow> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_file())
        .map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let size = e.metadata().map(|m| m.len()).unwrap_or(0);
            row(&name, "model", &e.path(), false, true, size)
        })
        .collect();
    rows.sort_by(|a, b| a.name.cmp(&b.name));
    rows
}

/// Ollama model rows from a running server (name + size from `/api/tags`).
fn ollama_model_rows(store: Option<&Path>, models: &[(String, u64)]) -> Vec<PayloadRow> {
    models
        .iter()
        .map(|(name, size)| {
            // The store dir itself is the best location we know per model.
            let path = store.map(|s| s.to_path_buf()).unwrap_or_default();
            row(name, "model", &path, true, true, *size)
        })
        .collect()
}

/// Every downloaded payload for the Downloads tab.
pub fn collect_payload_rows(
    base: &Path,
    ollama_models_store: Option<&Path>,
    ollama_models: &[(String, u64)],
) -> Vec<PayloadRow> {
    let mut rows = Vec::new();

    // whisper.cpp engine (binary + bundled .so libraries).
    let engine_dir = base.join("whisper.cpp");
    let engine_binary = engine_dir.join("whisper-cli");
    if engine_binary.is_file() {
        rows.push(row(
            "whisper.cpp engine (whisper-cli)",
            "engine",
            &engine_dir,
            true,
            true,
            dir_size(&engine_dir),
        ));
    }

    // Downloaded GGML models.
    rows.extend(ggml_model_rows(&base.join("whisper-cpp-models")));

    // Ollama binary.
    let ollama_dir = base.join("ollama");
    let ollama_binary = ollama_dir.join("ollama");
    if ollama_binary.is_file() {
        rows.push(row(
            &format!("Ollama {OLLAMA_VERSION_LABEL}"),
            "binary",
            &ollama_dir,
            true,
            true,
            dir_size(&ollama_dir),
        ));
    }

    // Ollama models live in the server's own store, so they are listed only
    // when the server answers.
    rows.extend(ollama_model_rows(ollama_models_store, ollama_models));
    rows
}

const OLLAMA_VERSION_LABEL: &str = "runtime";

/// Ollama's default model store (`$OLLAMA_MODELS` or `~/.ollama/models`).
/// `None` when home is unknown.
pub fn ollama_models_store() -> Option<PathBuf> {
    if let Ok(dir) = std::env::var("OLLAMA_MODELS") {
        if !dir.trim().is_empty() {
            return Some(PathBuf::from(dir));
        }
    }
    dirs::home_dir().map(|h| h.join(".ollama/models"))
}

/// The app's data dir holding all downloaded payloads.
pub fn payload_base_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(format!(".local/share/{APP_DIR_NAME}"))
}

/// Input for the status builder. Plain data so tests can build it freely.
pub struct StatusInput<'a> {
    /// `~/.local/share/gravaai`
    pub base: &'a Path,
    /// The configured Ollama host ("" → default localhost).
    pub ollama_host: &'a str,
    /// Whether an Ollama server answers at `ollama_host`.
    pub ollama_serving: bool,
    /// Whether the Ollama binary is installed (bundled or on PATH).
    pub ollama_installed: bool,
    /// Models served by Ollama (empty when the server is down).
    pub ollama_models: &'a [(String, u64)],
    /// The store path Ollama keeps models in, when known.
    pub ollama_models_store: Option<&'a Path>,
}

/// Full status JSON consumed by the Models page (status card) and the
/// Downloads page (payload table). Shape:
/// `{"payloads":[…],"whisper":{"engine_installed","engine_path","engine_size_bytes","models_dir","models_url"},
///   "ollama":{"installed","binary_path","serving","host","models_dir","models":[{"name","size"}]}}`
pub fn service_status_json(input: &StatusInput<'_>) -> String {
    let engine_dir = input.base.join("whisper.cpp");
    let engine_binary = engine_dir.join("whisper-cli");
    let engine_installed = engine_binary.is_file();
    let models_dir = input.base.join("whisper-cpp-models");

    let whisper = serde_json::json!({
        "engine_installed": engine_installed,
        "engine_path": engine_dir.to_string_lossy(),
        "engine_size_bytes": if engine_installed { dir_size(&engine_dir) } else { 0 },
        "models_dir": models_dir.to_string_lossy(),
        "models_url": WHISPER_CPP_GGML_BASE_URL,
    });
    let ollama_binary = input.base.join("ollama/ollama");
    let ollama = serde_json::json!({
        "installed": input.ollama_installed,
        "binary_path": ollama_binary.to_string_lossy(),
        "serving": input.ollama_serving,
        "host": input.ollama_host,
        "models_dir": input.ollama_models_store.map(|s| s.to_string_lossy().into_owned()).unwrap_or_default(),
        "models": input.ollama_models.iter().map(|(n, s)| serde_json::json!({"name": n, "size": s})).collect::<Vec<_>>(),
    });
    let payloads = collect_payload_rows(input.base, input.ollama_models_store, input.ollama_models);
    serde_json::json!({
        "base_dir": input.base.to_string_lossy(),
        "payloads": payloads,
        "whisper": whisper,
        "ollama": ollama,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, bytes: &[u8]) {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(path, bytes).unwrap();
    }

    #[test]
    fn dir_size_walks_nested_files() {
        let dir = tempfile::tempdir().unwrap();
        write(&dir.path().join("a.bin"), &[0u8; 10]);
        write(&dir.path().join("sub/b.bin"), &[0u8; 5]);
        assert_eq!(dir_size(dir.path()), 15);
        assert_eq!(dir_size(&dir.path().join("missing")), 0);
        assert_eq!(file_size(&dir.path().join("a.bin")), 10);
        assert_eq!(file_size(&dir.path().join("missing")), 0);
    }

    #[test]
    fn ggml_rows_list_files_sorted() {
        let dir = tempfile::tempdir().unwrap();
        let models = dir.path().join("models");
        write(&models.join("ggml-small.bin"), &[0u8; 3]);
        write(&models.join("ggml-large-v3-turbo.bin"), &[0u8; 7]);
        let rows = ggml_model_rows(&models);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].name, "ggml-large-v3-turbo.bin");
        assert_eq!(rows[0].size_bytes, 7);
        assert!(rows.iter().all(|r| r.kind == "model" && r.present));
        // Missing dir → no rows, no panic.
        assert!(ggml_model_rows(&dir.path().join("nope")).is_empty());
    }

    #[test]
    fn payload_rows_cover_engines_and_models() {
        let base = tempfile::tempdir().unwrap();
        write(&base.path().join("whisper.cpp/whisper-cli"), &[0u8; 4]);
        write(&base.path().join("whisper.cpp/libwhisper.so.1"), &[0u8; 6]);
        write(
            &base.path().join("whisper-cpp-models/ggml-small.bin"),
            &[0u8; 9],
        );
        write(&base.path().join("ollama/ollama"), &[0u8; 2]);
        let store = tempfile::tempdir().unwrap();
        let models = vec![
            ("phi4-mini:latest".to_string(), 2_500_000_000u64),
            ("gemma3:4b".to_string(), 3_300_000_000u64),
        ];
        let rows = collect_payload_rows(base.path(), Some(store.path()), &models);
        let by_name = |n: &str| {
            rows.iter()
                .find(|r| r.name == n)
                .unwrap_or_else(|| panic!("missing row {n}"))
        };
        let engine = by_name("whisper.cpp engine (whisper-cli)");
        assert_eq!(engine.kind, "engine");
        assert_eq!(engine.size_bytes, 10);
        assert!(engine.path_is_dir, "engine row points at a directory");
        assert!(engine.path.ends_with("whisper.cpp"));
        let ggml = by_name("ggml-small.bin");
        assert_eq!(ggml.size_bytes, 9);
        assert!(!ggml.path_is_dir, "ggml row points at a file");
        assert!(ggml.path.ends_with("whisper-cpp-models/ggml-small.bin"));
        let ollama = by_name("Ollama runtime");
        assert_eq!(ollama.size_bytes, 2);
        let model = by_name("phi4-mini:latest");
        assert_eq!(model.size_bytes, 2_500_000_000);
        assert_eq!(model.path, store.path().to_string_lossy());
        assert!(
            model.path_is_dir,
            "ollama model row points at the store dir"
        );
        // Empty base (nothing downloaded) → no engine rows, only server models.
        let empty = tempfile::tempdir().unwrap();
        let rows = collect_payload_rows(empty.path(), None, &[]);
        assert!(rows.is_empty());
    }

    #[test]
    fn status_json_shape_and_flags() {
        let base = tempfile::tempdir().unwrap();
        let store = tempfile::tempdir().unwrap();
        write(&base.path().join("whisper.cpp/whisper-cli"), &[0u8; 4]);
        let models = vec![("phi4-mini".to_string(), 100u64)];
        let input = StatusInput {
            base: base.path(),
            ollama_host: "http://localhost:11434",
            ollama_serving: true,
            ollama_installed: true,
            ollama_models: &models,
            ollama_models_store: Some(store.path()),
        };
        let json: serde_json::Value = serde_json::from_str(&service_status_json(&input)).unwrap();
        assert_eq!(
            json["base_dir"].as_str().unwrap(),
            base.path().to_string_lossy()
        );
        assert_eq!(json["whisper"]["engine_installed"], true);
        assert_eq!(json["whisper"]["engine_size_bytes"], 4);
        assert_eq!(json["ollama"]["serving"], true);
        assert_eq!(json["ollama"]["models"][0]["size"], 100);
        assert_eq!(json["payloads"].as_array().unwrap().len(), 2); // engine + 1 ollama model
                                                                   // Engine missing → installed false, size 0.
        let empty = tempfile::tempdir().unwrap();
        let input = StatusInput {
            base: empty.path(),
            ollama_host: "",
            ollama_serving: false,
            ollama_installed: false,
            ollama_models: &[],
            ollama_models_store: None,
        };
        let json: serde_json::Value = serde_json::from_str(&service_status_json(&input)).unwrap();
        assert_eq!(json["whisper"]["engine_installed"], false);
        assert_eq!(json["whisper"]["engine_size_bytes"], 0);
        assert_eq!(json["ollama"]["serving"], false);
        assert_eq!(json["payloads"].as_array().unwrap().len(), 0);
    }
}
