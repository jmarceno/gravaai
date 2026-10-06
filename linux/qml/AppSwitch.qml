import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Switch {
    id: root
    implicitHeight: 32
    spacing: 12
    contentItem: Label {
        text: root.text
        color: Theme.textSecondary
        font.pixelSize: 13
        verticalAlignment: Text.AlignVCenter
        leftPadding: root.indicator.width + root.spacing
        rightPadding: 4
        wrapMode: Text.WordWrap
    }
    indicator: Rectangle {
        implicitWidth: 42
        implicitHeight: 24
        x: root.leftPadding
        y: (root.height - height) / 2
        radius: height / 2
        color: root.checked ? Theme.accent : Theme.sliderTrack
        border.color: root.checked ? Theme.accent : Theme.borderSubtle
        border.width: 1
        Rectangle {
            width: 18
            height: 18
            radius: 9
            x: root.checked ? parent.width - width - 3 : 3
            y: 3
            color: root.checked ? "#ffffff" : Theme.textMuted
            Behavior on x { NumberAnimation { duration: Theme.animationDuration } }
        }
    }
}
