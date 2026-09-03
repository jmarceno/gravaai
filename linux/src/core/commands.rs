//! Shared command vocabulary.

/// Recording lifecycle commands (no arguments).
pub const RECORD_HEADPHONES: &str = "record_headphones";
pub const RECORD_SPEAKER: &str = "record_speaker";
pub const PAUSE: &str = "pause";
pub const RESUME: &str = "resume";
pub const STOP: &str = "stop";
pub const CANCEL_SAVE: &str = "cancel_save";
pub const CANCEL: &str = "cancel";
pub const CANCEL_COUNTDOWN: &str = "cancel_countdown";

/// UI-mediated commands the tray forwards to the window process.
pub const USE_EXISTING: &str = "use_existing";
pub const SHOW_WINDOW: &str = "show";
pub const QUIT: &str = "quit";
