import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    property var installs: []
    property var status: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    function readData() {
        try { root.cfg = JSON.parse(controller.settings_json) } catch (error) { root.cfg = {} }
        try { root.installs = JSON.parse(controller.installs_json) } catch (error2) { root.installs = [] }
        readStatus()
    }
    function readStatus() {
        try { root.status = JSON.parse(controller.engine_status_json) } catch (error) { root.status = {} }
    }
    function install(kind, model, backend, host) {
        controller.startInstall(JSON.stringify({kind: kind || "", model: model || "", backend: backend || "", host: host || ""}))
        controller.refreshInstalls()
    }
    function save() {
        var c = {
            transcription_service: stt.currentText,
            summarization_service: chat.currentText,
            openai_api_key: apiKey.text,
            openai_base_url: baseUrl.text,
            openai_transcription_model: sttModel.text,
            openai_summarization_model: chatModel.text,
            output_folder: root.cfg.output_folder || "~/meetings",
            recording_quality: root.cfg.recording_quality || "high",
            call_detection_enabled: root.cfg.call_detection_enabled || false,
            start_at_startup: root.cfg.start_at_startup || false,
            auto_title: root.cfg.auto_title !== false,
            processing_countdown_enabled: root.cfg.processing_countdown_enabled || false,
            auto_process_enabled: root.cfg.auto_process_enabled !== false,
            low_memory_mode: root.cfg.low_memory_mode || false,
            llm_request_timeout_minutes: Number(timeout.currentText || 5),
            whisper_cpp_model: whisperModel.currentText,
            whisper_cpp_backend: whisperBackend.currentText,
            crisp_asr_model: crispModel.currentText,
            crisp_asr_backend: crispBackend.currentText,
            ollama_model: ollamaModel.currentText,
            ollama_host: ollamaHost.text,
            transcription_prompt: root.cfg.transcription_prompt || "",
            summarization_prompt: root.cfg.summarization_prompt || "",
            title_prompt: root.cfg.title_prompt || ""
        }
        controller.saveSettings(JSON.stringify(c), false)
    }
    function indexOfValue(values, value) {
        var i = values.indexOf(value)
        return i < 0 ? 0 : i
    }
    function installStatusText() {
        if (root.installs.length === 0) return "No installs running."
        return root.installs.map(function(i) { return (i.key || "?") + ": " + (i.status || "running") }).join("\n")
    }
    function fmtSize(bytes) {
        var b = Number(bytes || 0)
        if (b <= 0) return ""
        if (b < 1048576) return Math.max(1, Math.round(b / 1024)) + " KB"
        if (b < 1073741824) return (b / 1048576).toFixed(1) + " MB"
        return (b / 1073741824).toFixed(2) + " GB"
    }
    function whisperStatus() {
        var w = root.status.whisper || {}
        if (!w.engine_path) return "Checking…"
        return w.engine_installed ? "Installed · " + root.fmtSize(w.engine_size_bytes) : "Not installed"
    }
    function ggmlModels() {
        return (root.status.payloads || []).filter(function(p) { return p.kind === "model" && p.name.indexOf("ggml-") === 0 })
    }
    function crispModels() {
        return (root.status.payloads || []).filter(function(p) { return p.kind === "model" && p.name.indexOf(".gguf") >= 0 })
    }
    function crispStatus() {
        var c = root.status.crispasr || {}
        if (!c.engine_path) return "Checking…"
        return c.engine_installed ? "Installed · " + root.fmtSize(c.engine_size_bytes) : "Not installed"
    }
    function ollamaModels() {
        return (root.status.ollama || {}).models || []
    }
    function ollamaStatus() {
        var o = root.status.ollama || {}
        if (!root.status.base_dir) return "Checking…"
        if (!o.installed) return "Not installed"
        if (o.serving) return "Running at " + (o.host || "http://localhost:11434") + " · " + root.ollamaModels().length + " model(s)"
        return "Installed — the server starts automatically when a job needs it"
    }

    Component.onCompleted: readData()
    property Connections controllerConnection: Connections {
        target: controller
        function onSettings_jsonChanged() { root.readData() }
        function onInstalls_jsonChanged() { root.readData() }
        function onEngine_status_jsonChanged() { root.readStatus() }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.implicitHeight
        clip: true
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: contentColumn
            width: root.width
            spacing: 14

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 10
                    Label { text: "Transcription"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Service"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: stt; model: ["openai", "whisper_cpp", "crisp_asr"]; currentIndex: root.indexOfValue(["openai", "whisper_cpp", "crisp_asr"], root.cfg.transcription_service || "whisper_cpp"); Layout.fillWidth: true }
                    }
                    AppField { id: apiKey; label: "OpenAI-compatible API key"; password: true; text: root.cfg.openai_api_key || ""; visible: stt.currentText === "openai"; Layout.fillWidth: true }
                    AppField { id: baseUrl; label: "Base URL"; placeholderText: "https://api.openai.com/v1"; text: root.cfg.openai_base_url || ""; visible: stt.currentText === "openai" || chat.currentText === "openai"; Layout.fillWidth: true }
                    AppField { id: sttModel; label: "Speech-to-text model"; text: root.cfg.openai_transcription_model || "whisper-1"; visible: stt.currentText === "openai"; Layout.fillWidth: true }
                    RowLayout {
                        visible: stt.currentText === "whisper_cpp"
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Model"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: whisperModel; model: ["large-v3-turbo", "large-v3", "medium", "small"]; currentIndex: root.indexOfValue(["large-v3-turbo", "large-v3", "medium", "small"], root.cfg.whisper_cpp_model || "large-v3-turbo"); Layout.fillWidth: true }
                        AppComboBox { id: whisperBackend; model: ["auto", "cpu", "cuda"]; currentIndex: root.indexOfValue(["auto", "cpu", "cuda"], root.cfg.whisper_cpp_backend || "auto"); Layout.fillWidth: true }
                    }
                    RowLayout {
                        visible: stt.currentText === "whisper_cpp"
                        Layout.fillWidth: true
                        spacing: 8
                        Label { text: "Engine"; color: Theme.textMuted; Layout.preferredWidth: 120 }
                        AppButton { text: "Install whisper.cpp"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("whisper_cpp_engine", "", whisperBackend.currentText, "") }
                        AppButton { text: "Download model"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("whisper_cpp_model", whisperModel.currentText, "", "") }
                    }
                    Label { visible: stt.currentText === "whisper_cpp"; text: "CPU prebuilt for Linux — no compiler needed. Upstream ships no CUDA prebuilt for Linux."; color: Theme.textDim; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                    RowLayout {
                        visible: stt.currentText === "crisp_asr"
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Model"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: crispModel; model: ["nemotron-3.5-asr-0.6b-q8_0", "nemotron-3.5-asr-0.6b-q6_k", "nemotron-3.5-asr-0.6b-q5_k_m", "nemotron-3.5-asr-0.6b-q4_k_m", "nemotron-3.5-asr-0.6b-f16"]; currentIndex: root.indexOfValue(["nemotron-3.5-asr-0.6b-q8_0", "nemotron-3.5-asr-0.6b-q6_k", "nemotron-3.5-asr-0.6b-q5_k_m", "nemotron-3.5-asr-0.6b-q4_k_m", "nemotron-3.5-asr-0.6b-f16"], root.cfg.crisp_asr_model || "nemotron-3.5-asr-0.6b-q8_0"); Layout.fillWidth: true }
                        AppComboBox { id: crispBackend; model: ["auto", "cpu", "vulkan", "cuda"]; currentIndex: root.indexOfValue(["auto", "cpu", "vulkan", "cuda"], root.cfg.crisp_asr_backend || "auto"); Layout.fillWidth: true }
                    }
                    RowLayout {
                        visible: stt.currentText === "crisp_asr"
                        Layout.fillWidth: true
                        spacing: 8
                        Label { text: "Engine"; color: Theme.textMuted; Layout.preferredWidth: 120 }
                        AppButton { text: "Install CrispASR"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("crisp_asr_engine", "", crispBackend.currentText, "") }
                        AppButton { text: "Download model"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("crisp_asr_model", crispModel.currentText, "", "") }
                    }
                    Label { visible: stt.currentText === "crisp_asr"; text: "Experimental. Prebuilt, no compiler needed — CPU ~25 MB, Vulkan ~60 MB, CUDA ~206–271 MB. Auto picks CUDA on NVIDIA, CPU elsewhere."; color: Theme.textDim; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 10
                    Label { text: "Summarization"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Service"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: chat; model: ["openai", "ollama"]; currentIndex: root.indexOfValue(["openai", "ollama"], root.cfg.summarization_service || "openai"); Layout.fillWidth: true }
                    }
                    AppField { id: chatModel; label: "Chat model"; text: root.cfg.openai_summarization_model || "gpt-5.6-luna"; visible: chat.currentText === "openai"; Layout.fillWidth: true }
                    RowLayout {
                        visible: chat.currentText === "ollama"
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Model"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: ollamaModel; model: ["phi4-mini", "gemma3:4b", "qwen2.5:7b", "llama3.1:8b", "gemma3:12b", "jewelzufo/granite-4.0-h-350m-base-GGUF:Q8_0"]; currentIndex: root.indexOfValue(["phi4-mini", "gemma3:4b", "qwen2.5:7b", "llama3.1:8b", "gemma3:12b", "jewelzufo/granite-4.0-h-350m-base-GGUF:Q8_0"], root.cfg.ollama_model || "phi4-mini"); Layout.fillWidth: true }
                    }
                    AppField { id: ollamaHost; label: "Ollama host"; text: root.cfg.ollama_host || "http://localhost:11434"; visible: chat.currentText === "ollama"; Layout.fillWidth: true }
                    RowLayout {
                        visible: chat.currentText === "ollama"
                        Layout.fillWidth: true
                        spacing: 8
                        AppButton { text: "Install Ollama"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("ollama", "", "", "") }
                        AppButton { text: "Download model"; variant: "secondary"; implicitHeight: 34; onClicked: root.install("ollama_model", ollamaModel.currentText, "", ollamaHost.text) }
                    }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 10
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Status"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true; Layout.fillWidth: true }
                        AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 30; onClicked: controller.refreshEngineStatus() }
                    }
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        Label { text: "whisper.cpp (transcription)"; color: Theme.textSecondary; font.pixelSize: 13; font.bold: true }
                        Label { text: root.whisperStatus(); color: Theme.textPrimary; font.pixelSize: 12 }
                        Label { text: (root.status.whisper || {}).engine_path || ""; visible: (root.status.whisper || {}).engine_installed; color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                    }
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        Label { text: "GGML models"; color: Theme.textSecondary; font.pixelSize: 13; font.bold: true }
                        Label {
                            visible: root.ggmlModels().length === 0
                            text: "No models downloaded."
                            color: Theme.textMuted; font.pixelSize: 12
                        }
                        Repeater {
                            model: root.ggmlModels()
                            delegate: RowLayout {
                                required property var modelData
                                Layout.fillWidth: true
                                spacing: 8
                                Label { text: modelData.name; color: Theme.textPrimary; font.pixelSize: 12; Layout.fillWidth: true; elide: Text.ElideRight }
                                Label { text: root.fmtSize(modelData.size_bytes); color: Theme.textMuted; font.pixelSize: 11 }
                            }
                        }
                        Label { text: "Models come from HuggingFace: " + ((root.status.whisper || {}).models_url || ""); color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                    }
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        Label { text: "CrispASR (transcription, experimental)"; color: Theme.textSecondary; font.pixelSize: 13; font.bold: true }
                        Label { text: root.crispStatus(); color: Theme.textPrimary; font.pixelSize: 12 }
                        Label { text: (root.status.crispasr || {}).engine_path || ""; visible: (root.status.crispasr || {}).engine_installed; color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                        Label {
                            visible: root.crispModels().length === 0
                            text: "No models downloaded."
                            color: Theme.textMuted; font.pixelSize: 12
                        }
                        Repeater {
                            model: root.crispModels()
                            delegate: RowLayout {
                                required property var modelData
                                Layout.fillWidth: true
                                spacing: 8
                                Label { text: modelData.name; color: Theme.textPrimary; font.pixelSize: 12; Layout.fillWidth: true; elide: Text.ElideRight }
                                Label { text: root.fmtSize(modelData.size_bytes); color: Theme.textMuted; font.pixelSize: 11 }
                            }
                        }
                        Label { text: "Models come from HuggingFace: " + ((root.status.crispasr || {}).models_url || ""); color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                    }
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        Label { text: "Ollama (summarization)"; color: Theme.textSecondary; font.pixelSize: 13; font.bold: true }
                        Label { text: root.ollamaStatus(); color: Theme.textPrimary; font.pixelSize: 12 }
                        Label { text: (root.status.ollama || {}).binary_path || ""; visible: (root.status.ollama || {}).installed; color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                        Repeater {
                            model: root.ollamaModels()
                            delegate: RowLayout {
                                required property var modelData
                                Layout.fillWidth: true
                                spacing: 8
                                Label { text: modelData.name; color: Theme.textPrimary; font.pixelSize: 12; Layout.fillWidth: true; elide: Text.ElideRight }
                                Label { text: root.fmtSize(modelData.size); color: Theme.textMuted; font.pixelSize: 11 }
                            }
                        }
                    }
                    Label { text: root.installStatusText(); color: Theme.textMuted; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.pixelSize: 12 }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 10
                    Label { text: "Request timeout"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Minutes"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: timeout; model: ["1", "2", "3", "5", "8", "10"]; currentIndex: root.indexOfValue(["1", "2", "3", "5", "8", "10"], String(root.cfg.llm_request_timeout_minutes || 5)); Layout.fillWidth: true }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        AppButton { text: "Save model settings"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
