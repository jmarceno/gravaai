//! UI-facing helpers shared by the daemon and the Qt companion.
//!
//! The tray model, tray implementation, notifications and D-Bus proxy are
//! toolkit-free and are compiled into the daemon. The Qt window is isolated
//! in `qt` and only linked by the `gravaai-ui` binary.

pub mod engine_proxy;
pub mod notifications;
pub mod settings_visibility;
pub mod tray;
pub mod tray_icon;
pub mod tray_model;

#[cfg(feature = "ui")]
pub mod qt;
