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
                    Label { text: "Transcription"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Service"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        ComboBox { id: stt; model: ["openai", "whisper_cpp"]; currentIndex: root.indexOfValue(model, root.cfg.transcription_service || "whisper_cpp"); Layout.fillWidth: true }
                    }
                    AppField { id: apiKey; label: "OpenAI-compatible API key"; password: true; text: root.cfg.openai_api_key || ""; visible: stt.currentText === "openai"; Layout.fillWidth: true }
                    AppField { id: baseUrl; label: "Base URL"; placeholderText: "https://api.openai.com/v1"; text: root.cfg.openai_base_url || ""; visible: stt.currentText === "openai" || chat.currentText === "openai"; Layout.fillWidth: true }
                    AppField { id: sttModel; label: "Speech-to-text model"; text: root.cfg.openai_transcription_model || "whisper-1"; visible: stt.currentText === "openai"; Layout.fillWidth: true }
                    RowLayout {
                        visible: stt.currentText === "whisper_cpp"
                        Layout.fillWidth: true
                        Label { text: "Model"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        ComboBox { id: whisperModel; model: ["large-v3-turbo", "large-v3", "medium", "small"]; currentIndex: root.indexOfValue(model, root.cfg.whisper_cpp_model || "large-v3-turbo"); Layout.fillWidth: true }
                        ComboBox { id: whisperBackend; model: ["auto", "cpu", "cuda"]; currentIndex: root.indexOfValue(model, root.cfg.whisper_cpp_backend || "auto"); Layout.fillWidth: true }
                    }
                    RowLayout {
                        visible: stt.currentText === "whisper_cpp"
                        Layout.fillWidth: true
                        Label { text: "Engine"; color: Theme.textMuted; Layout.preferredWidth: 120 }
                        Button { text: "Install whisper.cpp"; onClicked: root.install("whisper_cpp_engine", "", whisperBackend.currentText, "") }
                        Button { text: "Download model"; onClicked: root.install("whisper_cpp_model", whisperModel.currentText, "", "") }
                    }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Summarization"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Service"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        ComboBox { id: chat; model: ["openai", "ollama"]; currentIndex: root.indexOfValue(model, root.cfg.summarization_service || "openai"); Layout.fillWidth: true }
                    }
                    AppField { id: chatModel; label: "Chat model"; text: root.cfg.openai_summarization_model || "gpt-5.6-luna"; visible: chat.currentText === "openai"; Layout.fillWidth: true }
                    RowLayout {
                        visible: chat.currentText === "ollama"
                        Layout.fillWidth: true
                        Label { text: "Model"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        ComboBox { id: ollamaModel; model: ["phi4-mini", "gemma3:4b", "qwen2.5:7b", "llama3.1:8b", "gemma3:12b"]; currentIndex: root.indexOfValue(model, root.cfg.ollama_model || "phi4-mini"); Layout.fillWidth: true }
                    }
                    AppField { id: ollamaHost; label: "Ollama host"; text: root.cfg.ollama_host || "http://localhost:11434"; visible: chat.currentText === "ollama"; Layout.fillWidth: true }
                    RowLayout {
                        visible: chat.currentText === "ollama"
                        Layout.fillWidth: true
                        Button { text: "Install Ollama"; onClicked: root.install("ollama", "", "", "") }
                        Button { text: "Download model"; onClicked: root.install("ollama_model", ollamaModel.currentText, "", ollamaHost.text) }
                    }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Request timeout"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Minutes"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        ComboBox { id: timeout; model: ["1", "2", "3", "5", "8", "10"]; currentIndex: root.indexOfValue(model, String(root.cfg.llm_request_timeout_minutes || 5)); Layout.fillWidth: true }
                    }
                    Label { text: root.installs.length > 0 ? root.installs.map(function(i) { return i.key + ": " + i.status }).join("\n") : "No installs running."; color: Theme.textMuted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        Button { text: "Refresh installs"; onClicked: controller.refreshInstalls() }
                        AppButton { text: "Save model settings"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
