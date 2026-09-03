//! Background Jobs panel.
//!
//! An `Adw.PreferencesGroup` of job rows. Which buttons a row offers comes
//! from the pure [`actions_for_status`](crate::core::job::actions_for_status)
//! policy; this module only renders. Main-thread only.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;

use crate::core::job::{actions_for_status, JobStatus};
use crate::core::wire::JobView;

pub struct JobCallbacks {
    pub on_cancel: Box<dyn Fn(&JobView)>,
    pub on_retry: Box<dyn Fn(&JobView)>,
    pub on_open_folder: Box<dyn Fn(&JobView)>,
    pub on_dismiss: Box<dyn Fn(&JobView)>,
}

struct RowWidgets {
    row: adw::ActionRow,
    spinner: gtk::Spinner,
    status_icon: gtk::Image,
    action_box: gtk::Box,
}

pub struct JobsPanel {
    pub widget: adw::PreferencesGroup,
    rows: RefCell<HashMap<i64, RowWidgets>>,
    callbacks: Rc<JobCallbacks>,
}

impl JobsPanel {
    pub fn new(callbacks: JobCallbacks) -> Rc<Self> {
        let widget = adw::PreferencesGroup::builder()
            .title("Background Jobs")
            .build();
        widget.set_visible(false);
        Rc::new(Self {
            widget,
            rows: RefCell::new(HashMap::new()),
            callbacks: Rc::new(callbacks),
        })
    }

    /// Reconcile the panel to a snapshot's job list: remove vanished rows,
    /// update existing ones, add new ones. Processing rows show their
    /// transient `status_text` subtitle.
    pub fn render(self: &Rc<Self>, jobs: &[JobView]) {
        let incoming: std::collections::HashSet<i64> = jobs.iter().map(|j| j.job_id).collect();
        let vanished: Vec<i64> = self
            .rows
            .borrow()
            .keys()
            .filter(|id| !incoming.contains(id))
            .cloned()
            .collect();
        for id in vanished {
            if let Some(w) = self.rows.borrow_mut().remove(&id) {
                self.widget.remove(&w.row);
            }
        }
        for job in jobs {
            if !self.rows.borrow().contains_key(&job.job_id) {
                self.add_job(job);
            }
            self.update_job(job);
            if job.status == JobStatus::Processing && !job.status_text.is_empty() {
                if let Some(w) = self.rows.borrow().get(&job.job_id) {
                    w.row.set_subtitle(&job.status_text);
                }
            }
        }
        self.widget.set_visible(!self.rows.borrow().is_empty());
    }

    fn add_job(self: &Rc<Self>, job: &JobView) {
        let row = adw::ActionRow::builder()
            .title(job.label.as_str())
            .subtitle("Processing…")
            .build();
        row.set_title_lines(1);

        let spinner = gtk::Spinner::new();
        spinner.start();
        spinner.set_valign(gtk::Align::Center);
        row.add_prefix(&spinner);

        let status_icon = gtk::Image::from_icon_name("system-run-symbolic");
        status_icon.set_valign(gtk::Align::Center);
        status_icon.set_visible(false);
        row.add_prefix(&status_icon);

        let action_box = gtk::Box::builder()
            .orientation(gtk::Orientation::Horizontal)
            .spacing(4)
            .valign(gtk::Align::Center)
            .build();
        row.add_suffix(&action_box);

        self.rows.borrow_mut().insert(
            job.job_id,
            RowWidgets {
                row: row.clone(),
                spinner,
                status_icon,
                action_box,
            },
        );
        self.rebuild_action_box(job);
        self.widget.add(&row);
        self.widget.set_visible(true);
    }

    fn update_job(self: &Rc<Self>, job: &JobView) {
        let rows = self.rows.borrow();
        let Some(w) = rows.get(&job.job_id) else {
            return;
        };
        match job.status {
            JobStatus::Processing => {
                // A retried job goes back to spinning instead of keeping the
                // stale error icon and message.
                w.status_icon.set_visible(false);
                w.spinner.set_visible(true);
                w.spinner.start();
                w.row.set_subtitle("Processing…");
            }
            JobStatus::Done => {
                w.spinner.stop();
                w.spinner.set_visible(false);
                w.status_icon.set_visible(true);
                w.status_icon.set_icon_name(Some("emblem-ok-symbolic"));
                w.row.set_subtitle("Done");
            }
            JobStatus::Error => {
                w.spinner.stop();
                w.spinner.set_visible(false);
                w.status_icon.set_visible(true);
                w.status_icon.set_icon_name(Some("dialog-error-symbolic"));
                let err: String = job.error_msg.clone().unwrap_or_else(|| "Error".to_string());
                let short: String = err.chars().take(60).collect();
                w.row.set_subtitle(&format!("Error: {short}"));
            }
        }
        drop(rows);
        self.rebuild_action_box(job);
    }

    fn rebuild_action_box(self: &Rc<Self>, job: &JobView) {
        let rows = self.rows.borrow();
        let Some(w) = rows.get(&job.job_id) else {
            return;
        };
        let action_box = w.action_box.clone();
        drop(rows);
        while let Some(child) = action_box.first_child() {
            action_box.remove(&child);
        }
        let job_owned = job.clone();
        for action in actions_for_status(job.status) {
            match *action {
                "dismiss" => {
                    let btn = gtk::Button::builder()
                        .icon_name("window-close-symbolic")
                        .build();
                    btn.set_tooltip_text(Some("Dismiss"));
                    let cbs = self.callbacks.clone();
                    let j = job_owned.clone();
                    btn.connect_clicked(move |_| (cbs.on_dismiss)(&j));
                    btn.add_css_class("flat");
                    action_box.append(&btn);
                }
                other => {
                    let (label, handler): (&str, fn(&Rc<JobCallbacks>, &JobView)) = match other {
                        "cancel" => ("Cancel", |c, j| (c.on_cancel)(j)),
                        "open_folder" => ("Open Folder", |c, j| (c.on_open_folder)(j)),
                        "retry" => ("Retry", |c, j| (c.on_retry)(j)),
                        _ => continue,
                    };
                    let btn = gtk::Button::with_label(label);
                    let cbs = self.callbacks.clone();
                    let j = job_owned.clone();
                    btn.connect_clicked(move |_| handler(&cbs, &j));
                    btn.add_css_class("flat");
                    action_box.append(&btn);
                }
            }
        }
    }
}
