import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    Layout.fillWidth: true
    Layout.fillHeight: true

    ColumnLayout {
        anchors.fill: parent
        spacing: 16
        AppCard {
            Layout.fillWidth: true
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10
                Image {
                    source: "qrc:/qt/qml/io/github/jmarceno/gravaai/assets/icons/hicolor/scalable/apps/gravaai.svg"
                    sourceSize.width: 72
                    sourceSize.height: 72
                    Layout.preferredWidth: 72
                    Layout.preferredHeight: 72
                    Layout.alignment: Qt.AlignHCenter
                    Accessible.name: "Grava Aí application icon"
                }
                Label {
                    text: "Grava Aí"
                    color: Theme.textPrimary
                    font.pixelSize: 26
                    font.bold: true
                    Layout.alignment: Qt.AlignHCenter
                }
                Label {
                    text: "Meeting recorder, transcription and organized notes"
                    color: Theme.textSecondary
                    wrapMode: Text.WordWrap
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
                Label {
                    text: "Version " + (Qt.application.version || "development")
                    color: Theme.textMuted
                    Layout.alignment: Qt.AlignHCenter
                }
                Label {
                    text: "The daemon keeps recordings and background work running while this Qt window is closed."
                    color: Theme.textMuted
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }
            }
        }
        Label {
            text: "Grava Aí is distributed as a self-contained AppImage. Optional whisper.cpp, Ollama and model files are installed only when requested."
            color: Theme.textDim
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
        }
        Item { Layout.fillHeight: true }
    }
}
