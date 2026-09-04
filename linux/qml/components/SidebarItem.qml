import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Button {
    id: root
    property bool selected: false
    property string iconText: ""
    implicitHeight: 38
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
        Text {
            text: root.iconText.length > 0 ? root.iconText : root.text.slice(0, 1)
            width: 20
            color: root.selected ? Theme.accentStrong : Theme.textMuted
            font.pixelSize: 14
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            anchors.verticalCenter: parent.verticalCenter
        }
        Label {
            text: root.text
            color: root.selected ? Theme.textPrimary : Theme.textSecondary
            font.pixelSize: 13
            font.bold: root.selected
            verticalAlignment: Text.AlignVCenter
            anchors.verticalCenter: parent.verticalCenter
        }
    }
}
