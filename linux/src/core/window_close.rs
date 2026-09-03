//! Window close policy.

use crate::config::defaults::Config;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CloseAction {
    Hide,
    Exit,
}

/// By default the window hides on close (instant reopen); with Low memory mode
/// it exits so GTK memory is reclaimed and the daemon respawns it on demand.
pub fn resolve_close_action(cfg: &Config) -> CloseAction {
    if cfg.low_memory_mode {
        CloseAction::Exit
    } else {
        CloseAction::Hide
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_hides() {
        assert_eq!(resolve_close_action(&Config::default()), CloseAction::Hide);
    }

    #[test]
    fn low_memory_exits() {
        let c = Config {
            low_memory_mode: true,
            ..Config::default()
        };
        assert_eq!(resolve_close_action(&c), CloseAction::Exit);
    }
}
