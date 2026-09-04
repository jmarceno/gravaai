//! Settings → Models tab.
//!
//! Service selection, per-engine model management and opt-in engine/model
//! installs. The **OpenAI-compatible** section covers cloud
//! transcription/summarization; the whisper.cpp section covers the local
//! transcription engine (prebuilt download, no source builds).

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;

use crate::config::defaults::{
    Config, LLM_TIMEOUT_OPTIONS, OLLAMA_DEFAULT_HOST, OLLAMA_MODELS, OPENAI_CHAT_MODELS,
    OPENAI_DEFAULT_BASE_URL, OPENAI_DEFAULT_CHAT_MODEL, OPENAI_DEFAULT_STT_MODEL,
    OPENAI_STT_MODELS, SUMMARIZATION_SERVICES, TRANSCRIPTION_SERVICES, WHISPER_CPP_BACKENDS,
    WHISPER_CPP_MODELS,
};
use crate::core::install_spec::{self, InstallSpec};
use crate::services::ollama_service::OllamaClient;
use crate::services::system_installer::OllamaInstaller;
use crate::services::whisper_cpp_service::{
    detect_gpu_backend, WhisperCppEngineInstaller, WhisperCppStatusChecker,
};
use crate::ui::engine_proxy::ProxyHandle;
use crate::ui::model_row_grid::ModelRowGrid;
use crate::ui::settings_visibility::compute_section_visibility;

use super::widgets::{action_row, install_button, make_scroll_page, IdComboRow};

fn service_label(id: &str) -> &str {
    match id {
        "openai" => "OpenAI-compatible",
        "whisper_cpp" => "whisper.cpp (local)",
        "ollama" => "Ollama (local)",
        other => other,
    }
}

pub struct ModelsPage {
    pub widget: gtk::ScrolledWindow,
    proxy: Option<ProxyHandle>,
    ts_combo: IdComboRow,
    ss_combo: IdComboRow,
    // OpenAI section (free-text model fields — any OpenAI-compatible name).
    openai_section: gtk::Box,
    key_entry: adw::PasswordEntryRow,
    base_url_entry: adw::EntryRow,
    ts_model_entry: adw::EntryRow,
    ss_model_entry: adw::EntryRow,
    timeout_combo: IdComboRow,
    // whisper.cpp section.
    wcpp_section: gtk::Box,
    wcpp_box: gtk::Box,
    wcpp_model_entry: RefCell<Option<adw::EntryRow>>,
    wcpp_backend_combo: RefCell<Option<IdComboRow>>,
    wcpp_grid: RefCell<Option<Rc<ModelRowGrid>>>,
    wcpp_install_button: RefCell<Option<gtk::Button>>,
    wcpp_install_row: RefCell<Option<adw::ActionRow>>,
    // Ollama section.
    ollama_section: gtk::Box,
    ollama_box: gtk::Box,
    ollama_grid: RefCell<Option<Rc<ModelRowGrid>>>,
    ollama_status_row: RefCell<Option<adw::ActionRow>>,
    ollama_model_entry: RefCell<Option<adw::EntryRow>>,
    ollama_host_entry: RefCell<Option<adw::EntryRow>>,
    ollama_install_button: RefCell<Option<gtk::Button>>,
    ollama_install_row: RefCell<Option<adw::ActionRow>>,
}

impl ModelsPage {
    pub fn new(cfg: &Config, proxy: Option<ProxyHandle>) -> Rc<Self> {
        let (scroll, content) = make_scroll_page();

        // --- Services group ---
        let services = adw::PreferencesGroup::builder().title("Services").build();
        let ts_ids: Vec<&str> = TRANSCRIPTION_SERVICES.to_vec();
        let ts_labels: Vec<&str> = ts_ids.iter().map(|s| service_label(s)).collect();
        let ts_combo = IdComboRow::new(
            "Transcription service",
            &ts_ids,
            &ts_labels,
            &cfg.transcription_service,
        );
        services.add(&ts_combo.widget);
        let ss_ids: Vec<&str> = SUMMARIZATION_SERVICES.to_vec();
        let ss_labels: Vec<&str> = ss_ids.iter().map(|s| service_label(s)).collect();
        let ss_combo = IdComboRow::new(
            "Summarization service",
            &ss_ids,
            &ss_labels,
            &cfg.summarization_service,
        );
        services.add(&ss_combo.widget);
        content.append(&services);

        let ts_model_entry = adw::EntryRow::builder()
            .title("Transcription model")
            .build();
        ts_model_entry.set_text(if cfg.openai_transcription_model.trim().is_empty() {
            OPENAI_DEFAULT_STT_MODEL
        } else {
            cfg.openai_transcription_model.as_str()
        });
        let ss_model_entry = adw::EntryRow::builder()
            .title("Summarization model")
            .build();
        ss_model_entry.set_text(if cfg.openai_summarization_model.trim().is_empty() {
            OPENAI_DEFAULT_CHAT_MODEL
        } else {
            cfg.openai_summarization_model.as_str()
        });
        ts_model_entry.set_tooltip_text(Some(&format!(
            "Model name sent to /audio/transcriptions (e.g. {})",
            OPENAI_STT_MODELS.join(", ")
        )));
        ss_model_entry.set_tooltip_text(Some(&format!(
            "Model name sent to /chat/completions (e.g. {})",
            OPENAI_CHAT_MODELS.join(", ")
        )));

        let page = Rc::new(Self {
            widget: scroll,
            proxy,
            ts_combo,
            ss_combo,
            openai_section: gtk::Box::new(gtk::Orientation::Vertical, 12),
            key_entry: adw::PasswordEntryRow::builder().title("API key").build(),
            base_url_entry: adw::EntryRow::builder().title("Base URL").build(),
            ts_model_entry,
            ss_model_entry,
            timeout_combo: {
                let opts: Vec<String> = LLM_TIMEOUT_OPTIONS.iter().map(|n| n.to_string()).collect();
                let refs: Vec<&str> = opts.iter().map(|s| s.as_str()).collect();
                IdComboRow::new(
                    "Processing timeout (minutes)",
                    &refs,
                    &refs,
                    &cfg.llm_request_timeout_minutes.to_string(),
                )
            },
            wcpp_section: gtk::Box::new(gtk::Orientation::Vertical, 12),
            wcpp_box: gtk::Box::new(gtk::Orientation::Vertical, 12),
            wcpp_model_entry: RefCell::new(None),
            wcpp_backend_combo: RefCell::new(None),
            wcpp_grid: RefCell::new(None),
            wcpp_install_button: RefCell::new(None),
            wcpp_install_row: RefCell::new(None),
            ollama_section: gtk::Box::new(gtk::Orientation::Vertical, 12),
            ollama_box: gtk::Box::new(gtk::Orientation::Vertical, 12),
            ollama_grid: RefCell::new(None),
            ollama_status_row: RefCell::new(None),
            ollama_model_entry: RefCell::new(None),
            ollama_host_entry: RefCell::new(None),
            ollama_install_button: RefCell::new(None),
            ollama_install_row: RefCell::new(None),
        });

        page.build_openai_section(cfg);
        page.build_wcpp_section(cfg);
        page.build_ollama_section(cfg);
        content.append(&page.openai_section);
        content.append(&page.wcpp_section);
        content.append(&page.ollama_section);

        {
            let p = page.clone();
            page.ts_combo
                .widget
                .connect_selected_notify(move |_| p.update_visibility());
        }
        {
            let p = page.clone();
            page.ss_combo
                .widget
                .connect_selected_notify(move |_| p.update_visibility());
        }
        page.update_visibility();
        page
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    fn build_openai_section(&self, cfg: &Config) {
        let group = adw::PreferencesGroup::builder()
            .title("OpenAI-compatible")
            .description("Transcription and summarization through any OpenAI-compatible API (OpenAI, Azure OpenAI, LiteLLM, llama.cpp server, …).")
            .build();
        self.key_entry.set_text(&cfg.openai_api_key);
        group.add(&self.key_entry);
        let base_url = if cfg.openai_base_url.trim().is_empty() {
            OPENAI_DEFAULT_BASE_URL.to_string()
        } else {
            cfg.openai_base_url.clone()
        };
        self.base_url_entry.set_text(&base_url);
        group.add(&self.base_url_entry);
        group.add(&self.ts_model_entry);
        group.add(&self.ss_model_entry);
        group.add(&self.timeout_combo.widget);
        self.openai_section.append(&group);
    }

    fn build_wcpp_section(self: &Rc<Self>, cfg: &Config) {
        while let Some(child) = self.wcpp_box.first_child() {
            self.wcpp_box.remove(&child);
        }
        *self.wcpp_model_entry.borrow_mut() = None;
        *self.wcpp_backend_combo.borrow_mut() = None;
        *self.wcpp_grid.borrow_mut() = None;
        *self.wcpp_install_button.borrow_mut() = None;
        *self.wcpp_install_row.borrow_mut() = None;

        // Backend selector is always available — it picks which prebuilt
        // engine binary to download. Only CPU prebuilts exist for Linux
        // (upstream ships CUDA bundles for Windows only), so auto resolves
        // to cpu; an explicit cuda choice fails with guidance.
        let detected = detect_gpu_backend();
        let backend_combo = IdComboRow::new(
            "Acceleration backend",
            WHISPER_CPP_BACKENDS,
            WHISPER_CPP_BACKENDS,
            &cfg.whisper_cpp_backend,
        );
        backend_combo.widget.set_subtitle(&format!(
            "Detected: {detected} (Linux installs use the CPU build)"
        ));
        let group = adw::PreferencesGroup::builder()
            .title("whisper.cpp (local)")
            .build();
        group.add(&backend_combo.widget);
        self.wcpp_box.append(&group);
        *self.wcpp_backend_combo.borrow_mut() = Some(backend_combo);

        if !WhisperCppEngineInstaller.is_installed() {
            let install_group = adw::PreferencesGroup::builder()
                .description("The whisper.cpp engine is not installed. It is downloaded as an official prebuilt CPU binary with its models from HuggingFace. No compiler or system packages needed.")
                .build();
            let btn = install_button("Install");
            {
                let page = self.clone();
                btn.connect_clicked(move |b| page.on_install_whisper_cpp(b));
            }
            let row = action_row("whisper.cpp engine", "Not installed", &btn);
            install_group.add(&row);
            self.wcpp_box.append(&install_group);
            *self.wcpp_install_button.borrow_mut() = Some(btn);
            *self.wcpp_install_row.borrow_mut() = Some(row);
        } else {
            let cfg_group = adw::PreferencesGroup::builder()
                .description("GGML models are downloaded from HuggingFace and cached locally.")
                .build();
            let model_entry = adw::EntryRow::builder().title("Model").build();
            model_entry.set_text(&cfg.whisper_cpp_model);
            cfg_group.add(&model_entry);
            self.wcpp_box.append(&cfg_group);
            *self.wcpp_model_entry.borrow_mut() = Some(model_entry);

            let grid = ModelRowGrid::new(
                WHISPER_CPP_MODELS,
                &crate::config::defaults::whisper_cpp_model_info,
                {
                    let page = self.clone();
                    move |model| page.start_wcpp_download(model)
                },
                "whisper.cpp models",
            );
            self.wcpp_box.append(&grid.widget);
            *self.wcpp_grid.borrow_mut() = Some(grid);
        }
        if self.wcpp_section.first_child().is_none() {
            self.wcpp_section.append(&self.wcpp_box);
        }
    }

    fn build_ollama_section(self: &Rc<Self>, cfg: &Config) {
        while let Some(child) = self.ollama_box.first_child() {
            self.ollama_box.remove(&child);
        }
        *self.ollama_grid.borrow_mut() = None;
        *self.ollama_status_row.borrow_mut() = None;
        *self.ollama_model_entry.borrow_mut() = None;
        *self.ollama_host_entry.borrow_mut() = None;
        *self.ollama_install_button.borrow_mut() = None;
        *self.ollama_install_row.borrow_mut() = None;

        if !OllamaInstaller::is_available() {
            let group = adw::PreferencesGroup::builder()
                .title("Ollama")
                .description("Ollama is not installed. It is required for local summarization.")
                .build();
            let btn = install_button("Install");
            {
                let page = self.clone();
                btn.connect_clicked(move |b| page.on_install_ollama(b));
            }
            let row = action_row("Ollama", "Not installed", &btn);
            group.add(&row);
            self.ollama_box.append(&group);
            *self.ollama_install_button.borrow_mut() = Some(btn);
            *self.ollama_install_row.borrow_mut() = Some(row);
        } else {
            let group = adw::PreferencesGroup::builder()
                .title("Ollama")
                .description("Requires Ollama to be installed. Its server starts automatically for downloads and summarization.")
                .build();
            let model_entry = adw::EntryRow::builder().title("Ollama model").build();
            model_entry.set_text(&cfg.ollama_model);
            group.add(&model_entry);
            *self.ollama_model_entry.borrow_mut() = Some(model_entry);

            let host_entry = adw::EntryRow::builder().title("Ollama host").build();
            host_entry.set_text(&cfg.ollama_host);
            group.add(&host_entry);
            *self.ollama_host_entry.borrow_mut() = Some(host_entry);

            let status_row = adw::ActionRow::builder()
                .title("Connection")
                .subtitle("Checking Ollama connection…")
                .build();
            group.add(&status_row);
            *self.ollama_status_row.borrow_mut() = Some(status_row);
            self.ollama_box.append(&group);

            let grid = ModelRowGrid::new(
                OLLAMA_MODELS,
                &crate::config::defaults::ollama_model_info,
                {
                    let page = self.clone();
                    move |model| page.start_ollama_download(model)
                },
                "Ollama models",
            );
            self.ollama_box.append(&grid.widget);
            *self.ollama_grid.borrow_mut() = Some(grid);
        }
        if self.ollama_section.first_child().is_none() {
            self.ollama_section.append(&self.ollama_box);
        }
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    fn update_visibility(&self) {
        let ts = self
            .ts_combo
            .get_active_id()
            .unwrap_or_else(|| "whisper_cpp".to_string());
        let ss = self
            .ss_combo
            .get_active_id()
            .unwrap_or_else(|| "openai".to_string());
        let (show_openai, show_wcpp, show_ollama) = compute_section_visibility(&ts, &ss);
        self.openai_section.set_visible(show_openai);
        self.wcpp_section.set_visible(show_wcpp);
        self.ollama_section.set_visible(show_ollama);
    }

    // ------------------------------------------------------------------
    // Install dispatch (daemon-run) + progress/finished routing
    // ------------------------------------------------------------------

    fn start_install(&self, spec: InstallSpec) {
        if let Some(proxy) = &self.proxy {
            proxy.start_install(&crate::core::install_spec::spec_to_json(&spec));
        }
    }

    fn on_install_ollama(&self, button: &gtk::Button) {
        button.set_sensitive(false);
        button.set_label("Installing…");
        self.start_install(InstallSpec {
            kind: install_spec::KIND_OLLAMA.to_string(),
            ..Default::default()
        });
    }

    fn on_install_whisper_cpp(&self, button: &gtk::Button) {
        button.set_sensitive(false);
        button.set_label("Installing…");
        let backend = self
            .wcpp_backend_combo
            .borrow()
            .as_ref()
            .and_then(|c| c.get_active_id())
            .unwrap_or_else(|| "auto".to_string());
        self.start_install(InstallSpec {
            kind: install_spec::KIND_WHISPER_CPP_ENGINE.to_string(),
            backend,
            ..Default::default()
        });
    }

    fn start_wcpp_download(&self, model: &str) {
        if let Some(grid) = self.wcpp_grid.borrow().as_ref() {
            grid.set_progress(model, "Downloading…");
        }
        self.start_install(InstallSpec {
            kind: install_spec::KIND_WHISPER_CPP_MODEL.to_string(),
            model: model.to_string(),
            ..Default::default()
        });
    }

    fn start_ollama_download(&self, model: &str) {
        let host = self
            .ollama_host_entry
            .borrow()
            .as_ref()
            .map(|e| e.text().to_string())
            .unwrap_or_else(|| OLLAMA_DEFAULT_HOST.to_string());
        if let Some(grid) = self.ollama_grid.borrow().as_ref() {
            grid.set_progress(model, "Starting…");
        }
        self.start_install(InstallSpec {
            kind: install_spec::KIND_OLLAMA_MODEL.to_string(),
            model: model.to_string(),
            host: host.trim().to_string(),
            ..Default::default()
        });
    }

    fn grid_for_kind(&self, kind: &str) -> Option<Rc<ModelRowGrid>> {
        match kind {
            install_spec::KIND_WHISPER_CPP_MODEL => self.wcpp_grid.borrow().clone(),
            install_spec::KIND_OLLAMA_MODEL => self.ollama_grid.borrow().clone(),
            _ => None,
        }
    }

    /// Daemon InstallProgress signal — update the model row.
    pub fn on_install_progress(&self, key: &str, text: &str) {
        let (kind, model) = split_key(key);
        if let Some(grid) = self.grid_for_kind(kind) {
            if !model.is_empty() {
                grid.set_progress(model, text);
            }
        }
    }

    /// Daemon InstallFinished signal — reflect the outcome.
    /// Returns an error message when the install failed so the caller can
    /// surface it (dialog/notification) instead of failing silently.
    pub fn on_install_finished(
        self: &Rc<Self>,
        key: &str,
        ok: bool,
        message: &str,
    ) -> Option<String> {
        let (kind, arg) = split_key(key);
        let failure = if message.trim().is_empty() {
            "Install failed. Check the logs for details.".to_string()
        } else {
            message.trim().to_string()
        };
        match kind {
            install_spec::KIND_OLLAMA => self.on_ollama_install_finished(ok, &failure),
            install_spec::KIND_WHISPER_CPP_ENGINE => self.on_wcpp_install_finished(ok, &failure),
            _ => {
                if let Some(grid) = self.grid_for_kind(kind) {
                    if !arg.is_empty() {
                        if ok {
                            grid.set_ready(arg);
                        } else {
                            grid.set_error(arg, &failure);
                        }
                    }
                }
            }
        }
        if !ok {
            let button = match kind {
                install_spec::KIND_OLLAMA => self.ollama_install_button.borrow().clone(),
                install_spec::KIND_WHISPER_CPP_ENGINE => self.wcpp_install_button.borrow().clone(),
                _ => None,
            };
            if let Some(b) = button {
                b.set_tooltip_text(Some(&failure));
            }
            Some(format_install_error(kind, arg, &failure))
        } else {
            None
        }
    }

    fn on_ollama_install_finished(self: &Rc<Self>, ok: bool, failure: &str) {
        if ok && OllamaInstaller::is_available() {
            let cfg = crate::config::settings::load();
            self.build_ollama_section(&cfg);
            self.refresh_local_model_statuses();
        } else if let Some(b) = self.ollama_install_button.borrow().as_ref() {
            b.set_sensitive(true);
            b.set_label("Retry Install");
            if !ok {
                if let Some(row) = self.ollama_install_row.borrow().as_ref() {
                    row.set_subtitle(&short_error(failure));
                }
                b.set_tooltip_text(Some(failure));
            }
        }
    }

    fn on_wcpp_install_finished(self: &Rc<Self>, ok: bool, failure: &str) {
        if ok && WhisperCppEngineInstaller.is_installed() {
            let cfg = crate::config::settings::load();
            self.build_wcpp_section(&cfg);
            self.refresh_local_model_statuses();
        } else if let Some(b) = self.wcpp_install_button.borrow().as_ref() {
            b.set_sensitive(true);
            b.set_label("Retry Install");
            if !ok {
                if let Some(row) = self.wcpp_install_row.borrow().as_ref() {
                    row.set_subtitle(&short_error(failure));
                }
                b.set_tooltip_text(Some(failure));
            }
        }
    }

    /// On (re)open, show installs already running in the daemon as in-progress.
    pub fn reflect_running_installs(&self) {
        let Some(proxy) = &self.proxy else { return };
        let running: serde_json::Value =
            serde_json::from_str(&proxy.get_installs()).unwrap_or_default();
        let entries = running.as_array().cloned().unwrap_or_default();
        for entry in entries {
            let key = entry.get("key").and_then(|k| k.as_str()).unwrap_or("");
            let status = entry.get("status").and_then(|s| s.as_str()).unwrap_or("");
            self.reflect_running(key, status);
        }
    }

    fn reflect_running(&self, key: &str, status: &str) {
        let (kind, arg) = split_key(key);
        let button = match kind {
            install_spec::KIND_OLLAMA => {
                (self.ollama_install_button.borrow().clone(), "Installing…")
            }
            install_spec::KIND_WHISPER_CPP_ENGINE => {
                (self.wcpp_install_button.borrow().clone(), "Installing…")
            }
            _ => (None, ""),
        };
        if let (Some(b), label) = button {
            b.set_sensitive(false);
            b.set_label(label);
            return;
        }
        if let Some(grid) = self.grid_for_kind(kind) {
            if !arg.is_empty() {
                grid.set_progress(
                    arg,
                    if status.is_empty() {
                        "Downloading…"
                    } else {
                        status
                    },
                );
            }
        }
    }

    // ------------------------------------------------------------------
    // Background status checks (read-only probes; results hop to the loop)
    // ------------------------------------------------------------------

    pub fn refresh_local_model_statuses(self: &Rc<Self>) {
        self.check_wcpp_statuses();
        self.check_ollama_statuses();
    }

    fn check_wcpp_statuses(self: &Rc<Self>) {
        let (tx, rx) = std::sync::mpsc::channel::<Vec<(String, bool)>>();
        std::thread::spawn(move || {
            let checker = WhisperCppStatusChecker::default();
            let results: Vec<(String, bool)> = WHISPER_CPP_MODELS
                .iter()
                .map(|m| (m.to_string(), checker.is_cached(m)))
                .collect();
            let _ = tx.send(results);
        });
        let page = self.clone();
        glib::timeout_add_local(std::time::Duration::from_millis(50), move || {
            match rx.try_recv() {
                Ok(results) => {
                    if let Some(grid) = page.wcpp_grid.borrow().clone() {
                        for (model, ready) in &results {
                            if *ready {
                                grid.set_ready(model);
                            } else {
                                grid.set_not_downloaded(model);
                            }
                        }
                    }
                    glib::ControlFlow::Break
                }
                Err(std::sync::mpsc::TryRecvError::Disconnected) => glib::ControlFlow::Break,
                Err(std::sync::mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
            }
        });
    }

    fn check_ollama_statuses(self: &Rc<Self>) {
        if !OllamaInstaller::is_available() {
            return;
        }
        let host = self
            .ollama_host_entry
            .borrow()
            .as_ref()
            .map(|e| e.text().to_string())
            .unwrap_or_else(|| OLLAMA_DEFAULT_HOST.to_string());
        let (tx, rx) = std::sync::mpsc::channel::<Option<Vec<String>>>();
        std::thread::spawn(move || {
            let client = OllamaClient::new();
            let _ = tx.send(client.get_installed_models(&host));
        });
        let page = self.clone();
        glib::timeout_add_local(std::time::Duration::from_millis(50), move || {
            match rx.try_recv() {
                Ok(installed) => {
                    match installed {
                        None => page.set_ollama_unreachable(),
                        Some(list) => {
                            page.set_ollama_reachable();
                            let client = OllamaClient::new();
                            if let Some(grid) = page.ollama_grid.borrow().clone() {
                                for model in OLLAMA_MODELS {
                                    if client.is_model_installed(model, &list) {
                                        grid.set_ready(model);
                                    } else {
                                        grid.set_not_downloaded(model);
                                    }
                                }
                            }
                        }
                    }
                    glib::ControlFlow::Break
                }
                Err(std::sync::mpsc::TryRecvError::Disconnected) => glib::ControlFlow::Break,
                Err(std::sync::mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
            }
        });
    }

    fn set_ollama_unreachable(&self) {
        if let Some(row) = self.ollama_status_row.borrow().as_ref() {
            row.set_subtitle("Not running — starts automatically when needed, stops on app exit");
        }
        if let Some(grid) = self.ollama_grid.borrow().as_ref() {
            for model in OLLAMA_MODELS {
                grid.set_status_text(model, "Ollama offline");
            }
        }
    }

    fn set_ollama_reachable(&self) {
        if let Some(row) = self.ollama_status_row.borrow().as_ref() {
            row.set_subtitle("Ollama is running.");
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /// Write this tab's values into `cfg` (called by the dialog's save flow).
    pub fn apply(&self, cfg: &mut Config) {
        cfg.transcription_service = self
            .ts_combo
            .get_active_id()
            .unwrap_or_else(|| "whisper_cpp".to_string());
        cfg.summarization_service = self
            .ss_combo
            .get_active_id()
            .unwrap_or_else(|| "openai".to_string());
        cfg.openai_api_key = self.key_entry.text().to_string().trim().to_string();
        let base_url = self.base_url_entry.text().to_string();
        cfg.openai_base_url = if base_url.trim().is_empty() {
            OPENAI_DEFAULT_BASE_URL.to_string()
        } else {
            base_url.trim().to_string()
        };
        let stt = self.ts_model_entry.text().to_string();
        cfg.openai_transcription_model = if stt.trim().is_empty() {
            OPENAI_DEFAULT_STT_MODEL.to_string()
        } else {
            stt.trim().to_string()
        };
        let chat = self.ss_model_entry.text().to_string();
        cfg.openai_summarization_model = if chat.trim().is_empty() {
            OPENAI_DEFAULT_CHAT_MODEL.to_string()
        } else {
            chat.trim().to_string()
        };
        cfg.llm_request_timeout_minutes = self
            .timeout_combo
            .get_active_id()
            .and_then(|s| s.parse().ok())
            .unwrap_or(5);
        // These entries only exist once the opt-in engine is installed; preserve
        // the stored value otherwise.
        if let Some(e) = self.wcpp_model_entry.borrow().as_ref() {
            let v = e.text().to_string();
            cfg.whisper_cpp_model = if v.trim().is_empty() {
                WHISPER_CPP_MODELS[0].to_string()
            } else {
                v.trim().to_string()
            };
        }
        if let Some(c) = self.wcpp_backend_combo.borrow().as_ref() {
            cfg.whisper_cpp_backend = c.get_active_id().unwrap_or_else(|| "auto".to_string());
        }
        if let Some(e) = self.ollama_model_entry.borrow().as_ref() {
            let v = e.text().to_string();
            cfg.ollama_model = if v.trim().is_empty() {
                OLLAMA_MODELS[0].to_string()
            } else {
                v.trim().to_string()
            };
        }
        if let Some(e) = self.ollama_host_entry.borrow().as_ref() {
            let host = e.text().to_string();
            cfg.ollama_host = if host.trim().is_empty() {
                OLLAMA_DEFAULT_HOST.to_string()
            } else {
                host.trim().to_string()
            };
        }
    }
}

fn split_key(key: &str) -> (&str, &str) {
    match key.split_once(':') {
        Some((kind, arg)) => (kind, arg),
        None => (key, ""),
    }
}

/// Single-line truncation for inline row subtitles.
fn short_error(msg: &str) -> String {
    let one_line = msg.replace('\n', " ");
    let short: String = one_line.chars().take(120).collect();
    format!("Install failed: {short}")
}

/// Full sentence for dialogs/notifications.
fn format_install_error(kind: &str, arg: &str, failure: &str) -> String {
    let what = match kind {
        install_spec::KIND_WHISPER_CPP_ENGINE => "whisper.cpp engine install failed".to_string(),
        install_spec::KIND_OLLAMA => "Ollama install failed".to_string(),
        install_spec::KIND_WHISPER_CPP_MODEL => {
            format!("whisper.cpp model download failed ({arg})")
        }
        install_spec::KIND_OLLAMA_MODEL => format!("Ollama model download failed ({arg})"),
        other => format!("Install failed ({other})"),
    };
    format!("{what}: {failure}")
}
