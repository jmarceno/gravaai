import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt.labs.platform as Platform
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    readonly property var qualities: [
        { value: "low", label: "Low · ~64 kbps" },
        { value: "medium", label: "Medium · ~100 kbps" },
        { value: "high", label: "High · ~130 kbps (default)" },
        { value: "very_high", label: "Very high · ~190 kbps" }
    ]

    function readData() {
        try { cfg = JSON.parse(controller.settings_json) } catch (error) { cfg = {} }
    }
    function patch(values) {
        controller.saveSettings(JSON.stringify(values), true)
    }
    function qualityIndex(value) {
        for (var i = 0; i < qualities.length; i += 1)
            if (qualities[i].value === value) return i
        return 2
    }

    Component.onCompleted: readData()
    property Connections settingsConnection: Connections {
        target: root.controller
        function onSettings_jsonChanged() { root.readData() }
    }
    property Platform.FolderDialog folderDialog: Platform.FolderDialog {
        title: "Choose output folder"
        onAccepted: {
            var path = decodeURIComponent(String(folder).replace(/^file:\/\//, ""))
            outputFolder.text = path
            root.patch({ output_folder: path })
        }
    }

    component SettingSwitch: AppSwitch {
        property string key: ""
        property bool defaultValue: false
        Layout.fillWidth: true
        checked: root.cfg[key] === undefined ? defaultValue : !!root.cfg[key]
        onToggled: {
            var p = {}
            p[key] = checked
            root.patch(p)
        }
    }

    Flickable {
        id: flick
        anchors.fill: parent
        contentWidth: width
        contentHeight: column.implicitHeight + 8
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: flick.contentHeight > flick.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff }
        ColumnLayout {
            id: column
            width: flick.width - 14
            spacing: 14
            AppCard {
                Layout.fillWidth: true
                Label { text: "After recording"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                SettingSwitch { key: "auto_process_enabled"; defaultValue: true; text: "Transcribe and summarize automatically when a recording stops" }
                SettingSwitch { key: "auto_title"; defaultValue: true; text: "Name meetings automatically from their notes" }
                SettingSwitch { key: "processing_countdown_enabled"; text: "Wait a few seconds before processing (time to cancel)" }
            }
            AppCard {
                Layout.fillWidth: true
                Label { text: "Recording"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    Label { text: "Audio quality"; color: Theme.textSecondary; font.pixelSize: 12; Layout.preferredWidth: 110 }
                    AppComboBox {
                        Layout.fillWidth: true
                        model: root.qualities.map(function(q) { return q.label })
                        currentIndex: root.qualityIndex(root.cfg.recording_quality || "high")
                        onActivated: function(i) { root.patch({ recording_quality: root.qualities[i].value }) }
                    }
                }
                SettingSwitch { key: "show_recording_pill"; defaultValue: true; text: "Show the floating recording pill" }
                SettingSwitch { key: "call_detection_enabled"; text: "Notify me when a call starts using the microphone" }
            }
            AppCard {
                Layout.fillWidth: true
                Label { text: "Storage"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    AppField {
                        id: outputFolder
                        Layout.fillWidth: true
                        label: "Recordings folder"
                        text: root.cfg.output_folder || "~/meetings"
                        onEditingFinished: if (text.trim().length > 0 && text.trim() !== root.cfg.output_folder) root.patch({ output_folder: text.trim() })
                    }
                    AppButton { text: "Browse…"; variant: "secondary"; Layout.alignment: Qt.AlignBottom; onClicked: root.folderDialog.open() }
                }
                Label { text: "Each meeting gets its own folder with recording.mp3, transcript.md and notes.md."; color: Theme.textMuted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            }
            AppCard {
                Layout.fillWidth: true
                Label { text: "Background"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                SettingSwitch { key: "start_at_startup"; text: "Start GravaAI when I log in" }
                SettingSwitch { key: "low_memory_mode"; text: "Low memory mode (fully close the window instead of hiding it)" }
            }
            Label { text: "Changes are saved automatically."; color: Theme.textDim; font.pixelSize: 11 }
        }
    }
}
