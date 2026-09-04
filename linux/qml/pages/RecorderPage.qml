import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var snapshot: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    function updateSnapshot() {
        try { root.snapshot = JSON.parse(controller.snapshot_json) }
        catch (error) { root.snapshot = {} }
    }
    function timeLabel(seconds) {
        var s = Number(seconds || 0)
        var mm = Math.floor(s / 60)
        var ss = s % 60
        return (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss
    }

    Component.onCompleted: updateSnapshot()
    property Connections controllerConnection: Connections {
        target: root.controller
        function onSnapshot_jsonChanged() { root.updateSnapshot() }
    }

    property FileDialog importDialog: FileDialog {
        title: "Import recording"
        nameFilters: ["Audio recordings (*.mp3 *.wav *.m4a *.ogg *.flac *.webm)", "All files (*)"]
        onAccepted: {
            var audio = selectedFile.toLocalFile()
            root.controller.importExisting(audio, "", "", "Imported recording")
        }
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
            spacing: 16

            AppCard {
                Layout.fillWidth: true
                Layout.minimumHeight: 220
                ColumnLayout {
                    Label {
                        text: "Recorder"
                        color: Theme.textMuted
                        font.pixelSize: 12
                        font.bold: true
                    }
                    AppField {
                        id: titleField
                        label: "Meeting title (optional)"
                        placeholderText: "e.g. Product planning"
                        Layout.fillWidth: true
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        Label { text: "Capture"; color: Theme.textSecondary; font.pixelSize: 12 }
                        ButtonGroup { id: modeGroup }
                        RadioButton {
                            text: "Headphones"
                            checked: true
                            ButtonGroup.group: modeGroup
                            contentItem: Label { text: parent.text; color: Theme.textSecondary; leftPadding: 6; verticalAlignment: Text.AlignVCenter }
                        }
                        RadioButton {
                            id: speakerMode
                            text: "Speaker"
                            ButtonGroup.group: modeGroup
                            contentItem: Label { text: parent.text; color: Theme.textSecondary; leftPadding: 6; verticalAlignment: Text.AlignVCenter }
                        }
                        Item { Layout.fillWidth: true }
                        StatusBadge {
                            labelText: root.snapshot.state === "recording" ? "Recording" : (root.snapshot.state === "paused" ? "Paused" : "Ready")
                            dotColor: root.snapshot.state === "recording" ? Theme.danger : Theme.statusGreen
                        }
                    }
                    Label {
                        Layout.fillWidth: true
                        text: root.snapshot.state === "countdown"
                              ? "Processing starts in " + Number(root.snapshot.countdown || 0) + " seconds"
                              : (root.snapshot.status || "Waiting for a recording")
                        color: Theme.textMuted
                        wrapMode: Text.WordWrap
                    }
                    Label {
                        Layout.fillWidth: true
                        text: root.timeLabel(root.snapshot.elapsed)
                        color: root.snapshot.state === "recording" ? Theme.danger : Theme.textPrimary
                        font.pixelSize: 42
                        font.bold: true
                        horizontalAlignment: Text.AlignHCenter
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        Item { Layout.fillWidth: true }
                        AppButton {
                            text: "Start recording"
                            visible: ["idle", "done"].indexOf(root.snapshot.state || "idle") >= 0
                            onClicked: {
                                root.controller.setTitle(titleField.text)
                                root.controller.startRecording(speakerMode.checked ? "speaker" : "headphones")
                            }
                        }
                        AppButton { text: "Pause"; visible: root.snapshot.state === "recording"; onClicked: root.controller.pauseRecording() }
                        AppButton { text: "Resume"; visible: root.snapshot.state === "paused"; onClicked: root.controller.resumeRecording() }
                        AppButton { text: "Stop"; visible: ["recording", "paused"].indexOf(root.snapshot.state) >= 0; onClicked: root.controller.stopRecording() }
                        AppButton { text: "Cancel countdown"; visible: root.snapshot.state === "countdown"; onClicked: root.controller.cancelCountdown() }
                        Button {
                            text: "Save audio"
                            visible: root.snapshot.state === "recording" || root.snapshot.state === "paused"
                            onClicked: root.controller.cancelAndSave()
                        }
                        Button {
                            text: "Discard"
                            visible: root.snapshot.state === "recording" || root.snapshot.state === "paused" || root.snapshot.state === "countdown"
                            onClicked: root.controller.cancelAndDiscard()
                        }
                        AppButton { text: "Import"; onClicked: importDialog.open() }
                        Item { Layout.fillWidth: true }
                    }
                    Row {
                        Layout.alignment: Qt.AlignHCenter
                        spacing: 4
                        Repeater {
                            model: [8, 16, 24, 12, 28, 18, 34, 20, 14, 26, 10, 22, 16, 30, 12, 24, 18, 8]
                            delegate: Rectangle {
                                width: 4
                                height: modelData
                                radius: 2
                                color: root.snapshot.state === "recording" ? Theme.danger : Theme.accentMuted
                                Behavior on height { NumberAnimation { duration: Theme.animationDuration } }
                            }
                        }
                    }
                }
            }

            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    Label { text: "Processing pipeline"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    Label {
                        text: "Transcription → structured meeting notes → optional title"
                        color: Theme.textSecondary
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    Label { text: "Automatic processing is controlled in General settings."; color: Theme.textMuted; font.pixelSize: 12 }
                }
            }

            AppCard {
                Layout.fillWidth: true
                visible: (root.snapshot.jobs || []).length > 0
                ColumnLayout {
                    Label { text: "Background jobs"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                    Repeater {
                        model: root.snapshot.jobs || []
                        delegate: RowLayout {
                            required property var modelData
                            Layout.fillWidth: true
                            spacing: 10
                            Label { text: modelData.label || "Meeting"; color: Theme.textSecondary; Layout.fillWidth: true; elide: Text.ElideRight }
                            Label { text: modelData.status_text || modelData.status || "Processing"; color: Theme.textMuted; elide: Text.ElideRight }
                            Button { text: "Cancel"; visible: modelData.status === "processing"; onClicked: root.controller.cancelJob(modelData.job_id) }
                            Button { text: "Retry"; visible: modelData.status === "error"; onClicked: root.controller.retryJob(modelData.job_id) }
                            Button { text: "Open"; visible: modelData.status === "done"; onClicked: root.controller.openJobFolder(modelData.job_id) }
                            Button { text: "Dismiss"; visible: modelData.status !== "processing"; onClicked: root.controller.dismissJob(modelData.job_id) }
                        }
                    }
                }
            }
        }
    }
}
