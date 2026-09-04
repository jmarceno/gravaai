//! HTTP client for the Ollama local API.

use std::io::BufRead;
use std::time::Duration;

use crate::config::defaults::OLLAMA_DEFAULT_HOST;

/// Socket-read timeout for streaming responses. Applies per read, not to the
/// whole download: a healthy pull keeps data flowing, while a stalled server
/// errors instead of hanging the worker thread forever.
pub const STREAM_READ_TIMEOUT_SECS: u64 = 300;

pub struct OllamaClient {
    inner: reqwest::blocking::Client,
}

impl OllamaClient {
    pub fn new() -> Self {
        Self {
            inner: reqwest::blocking::Client::builder()
                .timeout(Duration::from_secs(STREAM_READ_TIMEOUT_SECS))
                .build()
                .unwrap_or_else(|_| reqwest::blocking::Client::new()),
        }
    }

    fn base(host: &str) -> String {
        if host.trim().is_empty() {
            OLLAMA_DEFAULT_HOST.to_string()
        } else {
            host.trim_end_matches('/').to_string()
        }
    }

    /// Installed model names, or None if Ollama is unreachable.
    pub fn get_installed_models(&self, host: &str) -> Option<Vec<String>> {
        Some(
            self.get_installed_models_with_sizes(host)?
                .into_iter()
                .map(|(name, _)| name)
                .collect(),
        )
    }

    /// Installed models with their on-disk sizes, or None when the server is
    /// unreachable. Sizes come straight from `/api/tags`.
    pub fn get_installed_models_with_sizes(&self, host: &str) -> Option<Vec<(String, u64)>> {
        let data: serde_json::Value = self
            .inner
            .get(format!("{}/api/tags", Self::base(host)))
            .timeout(Duration::from_secs(3))
            .send()
            .ok()?
            .json()
            .ok()?;
        Some(parse_ollama_tags(&data))
    }

    pub fn is_model_installed(&self, model: &str, installed: &[String]) -> bool {
        installed
            .iter()
            .any(|n| n == model || n.starts_with(&format!("{model}:")))
    }

    /// Stream-pull `model` from Ollama, reporting human-readable progress.
    /// Returns true when the server confirms success. Raises on network error
    /// or when the server reports an error mid-stream.
    pub fn pull_model(
        &self,
        model: &str,
        host: &str,
        on_progress: &dyn Fn(&str),
    ) -> anyhow::Result<bool> {
        // Fail fast with an actionable message when the server is not running
        // instead of surfacing a raw connection-refused error after the pull.
        if self.get_installed_models(host).is_none() {
            anyhow::bail!(
                "Cannot reach Ollama at {host}. Install Ollama and start it with `ollama serve`, then retry."
            );
        }
        let body = serde_json::json!({"name": model, "stream": true});
        let resp = self
            .inner
            .post(format!("{}/api/pull", Self::base(host)))
            .json(&body)
            .send()
            .map_err(|e| {
                anyhow::anyhow!(
                    "Ollama pull failed for {model:?} at {host}: {e:#}. Is Ollama still running (`ollama serve`)?"
                )
            })?;
        let reader = std::io::BufReader::new(resp);
        for line in reader.lines().map_while(Result::ok) {
            if line.trim().is_empty() {
                continue;
            }
            let data: serde_json::Value = match serde_json::from_str(&line) {
                Ok(d) => d,
                Err(_) => continue,
            };
            if !data.is_object() {
                continue;
            }
            if let Some(err) = data.get("error").and_then(|e| e.as_str()) {
                if !err.is_empty() {
                    anyhow::bail!("Ollama failed to pull {model:?}: {err}");
                }
            }
            let mut status_text = data
                .get("status")
                .and_then(|s| s.as_str())
                .unwrap_or("")
                .to_string();
            let total = data.get("total").and_then(|v| v.as_u64()).unwrap_or(0);
            let completed = data.get("completed").and_then(|v| v.as_u64()).unwrap_or(0);
            if total > 0 && completed > 0 {
                status_text = format!("{status_text} {}%", completed * 100 / total);
            }
            on_progress(&status_text);
            if data.get("status").and_then(|s| s.as_str()) == Some("success") {
                return Ok(true);
            }
        }
        // Stream ended without explicit "success" — do one final check.
        Ok(self
            .get_installed_models(host)
            .map(|installed| self.is_model_installed(model, &installed))
            .unwrap_or(false))
    }
}

impl Default for OllamaClient {
    fn default() -> Self {
        Self::new()
    }
}

/// Pure parser for an `/api/tags` payload: `(name, size_bytes)` pairs.
/// Tolerates missing sizes (0) and missing models (empty list).
pub fn parse_ollama_tags(data: &serde_json::Value) -> Vec<(String, u64)> {
    data.get("models")
        .and_then(|m| m.as_array())
        .unwrap_or(&Vec::new())
        .iter()
        .filter_map(|m| {
            let name = m.get("name").and_then(|n| n.as_str())?;
            let size = m.get("size").and_then(|s| s.as_u64()).unwrap_or(0);
            Some((name.to_string(), size))
        })
        .collect()
}

/// True when `host` points at this machine (empty = the default localhost).
/// Only local servers are ever auto-started — a remote hostname is left alone.
pub fn is_local_host(host: &str) -> bool {
    let h = host.trim();
    if h.is_empty() {
        return true;
    }
    let after_scheme = h.rsplit("://").next().unwrap_or(h);
    let authority = after_scheme.split('/').next().unwrap_or("");
    let authority = authority.split('@').next_back().unwrap_or(authority);
    let name = if let Some(rest) = authority.strip_prefix('[') {
        rest.split(']').next().unwrap_or("")
    } else {
        authority.split(':').next().unwrap_or(authority)
    };
    matches!(
        name.to_lowercase().as_str(),
        "localhost" | "127.0.0.1" | "::1" | "0.0.0.0"
    )
}

/// How long to wait for a freshly started server (1s polls).
pub const OLLAMA_START_TIMEOUT_SECS: u64 = 45;

/// Ensure an Ollama server answers at `host`, starting `ollama serve`
/// automatically when the binary is present and the host is local.
/// A started server is left running afterwards for future use.
pub fn ensure_ollama_serving(host: &str, on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
    let client = OllamaClient::new();
    ensure_ollama_serving_with(
        host,
        on_status,
        &|| client.get_installed_models(host).is_some(),
        crate::services::system_installer::OllamaInstaller::is_available(),
        &spawn_ollama_serve,
        OLLAMA_START_TIMEOUT_SECS,
        &|| std::thread::sleep(std::time::Duration::from_secs(1)),
    )
}

fn spawn_ollama_serve() -> anyhow::Result<()> {
    use std::process::{Command, Stdio};
    let bundled = crate::services::system_installer::OllamaInstaller::binary_path();
    let program = bundled
        .is_file()
        .then_some(bundled)
        .or_else(|| {
            crate::services::system_installer::which("ollama").map(std::path::PathBuf::from)
        })
        .ok_or_else(|| anyhow::anyhow!("Ollama is not installed"))?;
    let child = Command::new(&program)
        .arg("serve")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| anyhow::anyhow!("Failed to start `ollama serve`: {e:#}"))?;
    let pid = child.id();
    log::info!(
        "Started `{} serve` automatically (pid {pid}); it will be stopped on app exit",
        program.display()
    );
    // Intentionally not waited on: the starter is a short-lived child while
    // the server keeps running (reparented) for future pulls and jobs.
    // Ownership is recorded so daemon shutdown stops exactly this server —
    // a server that was already running is never touched (see below).
    std::mem::forget(child);
    record_spawned_server(&ollama_state_path(), pid);
    Ok(())
}

/// State file recording a server this app started (for stop-on-exit).
/// A server that was already running gets no record and is left alone.
pub fn ollama_state_path() -> std::path::PathBuf {
    crate::core::job_manager::default_state_dir().join("ollama-server.json")
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
struct OwnedServerRecord {
    pid: u32,
    started_at: String,
}

/// Best-effort ownership record. Failures only log — a missing record merely
/// means the server won't be stopped on exit (the safe direction is to never
/// stop what isn't recorded).
fn record_spawned_server(path: &std::path::Path, pid: u32) {
    if let Some(dir) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(dir) {
            log::warn!("Could not record auto-started Ollama server: {e:#}");
            return;
        }
    }
    let record = OwnedServerRecord {
        pid,
        started_at: chrono::Local::now().to_rfc3339(),
    };
    match serde_json::to_string(&record) {
        Ok(text) => {
            if let Err(e) = std::fs::write(path, text) {
                log::warn!("Could not record auto-started Ollama server: {e:#}");
            }
        }
        Err(e) => log::warn!("Could not record auto-started Ollama server: {e:#}"),
    }
}

/// Outcome of stopping an app-started server (see [`shutdown_owned_server`]).
#[derive(Debug, PartialEq)]
pub enum OwnedServerStop {
    /// No record — either nothing was started or the server was already
    /// running (that path records nothing and is never interfered with).
    NothingToDo,
    /// The recorded server was verified (via /proc cmdline) and stopped.
    Stopped,
    /// The recorded pid is already gone; the stale record was removed.
    AlreadyGone,
    /// The recorded pid is alive but is no longer our `ollama serve`
    /// (pid reuse or manual replacement) — left alone, record removed.
    NotOurs,
}

/// Stop the auto-started Ollama server, if any. Only ever stops the exact
/// process this app recorded: a pre-existing server has no record
/// ([`NothingToDo`][OwnedServerStop::NothingToDo]), and a live pid whose
/// `/proc` cmdline is not our `ollama serve` is left running.
pub fn shutdown_owned_server() -> OwnedServerStop {
    shutdown_owned_server_at(&ollama_state_path())
}

pub fn shutdown_owned_server_at(path: &std::path::Path) -> OwnedServerStop {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(_) => return OwnedServerStop::NothingToDo,
    };
    let record: OwnedServerRecord = match serde_json::from_str(&text) {
        Ok(r) => r,
        Err(_) => {
            let _ = std::fs::remove_file(path);
            return OwnedServerStop::NothingToDo;
        }
    };
    let pid = record.pid;
    let argv = match proc_argv_for_validation(pid) {
        // Nobody there (or unreadable): stale record, nothing to stop.
        None => {
            let _ = std::fs::remove_file(path);
            return OwnedServerStop::AlreadyGone;
        }
        // A process that has just become a zombie can still have a /proc
        // directory while its cmdline is already empty. Treat that exactly
        // like an exited process; it is not evidence that the pid was reused.
        Some(argv) if argv.is_empty() => {
            let _ = std::fs::remove_file(path);
            return if proc_state(pid) == Some('Z') || proc_argv(pid).is_none() {
                OwnedServerStop::AlreadyGone
            } else {
                // An empty cmdline that is neither a zombie nor gone is not
                // safe to identify as our server (it can be a pre-exec window
                // or an unusual process); leave it alone.
                OwnedServerStop::NotOurs
            };
        }
        Some(argv) => argv,
    };
    let cmdline = argv.join(" ");
    if !is_ollama_serve(&argv) {
        log::warn!("Recorded Ollama server pid {pid} is now `{cmdline}` — leaving it alone");
        let _ = std::fs::remove_file(path);
        return OwnedServerStop::NotOurs;
    }
    let gone = stop_pid(pid);
    let _ = std::fs::remove_file(path);
    if gone {
        log::info!("Stopped auto-started Ollama server (pid {pid})");
        OwnedServerStop::Stopped
    } else {
        log::error!("Could not stop auto-started Ollama server (pid {pid}) — leaving it running");
        OwnedServerStop::NotOurs
    }
}

/// Raw `/proc/<pid>/cmdline` with NULs as spaces, or None when the process
/// is gone/unreadable.
fn proc_cmdline(pid: u32) -> Option<String> {
    proc_argv(pid).map(|argv| argv.join(" "))
}

fn proc_argv(pid: u32) -> Option<Vec<String>> {
    std::fs::read(format!("/proc/{pid}/cmdline"))
        .ok()
        .map(|bytes| {
            bytes
                .split(|byte| *byte == 0)
                .filter(|arg| !arg.is_empty())
                .map(|arg| String::from_utf8_lossy(arg).into_owned())
                .collect()
        })
}

/// Read a process command line after exec has populated `/proc`. `spawn()` can
/// return during the tiny fork/exec window where cmdline is empty; treating
/// that as a foreign process would make the ownership check flaky. A genuine
/// zombie also has an empty cmdline, but is classified as gone immediately.
fn proc_argv_for_validation(pid: u32) -> Option<Vec<String>> {
    for _ in 0..20 {
        let argv = proc_argv(pid)?;
        if !argv.is_empty() || proc_state(pid) == Some('Z') {
            return Some(argv);
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    proc_argv(pid)
}

/// Return the Linux `/proc` process state (`R`, `S`, `Z`, …), if readable.
fn proc_state(pid: u32) -> Option<char> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    text.lines()
        .find_map(|line| line.strip_prefix("State:")?.trim().chars().next())
}

/// Validate the recorded process by executable and argument boundaries.
///
/// A substring search over the whole command line is unsafe: a test filter,
/// unrelated argument, or another process name can contain both words and
/// make us signal the wrong PID.  The small `ollama serve` invocation used by
/// the installer has either `/path/ollama serve` or (for wrappers/tests) the
/// equivalent `ollama serve` argv[0] spelling.
fn is_ollama_serve(argv: &[String]) -> bool {
    let Some(program) = argv.first() else {
        return false;
    };
    let program_name = std::path::Path::new(program)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(program);
    let executable_matches = program_name == "ollama" || program == "ollama serve";
    let serve_arg = argv.iter().skip(1).any(|arg| arg == "serve") || program == "ollama serve";
    executable_matches && serve_arg
}

fn pid_gone(pid: u32) -> bool {
    match proc_cmdline(pid) {
        // Unreadable: reaped or inaccessible.
        None => true,
        // Empty cmdline: zombie (or kernel thread) — no command left running.
        // Note the ownership check above runs first and requires the
        // `ollama serve` markers, so this only treats a *confirmed-ours*
        // pid as gone once it starts dying.
        Some(cmd) => cmd.trim().is_empty(),
    }
}

/// Request TERM and wait for the validated process to disappear.  Normal
/// daemon shutdown never escalates to SIGKILL; a process that ignores TERM is
/// left running and reported to the caller.
fn stop_pid(pid: u32) -> bool {
    request_term(pid);
    for _ in 0..30 {
        if pid_gone(pid) {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    pid_gone(pid)
}

#[cfg(unix)]
fn request_term(pid: u32) {
    // SAFETY: the caller verified /proc/<pid>/cmdline before signalling.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_term(_pid: u32) {}

fn ensure_ollama_serving_with(
    host: &str,
    on_status: &dyn Fn(&str),
    is_serving: &dyn Fn() -> bool,
    have_binary: bool,
    spawn: &dyn Fn() -> anyhow::Result<()>,
    polls: u64,
    wait: &dyn Fn(),
) -> anyhow::Result<()> {
    if is_serving() {
        // Already active: follow the original path — use it as-is, record
        // nothing, and never interfere with it on exit.
        log::info!("Ollama already serving at {host} — using it as-is");
        return Ok(());
    }
    if !is_local_host(host) {
        anyhow::bail!(
            "Cannot reach Ollama at {host}. Start it there first, or point the Ollama host at this machine."
        );
    }
    if !have_binary {
        anyhow::bail!("Ollama is not installed. Install it from Settings → Models first.");
    }
    on_status("Starting Ollama server…");
    log::info!("Ollama not serving at {host} — starting `ollama serve` automatically");
    if let Err(e) = spawn() {
        // Likely collision: another server just took the port — re-check.
        wait();
        if is_serving() {
            return Ok(());
        }
        return Err(anyhow::anyhow!("Could not start `ollama serve`: {e:#}"));
    }
    for _ in 0..polls.max(1) {
        wait();
        if is_serving() {
            log::info!("Auto-started Ollama server is responding at {host}");
            return Ok(());
        }
    }
    anyhow::bail!(
        "Started `ollama serve` but it is not responding at {host}. Check `ollama serve` output for errors (e.g. port already in use)."
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tags_extraction() {
        let data = serde_json::json!({
            "models": [
                {"name": "phi4-mini:latest", "size": 2_500_000_000u64},
                {"name": "gemma3:4b"},
                {"not-a-model": true}
            ]
        });
        let parsed = parse_ollama_tags(&data);
        assert_eq!(
            parsed,
            vec![
                ("phi4-mini:latest".to_string(), 2_500_000_000u64),
                ("gemma3:4b".to_string(), 0)
            ]
        );
        assert!(parse_ollama_tags(&serde_json::json!({})).is_empty());
        assert!(parse_ollama_tags(&serde_json::json!(null)).is_empty());
    }

    #[test]
    fn prefix_match() {
        let c = OllamaClient::new();
        let installed = vec!["phi4-mini:latest".to_string(), "qwen2.5:7b".to_string()];
        assert!(c.is_model_installed("phi4-mini", &installed));
        assert!(c.is_model_installed("qwen2.5:7b", &installed));
        assert!(!c.is_model_installed("llama3.1:8b", &installed));
    }

    #[test]
    fn unreachable_returns_none() {
        let c = OllamaClient::new();
        assert!(c.get_installed_models("http://127.0.0.1:1").is_none());
    }

    #[test]
    fn pull_prefails_fast_with_actionable_error() {
        let c = OllamaClient::new();
        let err = c
            .pull_model("phi4-mini", "http://127.0.0.1:1", &|_| {})
            .unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("ollama serve"), "unexpected message: {msg}");
    }

    #[test]
    fn local_host_matching() {
        assert!(is_local_host(""));
        assert!(is_local_host("  "));
        assert!(is_local_host("http://localhost:11434"));
        assert!(is_local_host("localhost"));
        assert!(is_local_host("http://127.0.0.1:11434"));
        assert!(is_local_host("http://[::1]:11434"));
        assert!(is_local_host("http://0.0.0.0:11434"));
        assert!(is_local_host("https://LOCALHOST:11434/api"));
        assert!(!is_local_host("http://192.168.0.59:11434"));
        assert!(!is_local_host("http://myserver:11434"));
        assert!(!is_local_host("http://localhost.example.com:11434"));
        assert!(!is_local_host("not a url at all"));
    }

    #[test]
    fn ensure_noops_when_serving() {
        let spawns = std::cell::Cell::new(0);
        let r = ensure_ollama_serving_with(
            "http://localhost:11434",
            &|_| {},
            &|| true,
            true,
            &|| {
                spawns.set(spawns.get() + 1);
                Ok::<(), anyhow::Error>(())
            },
            3,
            &|| {},
        );
        assert!(r.is_ok());
        assert_eq!(spawns.get(), 0);
    }

    #[test]
    fn ensure_spawns_and_waits_for_readiness() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        let calls = Arc::new(AtomicUsize::new(0));
        let polls = Arc::new(AtomicUsize::new(0));
        let spawns = std::cell::Cell::new(0);
        let r = ensure_ollama_serving_with(
            "http://localhost:11434",
            &|_| {},
            &{
                let calls = calls.clone();
                move || calls.fetch_add(1, Ordering::SeqCst) >= 2
            },
            true,
            &|| {
                spawns.set(spawns.get() + 1);
                Ok::<(), anyhow::Error>(())
            },
            5,
            &{
                let polls = polls.clone();
                move || {
                    polls.fetch_add(1, Ordering::SeqCst);
                }
            },
        );
        assert!(r.is_ok());
        assert_eq!(spawns.get(), 1);
        // One readiness check runs before spawning, so two polls follow.
        assert_eq!(polls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn ensure_refuses_remote_and_missing_binary() {
        // Remote host: never spawn, guidance error instead.
        let spawns = std::cell::Cell::new(0);
        let err = ensure_ollama_serving_with(
            "http://192.168.0.59:11434",
            &|_| {},
            &|| false,
            true,
            &|| {
                spawns.set(spawns.get() + 1);
                Ok::<(), anyhow::Error>(())
            },
            3,
            &|| {},
        )
        .unwrap_err();
        assert_eq!(spawns.get(), 0);
        assert!(format!("{err:#}").contains("Start it there first"));
        // No binary: never spawn, install guidance instead.
        let err = ensure_ollama_serving_with(
            "http://localhost:11434",
            &|_| {},
            &|| false,
            false,
            &|| {
                spawns.set(spawns.get() + 1);
                Ok::<(), anyhow::Error>(())
            },
            3,
            &|| {},
        )
        .unwrap_err();
        assert_eq!(spawns.get(), 0);
        assert!(format!("{err:#}").contains("not installed"));
    }

    #[test]
    fn ensure_times_out_with_actionable_error() {
        let spawns = std::cell::Cell::new(0);
        let err = ensure_ollama_serving_with(
            "http://localhost:11434",
            &|_| {},
            &|| false,
            true,
            &|| {
                spawns.set(spawns.get() + 1);
                Ok::<(), anyhow::Error>(())
            },
            3,
            &|| {},
        )
        .unwrap_err();
        assert_eq!(spawns.get(), 1);
        assert!(format!("{err:#}").contains("not responding"));
    }

    #[test]
    fn owned_server_lifecycle() {
        use std::os::unix::process::CommandExt as _;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ollama-server.json");

        // No record → nothing to do.
        assert_eq!(
            shutdown_owned_server_at(&path),
            OwnedServerStop::NothingToDo
        );
        // Corrupt record → cleared, nothing to do.
        std::fs::write(&path, "garbage").unwrap();
        assert_eq!(
            shutdown_owned_server_at(&path),
            OwnedServerStop::NothingToDo
        );
        assert!(!path.exists());

        // Dead pid → stale record cleared, nothing killed.
        let mut dead = std::process::Command::new("true").spawn().unwrap();
        let dead_pid = dead.id();
        dead.wait().unwrap();
        record_spawned_server(&path, dead_pid);
        assert_eq!(
            shutdown_owned_server_at(&path),
            OwnedServerStop::AlreadyGone
        );
        assert!(!path.exists());

        // Foreign live pid (this test process) → left alone, record cleared.
        let me = std::process::id();
        record_spawned_server(&path, me);
        assert_eq!(shutdown_owned_server_at(&path), OwnedServerStop::NotOurs);
        assert!(!path.exists());
        // Still alive (this assertion running proves it).
        assert!(proc_cmdline(me).is_some());

        // Owned server (argv[0] faked to `ollama serve`) → stopped.
        let mut child = std::process::Command::new("sleep");
        child.arg0("ollama serve").arg("60");
        let mut child = match child.spawn() {
            Ok(c) => c,
            Err(_) => return, // no `sleep` binary — skip kill-path coverage
        };
        let pid = child.id();
        // Reap lazily: shutdown requests TERM; wait() here reaps the child.
        record_spawned_server(&path, pid);
        assert_eq!(shutdown_owned_server_at(&path), OwnedServerStop::Stopped);
        assert!(!path.exists());
        assert!(pid_gone(pid), "owned server must be gone");
        let _ = child.wait();
    }

    #[test]
    fn ollama_pid_validation_uses_argument_boundaries() {
        assert!(is_ollama_serve(&["/usr/bin/ollama".into(), "serve".into()]));
        assert!(is_ollama_serve(&["ollama serve".into(), "60".into()]));
        assert!(!is_ollama_serve(&[
            "/usr/bin/gravaai-test".into(),
            "services::ollama_service::owned_server_lifecycle".into(),
        ]));
        assert!(!is_ollama_serve(&[
            "/usr/bin/ollama-helper".into(),
            "serve".into()
        ]));
        assert!(!is_ollama_serve(&[
            "/usr/bin/ollama".into(),
            "server".into()
        ]));
    }
}
