//! Meeting Explorer — browse, manage, and re-process recorded meetings.
//! (layout preserved).

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;

use crate::config::settings;
use crate::utils::meeting_scanner::{delete_meetings, rename_meeting_dir, scan_meetings, Meeting};

struct MeetingRow {
    meeting: Meeting,
    check: gtk::CheckButton,
}

pub struct MeetingExplorer {
    pub widget: gtk::Box,
    rows: RefCell<Vec<MeetingRow>>,
    list_box: gtk::ListBox,
    empty_label: gtk::Label,
    error_label: gtk::Label,
    delete_btn: gtk::Button,
    on_summarize: Box<dyn Fn(&Meeting)>,
}

impl MeetingExplorer {
    pub fn new(on_summarize: impl Fn(&Meeting) + 'static) -> Rc<Self> {
        let widget = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(0)
            .build();

        // Toolbar.
        let toolbar = gtk::Box::builder()
            .orientation(gtk::Orientation::Horizontal)
            .spacing(8)
            .build();
        toolbar.add_css_class("toolbar");
        toolbar.set_margin_top(12);
        toolbar.set_margin_bottom(8);
        toolbar.set_margin_start(16);
        toolbar.set_margin_end(16);

        let delete_btn = gtk::Button::with_label("Delete Selected");
        delete_btn.add_css_class("destructive-action");
        delete_btn.set_sensitive(false);
        toolbar.append(&delete_btn);

        let spacer = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        spacer.set_hexpand(true);
        toolbar.append(&spacer);

        let refresh_btn = gtk::Button::builder()
            .icon_name("view-refresh-symbolic")
            .build();
        refresh_btn.set_tooltip_text(Some("Refresh"));
        toolbar.append(&refresh_btn);
        widget.append(&toolbar);

        // Error label.
        let error_label = gtk::Label::builder().xalign(0.0).wrap(true).build();
        error_label.set_margin_start(16);
        error_label.set_margin_end(16);
        error_label.set_visible(false);
        widget.append(&error_label);

        // Scrollable meeting list — libadwaita boxed list in a clamp.
        let list_box = gtk::ListBox::new();
        list_box.set_selection_mode(gtk::SelectionMode::None);
        list_box.add_css_class("boxed-list");
        list_box.set_valign(gtk::Align::Start);

        let clamp = adw::Clamp::builder().maximum_size(760).build();
        clamp.set_margin_top(4);
        clamp.set_margin_bottom(16);
        clamp.set_margin_start(12);
        clamp.set_margin_end(12);
        clamp.set_child(Some(&list_box));

        let scroll = gtk::ScrolledWindow::builder().vexpand(true).build();
        scroll.set_policy(gtk::PolicyType::Never, gtk::PolicyType::Automatic);
        scroll.set_propagate_natural_height(true);
        scroll.set_child(Some(&clamp));
        widget.append(&scroll);

        // Empty state.
        let empty_label = gtk::Label::builder()
            .label("No meetings found")
            .vexpand(true)
            .build();
        empty_label.set_valign(gtk::Align::Center);
        empty_label.set_opacity(0.5);
        empty_label.set_visible(false);
        widget.append(&empty_label);

        let explorer = Rc::new(Self {
            widget,
            rows: RefCell::new(Vec::new()),
            list_box,
            empty_label,
            error_label,
            delete_btn,
            on_summarize: Box::new(on_summarize),
        });

        {
            let e = explorer.clone();
            explorer
                .delete_btn
                .connect_clicked(move |_| e.on_delete_clicked());
        }
        {
            let e = explorer.clone();
            refresh_btn.connect_clicked(move |_| e.refresh());
        }
        explorer
    }

    /// Rescan the output folder and rebuild the meeting list.
    pub fn refresh(self: &Rc<Self>) {
        self.error_label.set_visible(false);
        while let Some(child) = self.list_box.first_child() {
            self.list_box.remove(&child);
        }
        self.rows.borrow_mut().clear();

        let cfg = settings::load();
        let meetings = scan_meetings(&cfg.output_folder);
        if meetings.is_empty() {
            self.empty_label.set_visible(true);
            self.list_box.set_visible(false);
        } else {
            self.empty_label.set_visible(false);
            self.list_box.set_visible(true);
            for meeting in meetings {
                self.add_meeting_row(meeting);
            }
        }
        self.refresh_delete_sensitivity();
    }

    fn add_meeting_row(self: &Rc<Self>, meeting: Meeting) {
        let row = gtk::Box::builder()
            .orientation(gtk::Orientation::Horizontal)
            .spacing(8)
            .build();
        row.set_margin_top(8);
        row.set_margin_bottom(8);
        row.set_margin_start(12);
        row.set_margin_end(12);

        let check = gtk::CheckButton::new();
        {
            let e = self.clone();
            check.connect_toggled(move |_| e.refresh_delete_sensitivity());
        }
        row.append(&check);

        // Title area.
        let title_box = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(2)
            .hexpand(true)
            .build();
        let primary = meeting
            .title
            .clone()
            .unwrap_or_else(|| meeting.time_label.clone());
        let primary_label = gtk::Label::builder()
            .label(primary.as_str())
            .xalign(0.0)
            .build();
        primary_label.set_ellipsize(gtk::pango::EllipsizeMode::End);
        title_box.append(&primary_label);

        // Secondary line: date, time, duration.
        let date_str = meeting.date.format("%b %d, %Y").to_string();
        let time_str = meeting.date.format("%I:%M %p").to_string();
        let time_str = time_str.trim_start_matches('0').to_string();
        let mut parts = vec![date_str, time_str];
        if let Some(dur) = meeting.duration_seconds {
            if dur >= 3600 {
                parts.push(format!("{}h {}m", dur / 3600, dur % 3600 / 60));
            } else {
                parts.push(format!("{}m", dur / 60));
            }
        }
        let secondary_text = parts.join("  ·  ");
        let secondary_label = gtk::Label::builder().xalign(0.0).build();
        secondary_label.set_markup(&format!(
            "<span size=\"small\" foreground=\"gray\">{}</span>",
            glib::markup_escape_text(&secondary_text)
        ));
        title_box.append(&secondary_label);
        row.append(&title_box);

        // Status badges: notes / transcript indicators.
        let badges = gtk::Box::builder()
            .orientation(gtk::Orientation::Horizontal)
            .spacing(4)
            .valign(gtk::Align::Center)
            .build();
        if meeting.has_notes {
            let b = gtk::Label::builder().label("notes").build();
            b.add_css_class("caption");
            badges.append(&b);
        }
        if meeting.has_transcript {
            let b = gtk::Label::builder().label("transcript").build();
            b.add_css_class("caption");
            badges.append(&b);
        }
        row.append(&badges);

        // Summarize button (re-run summarization from the library).
        let sum_btn = gtk::Button::builder().icon_name("starred-symbolic").build();
        sum_btn.add_css_class("flat");
        sum_btn.set_valign(gtk::Align::Center);
        sum_btn.set_tooltip_text(Some("Summarize this meeting"));
        {
            let e = self.clone();
            let m = meeting.clone();
            sum_btn.connect_clicked(move |_| (e.on_summarize)(&m));
        }
        row.append(&sum_btn);

        // Open-folder button.
        let open_btn = gtk::Button::builder()
            .icon_name("document-open-symbolic")
            .build();
        open_btn.add_css_class("flat");
        open_btn.set_valign(gtk::Align::Center);
        open_btn.set_tooltip_text(Some("Open meeting folder"));
        {
            let path = meeting.path.clone();
            open_btn.connect_clicked(move |_| {
                let _ = std::process::Command::new("xdg-open")
                    .arg(&path)
                    .stdin(std::process::Stdio::null())
                    .stdout(std::process::Stdio::null())
                    .spawn();
            });
        }
        row.append(&open_btn);

        // Double-click the title to rename (GTK4: GestureClick on the label).
        {
            let e = self.clone();
            let m = meeting.clone();
            let primary_label_c = primary_label.clone();
            let gesture = gtk::GestureClick::new();
            gesture.connect_pressed(move |_, n_press, _, _| {
                if n_press == 2 {
                    e.rename_meeting(&m, &primary_label_c);
                }
            });
            primary_label.add_controller(gesture);
        }

        self.list_box.append(&row);
        self.rows.borrow_mut().push(MeetingRow { meeting, check });
    }

    fn rename_meeting(self: &Rc<Self>, meeting: &Meeting, primary_label: &gtk::Label) {
        let current = meeting.title.clone().unwrap_or_default();
        let parent = self
            .widget
            .root()
            .and_then(|r| r.downcast::<adw::ApplicationWindow>().ok());
        let dialog = adw::AlertDialog::builder()
            .heading("Rename meeting")
            .body("Enter a new title for this meeting.")
            .build();
        let entry = gtk::Entry::builder().text(current.as_str()).build();
        entry.set_margin_top(6);
        entry.set_margin_bottom(6);
        entry.set_margin_start(12);
        entry.set_margin_end(12);
        dialog.set_extra_child(Some(&entry));
        dialog.add_response("cancel", "Cancel");
        dialog.add_response("rename", "Rename");
        dialog.set_response_appearance("rename", adw::ResponseAppearance::Suggested);
        dialog.set_default_response(Some("rename"));
        let e = self.clone();
        let m = meeting.clone();
        let label = primary_label.clone();
        let Some(parent) = parent else {
            return;
        };
        dialog.choose(&parent, None::<&gtk::gio::Cancellable>, move |response| {
            if response.as_str() == "rename" {
                let title = entry.text().to_string();
                if !title.trim().is_empty() {
                    match rename_meeting_dir(&m, title.trim()) {
                        Ok(_) => {
                            label.set_text(title.trim());
                            e.refresh();
                        }
                        Err(err) => e.show_error(&format!("Rename failed: {err:#}")),
                    }
                }
            }
        });
    }

    fn on_delete_clicked(self: &Rc<Self>) {
        let selected: Vec<Meeting> = self
            .rows
            .borrow()
            .iter()
            .filter(|r| r.check.is_active())
            .map(|r| r.meeting.clone())
            .collect();
        if selected.is_empty() {
            return;
        }
        let (ok, failures) = delete_meetings(&selected);
        if !failures.is_empty() {
            let msgs: Vec<String> = failures
                .iter()
                .map(|(p, e)| format!("{}: {e}", p.display()))
                .collect();
            self.show_error(&format!(
                "Could not delete some meetings:\n{}",
                msgs.join("\n")
            ));
        }
        let _ = ok;
        self.refresh();
    }

    fn refresh_delete_sensitivity(&self) {
        let any = self.rows.borrow().iter().any(|r| r.check.is_active());
        self.delete_btn.set_sensitive(any);
    }

    fn show_error(&self, msg: &str) {
        self.error_label.set_text(msg);
        self.error_label.set_visible(true);
    }
}
