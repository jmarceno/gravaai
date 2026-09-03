//! Tabbed settings window (General / Models / Prompts) — a thin shell providing
//! the Cancel/ViewSwitcher/Save chrome, instantiating the pages, and running
//! the save flow.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;

use crate::config::defaults::Config;
use crate::config::settings;
use crate::ui::engine_proxy::{InstallUiEvent, ProxyHandle};
use crate::utils::autostart::update_autostart;

use super::settings_pages::{general::GeneralPage, models::ModelsPage, prompts::PromptsPage};

pub struct SettingsDialog {
    pub window: adw::Window,
    general: GeneralPage,
    models: Rc<ModelsPage>,
    prompts: PromptsPage,
    on_saved: RefCell<Option<Box<dyn Fn()>>>,
}

impl SettingsDialog {
    pub fn new(
        parent: &impl IsA<gtk::Window>,
        proxy: Option<ProxyHandle>,
        on_saved: Option<Box<dyn Fn()>>,
    ) -> Rc<Self> {
        let window = adw::Window::builder()
            .title("Settings")
            .transient_for(parent)
            .modal(true)
            .build();
        window.set_default_size(620, 680);

        let cfg = settings::load();
        let general = GeneralPage::new(&cfg, &window);
        let models = ModelsPage::new(&cfg, proxy);
        let prompts = PromptsPage::new(&cfg);

        let dialog = Rc::new(Self {
            window: window.clone(),
            general,
            models: models.clone(),
            prompts,
            on_saved: RefCell::new(on_saved),
        });

        // Chrome: Cancel / ViewSwitcher / Save.
        let toolbar_view = adw::ToolbarView::new();
        window.set_content(Some(&toolbar_view));

        let stack = adw::ViewStack::new();
        stack.set_vexpand(true);

        let switcher = adw::ViewSwitcher::new();
        switcher.set_stack(Some(&stack));
        switcher.set_policy(adw::ViewSwitcherPolicy::Wide);

        let header = adw::HeaderBar::new();
        header.set_show_end_title_buttons(false);
        header.set_show_start_title_buttons(false);
        header.set_title_widget(Some(&switcher));

        let cancel_btn = gtk::Button::with_label("Cancel");
        {
            let window_c = window.clone();
            cancel_btn.connect_clicked(move |_| window_c.close());
        }
        header.pack_start(&cancel_btn);

        let save_btn = gtk::Button::with_label("Save");
        save_btn.add_css_class("suggested-action");
        {
            let dialog_c = dialog.clone();
            save_btn.connect_clicked(move |_| dialog_c.save());
        }
        header.pack_end(&save_btn);

        toolbar_view.add_top_bar(&header);
        toolbar_view.set_content(Some(&stack));

        stack.add_titled_with_icon(
            &dialog.general.widget,
            Some("general"),
            "General",
            "preferences-system-symbolic",
        );
        stack.add_titled_with_icon(
            &models.widget,
            Some("models"),
            "Models",
            "folder-download-symbolic",
        );
        stack.add_titled_with_icon(
            &dialog.prompts.widget,
            Some("prompts"),
            "Prompts",
            "document-edit-symbolic",
        );

        // Route daemon install progress/finished to the Models page while open.
        models.reflect_running_installs();
        models.refresh_local_model_statuses();

        dialog
    }

    /// Forward one daemon install event to the Models page.
    pub fn on_install_event(&self, event: &InstallUiEvent) {
        match event {
            InstallUiEvent::Progress(key, text) => self.models.on_install_progress(key, text),
            InstallUiEvent::Finished(key, ok, message) => {
                self.models.on_install_finished(key, *ok, message)
            }
        }
    }

    pub fn present(&self) {
        self.window.present();
    }

    fn save(self: &Rc<Self>) {
        let mut cfg: Config = settings::load();
        self.general.apply(&mut cfg);
        self.models.apply(&mut cfg);
        self.prompts.apply(&mut cfg);
        if let Err(e) = settings::save(&cfg) {
            log::error!("Failed to save settings: {e:#}");
        }
        update_autostart(cfg.start_at_startup);
        // Soft format check so a pasted-wrong key surfaces now instead of as a
        // failed job at the end of a meeting. Non-blocking: still saves.
        if let Some(warning) = settings::api_key_warning(&cfg) {
            let alert = gtk::AlertDialog::builder()
                .message("API Key Warning")
                .detail(warning.as_str())
                .buttons(["OK"])
                .build();
            if let Some(parent) = self.window.transient_for() {
                alert.show(Some(&parent));
            }
        }
        if let Some(cb) = self.on_saved.borrow().as_ref() {
            cb();
        }
        self.window.close();
    }
}
