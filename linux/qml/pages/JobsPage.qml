import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var snapshot: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    function refresh() {
        try { snapshot = JSON.parse(controller.snapshot_json) }
        catch (error) { snapshot = {} }
    }

    Component.onCompleted: refresh()
    property Connections controllerConnection: Connections {
        target: controller
        function onSnapshot_jsonChanged() { root.refresh() }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: jobsColumn.implicitHeight
        clip: true
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: jobsColumn
            width: root.width
            spacing: 12
            Label {
                visible: (root.snapshot.jobs || []).length === 0
                text: "No background jobs. Completed recordings appear here while they are processed."
                color: Theme.textMuted
                wrapMode: Text.WordWrap
                horizontalAlignment: Text.AlignHCenter
                Layout.fillWidth: true
                Layout.topMargin: 28
            }
            Repeater {
                model: root.snapshot.jobs || []
                delegate: AppCard {
                    required property var modelData
                    Layout.fillWidth: true
                    ColumnLayout {
                        RowLayout {
                            Layout.fillWidth: true
                            Label {
                                text: modelData.label || "Meeting"
                                color: Theme.textPrimary
                                font.pixelSize: 15
                                font.bold: true
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }
                            StatusBadge {
                                labelText: modelData.status_text || modelData.status || "Pending"
                                dotColor: modelData.status === "error" ? Theme.danger : (modelData.status === "done" ? Theme.statusGreen : Theme.accent)
                            }
                        }
                        Label {
                            text: modelData.error || modelData.message || modelData.status_text || "Working…"
                            color: modelData.status === "error" ? Theme.danger : Theme.textMuted
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            ProgressBar {
                                visible: modelData.status === "processing"
                                indeterminate: true
                                Layout.fillWidth: true
                                Accessible.name: "Job progress"
                            }
                            Button {
                                text: "Cancel"
                                visible: modelData.status === "processing"
                                onClicked: root.controller.cancelJob(modelData.job_id)
                                Accessible.name: "Cancel job"
                            }
                            Button {
                                text: "Retry"
                                visible: modelData.status === "error"
                                onClicked: root.controller.retryJob(modelData.job_id)
                                Accessible.name: "Retry job"
                            }
                            Button {
                                text: "Open folder"
                                visible: modelData.status === "done"
                                onClicked: root.controller.openJobFolder(modelData.job_id)
                                Accessible.name: "Open job folder"
                            }
                            Button {
                                text: "Dismiss"
                                visible: modelData.status !== "processing"
                                onClicked: root.controller.dismissJob(modelData.job_id)
                                Accessible.name: "Dismiss job"
                            }
                        }
                    }
                }
            }
        }
    }
}
