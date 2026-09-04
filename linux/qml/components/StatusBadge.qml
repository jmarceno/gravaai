import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Label {
    id: root
    property color dotColor: Theme.statusGreen
    text: "●  " + labelText
    property string labelText: "Ready"
    color: dotColor
    font.pixelSize: 12
    font.bold: true
    padding: 8
    leftPadding: 10
    rightPadding: 10
    background: Rectangle {
        radius: Theme.radiusSm
        color: Theme.accentSoft
        border.color: Theme.accentMuted
        border.width: 1
    }
}
