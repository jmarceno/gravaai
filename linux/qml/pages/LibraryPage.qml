import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var meetings: []
    property var selected: ({})
    property int selectedCount: 0
    property string renamePath: ""
    Layout.fillWidth: true
    Layout.fillHeight: true

    function refresh() {
        try { meetings = JSON.parse(controller.meetings_json) }
        catch (error) { meetings = [] }
    }
    function toggleSelected(path, checked) {
        var s = {}
        for (var k in selected) s[k] = selected[k]
        if (checked) s[path] = true
        else delete s[path]
        selected = s
        var n = 0
        for (var k2 in s) n += 1
        selectedCount = n
    }
    function isSelected(path) {
        return selected[path] === true
    }
    function selectedList() {
        var out = []
        for (var k in selected) out.push(k)
        return out
    }
    function clearSelection() {
        selected = {}
        selectedCount = 0
    }
    function durationLabel(secs) {
        var s = Number(secs || 0)
        if (s <= 0) return ""
        if (s < 60) return s + "s"
        var m = Math.floor(s / 60)
        if (m < 60) return m + "m"
        return Math.floor(m / 60) + "h " + (m % 60) + "m"
    }
    function displayTitle(m) {
        if (m.title && m.title.length > 0) return m.title
        return m.time_label || "Meeting"
    }
    function audioFor(m) {
        return m.audio_path || (m.path + "/recording.mp3")
    }
    function transcriptFor(m) {
        return m.transcript_path || (m.path + "/transcript.md")
    }
    function notesFor(m) {
        return m.notes_path || (m.path + "/notes.md")
    }
    function resummarize(m) {
        controller.summarizeMeeting(audioFor(m), transcriptFor(m), notesFor(m), displayTitle(m))
    }
    function transcribe(m) {
        controller.transcribeMeeting(audioFor(m), transcriptFor(m), notesFor(m), displayTitle(m))
    }

    Component.onCompleted: refresh()
    property Connections controllerConnection: Connections {
        target: controller
        function onMeetings_jsonChanged() { root.refresh() }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Label { text: "Past meetings"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
            Label { visible: root.selectedCount > 0; text: root.selectedCount + " selected"; color: Theme.textMuted; font.pixelSize: 12 }
            AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 34; onClicked: controller.refreshMeetings() }
            AppButton {
                text: root.selectedCount > 0 ? ("Delete (" + root.selectedCount + ")") : "Delete selected"
                variant: "secondary"
                implicitHeight: 34
                enabled: root.selectedCount > 0
                opacity: enabled ? 1.0 : 0.5
                onClicked: {
                    controller.deleteMeetings(JSON.stringify(root.selectedList()))
                    root.clearSelection()
                }
            }
            AppButton { text: "Open folder"; variant: "secondary"; implicitHeight: 34; onClicked: controller.openOutputFolder() }
        }
        Label {
            visible: root.meetings.length === 0
            text: "No meetings yet. Completed recordings will appear here."
            color: Theme.textMuted
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            Layout.topMargin: 32
        }
        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: width
            contentHeight: listColumn.implicitHeight
            clip: true
            ScrollBar.vertical: ScrollBar {}
            Column {
                id: listColumn
                width: parent.width
                spacing: 10
                Repeater {
                    model: root.meetings
                    delegate: AppCard {
                        required property var modelData
                        required property int index
                        property string mpath: modelData.path || ""
                        width: listColumn.width
                        ColumnLayout {
                            spacing: 10
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 10
                                AppCheckBox {
                                    text: ""
                                    checked: root.isSelected(mpath)
                                    onToggled: root.toggleSelected(mpath, checked)
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Label { text: root.displayTitle(modelData); color: Theme.textPrimary; font.pixelSize: 14; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                    Label {
                                        text: (modelData.time_label || "") + (modelData.duration_seconds ? " · " + root.durationLabel(modelData.duration_seconds) : "")
                                        color: Theme.textMuted; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true
                                    }
                                }
                                StatusBadge {
                                    labelText: modelData.has_notes ? "Notes" : (modelData.has_transcript ? "Transcript" : (modelData.has_audio ? "Audio only" : "Empty"))
                                    dotColor: modelData.has_notes ? Theme.statusGreen : (modelData.has_audio || modelData.has_transcript ? Theme.accentStrong : Theme.warning)
                                    pillBg: modelData.has_notes ? Theme.statusGreenBg : Theme.accentSoft
                                }
                            }
                            Label {
                                text: modelData.path || ""
                                color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle
                                Layout.fillWidth: true
                            }
                            Flow {
                                Layout.fillWidth: true
                                spacing: 8
                                AppButton {
                                    text: "Transcribe"
                                    variant: "teal"
                                    implicitHeight: 32
                                    visible: !modelData.has_transcript
                                    enabled: modelData.has_audio !== false
                                    opacity: enabled ? 1.0 : 0.5
                                    onClicked: root.transcribe(modelData)
                                }
                                AppButton {
                                    text: "Summarize"
                                    variant: "teal"
                                    implicitHeight: 32
                                    enabled: modelData.has_transcript || modelData.has_audio !== false
                                    opacity: enabled ? 1.0 : 0.5
                                    onClicked: root.resummarize(modelData)
                                }
                                AppButton {
                                    text: "Transcript"
                                    variant: "secondary"
                                    implicitHeight: 32
                                    enabled: modelData.has_transcript
                                    opacity: enabled ? 1.0 : 0.45
                                    onClicked: root.controller.openFile(root.transcriptFor(modelData))
                                }
                                AppButton {
                                    text: "Notes"
                                    variant: "secondary"
                                    implicitHeight: 32
                                    enabled: modelData.has_notes
                                    opacity: enabled ? 1.0 : 0.45
                                    onClicked: root.controller.openFile(root.notesFor(modelData))
                                }
                                AppButton { text: "Rename"; variant: "secondary"; implicitHeight: 32; onClicked: root.renamePath = (root.renamePath === mpath ? "" : mpath) }
                                AppButton { text: "Open"; variant: "secondary"; implicitHeight: 32; onClicked: controller.openMeetingFolder(mpath) }
                            }
                            RowLayout {
                                visible: root.renamePath === mpath
                                Layout.fillWidth: true
                                spacing: 8
                                TextField {
                                    id: renameInput
                                    Layout.fillWidth: true
                                    implicitHeight: 36
                                    placeholderText: "New title"
                                    color: Theme.textPrimary
                                    placeholderTextColor: Theme.textDim
                                    font.pixelSize: 13
                                    background: Rectangle {
                                        radius: Theme.radiusSm
                                        color: Theme.inputBg
                                        border.color: renameInput.activeFocus ? Theme.accent : Theme.borderSubtle
                                        border.width: renameInput.activeFocus ? 2 : 1
                                    }
                                }
                                AppButton {
                                    text: "Save"
                                    implicitHeight: 36
                                    onClicked: {
                                        if (renameInput.text.trim().length > 0) {
                                            controller.renameMeeting(mpath, renameInput.text)
                                        }
                                        root.renamePath = ""
                                    }
                                }
                                AppButton { text: "Cancel"; variant: "secondary"; implicitHeight: 36; onClicked: root.renamePath = "" }
                            }
                        }
                    }
                }
            }
        }
    }
}
