//! File logging setup.

use std::path::PathBuf;

use crate::config::defaults::APP_DIR_NAME;

fn system_log_dir() -> PathBuf {
    PathBuf::from(format!("/var/log/{APP_DIR_NAME}"))
}

fn fallback_log_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("~"))
        .join(format!(".local/share/{APP_DIR_NAME}"))
}

/// Persistent stderr capture for the short-lived Qt companion. A bounded
/// pair of files prevents a broken QML binding from filling user storage.
pub fn window_stderr_path() -> PathBuf {
    fallback_log_dir().join("window-qt.log")
}

pub fn open_window_stderr() -> std::io::Result<std::fs::File> {
    let path = window_stderr_path();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if let Ok(meta) = std::fs::metadata(&path) {
        if meta.len() > 1024 * 1024 {
            let backup = path.with_extension("log.1");
            let _ = std::fs::rename(&path, backup);
        }
    }
    std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
}

fn writable_dir(p: &PathBuf) -> bool {
    std::fs::create_dir_all(p).is_ok()
        && std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(p.join(".probe"))
            .map(|_| {
                let _ = std::fs::remove_file(p.join(".probe"));
            })
            .is_ok()
}

/// Initialize logging for `role` (daemon/window/process/install/client).
/// Writes DEBUG+ to app.log and WARNING+ to error.log under the system log
/// dir, falling back to ~/.local/share when /var/log is not writable.
pub fn setup_logging(role: &str) {
    let dir = if writable_dir(&system_log_dir()) {
        system_log_dir()
    } else {
        fallback_log_dir()
    };
    let _ = std::fs::create_dir_all(&dir);
    let app_log = dir.join("app.log");
    let err_log = dir.join("error.log");

    let app_file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&app_log)
        .ok();
    let err_file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&err_log)
        .ok();

    // Two-file split: everything to app.log, warnings+ to error.log.
    // Mirror to stderr only when attached to a TTY (checked once here).
    let stderr_is_tty = std::fs::read_link("/proc/self/fd/2")
        .map(|t| {
            let s = t.to_string_lossy().into_owned();
            s.starts_with("/dev/pts/") || s == "/dev/tty" || s.starts_with("/dev/tty")
        })
        .unwrap_or(false);
    struct SplitLogger {
        role: String,
        stderr_is_tty: bool,
        app: Option<std::sync::Mutex<std::fs::File>>,
        err: Option<std::sync::Mutex<std::fs::File>>,
    }
    impl log::Log for SplitLogger {
        fn enabled(&self, m: &log::Metadata) -> bool {
            m.level() <= log::Level::Debug
        }
        fn log(&self, record: &log::Record) {
            if !self.enabled(record.metadata()) {
                return;
            }
            let line = format!(
                "{} [{}] {} — {}",
                chrono::Local::now().format("%Y-%m-%d %H:%M:%S"),
                self.role,
                record.level(),
                record.args()
            );
            if let Some(f) = &self.app {
                use std::io::Write as _;
                let _ = writeln!(f.lock().unwrap(), "{line}");
            }
            if record.level() >= log::Level::Warn {
                if let Some(f) = &self.err {
                    use std::io::Write as _;
                    let _ = writeln!(f.lock().unwrap(), "{line}");
                }
            }
            if self.stderr_is_tty {
                eprintln!("{line}");
            }
        }
        fn flush(&self) {}
    }

    let logger = SplitLogger {
        role: role.to_string(),
        stderr_is_tty,
        app: app_file.map(std::sync::Mutex::new),
        err: err_file.map(std::sync::Mutex::new),
    };
    let _ = log::set_boxed_logger(Box::new(logger));
    log::set_max_level(log::LevelFilter::Debug);
}
