import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var meetings: []
    property var jobs: []
    property var selected: ({})
    property int selectedCount: 0
    property string renamePath: ""
    Layout.fillWidth: true
    Layout.fillHeight: true

    function refresh() {
        try { meetings = JSON.parse(controller.meetings_json) }
        catch (error) { meetings = [] }
    }
    function refreshJobs() {
        try { jobs = JSON.parse(controller.snapshot_json).jobs || [] }
        catch (error) { jobs = [] }
    }
    function jobFor(m) {
        // Jobs report their meeting directory as `audio_dir`.
        for (var i = 0; i < jobs.length; i += 1)
            if (jobs[i].audio_dir === m.path && jobs[i].status === "processing") return jobs[i]
        return null
    }
    function toggleSelected(path, checked) {
        var s = {}
        for (var k in selected) s[k] = selected[k]
        if (checked) s[path] = true
        else delete s[path]
        selected = s
        selectedCount = Object.keys(s).length
    }
    function isSelected(path) { return selected[path] === true }
    function selectedList() { return Object.keys(selected) }
    function clearSelection() { selected = {}; selectedCount = 0 }
    function durationLabel(secs) {
        var s = Number(secs || 0)
        if (s <= 0) return ""
        if (s < 60) return s + "s"
        var m = Math.floor(s / 60)
        if (m < 60) return m + " min"
        return Math.floor(m / 60) + "h " + (m % 60) + "m"
    }
    function whenLabel(label) {
        var m = String(label || "").match(/(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})/)
        if (!m) return String(label || "")
        return m[3] + "/" + m[2] + "/" + m[1] + " · " + m[4] + ":" + m[5]
    }
    function displayTitle(m) {
        if (m.title && m.title.length > 0) return m.title
        return "Meeting " + whenLabel(m.time_label)
    }
    function audioFor(m) { return m.audio_path || (m.path + "/recording.mp3") }
    function transcriptFor(m) { return m.transcript_path || (m.path + "/transcript.md") }
    function notesFor(m) { return m.notes_path || (m.path + "/notes.md") }
    function summarize(m) {
        controller.summarizeMeeting(audioFor(m), transcriptFor(m), notesFor(m), displayTitle(m))
    }
    function transcribe(m) {
        controller.transcribeMeeting(audioFor(m), transcriptFor(m), notesFor(m), displayTitle(m))
    }

    Component.onCompleted: { refresh(); refreshJobs() }
    property Connections controllerConnection: Connections {
        target: root.controller
        function onMeetings_jsonChanged() { root.refresh() }
        function onSnapshot_jsonChanged() { root.refreshJobs() }
    }

    property Dialog confirmDelete: Dialog {
        modal: true
        title: "Delete meetings?"
        // Declared as a property, the popup has no visual parent of its own.
        parent: Overlay.overlay
        x: Math.round((parent ? parent.width - width : 0) / 2)
        y: Math.round((parent ? parent.height - height : 0) / 2)
        width: 420
        standardButtons: Dialog.Cancel | Dialog.Ok
        background: Rectangle { radius: Theme.radiusSm; color: Theme.cardBgRaised; border.color: Theme.borderSubtle }
        padding: 18
        Label {
            width: parent.width
            text: "Permanently delete " + root.selectedCount + " meeting(s) — audio, transcript and notes? This cannot be undone."
            color: Theme.textSecondary
            wrapMode: Text.WordWrap
        }
        onAccepted: {
            root.controller.deleteMeetings(JSON.stringify(root.selectedList()))
            root.clearSelection()
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            Layout.rightMargin: 14
            spacing: 8
            Label {
                text: root.selectedCount > 0 ? root.selectedCount + " selected" : root.meetings.length + " meeting(s)"
                color: Theme.textMuted; font.pixelSize: 12; Layout.fillWidth: true
            }
            AppButton {
                visible: root.selectedCount > 0
                text: "Delete…"
                variant: "danger"
                implicitHeight: 34
                onClicked: root.confirmDelete.open()
            }
            AppButton { visible: root.selectedCount > 0; text: "Clear"; variant: "secondary"; implicitHeight: 34; onClicked: root.clearSelection() }
            AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 34; onClicked: root.controller.refreshMeetings() }
            AppButton { text: "Open folder"; variant: "secondary"; implicitHeight: 34; onClicked: root.controller.openOutputFolder() }
        }
        Label {
            visible: root.meetings.length === 0
            text: "No meetings yet. Completed recordings will appear here."
            color: Theme.textMuted
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            Layout.topMargin: 32
        }
        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 10
            boundsBehavior: Flickable.StopAtBounds
            model: root.meetings
            ScrollBar.vertical: ScrollBar { policy: list.contentHeight > list.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff }
            delegate: AppCard {
                id: card
                required property var modelData
                property string mpath: modelData.path || ""
                property var job: root.jobFor(modelData)
                width: list.width - 14
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    AppCheckBox {
                        text: ""
                        padding: 0
                        Layout.preferredWidth: 22
                        checked: root.isSelected(card.mpath)
                        onToggled: root.toggleSelected(card.mpath, checked)
                        Layout.alignment: Qt.AlignTop
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label { text: root.displayTitle(card.modelData); color: Theme.textPrimary; font.pixelSize: 14; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label {
                            text: root.whenLabel(card.modelData.time_label) + (card.modelData.duration_seconds ? " · " + root.durationLabel(card.modelData.duration_seconds) : "")
                            color: Theme.textMuted; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true
                        }
                    }
                    StatusBadge {
                        Layout.alignment: Qt.AlignTop
                        labelText: card.job ? "Processing" : (card.modelData.has_notes ? "Notes" : (card.modelData.has_transcript ? "Transcript" : (card.modelData.has_audio ? "Audio only" : "Empty")))
                        dotColor: card.job ? Theme.warning : (card.modelData.has_notes ? Theme.statusGreen : (card.modelData.has_audio || card.modelData.has_transcript ? Theme.accentStrong : Theme.warning))
                        pillBg: card.job ? Theme.warningBg : (card.modelData.has_notes ? Theme.statusGreenBg : Theme.accentSoft)
                    }
                }
                Label {
                    visible: card.job !== null
                    text: card.job ? (card.job.status_text || "Processing…") : ""
                    color: Theme.warning; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true
                }
                Flow {
                    Layout.fillWidth: true
                    spacing: 8
                    AppButton {
                        text: "Transcribe"
                        variant: "teal"
                        implicitHeight: 32
                        visible: !card.modelData.has_transcript
                        enabled: card.modelData.has_audio !== false && card.job === null
                        onClicked: root.transcribe(card.modelData)
                    }
                    AppButton {
                        text: card.modelData.has_notes ? "Re-summarize" : "Summarize"
                        variant: card.modelData.has_notes ? "secondary" : "teal"
                        implicitHeight: 32
                        enabled: (card.modelData.has_transcript || card.modelData.has_audio !== false) && card.job === null
                        onClicked: root.summarize(card.modelData)
                    }
                    AppButton {
                        text: "Transcript"
                        variant: "secondary"
                        implicitHeight: 32
                        visible: card.modelData.has_transcript
                        onClicked: root.controller.openFile(root.transcriptFor(card.modelData))
                    }
                    AppButton {
                        text: "Notes"
                        variant: "secondary"
                        implicitHeight: 32
                        visible: card.modelData.has_notes
                        onClicked: root.controller.openFile(root.notesFor(card.modelData))
                    }
                    AppButton { text: "Rename"; variant: "secondary"; implicitHeight: 32; onClicked: root.renamePath = (root.renamePath === card.mpath ? "" : card.mpath) }
                    AppButton { text: "Folder"; variant: "secondary"; implicitHeight: 32; onClicked: root.controller.openMeetingFolder(card.mpath) }
                }
                RowLayout {
                    visible: root.renamePath === card.mpath
                    Layout.fillWidth: true
                    spacing: 8
                    TextField {
                        id: renameInput
                        Layout.fillWidth: true
                        implicitHeight: 36
                        text: card.modelData.title || ""
                        placeholderText: "New title"
                        color: Theme.textPrimary
                        placeholderTextColor: Theme.textDim
                        font.pixelSize: 13
                        leftPadding: 12
                        background: Rectangle {
                            radius: Theme.radiusSm
                            color: Theme.inputBg
                            border.color: renameInput.activeFocus ? Theme.accent : Theme.borderSubtle
                            border.width: renameInput.activeFocus ? 2 : 1
                        }
                        onAccepted: saveRename.clicked()
                    }
                    AppButton {
                        id: saveRename
                        text: "Save"
                        implicitHeight: 36
                        onClicked: {
                            if (renameInput.text.trim().length > 0)
                                root.controller.renameMeeting(card.mpath, renameInput.text.trim())
                            root.renamePath = ""
                        }
                    }
                    AppButton { text: "Cancel"; variant: "secondary"; implicitHeight: 36; onClicked: root.renamePath = "" }
                }
            }
        }
    }
}
