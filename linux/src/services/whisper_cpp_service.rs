//! whisper.cpp engine provisioning + GGML model management.
//!
//! The engine is fetched as an official upstream binary release — never built
//! from source, so the user machine needs no compiler. Binaries land in
//! `~/.local/share/meeting-recorder/whisper.cpp/` (`whisper-cli` plus its
//! `.so` libraries, run with `LD_LIBRARY_PATH` pointed there); GGML models
//! live in `~/.local/share/meeting-recorder/whisper-cpp-models/`.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::config::defaults::{
    whisper_cpp_engine_asset, whisper_cpp_engine_url, whisper_cpp_ggml_file,
    WHISPER_CPP_GGML_BASE_URL,
};

pub fn whisper_cpp_home() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(".local/share/meeting-recorder/whisper.cpp")
}

pub fn whisper_cpp_binary() -> PathBuf {
    whisper_cpp_home().join("whisper-cli")
}

pub fn whisper_cpp_models_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(".local/share/meeting-recorder/whisper-cpp-models")
}

/// Best engine flavor for this machine: `"cuda"` when an NVIDIA GPU is
/// present, else `"cpu"`. Pure aside from the injected probe.
pub fn detect_gpu_backend() -> String {
    detect_gpu_backend_with(&|b| crate::services::system_installer::which(b))
}

pub fn detect_gpu_backend_with(which_fn: &dyn Fn(&str) -> Option<String>) -> String {
    if which_fn("nvidia-smi").is_some() {
        return "cuda".to_string();
    }
    "cpu".to_string()
}

/// Resolve a backend selector (`"auto"`/`"cuda"`/`"cpu"`) to a concrete
/// flavor, validating it against this machine. Returns Err with an actionable
/// message when the choice cannot be served.
///
/// Upstream ships no prebuilt CUDA engine for Linux (their `whisper-cublas-*`
/// bundles are Windows `.exe`/`.dll`), so `auto` always resolves to `cpu`
/// — even on NVIDIA machines — and an explicit `cuda` fails immediately
/// (before any 670 MB download) telling the user to pick `cpu`.
pub fn resolve_backend(backend: &str) -> anyhow::Result<String> {
    resolve_backend_with(backend, &detect_gpu_backend())
}

/// Pure core of [`resolve_backend`] (`detected` is `detect_gpu_backend()`).
pub fn resolve_backend_with(backend: &str, detected: &str) -> anyhow::Result<String> {
    if backend == "auto" {
        if detected == "cuda" {
            log::info!(
                "NVIDIA GPU detected but upstream ships no prebuilt CUDA engine for Linux — using the CPU build"
            );
        }
        return Ok("cpu".to_string());
    }
    if backend == "cpu" {
        return Ok("cpu".to_string());
    }
    if backend == "cuda" {
        anyhow::bail!(
            "No prebuilt CUDA engine for Linux (upstream ships CUDA bundles for Windows only). Use the CPU backend instead — it runs on any machine."
        );
    }
    anyhow::bail!("Unknown whisper.cpp backend: {backend:?} (expected auto, cuda or cpu)");
}

#[derive(Default)]
pub struct WhisperCppEngineInstaller;

impl WhisperCppEngineInstaller {
    fn home_path() -> PathBuf {
        whisper_cpp_home()
    }

    fn binary_path() -> PathBuf {
        Self::home_path().join("whisper-cli")
    }

    pub fn is_installed(&self) -> bool {
        Self::binary_path().is_file()
    }

    /// Download the prebuilt engine for `backend`, verify, extract and smoke
    /// test it. No compiler, no privilege escalation, no system packages.
    pub fn install(&self, backend: &str, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
        let flavor = resolve_backend(backend)?;
        let asset = whisper_cpp_engine_asset(std::env::consts::ARCH, &flavor)
            .ok_or_else(|| anyhow::anyhow!("No prebuilt engine for this platform"))?;
        let url = whisper_cpp_engine_url(&asset);
        log::info!("Fetching whisper.cpp engine ({flavor}): {url}");

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

        // Smoke test: the binary must actually execute on this machine
        // (catches wrong-arch downloads immediately, not at 2 AM mid-meeting).
        on_status("Verifying engine…");
        let out = Command::new(Self::binary_path())
            .arg("--help")
            .env("LD_LIBRARY_PATH", &home)
            .output()?;
        if !out.status.success() {
            anyhow::bail!("Downloaded engine failed to run on this machine");
        }
        log::info!(
            "whisper.cpp engine ready: {}",
            Self::binary_path().display()
        );
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
    // Stream to disk (the CUDA bundle is ~670 MB — never hold it in RAM).
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
        if crate::services::system_installer::which("tar").is_none() {
            anyhow::bail!("`tar` is required to unpack the engine (install the tar package)");
        }
        let status = Command::new("tar")
            .args([
                "xzf",
                &archive.to_string_lossy(),
                "-C",
                &stage.to_string_lossy(),
            ])
            .status()?;
        if !status.success() {
            anyhow::bail!("Failed to unpack the engine archive");
        }
        return Ok(());
    }
    if format == "zip" {
        extract_zip(archive, stage)?;
        return Ok(());
    }
    anyhow::bail!("Unknown engine archive format: {format}");
}

fn extract_zip(archive: &Path, stage: &Path) -> anyhow::Result<()> {
    let file = std::fs::File::open(archive)?;
    let mut zip = zip::ZipArchive::new(file)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(path) = entry.enclosed_name() else {
            continue;
        };
        let dest = stage.join(path);
        if entry.is_dir() {
            std::fs::create_dir_all(&dest)?;
        } else {
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::File::create(&dest)?;
            std::io::copy(&mut entry, &mut out)?;
        }
    }
    Ok(())
}

/// Find `whisper-cli` plus its `.so` libraries anywhere in the staged tree
/// and flatten them into the engine home (robust to upstream layout changes).
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
                if name == "whisper-cli" {
                    binary = Some(path);
                } else if name.contains(".so") {
                    libs.push(path);
                }
            }
        }
    }
    let Some(binary) = binary else {
        anyhow::bail!(
            "Engine archive did not contain whisper-cli (top-level: {}). The upstream release layout may have changed — please report this.",
            top_level_names(stage, 12)
        );
    };
    std::fs::copy(&binary, home.join("whisper-cli"))?;
    for lib in libs {
        if let Some(name) = lib.file_name() {
            std::fs::copy(&lib, home.join(name))?;
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        std::fs::set_permissions(
            home.join("whisper-cli"),
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

pub struct WhisperCppStatusChecker {
    cache_root: PathBuf,
}

impl WhisperCppStatusChecker {
    pub fn new(cache_root: Option<PathBuf>) -> Self {
        Self {
            cache_root: cache_root.unwrap_or_else(whisper_cpp_models_dir),
        }
    }

    pub fn model_path(model: &str) -> PathBuf {
        Self::new(None).model_path_for(model)
    }

    pub fn model_path_for(&self, model: &str) -> PathBuf {
        let filename = whisper_cpp_ggml_file(model)
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("ggml-{model}.bin"));
        self.cache_root.join(filename)
    }

    pub fn is_cached(&self, model: &str) -> bool {
        self.model_path_for(model).exists()
    }
}

impl Default for WhisperCppStatusChecker {
    fn default() -> Self {
        Self::new(None)
    }
}

pub struct WhisperCppModelDownloader {
    cache_root: PathBuf,
}

impl WhisperCppModelDownloader {
    pub fn new(cache_root: Option<PathBuf>) -> Self {
        Self {
            cache_root: cache_root.unwrap_or_else(whisper_cpp_models_dir),
        }
    }

    /// Download `model`'s GGML weights. Raises (Err) on failure.
    pub fn download(&self, model: &str, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
        let filename = whisper_cpp_ggml_file(model)
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("ggml-{model}.bin"));
        let url = format!("{WHISPER_CPP_GGML_BASE_URL}{filename}");
        let dest = self.cache_root.join(&filename);
        std::fs::create_dir_all(&self.cache_root)?;
        on_status(&format!("Downloading {filename}…"));
        log::info!("Downloading {url}");
        let bytes = reqwest::blocking::get(&url)
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.bytes())
            .map_err(|e| {
                anyhow::anyhow!("Failed to download GGML model {model:?} from HuggingFace: {e:#}")
            })?;
        std::fs::write(&dest, &bytes)?;
        Ok(())
    }
}

impl Default for WhisperCppModelDownloader {
    fn default() -> Self {
        Self::new(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::defaults::whisper_cpp_engine_asset;

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
        // cpu always resolves (asset table covers both arches).
        let flavor = resolve_backend("cpu").unwrap();
        assert_eq!(flavor, "cpu");
        assert!(whisper_cpp_engine_asset(std::env::consts::ARCH, "cpu").is_some());
        // No CUDA prebuilt for any arch (upstream ships CUDA for Windows only).
        assert!(whisper_cpp_engine_asset("aarch64", "cuda").is_none());
        assert!(whisper_cpp_engine_asset("x86_64", "cuda").is_none());
        let url = whisper_cpp_engine_url(
            &whisper_cpp_engine_asset(std::env::consts::ARCH, "cpu").unwrap(),
        );
        assert!(url.starts_with("https://github.com/ggml-org/whisper.cpp/releases/download/"));
    }

    #[test]
    fn auto_resolves_to_cpu_even_with_nvidia() {
        // Upstream has no Linux CUDA prebuilt, so auto never picks cuda —
        // an NVIDIA machine gets the CPU build instead of a 670 MB Windows zip.
        assert_eq!(resolve_backend_with("auto", "cuda").unwrap(), "cpu");
        assert_eq!(resolve_backend_with("auto", "cpu").unwrap(), "cpu");
        assert_eq!(resolve_backend_with("cpu", "cuda").unwrap(), "cpu");
    }

    #[test]
    fn explicit_cuda_fails_before_downloading() {
        let err = resolve_backend_with("cuda", "cuda").unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("CPU"), "unexpected message: {msg}");
        assert!(resolve_backend("cuda").is_err());
    }

    #[test]
    fn staged_install_layout() {
        let root = tempfile::tempdir().unwrap();
        let stage = root.path().join("stage");
        let home = root.path().join("home");
        std::fs::create_dir_all(stage.join("nested/sub")).unwrap();
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(stage.join("nested/whisper-cli"), b"bin").unwrap();
        std::fs::write(stage.join("nested/sub/libwhisper.so.1"), b"lib").unwrap();
        std::fs::write(stage.join("README.md"), b"docs").unwrap();
        install_staged_engine(&stage, &home).unwrap();
        assert!(home.join("whisper-cli").is_file());
        assert!(home.join("libwhisper.so.1").is_file());
        assert!(!home.join("README.md").exists());
    }

    #[test]
    fn staged_install_missing_binary_names_contents() {
        // A layout change (e.g. a Windows-only bundle) must say what the
        // archive contained instead of just what was missing.
        let root = tempfile::tempdir().unwrap();
        let stage = root.path().join("stage");
        let home = root.path().join("home");
        std::fs::create_dir_all(stage.join("Release")).unwrap();
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(stage.join("Release/whisper-cli.exe"), b"bin").unwrap();
        let err = install_staged_engine(&stage, &home).unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("whisper-cli"), "unexpected message: {msg}");
        assert!(msg.contains("Release"), "unexpected message: {msg}");
        assert!(!home.join("whisper-cli").exists());
    }

    #[test]
    fn model_paths() {
        let root = tempfile::tempdir().unwrap();
        let checker = WhisperCppStatusChecker::new(Some(root.path().to_path_buf()));
        assert!(!checker.is_cached("small"));
        std::fs::write(checker.model_path_for("small"), b"x").unwrap();
        assert!(checker.is_cached("small"));
    }
}
