//! Pure tray icon/menu policy.
//!
//! Toolkit-free and bus-free so it is unit-testable without a display.

use std::sync::atomic::{AtomicU64, Ordering};

use super::tray_icon::TrayAppearance;

/// Appearance for the current recording state + job activity.
///
/// Priority: recording > paused > jobs processing > idle.
pub fn appearance_for_state(recording_state: &str, jobs_processing: usize) -> TrayAppearance {
    if recording_state == "recording" {
        return TrayAppearance::Recording;
    }
    if recording_state == "paused" {
        return TrayAppearance::Paused;
    }
    if jobs_processing > 0 {
        return TrayAppearance::Processing;
    }
    TrayAppearance::Idle
}

#[derive(Debug, Clone, PartialEq)]
pub enum MenuKind {
    Action,
    Label,
    Separator,
}

#[derive(Debug, Clone)]
pub struct MenuItem {
    pub id: u64,
    pub kind: MenuKind,
    pub label: String,
    /// Engine command (`core::commands`) or `cancel_job:<job_id>`.
    pub command: String,
    pub enabled: bool,
}

static NEXT_MENU_ID: AtomicU64 = AtomicU64::new(1);

/// Stamp each item with a unique, monotonically increasing dbusmenu id.
///
/// Ids are **never reused**: hosts cache items by id and merge fresh
/// properties onto cached items on layout updates, so reusing an id whose
/// type flips (action ↔ separator) leaves stale props behind (a disabled
/// ghost row). Fresh ids force the host to drop vanished items cleanly.
pub fn assign_menu_ids(items: &mut [MenuItem]) {
    for item in items {
        item.id = NEXT_MENU_ID.fetch_add(1, Ordering::SeqCst);
    }
}

fn action(label: &str, command: &str) -> MenuItem {
    MenuItem {
        id: 0,
        kind: MenuKind::Action,
        label: label.to_string(),
        command: command.to_string(),
        enabled: true,
    }
}

/// Build the tray menu model.
pub fn build_menu_model(recording_state: &str, processing: &[(i64, String)]) -> Vec<MenuItem> {
    use crate::core::commands::*;
    let mut items = Vec::new();
    // Recording controls reflect the current recording state.
    match recording_state {
        "recording" => {
            items.push(action("Pause Recording", PAUSE));
            items.push(action("Stop Recording", STOP));
            items.push(action("Cancel (save recording)", CANCEL_SAVE));
            items.push(action("Cancel", CANCEL));
        }
        "paused" => {
            items.push(action("Resume Recording", RESUME));
            items.push(action("Stop Recording", STOP));
            items.push(action("Cancel (save recording)", CANCEL_SAVE));
            items.push(action("Cancel", CANCEL));
        }
        _ => {
            items.push(action("Record (Headphones)", RECORD_HEADPHONES));
            items.push(action("Record (Speaker)", RECORD_SPEAKER));
            items.push(action("Use Existing Recording", USE_EXISTING));
        }
    }
    // Background-jobs section (only when jobs are active).
    if !processing.is_empty() {
        items.push(MenuItem {
            id: 0,
            kind: MenuKind::Separator,
            label: String::new(),
            command: String::new(),
            enabled: false,
        });
        items.push(MenuItem {
            id: 0,
            kind: MenuKind::Label,
            label: format!("Processing ({} active)", processing.len()),
            command: String::new(),
            enabled: false,
        });
        for (job_id, label) in processing {
            items.push(MenuItem {
                id: 0,
                kind: MenuKind::Action,
                label: format!("  Cancel: {label}"),
                command: format!("cancel_job:{job_id}"),
                enabled: true,
            });
        }
    }
    // Footer.
    items.push(MenuItem {
        id: 0,
        kind: MenuKind::Separator,
        label: String::new(),
        command: String::new(),
        enabled: false,
    });
    items.push(action("Open", SHOW_WINDOW));
    items.push(action("Quit", QUIT));
    assign_menu_ids(&mut items);
    items
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appearance_priority() {
        assert_eq!(
            appearance_for_state("recording", 3),
            TrayAppearance::Recording
        );
        assert_eq!(appearance_for_state("paused", 3), TrayAppearance::Paused);
        assert_eq!(appearance_for_state("idle", 2), TrayAppearance::Processing);
        assert_eq!(appearance_for_state("idle", 0), TrayAppearance::Idle);
        assert_eq!(appearance_for_state("countdown", 0), TrayAppearance::Idle);
    }

    #[test]
    fn menu_per_state() {
        let idle = build_menu_model("idle", &[]);
        let labels: Vec<&str> = idle.iter().map(|i| i.label.as_str()).collect();
        assert!(labels.contains(&"Record (Headphones)"));
        assert!(labels.contains(&"Use Existing Recording"));
        assert!(labels.contains(&"Open"));
        assert!(labels.contains(&"Quit"));

        let rec = build_menu_model("recording", &[]);
        let labels: Vec<&str> = rec.iter().map(|i| i.label.as_str()).collect();
        assert!(labels.contains(&"Pause Recording"));
        assert!(labels.contains(&"Stop Recording"));

        let jobs = build_menu_model("idle", &[(7, "Standup".to_string())]);
        assert!(jobs.iter().any(|i| i.command == "cancel_job:7"));
        assert!(jobs.iter().any(|i| i.label.contains("1 active")));
    }

    #[test]
    fn ids_never_reused() {
        let a = build_menu_model("idle", &[]);
        let b = build_menu_model("recording", &[]);
        let max_a = a.iter().map(|i| i.id).max().unwrap();
        let min_b = b.iter().map(|i| i.id).min().unwrap();
        assert!(min_b > max_a);
        // No duplicates within a menu either.
        let mut ids: Vec<u64> = a.iter().map(|i| i.id).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), a.len());
    }
}
