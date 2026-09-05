import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtQuick.Window
import io.github.jmarceno.gravaai

ApplicationWindow {
    id: root
    property AppController controller: AppController {}
    width: 1120
    height: 760
    minimumWidth: 960
    minimumHeight: 640
    visible: true
    color: "transparent"
    title: "Grava Aí"
    flags: Qt.Window | Qt.FramelessWindowHint
    property var snapshotData: ({})
    property var settingsData: ({})
    property var meetingsData: []
    property var installsData: []

    function parse(value, fallback) {
        try { return JSON.parse(value) } catch (error) { return fallback }
    }
    function refreshData() {
        snapshotData = parse(controller.snapshot_json, {})
        settingsData = parse(controller.settings_json, {})
        meetingsData = parse(controller.meetings_json, [])
        installsData = parse(controller.installs_json, [])
    }
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
    function recorderStatus() {
        var st = snapshotData.state || "idle"
        if (st === "recording") return "Recording"
        if (st === "paused") return "Paused"
        if (st === "countdown") return "Processing"
        return "Ready"
    }
    function beginResize(edges) { root.startSystemResize(edges) }
    function requestCloseWindow() {
        // Hide synchronously so the X button always closes the window even
        // when the worker/D-Bus round-trip is slow or stuck. In Low-memory
        // mode quit the process directly: the async CloseAction reply is only
        // a backup, so a lost worker reply can never leave a hidden window
        // that ignores reopen requests. Otherwise the async reply (hide) is
        // idempotent and the hidden window is presented on demand.
        root.hide()
        controller.requestClose()
        if (settingsData.low_memory_mode) controller.requestAppQuit()
    }

    Component.onCompleted: {
        refreshData()
        controller.bootstrap()
    }

    property Timer inputTimer: Timer {
        interval: 33
        repeat: true
        running: true
        onTriggered: controller.pollInput()
    }

    property Connections controllerConnections: Connections {
        target: controller
        function onSnapshot_jsonChanged() { root.refreshData() }
        function onSettings_jsonChanged() { root.refreshData() }
        function onMeetings_jsonChanged() { root.refreshData() }
        function onInstalls_jsonChanged() { root.refreshData() }
        function onToast(message) { toast.showMessage(message) }
        function onDialog(message, confirm) {
            if (message.length > 0) alertDialog.open()
        }
        function onPresentWindow() {
            root.showNormal()
            root.raise()
            root.requestActivate()
        }
        function onOpenImport() { importDialog.open() }
        function onCloseAction(action) {
            // The window was already hidden synchronously by requestCloseWindow;
            // only Low-memory mode needs to quit the process here.
            if (action === "hide") root.hide()
            else controller.requestAppQuit()
        }
        function onFatalError(message) {
            if (message.length > 0) alertDialog.open()
        }
    }

    property FileDialog importDialog: FileDialog {
        title: "Import existing recording"
        nameFilters: ["Audio recordings (*.mp3 *.wav *.m4a *.ogg *.flac *.webm)", "All files (*)"]
        onAccepted: controller.importExisting(selectedFile.toLocalFile(), "", "", "Imported recording")
    }

    property Dialog alertDialog: Dialog {
        modal: true
        title: controller.dialog_confirm ? "Confirm settings" : "Grava Aí"
        standardButtons: controller.dialog_confirm ? (Dialog.Ok | Dialog.Cancel) : Dialog.Ok
        anchors.centerIn: Overlay.overlay
        width: Math.min(480, root.width - 80)
        background: Rectangle { radius: Theme.radiusSm; color: Theme.cardBgRaised; border.color: Theme.borderSubtle }
        contentItem: Label {
            text: controller.dialog_message
            color: Theme.textSecondary
            wrapMode: Text.WordWrap
            padding: 22
        }
        onAccepted: if (controller.dialog_confirm) controller.confirmSaveSettings()
    }

    Rectangle {
        id: toast
        property bool shown: false
        function showMessage(message) {
            if (!message || message.length === 0) return
            textLabel.text = message
            shown = true
            hideTimer.restart()
        }
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 18
        width: Math.min(parent.width - 48, Math.max(280, textLabel.implicitWidth + 40))
        height: Math.max(42, textLabel.implicitHeight + 20)
        radius: Theme.radiusSm
        color: Theme.cardBgRaised
        border.color: Theme.accentMuted
        border.width: 1
        visible: shown
        z: 100
        Label { id: textLabel; anchors.centerIn: parent; width: parent.width - 32; color: Theme.textSecondary; wrapMode: Text.WordWrap; horizontalAlignment: Text.AlignHCenter }
        property Timer hideTimer: Timer { interval: 4200; onTriggered: toast.shown = false }
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
            TitleBar { window: root; controller: root.controller; Layout.fillWidth: true }
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
                            text: controller.daemon_alive ? "Local daemon running" : "Connecting to daemon…"
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
                        SidebarItem { iconText: "💬"; text: "Prompts"; selected: root.controller.selected_page === "prompts"; onClicked: root.controller.selectPage("prompts"); Layout.fillWidth: true }
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

    property var escapeShortcut: Shortcut {
        sequence: "Escape"
        onActivated: root.requestCloseWindow()
    }
    onClosing: function(close) {
        close.accepted = false
        root.requestCloseWindow()
    }

    // Native system resize requests work on both X11 and Wayland. The small
    // edge handles sit above the content but leave the title-bar buttons clear.
    MouseArea { x: 8; y: 0; width: Math.max(0, root.width - 16); height: 6; z: 200; cursorShape: Qt.SizeVerCursor; onPressed: root.beginResize(Qt.TopEdge) }
    MouseArea { x: 8; y: root.height - 6; width: Math.max(0, root.width - 16); height: 6; z: 200; cursorShape: Qt.SizeVerCursor; onPressed: root.beginResize(Qt.BottomEdge) }
    MouseArea { x: 0; y: 8; width: 6; height: Math.max(0, root.height - 16); z: 200; cursorShape: Qt.SizeHorCursor; onPressed: root.beginResize(Qt.LeftEdge) }
    MouseArea { x: root.width - 6; y: 8; width: 6; height: Math.max(0, root.height - 16); z: 200; cursorShape: Qt.SizeHorCursor; onPressed: root.beginResize(Qt.RightEdge) }
    MouseArea { x: 0; y: 0; width: 10; height: 10; z: 201; cursorShape: Qt.SizeFDiagCursor; onPressed: root.beginResize(Qt.LeftEdge | Qt.TopEdge) }
    MouseArea { x: root.width - 10; y: 0; width: 10; height: 10; z: 201; cursorShape: Qt.SizeBDiagCursor; onPressed: root.beginResize(Qt.RightEdge | Qt.TopEdge) }
    MouseArea { x: 0; y: root.height - 10; width: 10; height: 10; z: 201; cursorShape: Qt.SizeBDiagCursor; onPressed: root.beginResize(Qt.LeftEdge | Qt.BottomEdge) }
    MouseArea { x: root.width - 10; y: root.height - 10; width: 10; height: 10; z: 201; cursorShape: Qt.SizeFDiagCursor; onPressed: root.beginResize(Qt.RightEdge | Qt.BottomEdge) }
}
