//! Shared settings-row helpers.

use adw::prelude::*;

/// A ComboRow addressed by stable string ids ( backed by a StringList of labels).
pub struct IdComboRow {
    pub widget: adw::ComboRow,
    ids: Vec<String>,
}

impl IdComboRow {
    pub fn new(title: &str, ids: &[&str], labels: &[&str], active_id: &str) -> Self {
        let mut ids: Vec<String> = ids.iter().map(|s| s.to_string()).collect();
        let mut labels: Vec<String> = labels.iter().map(|s| s.to_string()).collect();
        if !active_id.is_empty() && !ids.iter().any(|i| i == active_id) {
            // Preserve a stored value not in the built-in list (e.g. a custom
            // OpenAI-compatible model name) instead of resetting it.
            ids.push(active_id.to_string());
            labels.push(active_id.to_string());
        }
        let model = gtk::StringList::new(&labels.iter().map(|s| s.as_str()).collect::<Vec<_>>());
        let widget = adw::ComboRow::builder().title(title).model(&model).build();
        let row = Self { widget, ids };
        row.set_active_id(if active_id.is_empty() {
            None
        } else {
            Some(active_id)
        });
        row
    }

    pub fn get_active_id(&self) -> Option<String> {
        let selected = self.widget.selected();
        if selected == gtk::INVALID_LIST_POSITION {
            return None;
        }
        self.ids.get(selected as usize).cloned()
    }

    pub fn set_active_id(&self, id: Option<&str>) {
        let pos = id
            .and_then(|i| self.ids.iter().position(|x| x == i))
            .unwrap_or(0) as u32;
        self.widget.set_selected(pos);
    }
}

/// Scrolled settings page: returns (scroll, content box).
pub fn make_scroll_page() -> (gtk::ScrolledWindow, gtk::Box) {
    let content = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .build();
    content.set_margin_top(12);
    content.set_margin_bottom(12);
    content.set_margin_start(12);
    content.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(640).build();
    clamp.set_child(Some(&content));
    let scroll = gtk::ScrolledWindow::builder()
        .vexpand(true)
        .hexpand(true)
        .build();
    scroll.set_policy(gtk::PolicyType::Never, gtk::PolicyType::Automatic);
    scroll.set_child(Some(&clamp));
    (scroll, content)
}

pub fn install_button(label: &str) -> gtk::Button {
    let btn = gtk::Button::with_label(label);
    btn.add_css_class("suggested-action");
    btn.set_valign(gtk::Align::Center);
    btn
}

pub fn action_row(title: &str, subtitle: &str, suffix: &impl IsA<gtk::Widget>) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(title)
        .subtitle(subtitle)
        .build();
    row.add_suffix(suffix);
    row
}
