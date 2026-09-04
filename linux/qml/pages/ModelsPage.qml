import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    property var installs: []
    Layout.fillWidth: true
    Layout.fillHeight: true

    function readData() {
        try { root.cfg = JSON.parse(controller.settings_json) } catch (error) { root.cfg = {} }
        try { root.installs = JSON.parse(controller.installs_json) } catch (error2) { root.installs = [] }
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

    Component.onCompleted: readData()
    property Connections controllerConnection: Connections {
        target: controller
        function onSettings_jsonChanged() { root.readData() }
        function onInstalls_jsonChanged() { root.readData() }
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
                        AppComboBox { id: stt; model: ["openai", "whisper_cpp"]; currentIndex: root.indexOfValue(["openai", "whisper_cpp"], root.cfg.transcription_service || "whisper_cpp"); Layout.fillWidth: true }
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
                        AppComboBox { id: ollamaModel; model: ["phi4-mini", "gemma3:4b", "qwen2.5:7b", "llama3.1:8b", "gemma3:12b"]; currentIndex: root.indexOfValue(["phi4-mini", "gemma3:4b", "qwen2.5:7b", "llama3.1:8b", "gemma3:12b"], root.cfg.ollama_model || "phi4-mini"); Layout.fillWidth: true }
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
                    Label { text: "Request timeout"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label { text: "Minutes"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: timeout; model: ["1", "2", "3", "5", "8", "10"]; currentIndex: root.indexOfValue(["1", "2", "3", "5", "8", "10"], String(root.cfg.llm_request_timeout_minutes || 5)); Layout.fillWidth: true }
                    }
                    Label { text: root.installStatusText(); color: Theme.textMuted; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.pixelSize: 12 }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        AppButton { text: "Refresh installs"; variant: "secondary"; onClicked: controller.refreshInstalls() }
                        AppButton { text: "Save model settings"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
