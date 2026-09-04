import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

ColumnLayout {
    id: root
    property alias label: label.text
    property alias text: field.text
    property alias placeholderText: field.placeholderText
    property bool password: false
    spacing: 5
    implicitWidth: 300
    implicitHeight: label.implicitHeight + field.implicitHeight + spacing

    Label {
        id: label
        color: Theme.textSecondary
        font.pixelSize: 12
        Layout.fillWidth: true
    }
    TextField {
        id: field
        Layout.fillWidth: true
        implicitHeight: 36
        color: Theme.textPrimary
        placeholderTextColor: Theme.textDim
        echoMode: root.password ? TextInput.Password : TextInput.Normal
        background: Rectangle {
            radius: Theme.radiusSm
            color: Theme.inputBg
            border.color: field.activeFocus ? Theme.accent : Theme.borderSubtle
            border.width: field.activeFocus ? 2 : 1
        }
    }
}
