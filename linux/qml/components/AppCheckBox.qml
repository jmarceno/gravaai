import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

CheckBox {
    id: root
    implicitHeight: 30
    spacing: 10
    contentItem: Label {
        text: root.text
        color: Theme.textSecondary
        font.pixelSize: 13
        verticalAlignment: Text.AlignVCenter
        leftPadding: root.indicator.width + root.spacing
        rightPadding: 4
        elide: Text.ElideRight
        wrapMode: Text.WordWrap
    }
    indicator: Rectangle {
        implicitWidth: 20
        implicitHeight: 20
        x: root.leftPadding
        y: (root.height - height) / 2
        radius: 6
        color: root.checked ? Theme.accentSoft : Theme.inputBg
        border.color: root.checked ? Theme.accent : Theme.borderSubtle
        border.width: 1
        Text {
            anchors.centerIn: parent
            text: "✓"
            color: Theme.accentStrong
            font.pixelSize: 13
            font.bold: true
            visible: root.checked
        }
    }
}
