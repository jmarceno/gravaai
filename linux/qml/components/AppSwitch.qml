import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Switch {
    id: root
    implicitHeight: 32
    contentItem: Label {
        text: root.text
        color: Theme.textSecondary
        font.pixelSize: 13
        verticalAlignment: Text.AlignVCenter
        leftPadding: 10
    }
    indicator: Rectangle {
        implicitWidth: 42
        implicitHeight: 24
        x: root.leftPadding
        y: parent.height / 2 - height / 2
        radius: height / 2
        color: root.checked ? Theme.accent : Theme.sliderTrack
        border.color: root.checked ? Theme.accent : Theme.borderSubtle
        Rectangle {
            width: 18
            height: 18
            radius: 9
            x: root.checked ? parent.width - width - 3 : 3
            y: 3
            color: root.checked ? Theme.accentText : Theme.textMuted
            Behavior on x { NumberAnimation { duration: Theme.animationDuration } }
        }
    }
}
