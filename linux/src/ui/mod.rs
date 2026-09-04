//! GTK window (feature `ui`) plus the GTK-free UI helpers the daemon uses
//! in every build (tray model, notifications, engine proxy).

pub mod engine_proxy;
pub mod notifications;
pub mod settings_visibility;
pub mod tray;
pub mod tray_icon;
pub mod tray_model;

#[cfg(feature = "ui")]
pub mod jobs_panel;
#[cfg(feature = "ui")]
pub mod main_window;
#[cfg(feature = "ui")]
pub mod meeting_explorer;
#[cfg(feature = "ui")]
pub mod model_row_grid;
#[cfg(feature = "ui")]
pub mod settings_dialog;
#[cfg(feature = "ui")]
pub mod settings_pages;
#[cfg(feature = "ui")]
pub mod window_app;
