import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

// Window chrome shared by Main.qml and the offscreen smoke harness: title
// bar, sidebar navigation and the page stack.
Item {
    id: root
    required property var window
    required property AppController controller
    property var snapshotData: ({})

    function pageTitle() {
        var titles = { recorder: "New recording", library: "Library", models: "Models & services", downloads: "Downloads", prompts: "Prompts", general: "General" }
        return titles[controller.selected_page] || "New recording"
    }
    function pageSubtitle() {
        var subtitles = {
            recorder: "Record a meeting, then transcribe and summarize it automatically.",
            library: "Browse recordings, transcripts and notes.",
            models: "Configure cloud and optional local AI services.",
            downloads: "Everything the app downloaded: engines, models and their sizes.",
            prompts: "Tune the instructions used for transcription and notes.",
            general: "Recording, storage and background behavior."
        }
        return subtitles[controller.selected_page] || subtitles.recorder
    }
    function currentPage() {
        return pages.children[pages.currentIndex]
    }
    function recorderStatus() {
        var st = snapshotData.state || "idle"
        if (st === "recording") return "Recording"
        if (st === "paused") return "Paused"
        if (st === "countdown") return "Processing"
        return "Ready"
    }

    Rectangle {
        id: outer
        anchors.fill: parent
        radius: 18
        color: Theme.windowBg
        border.color: Theme.borderSubtle
        border.width: 1
        clip: true

        ColumnLayout {
            anchors.fill: parent
            spacing: 0
            TitleBar { window: root.window; controller: root.controller; Layout.fillWidth: true }
            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                Rectangle {
                    Layout.fillHeight: true
                    Layout.preferredWidth: Theme.sidebarWidth
                    color: Theme.windowBg
                    Rectangle { width: 1; height: parent.height; x: parent.width - 1; color: Theme.borderSubtle }
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 14
                        anchors.topMargin: 10
                        spacing: 4
                        Label { text: "RECORDER"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; Layout.topMargin: 4; Layout.leftMargin: 4; font.letterSpacing: 1.1 }
                        RowLayout {
                            Layout.fillWidth: true
                            Layout.leftMargin: 4
                            Layout.topMargin: 6
                            spacing: 8
                            Rectangle {
                                width: 8; height: 8; radius: 4
                                color: root.recorderStatus() === "Ready" ? Theme.statusGreen : (root.recorderStatus() === "Recording" ? Theme.danger : Theme.warning)
                                Layout.alignment: Qt.AlignVCenter
                            }
                            Label { text: root.recorderStatus(); color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                        }
                        Label {
                            text: root.controller.daemon_alive ? "Local daemon running" : "Connecting to daemon…"
                            color: Theme.textMuted; font.pixelSize: 11
                            Layout.fillWidth: true; Layout.leftMargin: 20
                            elide: Text.ElideRight
                        }
                        Item { Layout.preferredHeight: 8 }
                        SidebarItem { iconText: "◉"; text: "Record"; selected: root.controller.selected_page === "recorder"; onClicked: root.controller.selectPage("recorder"); Layout.fillWidth: true }
                        SidebarItem { iconText: "▢"; text: "Library"; selected: root.controller.selected_page === "library"; onClicked: root.controller.selectPage("library"); Layout.fillWidth: true }
                        Label { text: "CONFIGURATION"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; Layout.topMargin: 16; Layout.leftMargin: 4; font.letterSpacing: 1.1 }
                        Item { Layout.preferredHeight: 2 }
                        SidebarItem { iconText: "◫"; text: "Models & services"; selected: root.controller.selected_page === "models"; onClicked: root.controller.selectPage("models"); Layout.fillWidth: true }
                        SidebarItem { iconText: "⤓"; text: "Downloads"; selected: root.controller.selected_page === "downloads"; onClicked: root.controller.selectPage("downloads"); Layout.fillWidth: true }
                        SidebarItem { iconText: "¶"; text: "Prompts"; selected: root.controller.selected_page === "prompts"; onClicked: root.controller.selectPage("prompts"); Layout.fillWidth: true }
                        SidebarItem { iconText: "◍"; text: "General"; selected: root.controller.selected_page === "general"; onClicked: root.controller.selectPage("general"); Layout.fillWidth: true }
                        Item { Layout.fillHeight: true }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    color: Theme.windowBg
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 24
                        anchors.topMargin: 12
                        spacing: 14
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Label { text: root.pageTitle(); color: Theme.textPrimary; font.pixelSize: 26; font.bold: true }
                            Label { text: root.pageSubtitle(); color: Theme.textMuted; font.pixelSize: 13; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        }
                        StackLayout {
                            id: pages
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            currentIndex: Math.max(0, ["recorder", "library", "models", "downloads", "prompts", "general"].indexOf(root.controller.selected_page))
                            RecorderPage { controller: root.controller }
                            LibraryPage { controller: root.controller }
                            ModelsPage { controller: root.controller }
                            DownloadsPage { controller: root.controller }
                            PromptsPage { controller: root.controller }
                            GeneralPage { controller: root.controller }
                        }
                    }
                }
            }
        }
    }

}
