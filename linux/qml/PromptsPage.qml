import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    property bool loaded: false
    Layout.fillWidth: true
    Layout.fillHeight: true

    function builtinTranscription() { return controller.transcriptionDefault() }
    function builtinSummarization() { return controller.summarizationDefault() }
    function builtinTitle() { return controller.titleDefault() }

    function readData() {
        try { cfg = JSON.parse(controller.settings_json) } catch (error) { cfg = {} }
        if (!loaded) {
            transcription.text = (cfg.transcription_prompt && cfg.transcription_prompt.length > 0) ? cfg.transcription_prompt : builtinTranscription()
            summarization.text = (cfg.summarization_prompt && cfg.summarization_prompt.length > 0) ? cfg.summarization_prompt : builtinSummarization()
            title.text = (cfg.title_prompt && cfg.title_prompt.length > 0) ? cfg.title_prompt : builtinTitle()
            loaded = true
        }
    }
    function isDefault(text, defText) {
        return text === defText
    }
    function storedValue(text, defText) {
        // Empty string means "use built-in default" on the backend.
        if (!text || text.trim().length === 0) return ""
        if (text === defText) return ""
        return text
    }
    function save() {
        // Partial patch: only the prompt keys this page owns.
        var c = {
            transcription_prompt: storedValue(transcription.text, builtinTranscription()),
            summarization_prompt: storedValue(summarization.text, builtinSummarization()),
            title_prompt: storedValue(title.text, builtinTitle())
        }
        controller.saveSettings(JSON.stringify(c), true)
        // Refresh local view to reflect stored-vs-default state.
        loaded = false
    }
    function resetAll() {
        transcription.text = builtinTranscription()
        summarization.text = builtinSummarization()
        title.text = builtinTitle()
    }

    Component.onCompleted: readData()
    property Connections settingsConnection: Connections {
        target: controller
        function onSettings_jsonChanged() {
            // Only auto-fill once; afterwards preserve user edits until Save.
            if (!root.loaded) root.readData()
            else {
                try { root.cfg = JSON.parse(controller.settings_json) } catch (e) { root.cfg = {} }
            }
        }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: column.implicitHeight
        clip: true
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: column
            width: root.width - 14
            spacing: 14
            Label { text: "Prompt templates"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
            Label { text: "Defaults are shown below. Edit to customize — Reset restores the built-in defaults. Empty is stored as default. Changes apply to the next job."; color: Theme.textMuted; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.pixelSize: 12 }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 8
                    Label { text: "Transcription prompt"; color: Theme.textSecondary; font.bold: true; font.pixelSize: 13 }
                    Label { text: "Used by OpenAI-compatible transcription only — local whisper.cpp ignores it."; color: Theme.textDim; font.pixelSize: 11 }
                    TextArea {
                        id: transcription
                        wrapMode: TextEdit.Wrap
                        Layout.fillWidth: true
                        Layout.preferredHeight: 180
                        color: Theme.textPrimary
                        font.pixelSize: 12
                        background: Rectangle { radius: Theme.radiusSm; color: Theme.inputBg; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle; border.width: parent.activeFocus ? 2 : 1 }
                    }
                }
            }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 8
                    Label { text: "Summarization prompt"; color: Theme.textSecondary; font.bold: true; font.pixelSize: 13 }
                    Label { text: "Use {transcript} where the transcript should be inserted — appended automatically if missing."; color: Theme.textDim; font.pixelSize: 11 }
                    TextArea {
                        id: summarization
                        wrapMode: TextEdit.Wrap
                        Layout.fillWidth: true
                        Layout.preferredHeight: 210
                        color: Theme.textPrimary
                        font.pixelSize: 12
                        background: Rectangle { radius: Theme.radiusSm; color: Theme.inputBg; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle; border.width: parent.activeFocus ? 2 : 1 }
                    }
                }
            }
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 8
                    Label { text: "Title prompt"; color: Theme.textSecondary; font.bold: true; font.pixelSize: 13 }
                    TextArea {
                        id: title
                        wrapMode: TextEdit.Wrap
                        Layout.fillWidth: true
                        Layout.preferredHeight: 120
                        color: Theme.textPrimary
                        font.pixelSize: 12
                        background: Rectangle { radius: Theme.radiusSm; color: Theme.inputBg; border.color: parent.activeFocus ? Theme.accent : Theme.borderSubtle; border.width: parent.activeFocus ? 2 : 1 }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true }
                        AppButton { text: "Reset defaults"; variant: "secondary"; onClicked: root.resetAll() }
                        AppButton { text: "Save prompts"; onClicked: root.save() }
                    }
                }
            }
        }
    }
}
