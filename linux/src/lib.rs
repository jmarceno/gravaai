//! Shared GravaAI core library.
//!
//! The `gravaai` daemon/process/install executable deliberately has no UI
//! dependencies. The optional `ui` feature is linked only by the companion
//! `gravaai-ui` executable.

pub mod audio;
pub mod client;
pub mod config;
pub mod core;
pub mod daemon;
pub mod detection;
pub mod processing;
pub mod services;
pub mod ui;
pub mod utils;
