//! The recorder window — a thin client of the daemon Engine.
//! (layout preserved).
//!
//! Renders snapshots fetched over D-Bus and forwards button clicks back to
//! the engine. File/meeting selection (which needs GTK dialogs) happens here,
//! then the resolved paths are handed to the engine to process.

use std::cell::RefCell;
use std::rc::{Rc, Weak};

use adw::prelude::*;

use crate::config::settings;
use crate::core::errors::{error_presentation, Presentation};
use crate::core::window_close::{resolve_close_action, CloseAction};
use crate::core::wire::{snapshot_from_json, Snapshot};
use crate::ui::engine_proxy::{InstallUiEvent, ProxyHandle};
use crate::ui::jobs_panel::{JobCallbacks, JobsPanel};
use crate::ui::meeting_explorer::MeetingExplorer;
use crate::ui::settings_dialog::SettingsDialog;
use crate::utils::filename::output_paths;
use crate::utils::meeting_scanner::{find_audio_file, Meeting};
use crate::utils::recording_import::resolve_existing_recording_target;

fn format_time(seconds: u64) -> String {
    let (h, m, s) = (seconds / 3600, seconds % 3600 / 60, seconds % 60);
    if h > 0 {
        format!("{h:02}:{m:02}:{s:02}")
    } else {
        format!("{m:02}:{s:02}")
    }
}

fn icon_label_button(icon_name: &str, label: &str) -> gtk::Button {
    let btn = gtk::Button::new();
    let content = adw::ButtonContent::builder()
        .icon_name(icon_name)
        .label(label)
        .build();
    btn.set_child(Some(&content));
    btn
}

pub struct MainWindow {
    pub window: adw::ApplicationWindow,
    proxy: ProxyHandle,
    snapshot: RefCell<Snapshot>,
    recording_mode: RefCell<String>,
    timer_label: gtk::Label,
    status_label: gtk::Label,
    title_entry: adw::EntryRow,
    button_box: gtk::Box,
    output_box: gtk::Box,
    output_label: gtk::Label,
    jobs_panel: Rc<JobsPanel>,
    explorer: Rc<MeetingExplorer>,
    stack: adw::ViewStack,
    toast_overlay: adw::ToastOverlay,
    settings_dialog: RefCell<Option<Rc<SettingsDialog>>>,
}

impl MainWindow {
    pub fn new(application: &adw::Application, proxy: ProxyHandle) -> Rc<Self> {
        Rc::new_cyclic(|weak: &Weak<Self>| {
            let weak_cancel = weak.clone();
            let weak_retry = weak.clone();
            let weak_folder = weak.clone();
            let weak_dismiss = weak.clone();
            let weak_summarize = weak.clone();

            let window = adw::ApplicationWindow::builder()
                .application(application)
                .title("Meeting Recorder")
                .default_width(1100)
                .default_height(760)
                .resizable(true)
                .build();

            let toast_overlay = adw::ToastOverlay::new();
            window.set_content(Some(&toast_overlay));

            let toolbar_view = adw::ToolbarView::new();
            toast_overlay.set_child(Some(&toolbar_view));

            let stack = adw::ViewStack::new();
            stack.set_vexpand(true);

            let switcher = adw::ViewSwitcher::new();
            switcher.set_stack(Some(&stack));
            switcher.set_policy(adw::ViewSwitcherPolicy::Wide);

            let header = adw::HeaderBar::new();
            header.set_title_widget(Some(&switcher));
            toolbar_view.add_top_bar(&header);

            // Recorder view.
            let recorder_box = gtk::Box::builder()
                .orientation(gtk::Orientation::Vertical)
                .spacing(24)
                .build();
            recorder_box.set_margin_top(24);
            recorder_box.set_margin_bottom(24);
            recorder_box.set_margin_start(12);
            recorder_box.set_margin_end(12);

            let vbox = gtk::Box::builder()
                .orientation(gtk::Orientation::Vertical)
                .spacing(12)
                .build();

            let timer_label = gtk::Label::builder().label("00:00").build();
            timer_label.add_css_class("timer-label");
            let attrs = gtk::pango::AttrList::new();
            attrs.insert(gtk::pango::AttrSize::new_size_absolute(
                48 * gtk::pango::SCALE,
            ));
            timer_label.set_attributes(Some(&attrs));
            vbox.append(&timer_label);

            let status_label = gtk::Label::builder()
                .label("")
                .wrap(true)
                .xalign(0.5)
                .build();
            status_label.add_css_class("dim-label");
            vbox.append(&status_label);

            let title_group = adw::PreferencesGroup::new();
            let title_entry = adw::EntryRow::builder().title("Title (optional)").build();
            title_group.add(&title_entry);
            vbox.append(&title_group);

            let button_box = gtk::Box::builder()
                .orientation(gtk::Orientation::Horizontal)
                .spacing(8)
                .halign(gtk::Align::Center)
                .build();
            vbox.append(&button_box);

            let output_box = gtk::Box::builder()
                .orientation(gtk::Orientation::Vertical)
                .spacing(8)
                .build();
            output_box.set_visible(false);
            let output_label = gtk::Label::builder()
                .label("")
                .wrap(true)
                .xalign(0.0)
                .build();
            output_label.add_css_class("dim-label");
            let open_folder_btn = gtk::Button::with_label("Open Output Folder");
            open_folder_btn.set_halign(gtk::Align::Center);
            output_box.append(&output_label);
            output_box.append(&open_folder_btn);
            vbox.append(&output_box);

            let jobs_panel = JobsPanel::new(JobCallbacks {
                on_cancel: Box::new(move |j| {
                    if let Some(this) = weak_cancel.upgrade() {
                        this.proxy.cancel_job(j.job_id);
                    }
                }),
                on_retry: Box::new(move |j| {
                    if let Some(this) = weak_retry.upgrade() {
                        this.proxy.retry_job(j.job_id);
                    }
                }),
                on_open_folder: Box::new(move |j| {
                    if let Some(this) = weak_folder.upgrade() {
                        this.on_open_job_folder(&j.audio_dir);
                    }
                }),
                on_dismiss: Box::new(move |j| {
                    if let Some(this) = weak_dismiss.upgrade() {
                        this.proxy.dismiss_job(j.job_id);
                    }
                }),
            });
            vbox.append(&jobs_panel.widget);
            recorder_box.append(&vbox);

            let clamp = adw::Clamp::builder().maximum_size(560).build();
            clamp.set_child(Some(&recorder_box));
            let recorder_scroll = gtk::ScrolledWindow::new();
            recorder_scroll.set_policy(gtk::PolicyType::Never, gtk::PolicyType::Automatic);
            recorder_scroll.set_child(Some(&clamp));

            stack.add_titled_with_icon(
                &recorder_scroll,
                Some("recorder"),
                "Record",
                "media-record-symbolic",
            );

            let explorer = MeetingExplorer::new(move |meeting| {
                if let Some(this) = weak_summarize.upgrade() {
                    this.on_summarize_from_explorer(meeting);
                }
            });
            stack.add_titled_with_icon(
                &explorer.widget,
                Some("explorer"),
                "Library",
                "view-list-symbolic",
            );

            toolbar_view.set_content(Some(&stack));

            Self {
                window,
                proxy,
                snapshot: RefCell::new(Snapshot::default()),
                recording_mode: RefCell::new("headphones".to_string()),
                timer_label,
                status_label,
                title_entry,
                button_box,
                output_box,
                output_label,
                jobs_panel,
                explorer,
                stack,
                toast_overlay,
                settings_dialog: RefCell::new(None),
            }
        })
    }

    /// Post-construction wiring that needs `Rc<Self>` (called once by window_app).
    pub fn finish_init(self: &Rc<Self>) {
        self.wire_header_gear();
        {
            let explorer = self.explorer.clone();
            let this = self.clone();
            self.stack.connect_visible_child_name_notify(move |stack| {
                if stack.visible_child_name().as_deref() == Some("explorer") {
                    explorer.refresh();
                }
                let _ = &this;
            });
        }
        {
            let p = self.proxy.clone();
            // Open Output Folder button was created in new_cyclic without a
            // handler; find it via the output box children.
            if let Some(btn) = self
                .output_box
                .first_child()
                .and_then(|l| l.next_sibling())
                .and_then(|b| b.downcast::<gtk::Button>().ok())
            {
                btn.connect_clicked(move |_| {
                    let folder = p.output_folder();
                    open_folder(&folder);
                });
            }
        }
        {
            let this = self.clone();
            self.window
                .connect_close_request(move |_| this.on_close_request());
        }
        // Paint the current daemon state immediately.
        self.apply_snapshot_json(&self.proxy.get_snapshot());
    }

    fn wire_header_gear(self: &Rc<Self>) {
        // Header bar gear: locate the HeaderBar through the widget tree and
        // pack the menu button. The tree is window → ToastOverlay →
        // ToolbarView → HeaderBar (top bar).
        let gear = Self::build_gear_menu_button(self);
        let mut found: Option<adw::HeaderBar> = None;
        let mut stack_visit = vec![self.window.clone().upcast::<gtk::Widget>()];
        while let Some(w) = stack_visit.pop() {
            if let Ok(header) = w.clone().downcast::<adw::HeaderBar>() {
                found = Some(header);
                break;
            }
            let mut child = w.first_child();
            while let Some(c) = child {
                stack_visit.push(c.clone());
                child = c.next_sibling();
            }
        }
        if let Some(header) = found {
            header.pack_end(&gear);
        }
    }

    // ------------------------------------------------------------------
    // Snapshot rendering
    // ------------------------------------------------------------------

    /// Parse a daemon snapshot and render it.
    pub fn apply_snapshot_json(self: &Rc<Self>, payload: &str) {
        let snap = snapshot_from_json(payload);
        *self.snapshot.borrow_mut() = snap;
        self.update_ui();
        let jobs = self.snapshot.borrow().jobs.clone();
        self.jobs_panel.render(&jobs);
    }

    fn update_ui(self: &Rc<Self>) {
        while let Some(child) = self.button_box.first_child() {
            self.button_box.remove(&child);
        }
        let (state, status, elapsed) = {
            let snap = self.snapshot.borrow();
            (snap.state.clone(), snap.status.clone(), snap.elapsed)
        };

        self.timer_label.set_text(&format_time(elapsed));

        match state.as_str() {
            "recording" => {
                self.status_label.set_text(if status.is_empty() {
                    "Recording…"
                } else {
                    &status
                });
                self.title_entry.set_sensitive(false);
                self.output_box.set_visible(false);

                let pause_btn = icon_label_button("media-playback-pause-symbolic", "Pause");
                pause_btn.add_css_class("pill");
                {
                    let p = self.proxy.clone();
                    pause_btn.connect_clicked(move |_| p.pause());
                }
                self.button_box.append(&pause_btn);
                self.append_stop_cancel_buttons();
            }
            "paused" => {
                self.status_label
                    .set_text(if status.is_empty() { "Paused" } else { &status });
                self.title_entry.set_sensitive(false);

                let resume_btn = icon_label_button("media-playback-start-symbolic", "Resume");
                resume_btn.add_css_class("suggested-action");
                resume_btn.add_css_class("pill");
                {
                    let p = self.proxy.clone();
                    resume_btn.connect_clicked(move |_| p.resume());
                }
                self.button_box.append(&resume_btn);
                self.append_stop_cancel_buttons();
            }
            "countdown" => {
                self.status_label.set_text(&status);
                self.title_entry.set_sensitive(false);
                self.output_box.set_visible(false);

                let cancel_btn = gtk::Button::with_label("Cancel");
                cancel_btn.add_css_class("destructive-action");
                cancel_btn.add_css_class("pill");
                {
                    let p = self.proxy.clone();
                    cancel_btn.connect_clicked(move |_| p.cancel_countdown());
                }
                self.button_box.append(&cancel_btn);
            }
            _ => {
                self.timer_label.set_text("00:00");
                self.status_label.set_text(if status.is_empty() {
                    "Ready to record"
                } else {
                    &status
                });
                self.title_entry.set_sensitive(true);

                let idle_vbox = gtk::Box::builder()
                    .orientation(gtk::Orientation::Vertical)
                    .spacing(6)
                    .build();
                let record_row = gtk::Box::builder()
                    .orientation(gtk::Orientation::Horizontal)
                    .spacing(8)
                    .homogeneous(true)
                    .build();

                let headphones_btn =
                    icon_label_button("media-record-symbolic", "Record (Headphones)");
                headphones_btn.set_tooltip_text(Some(
                    "Record mic + system audio. Use when wearing headphones.",
                ));
                headphones_btn.add_css_class("suggested-action");
                headphones_btn.add_css_class("pill");
                headphones_btn.set_hexpand(true);
                record_row.append(&headphones_btn);

                let speaker_btn =
                    icon_label_button("audio-input-microphone-symbolic", "Record (Speaker)");
                speaker_btn
                    .set_tooltip_text(Some("Record mic only. Use when on speaker to avoid echo."));
                speaker_btn.add_css_class("pill");
                speaker_btn.set_hexpand(true);
                record_row.append(&speaker_btn);
                idle_vbox.append(&record_row);

                let existing_btn =
                    icon_label_button("document-open-symbolic", "Use Existing Recording");
                existing_btn.add_css_class("pill");
                existing_btn.set_halign(gtk::Align::Center);
                idle_vbox.append(&existing_btn);
                self.button_box.append(&idle_vbox);

                {
                    let this = self.clone();
                    headphones_btn.connect_clicked(move |_| this.start_recording("headphones"));
                }
                {
                    let this = self.clone();
                    speaker_btn.connect_clicked(move |_| this.start_recording("speaker"));
                }
                {
                    let this = self.clone();
                    existing_btn.connect_clicked(move |_| this.on_use_existing_clicked());
                }
            }
        }
    }

    fn append_stop_cancel_buttons(&self) {
        let stop_btn = icon_label_button("media-playback-stop-symbolic", "Stop");
        stop_btn.add_css_class("destructive-action");
        stop_btn.add_css_class("pill");
        {
            let p = self.proxy.clone();
            stop_btn.connect_clicked(move |_| p.stop());
        }
        self.button_box.append(&stop_btn);

        let save_btn = gtk::Button::with_label("Cancel (save recording)");
        save_btn.add_css_class("pill");
        {
            let p = self.proxy.clone();
            save_btn.connect_clicked(move |_| p.cancel_save());
        }
        self.button_box.append(&save_btn);

        let cancel_btn = gtk::Button::with_label("Cancel");
        cancel_btn.add_css_class("pill");
        {
            let p = self.proxy.clone();
            cancel_btn.connect_clicked(move |_| p.cancel());
        }
        self.button_box.append(&cancel_btn);
    }

    /// Engine Output signal: recording saved without transcription.
    pub fn show_output(&self, text: &str) {
        self.output_label.set_text(text);
        self.output_box.set_visible(true);
    }

    // ------------------------------------------------------------------
    // Button handlers -> engine
    // ------------------------------------------------------------------

    fn start_recording(self: &Rc<Self>, mode: &str) {
        *self.recording_mode.borrow_mut() = mode.to_string();
        let title = self.title_entry.text().to_string();
        self.proxy.set_title(title.trim());
        self.proxy.start_recording(mode);
    }

    /// Forward one daemon install event to the open Settings dialog, if any.
    /// When no Settings dialog is open (installs survive window closing), a
    /// failure is still surfaced via the standard error presentation so it
    /// never fails silently.
    pub fn on_install_event(&self, event: &InstallUiEvent) {
        if let Some(dialog) = self.settings_dialog.borrow().as_ref() {
            dialog.on_install_event(event);
            return;
        }
        if let InstallUiEvent::Finished(key, ok, message) = event {
            if !ok {
                let detail = if message.trim().is_empty() {
                    format!("Install {key} failed. Check the logs for details.")
                } else {
                    format!("Install {key} failed: {}", message.trim())
                };
                self.show_error(&detail);
            }
        }
    }

    // ------------------------------------------------------------------
    // Use Existing / Summarize
    // ------------------------------------------------------------------

    pub fn on_use_existing_clicked(self: &Rc<Self>) {
        let cfg = settings::load();
        let dialog = gtk::FileDialog::builder()
            .title("Select Audio Recording")
            .build();
        let audio_filter = gtk::FileFilter::new();
        audio_filter.set_name(Some("Audio files"));
        for pat in ["*.mp3", "*.wav", "*.m4a", "*.ogg", "*.flac", "*.webm"] {
            audio_filter.add_pattern(pat);
        }
        let filters = gtk::gio::ListStore::new::<gtk::FileFilter>();
        filters.append(&audio_filter);
        dialog.set_filters(Some(&filters));
        dialog.set_default_filter(Some(&audio_filter));
        let this = self.clone();
        dialog.open(
            Some(&self.window),
            None::<&gtk::gio::Cancellable>,
            move |result| {
                this.on_existing_chosen(result, &cfg.output_folder);
            },
        );
    }

    fn on_existing_chosen(&self, result: Result<gtk::gio::File, glib::Error>, output_folder: &str) {
        let file = match result {
            Ok(f) => f,
            Err(_) => return, // cancelled
        };
        let Some(path) = file.path() else { return };
        let expanded = shellexpand(output_folder);
        let (reuse_in_place, paths) =
            resolve_existing_recording_target(&path, &std::path::PathBuf::from(&expanded));
        if reuse_in_place {
            if let Some((audio, transcript, notes)) = paths {
                self.proxy.import_existing(
                    &audio.to_string_lossy(),
                    &transcript.to_string_lossy(),
                    &notes.to_string_lossy(),
                    &path
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default(),
                );
            }
            return;
        }
        let (audio_path, transcript_path, notes_path) = output_paths(output_folder, None);
        if let Err(e) = std::fs::copy(&path, &audio_path) {
            self.show_error(&format!("Failed to copy audio file: {e}"));
            return;
        }
        self.proxy.import_existing(
            &audio_path.to_string_lossy(),
            &transcript_path.to_string_lossy(),
            &notes_path.to_string_lossy(),
            &path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
        );
    }

    fn on_summarize_from_explorer(&self, meeting: &Meeting) {
        let Some(audio_path) = find_audio_file(&meeting.path) else {
            self.show_error("No audio file found in meeting folder.");
            return;
        };
        let transcript_path = meeting.path.join("transcript.md");
        let notes_path = meeting.path.join("notes.md");
        let err = self.proxy.summarize_meeting(
            &audio_path.to_string_lossy(),
            &transcript_path.to_string_lossy(),
            &notes_path.to_string_lossy(),
            &meeting.time_label,
        );
        if !err.is_empty() {
            self.show_error(&err);
            return;
        }
        self.stack.set_visible_child_name("recorder");
    }

    fn on_open_job_folder(&self, audio_dir: &str) {
        if !audio_dir.is_empty() {
            open_folder(audio_dir);
        }
    }

    // ------------------------------------------------------------------
    // Error display (Engine Error signal)
    // ------------------------------------------------------------------

    pub fn show_error(&self, msg: &str) {
        log::error!("UI error shown: {msg}");
        match error_presentation(msg) {
            Presentation::Dialog => {
                let alert = gtk::AlertDialog::builder()
                    .modal(true)
                    .message("Meeting Recorder")
                    .detail(msg)
                    .buttons(["OK"])
                    .build();
                alert.show(Some(&self.window));
            }
            Presentation::Toast => {
                let toast = adw::Toast::builder().title(msg).build();
                toast.set_timeout(0);
                self.toast_overlay.add_toast(toast);
            }
        }
    }

    // ------------------------------------------------------------------
    // Settings + About
    // ------------------------------------------------------------------

    fn build_gear_menu_button(this: &Rc<Self>) -> gtk::MenuButton {
        let menu = gtk::gio::Menu::new();
        menu.append(Some("Preferences"), Some("gear.preferences"));
        menu.append(Some("About Meeting Recorder"), Some("gear.about"));

        let actions = gtk::gio::SimpleActionGroup::new();
        {
            let dialog_owner = this.clone();
            let preferences = gtk::gio::SimpleAction::new("preferences", None);
            preferences.connect_activate(move |_, _| dialog_owner.on_settings_clicked());
            actions.add_action(&preferences);
        }
        {
            let dialog_owner = this.clone();
            let about = gtk::gio::SimpleAction::new("about", None);
            about.connect_activate(move |_, _| dialog_owner.on_about_clicked());
            actions.add_action(&about);
        }
        this.window.insert_action_group("gear", Some(&actions));

        gtk::MenuButton::builder()
            .icon_name("preferences-system-symbolic")
            .tooltip_text("Menu")
            .menu_model(&menu)
            .build()
    }

    fn on_settings_clicked(self: &Rc<Self>) {
        let proxy = self.proxy.clone();
        let dialog = SettingsDialog::new(
            &self.window,
            Some(proxy.clone()),
            Some(Box::new(move || proxy.reload_config())),
        );
        *self.settings_dialog.borrow_mut() = Some(dialog.clone());
        {
            let this = self.clone();
            dialog.window.connect_close_request(move |_| {
                *this.settings_dialog.borrow_mut() = None;
                glib::Propagation::Proceed
            });
        }
        dialog.present();
    }

    fn on_about_clicked(&self) {
        use crate::core::app_info;
        let version = app_info::installed_version();
        let mut builder = adw::AboutDialog::builder()
            .application_name(crate::config::defaults::APP_NAME)
            .application_icon("meeting-recorder")
            .developer_name(app_info::DEVELOPER_NAME)
            .comments(app_info::DESCRIPTION)
            .website(app_info::REPOSITORY)
            .issue_url(app_info::ISSUE_URL)
            .developers(app_info::DEVELOPERS)
            .copyright(app_info::COPYRIGHT)
            .license_type(gtk::License::MitX11);
        if let Some(v) = &version {
            builder = builder.version(v.as_str());
        }
        let about = builder.build();
        about.present(Some(&self.window));
    }

    // ------------------------------------------------------------------
    // Window lifecycle
    // ------------------------------------------------------------------

    pub fn present_window(&self) {
        // GTK4 focus is mediated by the compositor; present() is the
        // supported path (best-effort on Wayland/GNOME).
        self.window.set_visible(true);
        self.window.unminimize();
        self.window.present();
    }

    pub fn open_use_existing(self: &Rc<Self>) {
        self.present_window();
        self.on_use_existing_clicked();
    }

    fn on_close_request(&self) -> glib::Propagation {
        // By default the window hides so the process stays resident and the
        // next Open is an instant present; Low memory mode exits instead so
        // GTK memory is reclaimed and the daemon respawns on demand.
        match resolve_close_action(&settings::load()) {
            CloseAction::Hide => {
                self.window.set_visible(false);
                glib::Propagation::Stop // veto the destroy; the window lives on
            }
            CloseAction::Exit => glib::Propagation::Proceed,
        }
    }
}

fn open_folder(folder: &str) {
    if folder.is_empty() {
        return;
    }
    let _ = std::process::Command::new("xdg-open")
        .arg(folder)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
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
