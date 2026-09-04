//! CrispASR engine provisioning + Nemotron GGUF model management.
//!
//! Experimental third transcription backend. The engine is fetched as an
//! official upstream `crispasr` binary release — never built from source, so
//! the user machine needs no compiler. Binaries land in
//! `~/.local/share/gravaai/crisp-asr/` (`crispasr` plus its `.so` backend
//! libraries); Nemotron models live in
//! `~/.local/share/gravaai/crisp-asr-models/`.
//!
//! Lifecycle mirrors whisper.cpp: transcription runs as a short-lived CLI
//! invocation inside the `--process` child (starts on transcribe, exits on
//! completion), so there is no persistent service to start/stop. GPU memory
//! is freed the same way — the transcription wrapper unloads Ollama models
//! before running, exactly like the whisper.cpp provider.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::config::defaults::{
    crisp_asr_engine_asset, crisp_asr_engine_url, crisp_asr_model_file, APP_DIR_NAME,
    CRISP_ASR_HF_BASE_URL,
};

pub fn crisp_asr_home() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(format!(".local/share/{APP_DIR_NAME}/crisp-asr"))
}

pub fn crisp_asr_binary() -> PathBuf {
    crisp_asr_home().join("crispasr")
}

pub fn crisp_asr_models_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(format!(".local/share/{APP_DIR_NAME}/crisp-asr-models"))
}

/// Best engine flavor for this machine: `"cuda"` when an NVIDIA GPU is
/// present, else `"cpu"`. Vulkan is cross-vendor and stays explicit-only —
/// `auto` never picks it. Pure aside from the injected probe.
pub fn detect_gpu_backend() -> String {
    detect_gpu_backend_with(&|b| crate::services::system_installer::which(b))
}

pub fn detect_gpu_backend_with(which_fn: &dyn Fn(&str) -> Option<String>) -> String {
    if which_fn("nvidia-smi").is_some() {
        return "cuda".to_string();
    }
    "cpu".to_string()
}

/// Resolve a backend selector (`"auto"`/`"cpu"`/`"vulkan"`/`"cuda"`) to a
/// concrete flavor. `auto` follows detection; every concrete flavor is
/// accepted here — the installer reports when no prebuilt exists for this
/// arch (e.g. Vulkan/CUDA on aarch64).
pub fn resolve_backend(backend: &str) -> anyhow::Result<String> {
    resolve_backend_with(backend, &detect_gpu_backend())
}

/// Pure core of [`resolve_backend`] (`detected` is `detect_gpu_backend()`).
pub fn resolve_backend_with(backend: &str, detected: &str) -> anyhow::Result<String> {
    match backend {
        "auto" => Ok(detected.to_string()),
        "cpu" | "vulkan" | "cuda" => Ok(backend.to_string()),
        _ => anyhow::bail!(
            "Unknown CrispASR backend: {backend:?} (expected auto, cpu, vulkan or cuda)"
        ),
    }
}

#[derive(Default)]
pub struct CrispAsrEngineInstaller;

impl CrispAsrEngineInstaller {
    fn home_path() -> PathBuf {
        crisp_asr_home()
    }

    fn binary_path() -> PathBuf {
        Self::home_path().join("crispasr")
    }

    pub fn is_installed(&self) -> bool {
        Self::binary_path().is_file()
    }

    /// Download the prebuilt engine for `backend`, verify, extract and smoke
    /// test it. No compiler, no privilege escalation, no system packages.
    pub fn install(&self, backend: &str, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
        let flavor = resolve_backend(backend)?;
        let asset = crisp_asr_engine_asset(std::env::consts::ARCH, &flavor).ok_or_else(|| {
            anyhow::anyhow!(
                "No prebuilt CrispASR engine for {} + {flavor} (upstream ships Vulkan/CUDA Linux tarballs for x86_64 only). Use the CPU backend instead.",
                std::env::consts::ARCH
            )
        })?;
        let url = crisp_asr_engine_url(&asset);
        log::info!("Fetching CrispASR engine ({flavor}): {url}");

        let home = Self::home_path();
        std::fs::create_dir_all(&home)?;
        let archive = home.join(asset.filename);

        download_verified(&url, asset.sha256, &archive, on_status)?;

        on_status("Extracting engine…");
        let stage = home.join(".stage");
        if stage.exists() {
            std::fs::remove_dir_all(&stage)?;
        }
        std::fs::create_dir_all(&stage)?;
        extract_archive(&archive, &stage, asset.format)?;
        install_staged_engine(&stage, &home)?;
        let _ = std::fs::remove_file(&archive);
        let _ = std::fs::remove_dir_all(&stage);

        // Smoke test: the binary must actually execute on this machine.
        on_status("Verifying engine…");
        let out = Command::new(Self::binary_path())
            .arg("--version")
            .env("LD_LIBRARY_PATH", &home)
            .output()?;
        if !out.status.success() {
            anyhow::bail!("Downloaded CrispASR engine failed to run on this machine");
        }
        log::info!("CrispASR engine ready: {}", Self::binary_path().display());
        Ok(())
    }
}

fn download_verified(
    url: &str,
    sha256: &str,
    dest: &Path,
    on_status: &dyn Fn(&str),
) -> anyhow::Result<()> {
    on_status("Downloading engine…");
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(3600))
        .build()?;
    let mut resp = client.get(url).send()?;
    if !resp.status().is_success() {
        anyhow::bail!("Engine download failed: HTTP {}", resp.status().as_u16());
    }
    let total = resp.content_length().unwrap_or(0);
    let mut file = std::fs::File::create(dest)?;
    let mut downloaded: u64 = 0;
    let mut last_pct = 0u64;
    // Stream to disk (the CUDA bundle is ~160 MB — never hold it in RAM).
    use std::io::Read as _;
    let mut buf = [0u8; 256 * 1024];
    loop {
        let n = resp.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        downloaded += n as u64;
        if let Some(pct) = (total > 0).then(|| downloaded.saturating_mul(100) / total) {
            if pct >= last_pct + 5 {
                last_pct = pct;
                on_status(&format!("Downloading engine… {pct}%"));
            }
        }
    }
    drop(file);
    if sha256.is_empty() {
        // Experimental branch: hashes not pinned yet — log and continue.
        log::warn!("Skipping SHA-256 check for CrispASR engine (no pinned hash on this branch)");
        return Ok(());
    }
    on_status("Verifying download…");
    let bytes = std::fs::read(dest)?;
    let digest = crate::services::system_installer::sha256_hex(&bytes);
    if digest != sha256 {
        let _ = std::fs::remove_file(dest);
        anyhow::bail!("Engine download failed integrity check (SHA-256 mismatch)");
    }
    Ok(())
}

fn extract_archive(archive: &Path, stage: &Path, format: &str) -> anyhow::Result<()> {
    if format == "tar.gz" {
        let file = std::fs::File::open(archive)?;
        let decoder = flate2::read::GzDecoder::new(file);
        let mut tar = tar::Archive::new(decoder);
        for entry in tar.entries()? {
            let mut entry = entry?;
            let entry_type = entry.header().entry_type();
            let relative = entry.path()?.into_owned();
            if relative.is_absolute()
                || relative
                    .components()
                    .any(|component| matches!(component, std::path::Component::ParentDir))
            {
                anyhow::bail!(
                    "Engine archive contains an unsafe path: {}",
                    relative.display()
                );
            }
            if entry_type.is_symlink() || entry_type.is_hard_link() {
                let target = entry
                    .link_name()?
                    .ok_or_else(|| anyhow::anyhow!("Engine archive has a link without target"))?;
                if target.is_absolute()
                    || target
                        .components()
                        .any(|c| matches!(c, std::path::Component::ParentDir))
                {
                    anyhow::bail!(
                        "Engine archive contains an unsafe link: {} -> {}",
                        relative.display(),
                        target.display()
                    );
                }
            }
            let destination = stage.join(&relative);
            if let Some(parent) = destination.parent() {
                std::fs::create_dir_all(parent)?;
            }
            entry.unpack(&destination)?;
        }
        return Ok(());
    }
    anyhow::bail!("Unknown engine archive format: {format}");
}

/// Find `crispasr` plus its `.so` backend libraries anywhere in the staged
/// tree and flatten them into the engine home.
fn install_staged_engine(stage: &Path, home: &Path) -> anyhow::Result<()> {
    let mut binary: Option<PathBuf> = None;
    let mut libs = Vec::new();
    let mut stack = vec![stage.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let entries = std::fs::read_dir(&dir)?;
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                if name == "crispasr" {
                    binary = Some(path);
                } else if name.contains(".so") {
                    libs.push(path);
                }
            }
        }
    }
    let Some(binary) = binary else {
        anyhow::bail!(
            "Engine archive did not contain crispasr (top-level: {}). The upstream release layout may have changed — please report this.",
            top_level_names(stage, 12)
        );
    };
    std::fs::copy(&binary, home.join("crispasr"))?;
    for lib in libs {
        if let Some(name) = lib.file_name() {
            std::fs::copy(&lib, home.join(name))?;
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        std::fs::set_permissions(
            home.join("crispasr"),
            std::fs::Permissions::from_mode(0o755),
        )?;
    }
    Ok(())
}

/// First `limit` entry names under `dir` (one level), for error messages that
/// say what an archive actually contained instead of just what was missing.
fn top_level_names(dir: &Path, limit: usize) -> String {
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .filter_map(|e| e.ok())
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names.truncate(limit);
    if names.is_empty() {
        "(empty)".to_string()
    } else {
        names.join(", ")
    }
}

pub struct CrispAsrStatusChecker {
    cache_root: PathBuf,
}

impl CrispAsrStatusChecker {
    pub fn new(cache_root: Option<PathBuf>) -> Self {
        Self {
            cache_root: cache_root.unwrap_or_else(crisp_asr_models_dir),
        }
    }

    pub fn model_path(model: &str) -> PathBuf {
        Self::new(None).model_path_for(model)
    }

    pub fn model_path_for(&self, model: &str) -> PathBuf {
        let filename = crisp_asr_model_file(model)
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("{model}.gguf"));
        self.cache_root.join(filename)
    }

    pub fn is_cached(&self, model: &str) -> bool {
        self.model_path_for(model).exists()
    }
}

impl Default for CrispAsrStatusChecker {
    fn default() -> Self {
        Self::new(None)
    }
}

pub struct CrispAsrModelDownloader {
    cache_root: PathBuf,
}

impl CrispAsrModelDownloader {
    pub fn new(cache_root: Option<PathBuf>) -> Self {
        Self {
            cache_root: cache_root.unwrap_or_else(crisp_asr_models_dir),
        }
    }

    /// Download `model`'s GGUF weights from HuggingFace. Raises (Err) on failure.
    pub fn download(&self, model: &str, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
        let filename = crisp_asr_model_file(model)
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("{model}.gguf"));
        let url = format!("{CRISP_ASR_HF_BASE_URL}{filename}");
        let dest = self.cache_root.join(&filename);
        std::fs::create_dir_all(&self.cache_root)?;
        on_status(&format!("Downloading {filename}…"));
        log::info!("Downloading {url}");
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(3600))
            .build()?;
        let mut resp = client
            .get(&url)
            .send()
            .map_err(|e| {
                anyhow::anyhow!(
                    "Failed to download CrispASR model {model:?} from HuggingFace: {e:#}"
                )
            })?
            .error_for_status()
            .map_err(|e| {
                anyhow::anyhow!(
                    "Failed to download CrispASR model {model:?} from HuggingFace: {e:#}"
                )
            })?;
        let total = resp.content_length().unwrap_or(0);
        let tmp = dest.with_extension("part");
        let mut file = std::fs::File::create(&tmp)?;
        {
            use std::io::{Read as _, Write as _};
            let mut buf = [0u8; 256 * 1024];
            let mut downloaded: u64 = 0;
            let mut last_pct = 0u64;
            loop {
                let n = resp.read(&mut buf).map_err(|e| {
                    anyhow::anyhow!(
                        "Failed to download CrispASR model {model:?} from HuggingFace: {e:#}"
                    )
                })?;
                if n == 0 {
                    break;
                }
                file.write_all(&buf[..n])?;
                downloaded += n as u64;
                if let Some(pct) = downloaded.saturating_mul(100).checked_div(total.max(1)) {
                    if total > 0 && pct >= last_pct + 5 {
                        last_pct = pct;
                        on_status(&format!("Downloading {filename}… {pct}%"));
                    }
                }
            }
        }
        drop(file);
        std::fs::rename(&tmp, &dest)?;
        Ok(())
    }
}

impl Default for CrispAsrModelDownloader {
    fn default() -> Self {
        Self::new(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::defaults::crisp_asr_engine_asset;

    #[test]
    fn backend_detection() {
        let none = |_: &str| None;
        assert_eq!(detect_gpu_backend_with(&none), "cpu");
        let nv = |b: &str| {
            if b == "nvidia-smi" {
                Some("x".to_string())
            } else {
                None
            }
        };
        assert_eq!(detect_gpu_backend_with(&nv), "cuda");
    }

    #[test]
    fn backend_resolution() {
        assert!(resolve_backend("bogus").is_err());
        // All three concrete flavors resolve on x86_64 (asset table covers them).
        assert_eq!(resolve_backend_with("cpu", "cpu").unwrap(), "cpu");
        assert_eq!(resolve_backend_with("vulkan", "cpu").unwrap(), "vulkan");
        assert_eq!(resolve_backend_with("cuda", "cpu").unwrap(), "cuda");
        assert_eq!(resolve_backend_with("auto", "cuda").unwrap(), "cuda");
        assert_eq!(resolve_backend_with("auto", "cpu").unwrap(), "cpu");
        assert!(crisp_asr_engine_asset("x86_64", "cpu").is_some());
        assert!(crisp_asr_engine_asset("x86_64", "vulkan").is_some());
        assert!(crisp_asr_engine_asset("x86_64", "cuda").is_some());
        // aarch64 is CPU-only upstream.
        assert!(crisp_asr_engine_asset("aarch64", "cpu").is_some());
        assert!(crisp_asr_engine_asset("aarch64", "vulkan").is_none());
        assert!(crisp_asr_engine_asset("aarch64", "cuda").is_none());
        let url = crisp_asr_engine_url(
            &crisp_asr_engine_asset(std::env::consts::ARCH, "cpu")
                .unwrap_or_else(|| crisp_asr_engine_asset("x86_64", "cpu").unwrap()),
        );
        assert!(url.starts_with("https://github.com/CrispStrobe/CrispASR/releases/download/"));
    }

    #[test]
    fn staged_install_layout() {
        let root = tempfile::tempdir().unwrap();
        let stage = root.path().join("stage");
        let home = root.path().join("home");
        std::fs::create_dir_all(stage.join("nested/sub")).unwrap();
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(stage.join("nested/crispasr"), b"bin").unwrap();
        std::fs::write(stage.join("nested/sub/libggml-cuda.so"), b"lib").unwrap();
        std::fs::write(stage.join("README.md"), b"docs").unwrap();
        install_staged_engine(&stage, &home).unwrap();
        assert!(home.join("crispasr").is_file());
        assert!(home.join("libggml-cuda.so").is_file());
        assert!(!home.join("README.md").exists());
    }

    #[test]
    fn staged_install_missing_binary_names_contents() {
        let root = tempfile::tempdir().unwrap();
        let stage = root.path().join("stage");
        let home = root.path().join("home");
        std::fs::create_dir_all(&stage).unwrap();
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(stage.join("other.bin"), b"bin").unwrap();
        let err = install_staged_engine(&stage, &home).unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("crispasr"), "unexpected message: {msg}");
        assert!(!home.join("crispasr").exists());
    }

    #[test]
    fn model_paths() {
        let root = tempfile::tempdir().unwrap();
        let checker = CrispAsrStatusChecker::new(Some(root.path().to_path_buf()));
        let model = crate::config::defaults::CRISP_ASR_DEFAULT_MODEL;
        assert!(!checker.is_cached(model));
        std::fs::write(checker.model_path_for(model), b"x").unwrap();
        assert!(checker.is_cached(model));
        // Default model file is the Nemotron Q8 GGUF.
        assert!(checker
            .model_path_for(model)
            .to_string_lossy()
            .ends_with("nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf"));
    }
}
