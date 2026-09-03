//! One-shot model/engine install child + daemon-side launcher.
//!
//! Each install runs in a short-lived `--install` child the daemon spawns and
//! tracks, so installs survive the Settings window closing and never bloat the
//! daemon. Protocol mirrors the processor: `STATUS:<text>` progress,
//! `RESULT:ok` success, `ERROR:<text>` failure.

use std::io::BufRead;
use std::path::PathBuf;
use std::process::{Command, Stdio};
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
        install_spec::KIND_OLLAMA => require(OllamaInstaller::install(on_status), "Ollama install"),
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
            use crate::services::ollama_service::OllamaClient;
            let host = if spec.host.is_empty() {
                crate::config::defaults::OLLAMA_DEFAULT_HOST.to_string()
            } else {
                spec.host.clone()
            };
            require(
                OllamaClient::new().pull_model(&spec.model, &host, on_status)?,
                &format!("Ollama pull {}", spec.model),
            )
        }
        kind => Err(anyhow::anyhow!("unknown install kind: {kind:?}")),
    }
}

/// Entry for `meeting-recorder --install <spec-json>`. Returns exit code.
pub fn run_install_child(spec_json: &str) -> i32 {
    crate::utils::logging::setup_logging("install");
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
}

impl InstallLauncher {
    pub fn new(tx: mpsc::UnboundedSender<(String, InstallEvent)>) -> Self {
        Self { tx }
    }

    pub fn launch(&self, key: String, spec_json: &str) {
        let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("meeting-recorder"));
        let spec_json = spec_json.to_string();
        let tx = self.tx.clone();
        std::thread::Builder::new()
            .name(format!("install-{key}"))
            .spawn(move || {
                let mut child = match Command::new(&exe)
                    .args(["--install", &spec_json])
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
