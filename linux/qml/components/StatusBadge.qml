import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Label {
    id: root
    property color dotColor: Theme.statusGreen
    property string labelText: "Ready"
    property string pillBg: Theme.statusGreenBg
    text: "●  " + labelText
    color: dotColor
    font.pixelSize: 12
    font.bold: true
    padding: 8
    leftPadding: 12
    rightPadding: 12
    background: Rectangle {
        radius: 12
        color: root.pillBg
        border.color: root.dotColor
        border.width: 1
        opacity: 0.95
    }
}
