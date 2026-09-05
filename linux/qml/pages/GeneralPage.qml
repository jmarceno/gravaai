import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
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
            output_folder: outputFolder.text,
            recording_quality: quality.currentText,
            call_detection_enabled: callDetection.checked,
            start_at_startup: autostart.checked,
            auto_title: autoTitle.checked,
            processing_countdown_enabled: countdown.checked,
            auto_process_enabled: autoProcess.checked,
            low_memory_mode: lowMemory.checked,
            llm_request_timeout_minutes: Number(cfg.llm_request_timeout_minutes || 5),
            whisper_cpp_model: cfg.whisper_cpp_model || "large-v3-turbo",
            whisper_cpp_backend: cfg.whisper_cpp_backend || "auto",
            crisp_asr_model: cfg.crisp_asr_model || "nemotron-3.5-asr-0.6b-q8_0",
            crisp_asr_backend: cfg.crisp_asr_backend || "auto",
            ollama_model: cfg.ollama_model || "phi4-mini",
            ollama_host: cfg.ollama_host || "http://localhost:11434",
            custom_devices: cfg.custom_devices || [],
            transcription_prompt: cfg.transcription_prompt || "",
            summarization_prompt: cfg.summarization_prompt || "",
            title_prompt: cfg.title_prompt || ""
        }
        controller.saveSettings(JSON.stringify(c), false)
    }
    function qualityIndex(value) {
        var values = ["low", "medium", "high", "very_high"]
        var i = values.indexOf(value)
        return i < 0 ? 2 : i
    }

    Component.onCompleted: readData()
    property Connections settingsConnection: Connections {
        target: controller
        function onSettings_jsonChanged() { root.readData() }
    }
    property FolderDialog folderDialog: FolderDialog {
        title: "Choose output folder"
        onAccepted: outputFolder.text = selectedFolder.toLocalFile()
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
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 6
                    Label { text: "Recording"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    AppSwitch { id: autostart; text: "Start daemon with the desktop"; checked: root.cfg.start_at_startup || false; Layout.fillWidth: true }
                    AppSwitch { id: callDetection; text: "Detect calls and notify me"; checked: root.cfg.call_detection_enabled || false; Layout.fillWidth: true }
                    AppSwitch { id: autoTitle; text: "Generate meeting titles automatically"; checked: root.cfg.auto_title !== false; Layout.fillWidth: true }
                    AppSwitch { id: autoProcess; text: "Automatically transcribe and summarize after stopping"; checked: root.cfg.auto_process_enabled !== false; Layout.fillWidth: true }
                    AppSwitch { id: countdown; text: "Show processing countdown"; checked: root.cfg.processing_countdown_enabled || false; Layout.fillWidth: true }
                    AppSwitch { id: lowMemory; text: "Low memory mode (exit window when closed)"; checked: root.cfg.low_memory_mode || false; Layout.fillWidth: true }
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.topMargin: 6
                        spacing: 10
                        Label { text: "Quality"; color: Theme.textSecondary; Layout.preferredWidth: 120 }
                        AppComboBox { id: quality; model: ["low", "medium", "high", "very_high"]; currentIndex: root.qualityIndex(root.cfg.recording_quality || "high"); Layout.fillWidth: true }
                    }
                }
            }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 10
                    Label { text: "Storage"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        AppField { id: outputFolder; label: "Default output folder"; text: root.cfg.output_folder || "~/meetings"; Layout.fillWidth: true }
                        AppButton { text: "Browse"; variant: "secondary"; Layout.alignment: Qt.AlignBottom; onClicked: folderDialog.open() }
                    }
                    Label { text: "Recordings remain on disk when the UI or daemon is upgraded."; color: Theme.textMuted; font.pixelSize: 12 }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        AppButton { text: "Save general settings"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
