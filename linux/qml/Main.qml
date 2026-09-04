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
    color: Theme.windowBg
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
        var titles = { recorder: "Record", library: "Library", jobs: "Background jobs", models: "Models & services", prompts: "Prompts", general: "General", about: "About" }
        return titles[controller.selected_page] || "Record"
    }
    function pageSubtitle() {
        var subtitles = {
            recorder: "Capture a meeting and turn it into professional notes.",
            library: "Browse recordings, transcripts and notes.",
            jobs: "Monitor transcription and summarization in the background.",
            models: "Configure cloud and optional local AI services.",
            prompts: "Tune the instructions used for transcription and notes.",
            general: "Recording, storage and background behavior.",
            about: "Grava Aí identity, version and runtime information."
        }
        return subtitles[controller.selected_page] || subtitles.recorder
    }
    function beginResize(edges) { root.startSystemResize(edges) }

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
        visible: shown
        z: 100
        Label { id: textLabel; anchors.centerIn: parent; width: parent.width - 32; color: Theme.textSecondary; wrapMode: Text.WordWrap; horizontalAlignment: Text.AlignHCenter }
        property Timer hideTimer: Timer { interval: 4200; onTriggered: toast.shown = false }
    }

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
                color: Theme.cardBg
                border.color: Theme.borderSubtle
                border.width: 1
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 6
                    Label { text: "RECORDER"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; Layout.topMargin: 4 }
                    SidebarItem { text: "Record"; selected: root.controller.selected_page === "recorder"; onClicked: root.controller.selectPage("recorder"); Layout.fillWidth: true }
                    SidebarItem { text: "Library"; selected: root.controller.selected_page === "library"; onClicked: root.controller.selectPage("library"); Layout.fillWidth: true }
                    SidebarItem { text: "Background jobs"; selected: root.controller.selected_page === "jobs"; onClicked: root.controller.selectPage("jobs"); Layout.fillWidth: true }
                    Label { text: "CONFIGURATION"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; Layout.topMargin: 18 }
                    SidebarItem { text: "Models & services"; selected: root.controller.selected_page === "models"; onClicked: root.controller.selectPage("models"); Layout.fillWidth: true }
                    SidebarItem { text: "Prompts"; selected: root.controller.selected_page === "prompts"; onClicked: root.controller.selectPage("prompts"); Layout.fillWidth: true }
                    SidebarItem { text: "General"; selected: root.controller.selected_page === "general"; onClicked: root.controller.selectPage("general"); Layout.fillWidth: true }
                    SidebarItem { text: "About"; selected: root.controller.selected_page === "about"; onClicked: root.controller.selectPage("about"); Layout.fillWidth: true }
                    Label { text: "LOCAL TOOLS"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; Layout.topMargin: 18 }
                    Button {
                        text: "Grava Aí   ✓"
                        flat: true
                        Layout.fillWidth: true
                        contentItem: Label { text: parent.text; color: Theme.statusGreen; horizontalAlignment: Text.AlignLeft; leftPadding: 14 }
                    }
                    Button {
                        text: root.controller.lepramim_ready ? "Lepramim   ✓" : "Lepramim   —"
                        flat: true
                        enabled: root.controller.lepramim_ready
                        Layout.fillWidth: true
                        contentItem: Label { text: parent.text; color: parent.enabled ? Theme.textSecondary : Theme.textDim; horizontalAlignment: Text.AlignLeft; leftPadding: 14 }
                        onClicked: root.controller.launchLepramim()
                    }
                    Item { Layout.fillHeight: true }
                    Label { text: root.controller.daemon_alive ? "●  Daemon ready" : "○  Connecting…"; color: root.controller.daemon_alive ? Theme.statusGreen : Theme.textMuted; font.pixelSize: 11; Layout.fillWidth: true; padding: 8 }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                color: Theme.windowBg
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 24
                    spacing: 16
                    RowLayout {
                        Layout.fillWidth: true
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Label { text: root.pageTitle(); color: Theme.textPrimary; font.pixelSize: 28; font.bold: true }
                            Label { text: root.pageSubtitle(); color: Theme.textMuted; font.pixelSize: 13; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        }
                        StatusBadge { labelText: root.controller.ready ? "Ready" : "Starting"; dotColor: root.controller.ready ? Theme.statusGreen : Theme.accent }
                    }
                    StackLayout {
                        id: pages
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        currentIndex: ["recorder", "library", "jobs", "models", "prompts", "general", "about"].indexOf(root.controller.selected_page)
                        RecorderPage { controller: root.controller }
                        LibraryPage { controller: root.controller }
                        JobsPage { controller: root.controller }
                        ModelsPage { controller: root.controller }
                        PromptsPage { controller: root.controller }
                        GeneralPage { controller: root.controller }
                        AboutPage { controller: root.controller }
                    }
                }
            }
        }
    }

    property var escapeShortcut: Shortcut {
        sequence: "Escape"
        onActivated: controller.requestClose()
    }
    onClosing: function(close) {
        close.accepted = false
        controller.requestClose()
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
