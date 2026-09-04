import QtQuick
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Rectangle {
    id: root
    property alias body: body
    property int padding: 18
    default property alias contentData: body.data
    implicitWidth: Math.max(240, body.implicitWidth + padding * 2)
    implicitHeight: Math.max(84, body.implicitHeight + padding * 2)
    color: Theme.cardBg
    radius: Theme.radius
    border.color: Theme.borderSubtle
    border.width: 1

    ColumnLayout {
        id: body
        anchors.fill: parent
        anchors.margins: root.padding
        spacing: 10
    }
}
