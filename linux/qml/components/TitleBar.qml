import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import io.github.jmarceno.gravaai

Rectangle {
    id: root
    required property var window
    required property AppController controller
    implicitHeight: 48
    color: Theme.windowBg

    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton
        onPressed: if (mouse.button === Qt.LeftButton) root.window.startSystemMove()
        onDoubleClicked: {
            if (root.window.visibility === Window.Maximized) root.window.showNormal()
            else root.window.showMaximized()
        }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 10
        spacing: 10
        Image {
            source: "qrc:/qt/qml/io/github/jmarceno/gravaai/assets/icons/hicolor/scalable/apps/gravaai.svg"
            sourceSize.width: 24
            sourceSize.height: 24
            Layout.preferredWidth: 24
            Layout.preferredHeight: 24
        }
        Label {
            text: "Grava Aí"
            color: Theme.textPrimary
            font.pixelSize: 15
            font.bold: true
            Layout.fillWidth: true
        }
        Row {
            spacing: 4
            Repeater {
                model: ["—", "□", "✕"]
                delegate: Button {
                    required property string modelData
                    text: modelData
                    implicitWidth: 32
                    implicitHeight: 28
                    flat: true
                    background: Rectangle {
                        radius: 6
                        color: parent.hovered ? (modelData === "✕" ? Theme.danger : Theme.cardBgRaised) : "transparent"
                    }
                    contentItem: Text {
                        text: parent.text
                        color: Theme.textSecondary
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                        font.pixelSize: 13
                    }
                    onClicked: {
                        if (modelData === "—") root.window.showMinimized()
                        else if (modelData === "□") {
                            if (root.window.visibility === Window.Maximized) root.window.showNormal()
                            else root.window.showMaximized()
                        } else root.window.requestCloseWindow()
                    }
                }
            }
        }
    }
}
