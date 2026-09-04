import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Button {
    id: root
    property bool selected: false
    implicitHeight: 36
    leftPadding: 14
    rightPadding: 12
    background: Rectangle {
        radius: Theme.radiusSm
        color: root.selected ? Theme.accentSoft : (root.hovered ? Theme.cardBgRaised : "transparent")
        border.color: root.selected ? Theme.accentMuted : "transparent"
        border.width: 1
    }
    contentItem: Row {
        spacing: 10
        Label {
            text: root.text.slice(0, 1)
            width: 18
            color: root.selected ? Theme.accent : Theme.textMuted
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
        }
        Label {
            text: root.text
            color: root.selected ? Theme.textPrimary : Theme.textSecondary
            font.pixelSize: 13
            verticalAlignment: Text.AlignVCenter
        }
    }
}
