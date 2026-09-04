//! Settings → General tab.

use adw::prelude::*;

use crate::config::defaults::Config;
use crate::utils::autostart::is_autostart_enabled;

use super::widgets::{make_scroll_page, IdComboRow};

pub struct GeneralPage {
    pub widget: gtk::ScrolledWindow,
    startup_switch: adw::SwitchRow,
    detection_switch: adw::SwitchRow,
    low_memory_switch: adw::SwitchRow,
    auto_title_switch: adw::SwitchRow,
    auto_process_switch: adw::SwitchRow,
    countdown_switch: adw::SwitchRow,
    quality_combo: IdComboRow,
    folder_entry: adw::EntryRow,
}

impl GeneralPage {
    pub fn new(cfg: &Config, parent_window: &adw::Window) -> Self {
        let (scroll, content) = make_scroll_page();

        let general = adw::PreferencesGroup::builder().title("General").build();

        let startup_switch = adw::SwitchRow::builder()
            .title("Start at system startup")
            .build();
        startup_switch.set_active(is_autostart_enabled());
        general.add(&startup_switch);

        let detection_switch = adw::SwitchRow::builder()
            .title("Enable call detection")
            .subtitle(
                "Monitor running processes and audio streams to detect active \
                 calls and notify you to start recording. May produce false \
                 positives for other apps that use the microphone.",
            )
            .build();
        detection_switch.set_active(cfg.call_detection_enabled);
        general.add(&detection_switch);

        let low_memory_switch = adw::SwitchRow::builder()
            .title("Low memory mode")
            .subtitle(
                "Unload the window from memory when you close it, saving RAM \
                 while idle in the tray (~20 MB vs. ~100 MB) at the cost of a \
                 brief delay when reopening. Enable on low-memory systems.",
            )
            .build();
        low_memory_switch.set_active(cfg.low_memory_mode);
        general.add(&low_memory_switch);
        content.append(&general);

        let recording = adw::PreferencesGroup::builder().title("Recording").build();

        let auto_title_switch = adw::SwitchRow::builder()
            .title("Auto-title recordings")
            .subtitle("Automatically generate a short title based on meeting notes.")
            .build();
        auto_title_switch.set_active(cfg.auto_title);
        recording.add(&auto_title_switch);

        let auto_process_switch = adw::SwitchRow::builder()
            .title("Auto-process recordings")
            .subtitle(
                "Automatically start transcription and summarization when a \
                  recording stops. When off, only the audio is saved — start \
                  processing manually from Jobs or the Library.",
            )
            .build();
        auto_process_switch.set_active(cfg.auto_process_enabled);
        recording.add(&auto_process_switch);

        let countdown_switch = adw::SwitchRow::builder()
            .title("Processing countdown")
            .subtitle(
                "Show a 5-second countdown after stopping a recording. Cancel \
                 during it to skip transcription and save the audio only.",
            )
            .build();
        countdown_switch.set_active(cfg.processing_countdown_enabled);
        recording.add(&countdown_switch);

        let qualities = ["very_high", "high", "medium", "low"];
        let labels: Vec<&str> = qualities
            .iter()
            .map(|q| crate::config::defaults::recording_quality_label(q).0)
            .collect();
        let quality_combo = IdComboRow::new(
            "Recording quality",
            &qualities,
            &labels,
            &cfg.recording_quality,
        );
        recording.add(&quality_combo.widget);

        let folder_entry = adw::EntryRow::builder().title("Output folder").build();
        folder_entry.set_text(&cfg.output_folder);
        let browse_btn = gtk::Button::builder()
            .icon_name("folder-open-symbolic")
            .build();
        browse_btn.add_css_class("flat");
        browse_btn.set_valign(gtk::Align::Center);
        browse_btn.set_tooltip_text(Some("Browse…"));
        {
            let entry = folder_entry.clone();
            // Gtk.FileDialog needs a transient parent; settings window works.
            let parent: adw::Window = parent_window.clone();
            browse_btn.connect_clicked(move |_| {
                let dialog = gtk::FileDialog::builder()
                    .title("Select Output Folder")
                    .build();
                let current = shellexpand(entry.text().as_str());
                if std::path::Path::new(&current).is_dir() {
                    dialog.set_initial_folder(Some(&gtk::gio::File::for_path(&current)));
                }
                let entry_c = entry.clone();
                dialog.select_folder(
                    Some(&parent),
                    None::<&gtk::gio::Cancellable>,
                    move |result| {
                        if let Ok(folder) = result {
                            if let Some(path) = folder.path() {
                                entry_c.set_text(&path.to_string_lossy());
                            }
                        }
                    },
                );
            });
        }
        folder_entry.add_suffix(&browse_btn);
        recording.add(&folder_entry);
        content.append(&recording);

        Self {
            widget: scroll,
            startup_switch,
            detection_switch,
            low_memory_switch,
            auto_title_switch,
            auto_process_switch,
            countdown_switch,
            quality_combo,
            folder_entry,
        }
    }

    /// Write this tab's values into `cfg` (called by the dialog's save flow).
    pub fn apply(&self, cfg: &mut Config) {
        let folder = self.folder_entry.text().to_string();
        cfg.output_folder = if folder.trim().is_empty() {
            "~/meetings".to_string()
        } else {
            folder.trim().to_string()
        };
        cfg.recording_quality = self
            .quality_combo
            .get_active_id()
            .unwrap_or_else(|| "high".to_string());
        cfg.call_detection_enabled = self.detection_switch.is_active();
        cfg.low_memory_mode = self.low_memory_switch.is_active();
        cfg.start_at_startup = self.startup_switch.is_active();
        cfg.auto_title = self.auto_title_switch.is_active();
        cfg.auto_process_enabled = self.auto_process_switch.is_active();
        cfg.processing_countdown_enabled = self.countdown_switch.is_active();
    }
}

fn shellexpand(p: &str) -> String {
    if let Some(rest) = p.strip_prefix("~/") {
        match dirs::home_dir() {
            Some(h) => h.join(rest).to_string_lossy().into_owned(),
            None => p.to_string(),
        }
    } else {
        p.to_string()
    }
}
