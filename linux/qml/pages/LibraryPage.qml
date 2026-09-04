import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var meetings: []
    Layout.fillWidth: true
    Layout.fillHeight: true

    function refresh() {
        try { meetings = JSON.parse(controller.meetings_json) }
        catch (error) { meetings = [] }
    }
    function selectedPaths() {
        var paths = []
        for (var i = 0; i < listColumn.children.length; ++i) {
            var item = listColumn.children[i]
            if (item && item.selected === true) paths.push(item.path)
        }
        return paths
    }
    function openMeeting(modelData) {
        controller.summarizeMeeting(modelData.path + "/recording.mp3", modelData.path + "/transcript.md", modelData.path + "/notes.md", modelData.title || modelData.time_label)
    }

    Component.onCompleted: refresh()
    property Connections controllerConnection: Connections {
        target: controller
        function onMeetings_jsonChanged() { root.refresh() }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 14
        RowLayout {
            Layout.fillWidth: true
            Label { text: "Past meetings"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
            Button { text: "Refresh"; onClicked: controller.refreshMeetings() }
            Button { text: "Delete selected"; enabled: root.selectedPaths().length > 0; onClicked: controller.deleteMeetings(JSON.stringify(root.selectedPaths())) }
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
                spacing: 8
                Repeater {
                    model: root.meetings
                    delegate: AppCard {
                        id: meetingCard
                        required property var modelData
                        property string path: modelData.path || ""
                        property bool selected: false
                        width: listColumn.width
                        implicitHeight: body.implicitHeight + padding * 2
                        ColumnLayout {
                            RowLayout {
                                Layout.fillWidth: true
                                CheckBox { checked: meetingCard.selected; onToggled: meetingCard.selected = checked; Accessible.name: "Select meeting" }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    Label { text: modelData.title || modelData.time_label || "Meeting"; color: Theme.textPrimary; font.pixelSize: 14; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                    Label { text: (modelData.time_label || "") + (modelData.duration_seconds ? " · " + modelData.duration_seconds + "s" : ""); color: Theme.textMuted; font.pixelSize: 12 }
                                }
                                StatusBadge { labelText: modelData.has_notes ? "Notes" : (modelData.has_transcript ? "Transcript" : "Audio only"); dotColor: modelData.has_notes ? Theme.statusGreen : Theme.accent }
                            }
                            RowLayout {
                                Layout.fillWidth: true
                                Label { text: modelData.path || ""; color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                                Button { text: "Re-summarize"; enabled: modelData.has_transcript; onClicked: root.openMeeting(modelData) }
                                Button { text: "Rename"; onClicked: renameField.visible = !renameField.visible }
                                Button { text: "Open"; onClicked: controller.openMeetingFolder(meetingCard.path) }
                            }
                            RowLayout {
                                id: renameField
                                visible: false
                                Layout.fillWidth: true
                                TextField { id: newTitle; placeholderText: "New title"; Layout.fillWidth: true; implicitHeight: 34 }
                                Button { text: "Save"; onClicked: { controller.renameMeeting(meetingCard.path, newTitle.text); renameField.visible = false } }
                            }
                        }
                    }
                }
            }
        }
    }
}
