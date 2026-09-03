//! Process-role dispatch.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunMode {
    Daemon,
    Window,
    Process,
    Install,
    Client,
}

pub const DAEMON_FLAG: &str = "--daemon";
pub const WINDOW_FLAG: &str = "--window";
pub const PROCESS_FLAG: &str = "--process";
pub const INSTALL_FLAG: &str = "--install";

/// `--daemon` wins if several flags are somehow present (defensive — a daemon
/// must never also try to be a window).
pub fn resolve_run_mode(argv: &[String]) -> RunMode {
    let args: Vec<&str> = argv.iter().skip(1).map(|s| s.as_str()).collect();
    if args.contains(&DAEMON_FLAG) {
        return RunMode::Daemon;
    }
    if args.contains(&WINDOW_FLAG) {
        return RunMode::Window;
    }
    if args.contains(&PROCESS_FLAG) {
        return RunMode::Process;
    }
    if args.contains(&INSTALL_FLAG) {
        return RunMode::Install;
    }
    RunMode::Client
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(flags: &[&str]) -> Vec<String> {
        std::iter::once("meeting-recorder".to_string())
            .chain(flags.iter().map(|s| s.to_string()))
            .collect()
    }

    #[test]
    fn dispatch() {
        assert_eq!(resolve_run_mode(&argv(&[])), RunMode::Client);
        assert_eq!(resolve_run_mode(&argv(&["--daemon"])), RunMode::Daemon);
        assert_eq!(resolve_run_mode(&argv(&["--window"])), RunMode::Window);
        assert_eq!(resolve_run_mode(&argv(&["--process"])), RunMode::Process);
        assert_eq!(resolve_run_mode(&argv(&["--install"])), RunMode::Install);
    }

    #[test]
    fn daemon_wins() {
        assert_eq!(
            resolve_run_mode(&argv(&["--window", "--daemon"])),
            RunMode::Daemon
        );
    }
}
