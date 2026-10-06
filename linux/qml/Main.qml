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
    function pillVisible() {
        if (root.settingsData.show_recording_pill === false)
            return false
        var st = snapshotData.state || "idle"
        return st === "recording" || st === "paused" || st === "countdown"
    }
    function presentMain() {
        root.showNormal()
        root.raise()
        root.requestActivate()
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
            root.presentMain()
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
        onAccepted: controller.importExisting(decodeURIComponent(String(selectedFile).replace(/^file:\/\//, "")), "", "", "Imported recording")
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

    // Handy-style mini overlay: a small frameless always-on-top pill pinned
    // to the bottom-right corner, just above the taskbar. It stays visible
    // while recording (even with the main window closed to the tray) and
    // offers pause/resume, stop and click-to-open without intruding.
    property Window pillWindow: Window {
        id: pillWindow
        title: "GravaAi recording"
        width: 300
        height: 52
        minimumWidth: 300
        maximumWidth: 300
        minimumHeight: 52
        maximumHeight: 52
        color: "transparent"
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        visible: root.pillVisible()
        x: Math.max(0, Screen.width - width - 16)
        y: Math.max(0, Screen.height - height - 48)

        property point pressPos: Qt.point(0, 0)
        property point pressWindowPos: Qt.point(0, 0)
        property bool dragging: false

        property MouseArea dragArea: MouseArea {
            anchors.fill: parent
            onPressed: function(mouse) {
                pillWindow.pressPos = Qt.point(mouse.x, mouse.y)
                pillWindow.pressWindowPos = Qt.point(pillWindow.x, pillWindow.y)
                pillWindow.dragging = false
            }
            onPositionChanged: function(mouse) {
                if (!pillWindow.dragging
                        && Math.hypot(mouse.x - pillWindow.pressPos.x,
                                      mouse.y - pillWindow.pressPos.y) > 4)
                    pillWindow.dragging = true
                if (pillWindow.dragging) {
                    pillWindow.x = pillWindow.pressWindowPos.x + (mouse.x - pillWindow.pressPos.x)
                    pillWindow.y = pillWindow.pressWindowPos.y + (mouse.y - pillWindow.pressPos.y)
                }
            }
            onReleased: function(mouse) {
                if (!pillWindow.dragging)
                    root.presentMain()
            }
        }

        RecordingPill {
            anchors.centerIn: parent
            recState: root.snapshotData.state || "idle"
            elapsedSeconds: Number(root.snapshotData.elapsed || 0)
            countdownSeconds: Number(root.snapshotData.countdown || 0)
            audioLevel: Number(root.snapshotData.audio_level || 0)
            onPauseRequested: root.controller.pauseRecording()
            onResumeRequested: root.controller.resumeRecording()
            onStopRequested: root.controller.stopRecording()
            onOpenRequested: root.presentMain()
        }
    }

    AppShell {
        anchors.fill: parent
        window: root
        controller: root.controller
        snapshotData: root.snapshotData
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
