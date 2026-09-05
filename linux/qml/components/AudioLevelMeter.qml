import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

// Live capture-level meter driven by the daemon's `audio_level` snapshot
// field (0.0–1.0, ~10 Hz while recording, 0 otherwise). This is real meter
// data from the same PulseAudio/PipeWire sources being recorded — not an
// animation. When the bar stays flat while recording, nothing is reaching
// the recorder (muted mic, wrong device, monitor of a silent sink).
Item {
    id: root
    // 0.0–1.0 live level. NaN/undefined snapshots clamp to silence.
    property double audioLevel: 0
    // Recording lifecycle state ("recording", "paused", "idle", "countdown").
    property string recState: "idle"
    // Compact variant for the pill overlay (thinner bar, no labels).
    property bool compact: false

    property double clamped: Math.max(0, Math.min(1, Number(root.audioLevel) || 0))
    property bool active: root.recState === "recording"
    property color fillColor: root.active ? Theme.danger : Theme.sliderTrack

    implicitHeight: root.compact ? 4 : 46
    Layout.fillWidth: true

    ColumnLayout {
        anchors.fill: parent
        spacing: 4
        visible: !root.compact

        RowLayout {
            Layout.fillWidth: true
            spacing: 6
            Label {
                text: "INPUT LEVEL"
                color: Theme.textDim
                font.pixelSize: 10
                font.bold: true
                font.letterSpacing: 0.8
                Layout.fillWidth: true
            }
            Label {
                text: root.active ? (root.clamped < 0.02 ? "no signal" : "live") : ""
                color: root.clamped < 0.02 && root.active ? Theme.warning : Theme.textMuted
                font.pixelSize: 10
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 10
            radius: 5
            color: Theme.inputBg
            border.color: Theme.borderSubtle
            border.width: 1
            clip: true

            Rectangle {
                id: fill
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: parent.width * root.clamped
                radius: 5
                color: root.fillColor
                opacity: root.active ? 0.95 : 0.6
                Behavior on width { NumberAnimation { duration: 90; easing.type: Easing.OutQuad } }
            }

            // Peak-zone marker at ~90%: levels pinned here are likely clipping.
            Rectangle {
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: Math.max(2, parent.width * 0.1)
                color: "transparent"
                border.color: Theme.warning
                border.width: 0
                Rectangle {
                    width: 1
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    color: Theme.warning
                    opacity: 0.7
                }
            }
        }

        Label {
            visible: root.active && root.clamped < 0.02
            text: "Flat while recording means nothing is being captured — check the mic and capture mode."
            color: Theme.warning
            font.pixelSize: 11
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
        }
    }

    // Compact bar used inside the recording-pill overlay.
    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Theme.inputBg
        border.color: Theme.borderSubtle
        border.width: 1
        clip: true
        visible: root.compact

        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            width: parent.width * root.clamped
            radius: parent.radius
            color: root.fillColor
            opacity: root.active ? 0.95 : 0.5
            Behavior on width { NumberAnimation { duration: 90; easing.type: Easing.OutQuad } }
        }
    }
}
