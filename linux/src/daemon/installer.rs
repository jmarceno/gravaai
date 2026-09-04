//! One-shot model/engine install child + daemon-side launcher.
//!
//! Each install runs in a short-lived `--install` child the daemon spawns and
//! tracks, so installs survive the Settings window closing and never bloat the
//! daemon. Protocol mirrors the processor: `STATUS:<text>` progress,
//! `RESULT:ok` success, `ERROR:<text>` failure.

use std::io::BufRead;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;

use super::child_io::StderrTail;
use crate::config::defaults::{CHILD_ERROR_PREFIX, CHILD_RESULT_PREFIX, CHILD_STATUS_PREFIX};
use crate::core::install_spec::{self, InstallSpec};

#[derive(Debug, Clone)]
pub enum InstallEvent {
    Status(String),
    Finished(bool, String),
}

fn emit(prefix: &str, text: &str) {
    println!("{prefix}{}", text.replace('\n', " "));
}

fn require(ok: bool, what: &str) -> anyhow::Result<()> {
    if ok {
        Ok(())
    } else {
        Err(anyhow::anyhow!("{what} failed"))
    }
}

fn run_install(spec: &InstallSpec, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
    use crate::services::system_installer::OllamaInstaller;
    match spec.kind.as_str() {
        install_spec::KIND_OLLAMA => OllamaInstaller::install(on_status)
            .map_err(|e| anyhow::anyhow!("Ollama install: {e:#}")),
        install_spec::KIND_WHISPER_CPP_ENGINE => {
            use crate::services::whisper_cpp_service::WhisperCppEngineInstaller;
            let backend = if spec.backend.is_empty() {
                "auto"
            } else {
                spec.backend.as_str()
            };
            WhisperCppEngineInstaller.install(backend, on_status)
        }
        install_spec::KIND_WHISPER_CPP_MODEL => {
            use crate::services::whisper_cpp_service::WhisperCppModelDownloader;
            WhisperCppModelDownloader::default().download(&spec.model, on_status)
        }
        install_spec::KIND_OLLAMA_MODEL => {
            use crate::services::ollama_service::{ensure_ollama_serving, OllamaClient};
            let host = if spec.host.is_empty() {
                crate::config::defaults::OLLAMA_DEFAULT_HOST.to_string()
            } else {
                spec.host.clone()
            };
            // Start the server automatically when it is down (binary present,
            // local host) so a Download click just works.
            ensure_ollama_serving(&host, on_status)?;
            require(
                OllamaClient::new().pull_model(&spec.model, &host, on_status)?,
                &format!(
                    "Ollama pull {} did not confirm success — check `ollama serve` output and retry",
                    spec.model
                ),
            )
        }
        kind => Err(anyhow::anyhow!("unknown install kind: {kind:?}")),
    }
}

/// Entry for `gravaai --install <spec-json>`. Internal daemon
/// plumbing only: refuses to run unless spawned by the daemon (see
/// `core::run_mode::CHILD_ENV`). Returns exit code.
pub fn run_install_child(spec_json: &str) -> i32 {
    crate::utils::logging::setup_logging("install");
    if !crate::core::run_mode::child_allowed() {
        eprintln!("--install is internal daemon plumbing, not a user command.");
        eprintln!("Run the app normally (no flags) to open the graphical UI.");
        eprintln!("To remove the app, run: gravaai --uninstall");
        return 2;
    }
    let spec = match crate::core::install_spec::spec_from_json(spec_json) {
        Ok(s) => s,
        Err(_) => {
            emit(
                CHILD_ERROR_PREFIX,
                "installer: missing or invalid spec argument",
            );
            return 2;
        }
    };
    match run_install(&spec, &|msg| emit(CHILD_STATUS_PREFIX, msg)) {
        Ok(_) => {
            emit(CHILD_RESULT_PREFIX, "ok");
            0
        }
        Err(e) => {
            log::error!("Install job failed: {e:#}");
            emit(CHILD_ERROR_PREFIX, &format!("{e:#}"));
            1
        }
    }
}

// ---------------------------------------------------------------------------
// Daemon side
// ---------------------------------------------------------------------------

/// Spawns `--install` children and forwards [`InstallEvent`]s (tagged by
/// install key) to the event loop.
pub struct InstallLauncher {
    tx: mpsc::UnboundedSender<(String, InstallEvent)>,
    children: Arc<Mutex<std::collections::HashMap<String, u32>>>,
}

impl InstallLauncher {
    pub fn new(tx: mpsc::UnboundedSender<(String, InstallEvent)>) -> Self {
        Self {
            tx,
            children: Arc::new(Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Request all in-flight install children to exit during daemon shutdown.
    /// Worker threads still own and reap their `Child` values; normal Quit
    /// never escalates this to a force-kill.
    pub fn shutdown(&self) {
        let pids: Vec<u32> = self.children.lock().unwrap().values().copied().collect();
        for pid in pids {
            request_pid_term(pid);
        }
    }

    pub fn launch(&self, key: String, spec_json: &str) {
        // Share the daemon's AppImage mount — do not re-exec $APPIMAGE.
        let exe = crate::utils::exe::internal_exe();
        let spec_json = spec_json.to_string();
        let tx = self.tx.clone();
        let children = self.children.clone();
        std::thread::Builder::new()
            .name(format!("install-{key}"))
            .spawn(move || {
                let mut child = match Command::new(&exe)
                    .args(["--install", &spec_json])
                    .env(crate::core::run_mode::CHILD_ENV, "1")
                    // Installers inherit fd 2, so tool errors land
                    // on the child's stderr — pipe it for the failure message.
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .spawn()
                {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = tx.send((
                            key,
                            InstallEvent::Finished(
                                false,
                                format!("failed to spawn installer: {e}"),
                            ),
                        ));
                        return;
                    }
                };
                children.lock().unwrap().insert(key.clone(), child.id());
                let stdout = child.stdout.take();
                let stderr_tail = StderrTail::new();
                let tail_clone = stderr_tail.clone();
                if let Some(err) = child.stderr.take() {
                    std::thread::spawn(move || {
                        for line in std::io::BufReader::new(err).lines().map_while(Result::ok) {
                            tail_clone.push(line);
                        }
                    });
                }
                let mut ok = false;
                let mut message = String::new();
                if let Some(out) = stdout {
                    for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
                        if let Some(text) = line.strip_prefix(CHILD_STATUS_PREFIX) {
                            let _ = tx.send((key.clone(), InstallEvent::Status(text.to_string())));
                        } else if line.strip_prefix(CHILD_RESULT_PREFIX).is_some() {
                            ok = true;
                        } else if let Some(text) = line.strip_prefix(CHILD_ERROR_PREFIX) {
                            ok = false;
                            message = text.to_string();
                        }
                    }
                }
                let _ = child.wait();
                children.lock().unwrap().remove(&key);
                if !ok && !stderr_tail.lines().is_empty() {
                    log::warn!("Install stderr: {}", stderr_tail.lines().join(" | "));
                    let tail = stderr_tail.tail();
                    message = if message.is_empty() {
                        tail
                    } else {
                        format!("{message} — {tail}")
                    };
                }
                let _ = tx.send((key, InstallEvent::Finished(ok, message)));
            })
            .ok();
    }
}

impl Drop for InstallLauncher {
    fn drop(&mut self) {
        self.shutdown();
    }
}

#[cfg(unix)]
fn request_pid_term(pid: u32) {
    // SAFETY: the pid was captured from a Child spawned by this launcher.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_pid_term(_pid: u32) {}
