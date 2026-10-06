import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

// Small non-intrusive recording overlay, styled after Handy's pill: a compact
// capsule with a status dot, elapsed time and pause/stop controls. It carries
// no window chrome itself; Main.qml hosts it in a frameless always-on-top
// Tool window pinned to the bottom-right corner, just above the taskbar.
Item {
    id: root
    required property string recState
    required property double elapsedSeconds
    property double countdownSeconds: 0
    // Live capture level 0.0–1.0 from the daemon snapshot (`audio_level`).
    property double audioLevel: 0
    signal pauseRequested()
    signal resumeRequested()
    signal stopRequested()
    signal openRequested()

    function timeLabel(seconds) {
        var s = Math.max(0, Math.floor(Number(seconds || 0)))
        var h = Math.floor(s / 3600)
        var m = Math.floor((s % 3600) / 60)
        var ss = s % 60
        var mm = (m < 10 ? "0" : "") + m
        var sss = (ss < 10 ? "0" : "") + ss
        if (h > 0)
            return h + ":" + mm + ":" + sss
        return mm + ":" + sss
    }
    function stateText() {
        if (root.recState === "paused")
            return "Paused"
        if (root.recState === "countdown")
            return "Processing"
        return "Recording"
    }
    function dotColor() {
        if (root.recState === "paused")
            return Theme.warning
        if (root.recState === "countdown")
            return Theme.accentStrong
        return Theme.danger
    }
    function mainLabel() {
        if (root.recState === "countdown")
            return "In " + Math.max(0, Math.ceil(Number(root.countdownSeconds || 0))) + "s"
        return root.timeLabel(root.elapsedSeconds)
    }

    implicitWidth: pill.implicitWidth
    implicitHeight: 44

    Rectangle {
        id: pill
        anchors.centerIn: parent
        width: pillRow.implicitWidth + 28
        height: 44
        radius: 22
        color: Theme.cardBgRaised
        border.color: Theme.borderSubtle
        border.width: 1
        clip: true

        // Live capture meter along the pill's bottom edge: flat while
        // recording means nothing is reaching the recorder.
        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 3
            color: "transparent"
            visible: root.recState === "recording" || root.recState === "paused"
            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: parent.width * Math.max(0, Math.min(1, Number(root.audioLevel) || 0))
                color: root.dotColor()
                opacity: root.recState === "recording" ? 0.9 : 0.4
                Behavior on width { NumberAnimation { duration: 90; easing.type: Easing.OutQuad } }
            }
        }

        RowLayout {
            id: pillRow
            anchors.centerIn: parent
            spacing: 8

            Rectangle {
                id: statusDot
                width: 10
                height: 10
                radius: 5
                color: root.dotColor()
                Layout.alignment: Qt.AlignVCenter
                opacity: 1.0
                property SequentialAnimation pulse: SequentialAnimation {
                    running: root.recState === "recording" && Theme.animationsEnabled
                    loops: Animation.Infinite
                    NumberAnimation { target: statusDot; property: "opacity"; to: 0.35; duration: 900; easing.type: Easing.InOutSine }
                    NumberAnimation { target: statusDot; property: "opacity"; to: 1.0; duration: 900; easing.type: Easing.InOutSine }
                }
            }

            ColumnLayout {
                spacing: 0
                Layout.alignment: Qt.AlignVCenter
                Label {
                    text: root.mainLabel()
                    color: Theme.textPrimary
                    font.pixelSize: 14
                    font.bold: true
                    font.family: "monospace"
                }
                Label {
                    text: root.stateText()
                    color: Theme.textMuted
                    font.pixelSize: 10
                }
            }

            Rectangle { width: 1; Layout.preferredHeight: 24; color: Theme.borderSubtle }

            Button {
                id: pauseResumeButton
                implicitWidth: 30
                implicitHeight: 30
                focusPolicy: Qt.NoFocus
                Accessible.name: root.recState === "paused" ? "Resume recording" : "Pause recording"
                ToolTip.text: root.recState === "paused" ? "Resume" : "Pause"
                ToolTip.visible: hovered
                visible: root.recState === "recording" || root.recState === "paused"
                contentItem: Label {
                    text: root.recState === "paused" ? "▶" : "⏸"
                    color: Theme.textPrimary
                    font.pixelSize: 12
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    radius: 15
                    color: parent.hovered ? Theme.cardBg : "transparent"
                    border.color: Theme.borderSubtle
                    border.width: 1
                }
                onClicked: {
                    if (root.recState === "paused")
                        root.resumeRequested()
                    else
                        root.pauseRequested()
                }
            }

            Button {
                id: stopButton
                implicitWidth: 30
                implicitHeight: 30
                focusPolicy: Qt.NoFocus
                Accessible.name: "Stop recording"
                ToolTip.text: "Stop"
                ToolTip.visible: hovered
                visible: root.recState === "recording" || root.recState === "paused"
                contentItem: Label {
                    text: "■"
                    color: Theme.danger
                    font.pixelSize: 11
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    radius: 15
                    color: parent.hovered ? Theme.cardBg : "transparent"
                    border.color: Theme.borderSubtle
                    border.width: 1
                }
                onClicked: root.stopRequested()
            }

            Button {
                id: expandButton
                implicitWidth: 30
                implicitHeight: 30
                focusPolicy: Qt.NoFocus
                Accessible.name: "Open GravaAi window"
                ToolTip.text: "Open window"
                ToolTip.visible: hovered
                contentItem: Label {
                    text: "⧉"
                    color: Theme.textMuted
                    font.pixelSize: 13
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    radius: 15
                    color: parent.hovered ? Theme.cardBg : "transparent"
                }
                onClicked: root.openRequested()
            }
        }
    }
}
