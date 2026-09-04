//! Per-model download rows.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;

struct RowState {
    button: gtk::Button,
    status: gtk::Label,
}

pub struct ModelRowGrid {
    pub widget: adw::PreferencesGroup,
    rows: RefCell<HashMap<String, RowState>>,
}

impl ModelRowGrid {
    /// `on_download(model)` fires when the row's Download button is clicked.
    pub fn new(
        models: &[&str],
        info: &dyn Fn(&str) -> (&'static str, &'static str),
        on_download: impl Fn(&str) + 'static,
        title: &str,
    ) -> Rc<Self> {
        let widget = adw::PreferencesGroup::builder().title(title).build();
        let grid = Rc::new(Self {
            widget,
            rows: RefCell::new(HashMap::new()),
        });
        let on_download = Rc::new(on_download);
        for model in models {
            let (size, note) = info(model);
            let row = adw::ActionRow::builder()
                .title(*model)
                .subtitle(format!("{size} · {note}"))
                .build();
            let status = gtk::Label::builder().label("Checking…").build();
            status.add_css_class("dim-label");
            let button = gtk::Button::with_label("Download");
            button.set_valign(gtk::Align::Center);
            {
                let cb = on_download.clone();
                let m = model.to_string();
                button.connect_clicked(move |_| cb(&m));
            }
            let suffix = gtk::Box::builder()
                .orientation(gtk::Orientation::Horizontal)
                .spacing(8)
                .valign(gtk::Align::Center)
                .build();
            suffix.append(&status);
            suffix.append(&button);
            row.add_suffix(&suffix);
            grid.widget.add(&row);
            grid.rows
                .borrow_mut()
                .insert(model.to_string(), RowState { button, status });
        }
        grid
    }

    pub fn set_not_downloaded(&self, model: &str) {
        self.update_row(model, "Not downloaded", "Download", true);
    }

    pub fn set_ready(&self, model: &str) {
        self.update_row(model, "Downloaded", "Re-download", true);
    }

    pub fn set_error(&self, model: &str, msg: &str) {
        let short: String = msg.chars().take(60).collect();
        self.update_row(model, &format!("Error: {short}"), "Retry", true);
    }

    pub fn set_progress(&self, model: &str, text: &str) {
        self.update_row(model, text, "Downloading…", false);
    }

    pub fn set_status_text(&self, model: &str, text: &str) {
        if let Some(r) = self.rows.borrow().get(model) {
            r.status.set_text(text);
        }
    }

    /// Enable/disable every row's Download button (e.g. while Ollama is
    /// offline, so a pull cannot fail with a raw connection error).
    pub fn set_all_sensitive(&self, sensitive: bool) {
        for r in self.rows.borrow().values() {
            r.button.set_sensitive(sensitive);
        }
    }

    fn update_row(&self, model: &str, status: &str, btn_label: &str, sensitive: bool) {
        if let Some(r) = self.rows.borrow().get(model) {
            r.status.set_text(status);
            r.button.set_label(btn_label);
            r.button.set_sensitive(sensitive);
        }
    }
}
