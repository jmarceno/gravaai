import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import io.github.jmarceno.gravaai

// Intentionally does not call controller.bootstrap(). It renders every page
// against an in-memory controller so CI can exercise the embedded module,
// required-property contract and minimum geometry without a D-Bus/tray host.
ApplicationWindow {
    id: root
    property AppController controller: AppController {}
    property int requestedWidth: 1332
    property int requestedHeight: 820
    property int pagesChecked: 0
    property Timer smokeTimer: Timer {
        interval: 250
        repeat: false
        onTriggered: {
            var current = pages.itemAt(pages.currentIndex)
            if (!current || current.width <= 0 || current.height <= 0) {
                console.error("QML smoke geometry is not positive")
                Qt.exit(1)
            } else if (pages.currentIndex < pages.count - 1) {
                pages.currentIndex += 1
                pagesChecked += 1
                restart()
            } else {
                Qt.exit(0)
            }
        }
    }

    width: requestedWidth
    height: requestedHeight
    visible: true
    color: Theme.windowBg
    title: "GravaAI QML smoke"

    Component.onCompleted: {
        for (var i = 0; i < Qt.application.arguments.length; ++i) {
            var arg = Qt.application.arguments[i]
            if (arg.indexOf("--smoke-width=") === 0)
                requestedWidth = Number(arg.substring(14))
            if (arg.indexOf("--smoke-height=") === 0)
                requestedHeight = Number(arg.substring(15))
        }
        controller.snapshot_json = JSON.stringify({
            state: "recording",
            elapsed: 73,
            jobs: [
                { id: 1, status: "processing", label: "A long meeting title that still fits", progress: 42 },
                { id: 2, status: "error", label: "A failed job", error: "Network timeout" }
            ]
        })
        controller.settings_json = JSON.stringify({
            transcription_service: "whisper_cpp",
            summarization_service: "openai",
            output_folder: "~/meetings"
        })
        controller.meetings_json = JSON.stringify([
            { path: "/tmp/short", label: "Short", has_transcript: true, has_notes: false },
            { path: "/tmp/long", label: "A long library entry with an intentionally verbose title", has_transcript: true, has_notes: true }
        ])
        controller.installs_json = JSON.stringify([
            { key: "whisper_cpp:model", status: "Downloading 42%" },
            { key: "ollama:model", status: "Ready" }
        ])
        smokeTimer.start()
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 10
        Label { text: "QML smoke"; color: Theme.textPrimary; Layout.fillWidth: true }
        StackLayout {
            id: pages
            Layout.fillWidth: true
            Layout.fillHeight: true
            RecorderPage { id: recorderPage; controller: root.controller }
            LibraryPage { id: libraryPage; controller: root.controller }
            JobsPage { id: jobsPage; controller: root.controller }
            ModelsPage { id: modelsPage; controller: root.controller }
            PromptsPage { id: promptsPage; controller: root.controller }
            GeneralPage { id: generalPage; controller: root.controller }
            AboutPage { id: aboutPage; controller: root.controller }
        }
    }
}
