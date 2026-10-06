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
    function jobId(j) {
        return (j.job_id !== undefined) ? j.job_id : (j.id !== undefined ? j.id : -1)
    }
    function jobError(j) {
        return j.error_msg || j.error || j.message || ""
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
                        spacing: 8
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
                                dotColor: modelData.status === "error" ? Theme.danger : (modelData.status === "done" ? Theme.statusGreen : Theme.accentStrong)
                                pillBg: modelData.status === "error" ? Theme.dangerBg : (modelData.status === "done" ? Theme.statusGreenBg : Theme.accentSoft)
                            }
                        }
                        Label {
                            text: root.jobError(modelData) || modelData.status_text || "Working…"
                            color: modelData.status === "error" ? Theme.danger : Theme.textMuted
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                            font.pixelSize: 12
                            visible: (root.jobError(modelData) || modelData.status_text || "").length > 0
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            AppProgressBar {
                                visible: modelData.status === "processing"
                                indeterminate: true
                                Layout.fillWidth: true
                            }
                            AppButton {
                                text: "Cancel"
                                variant: "secondary"
                                implicitHeight: 32
                                visible: modelData.status === "processing"
                                onClicked: root.controller.cancelJob(root.jobId(modelData))
                            }
                            AppButton {
                                text: "Retry"
                                variant: "secondary"
                                implicitHeight: 32
                                visible: modelData.status === "error"
                                onClicked: root.controller.retryJob(root.jobId(modelData))
                            }
                            AppButton {
                                text: "Open folder"
                                variant: "secondary"
                                implicitHeight: 32
                                visible: modelData.status === "done"
                                onClicked: root.controller.openJobFolder(root.jobId(modelData))
                            }
                            AppButton {
                                text: "Dismiss"
                                variant: "secondary"
                                implicitHeight: 32
                                visible: modelData.status !== "processing"
                                onClicked: root.controller.dismissJob(root.jobId(modelData))
                            }
                        }
                    }
                }
            }
        }
    }
}
