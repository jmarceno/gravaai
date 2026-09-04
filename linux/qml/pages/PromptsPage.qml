import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    function readData() {
        try { cfg = JSON.parse(controller.settings_json) } catch (error) { cfg = {} }
    }
    function save() {
        var c = {
            transcription_service: cfg.transcription_service || "whisper_cpp",
            summarization_service: cfg.summarization_service || "openai",
            openai_api_key: cfg.openai_api_key || "",
            openai_base_url: cfg.openai_base_url || "https://api.openai.com/v1",
            openai_transcription_model: cfg.openai_transcription_model || "whisper-1",
            openai_summarization_model: cfg.openai_summarization_model || "gpt-5.6-luna",
            output_folder: cfg.output_folder || "~/meetings",
            recording_quality: cfg.recording_quality || "high",
            call_detection_enabled: cfg.call_detection_enabled || false,
            start_at_startup: cfg.start_at_startup || false,
            auto_title: cfg.auto_title !== false,
            processing_countdown_enabled: cfg.processing_countdown_enabled || false,
            auto_process_enabled: cfg.auto_process_enabled !== false,
            low_memory_mode: cfg.low_memory_mode || false,
            llm_request_timeout_minutes: Number(cfg.llm_request_timeout_minutes || 5),
            whisper_cpp_model: cfg.whisper_cpp_model || "large-v3-turbo",
            whisper_cpp_backend: cfg.whisper_cpp_backend || "auto",
            ollama_model: cfg.ollama_model || "phi4-mini",
            ollama_host: cfg.ollama_host || "http://localhost:11434",
            transcription_prompt: transcription.text,
            summarization_prompt: summarization.text,
            title_prompt: title.text
        }
        controller.saveSettings(JSON.stringify(c), false)
    }
    function resetAll() {
        transcription.text = ""
        summarization.text = ""
        title.text = ""
    }

    Component.onCompleted: readData()
    property Connections settingsConnection: Connections {
        target: controller
        function onSettings_jsonChanged() { root.readData() }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: column.implicitHeight
        clip: true
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: column
            width: root.width
            spacing: 14
            Label { text: "Prompt templates"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
            Label { text: "Leave a prompt empty to use the built-in default. Changes apply to the next job."; color: Theme.textMuted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Transcription prompt"; color: Theme.textSecondary; font.bold: true }
                    TextArea { id: transcription; text: root.cfg.transcription_prompt || ""; placeholderText: "Built-in speaker-labelled transcription prompt"; wrapMode: TextEdit.Wrap; Layout.fillWidth: true; Layout.preferredHeight: 130; color: Theme.textPrimary; background: Rectangle { color: Theme.inputBg; radius: Theme.radiusSm; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle } }
                }
            }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Summarization prompt"; color: Theme.textSecondary; font.bold: true }
                    TextArea { id: summarization; text: root.cfg.summarization_prompt || ""; placeholderText: "Built-in executive meeting-notes prompt"; wrapMode: TextEdit.Wrap; Layout.fillWidth: true; Layout.preferredHeight: 160; color: Theme.textPrimary; background: Rectangle { color: Theme.inputBg; radius: Theme.radiusSm; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle } }
                }
            }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Title prompt"; color: Theme.textSecondary; font.bold: true }
                    TextArea { id: title; text: root.cfg.title_prompt || ""; placeholderText: "Built-in concise title prompt"; wrapMode: TextEdit.Wrap; Layout.fillWidth: true; Layout.preferredHeight: 100; color: Theme.textPrimary; background: Rectangle { color: Theme.inputBg; radius: Theme.radiusSm; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle } }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        Button { text: "Reset defaults"; onClicked: root.resetAll() }
                        AppButton { text: "Save prompts"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
