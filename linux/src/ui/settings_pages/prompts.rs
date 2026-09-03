//! Settings → Prompts tab.
//!
//! Storing an empty string for a prompt key means "use the built-in default";
//! `apply()` writes `""` when the editor content equals the default.

use std::collections::HashMap;

use adw::prelude::*;

use crate::config::defaults::{Config, SUMMARIZATION_PROMPT, TITLE_PROMPT, TRANSCRIPTION_PROMPT};

pub struct PromptsPage {
    pub widget: gtk::ScrolledWindow,
    views: HashMap<String, gtk::TextView>,
}

impl PromptsPage {
    pub fn new(cfg: &Config) -> Self {
        let (scroll, content) = super::widgets::make_scroll_page();
        let mut page = Self {
            widget: scroll,
            views: HashMap::new(),
        };
        content.append(&page.build_prompt_section(
            "transcription",
            "Transcription prompt",
            Some("Transcription prompts apply to the OpenAI-compatible service only. Local engines do not use prompts."),
            &cfg.transcription_prompt,
            TRANSCRIPTION_PROMPT,
            160,
        ));
        content.append(&page.build_prompt_section(
            "summarization",
            "Summarization prompt",
            None,
            &cfg.summarization_prompt,
            SUMMARIZATION_PROMPT,
            160,
        ));
        content.append(&page.build_prompt_section(
            "title",
            "Title prompt",
            Some("Used for auto-titling recordings. Must contain {transcript}."),
            &cfg.title_prompt,
            TITLE_PROMPT,
            120,
        ));
        page
    }

    fn build_prompt_section(
        &mut self,
        key: &str,
        label: &str,
        note: Option<&str>,
        stored: &str,
        default: &str,
        height: i32,
    ) -> adw::PreferencesGroup {
        let group = adw::PreferencesGroup::builder().title(label).build();
        if let Some(note) = note {
            group.set_description(Some(note));
        }
        let reset_btn = gtk::Button::with_label("Reset to default");
        reset_btn.add_css_class("flat");
        let view = gtk::TextView::new();
        view.set_wrap_mode(gtk::WrapMode::Word);
        view.set_monospace(true);
        view.set_top_margin(8);
        view.set_bottom_margin(8);
        view.set_left_margin(8);
        view.set_right_margin(8);
        let initial = if stored.trim().is_empty() {
            default
        } else {
            stored
        };
        view.buffer().set_text(initial);
        {
            let view_c = view.clone();
            let default_owned = default.to_string();
            reset_btn.connect_clicked(move |_| view_c.buffer().set_text(&default_owned));
        }
        group.set_header_suffix(Some(&reset_btn));

        let scroll = gtk::ScrolledWindow::new();
        scroll.set_min_content_height(height);
        scroll.set_child(Some(&view));
        scroll.add_css_class("card");
        group.add(&scroll);

        self.views.insert(key.to_string(), view);
        group
    }

    /// Write this tab's values into `cfg` (called by the dialog's save flow).
    pub fn apply(&self, cfg: &mut Config) {
        cfg.transcription_prompt = read_prompt(&self.views["transcription"], TRANSCRIPTION_PROMPT);
        cfg.summarization_prompt = read_prompt(&self.views["summarization"], SUMMARIZATION_PROMPT);
        cfg.title_prompt = read_prompt(&self.views["title"], TITLE_PROMPT);
    }
}

fn read_prompt(view: &gtk::TextView, default: &str) -> String {
    let buf = view.buffer();
    let text = buf
        .text(&buf.start_iter(), &buf.end_iter(), false)
        .to_string();
    if text.trim() == default.trim() {
        String::new()
    } else {
        text.trim().to_string()
    }
}
