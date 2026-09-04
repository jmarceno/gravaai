//! Secret Service keyring storage for the API key.
//!
//! When no Secret Service is reachable
//! everything falls back to chmod-600 `config.json`, exactly like before.

use crate::config::defaults::APP_DIR_NAME;

const ACCOUNT: &str = "openai-api-key";

pub struct KeyringStore {
    entry: Option<keyring::Entry>,
}

impl KeyringStore {
    pub fn new() -> Self {
        let entry = keyring::Entry::new(APP_DIR_NAME, ACCOUNT).ok();
        Self { entry }
    }

    /// True when a Secret Service collection can be reached (never auto-unlocks).
    ///
    /// `NoEntry` means the service answered but holds no secret yet — that
    /// still counts as available. `NoStorageAccess` / platform failures mean
    /// there is no usable Secret Service (or it is locked), in which case the
    /// app falls back to chmod-600 `config.json`.
    pub fn available(&self) -> bool {
        match &self.entry {
            Some(e) => match e.get_password() {
                Ok(_) => true,
                Err(keyring::Error::NoEntry) => true,
                Err(_) => false,
            },
            None => false,
        }
    }

    pub fn get(&self) -> Option<String> {
        self.entry.as_ref().and_then(|e| e.get_password().ok())
    }

    pub fn set(&self, secret: &str) -> bool {
        match &self.entry {
            Some(e) => e.set_password(secret).is_ok(),
            None => false,
        }
    }

    pub fn delete(&self) {
        if let Some(e) = &self.entry {
            let _ = e.delete_credential();
        }
    }
}

impl Default for KeyringStore {
    fn default() -> Self {
        Self::new()
    }
}
