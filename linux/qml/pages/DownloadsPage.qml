import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var status: ({})
    property var payloads: []
    Layout.fillWidth: true
    Layout.fillHeight: true

    function readStatus() {
        try { root.status = JSON.parse(controller.engine_status_json) } catch (error) { root.status = {} }
        root.payloads = root.status.payloads || []
    }
    function fmtSize(bytes) {
        var b = Number(bytes || 0)
        if (b <= 0) return "—"
        if (b < 1048576) return Math.max(1, Math.round(b / 1024)) + " KB"
        if (b < 1073741824) return (b / 1048576).toFixed(1) + " MB"
        return (b / 1073741824).toFixed(2) + " GB"
    }
    function totalSize() {
        var total = 0
        for (var i = 0; i < root.payloads.length; i += 1) total += Number(root.payloads[i].size_bytes || 0)
        return total
    }
    function kindLabel(kind) {
        if (kind === "engine") return "Engine"
        if (kind === "binary") return "Runtime"
        return "Model"
    }
    function folderFor(path) {
        var p = String(path || "")
        var idx = p.lastIndexOf("/")
        return idx > 0 ? p.substring(0, idx) : p
    }
    function openTarget(modelData) {
        // Directory rows (engines, the Ollama store) open directly; file rows
        // open their parent folder.
        return modelData.path_is_dir ? String(modelData.path || "") : root.folderFor(modelData.path)
    }

    Component.onCompleted: readStatus()
    property Connections controllerConnection: Connections {
        target: controller
        function onEngine_status_jsonChanged() { root.readStatus() }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Label { text: root.payloads.length + " payload(s) · " + root.fmtSize(root.totalSize()) + " total"; color: Theme.textMuted; font.pixelSize: 12; Layout.fillWidth: true }
            AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 34; onClicked: controller.refreshEngineStatus() }
            AppButton { text: "Open data folder"; variant: "secondary"; implicitHeight: 34; onClicked: controller.openDataFolder(String(root.status.base_dir || "")) }
        }
        Label {
            visible: root.payloads.length === 0 && !!root.status.base_dir
            text: "Nothing downloaded yet.\nEngines and models you install from Models & services appear here with their location and size."
            color: Theme.textMuted
            horizontalAlignment: Text.AlignHCenter
            Layout.fillWidth: true
            Layout.topMargin: 32
        }
        Label {
            visible: root.payloads.length === 0 && !root.status.base_dir
            text: "Checking downloads…"
            color: Theme.textMuted
            Layout.fillWidth: true
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
                    model: root.payloads
                    delegate: AppCard {
                        required property var modelData
                        width: listColumn.width
                        ColumnLayout {
                            spacing: 6
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 10
                                StatusBadge {
                                    labelText: root.kindLabel(modelData.kind)
                                    dotColor: modelData.kind === "model" ? Theme.accentStrong : Theme.statusGreen
                                    pillBg: modelData.kind === "model" ? Theme.accentSoft : Theme.statusGreenBg
                                }
                                Label { text: modelData.name; color: Theme.textPrimary; font.pixelSize: 14; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                Label { text: root.fmtSize(modelData.size_bytes); color: Theme.textSecondary; font.pixelSize: 13; font.bold: true }
                            }
                            Label {
                                text: modelData.path || ""
                                color: Theme.textDim; font.pixelSize: 11; elide: Text.ElideMiddle
                                Layout.fillWidth: true
                            }
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8
                                AppButton {
                                    text: "Open folder"
                                    variant: "secondary"
                                    implicitHeight: 30
                                    onClicked: root.controller.openDataFolder(root.openTarget(modelData))
                                }
                                Item { Layout.fillWidth: true }
                            }
                        }
                    }
                }
            }
        }
    }
}
