import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Button {
    id: root
    implicitHeight: 38
    leftPadding: 16
    rightPadding: 16
    topPadding: 8
    bottomPadding: 8
    font.pixelSize: 13
    font.bold: true
    background: Rectangle {
        radius: Theme.radiusSm
        color: root.enabled
               ? (root.down ? Theme.hover : (root.hovered ? Theme.hover : Theme.accent))
               : Theme.cardBgRaised
        border.color: root.enabled ? Theme.accent : Theme.borderSubtle
        border.width: 1
    }
    contentItem: Text {
        text: root.text
        color: root.enabled ? Theme.accentText : Theme.textDim
        font: root.font
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
}
