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
                { job_id: 1, status: "processing", label: "A long meeting title that still fits", status_text: "Transcribing… 42%" },
                { job_id: 2, status: "error", label: "A failed job", error_msg: "Network timeout" }
            ]
        })
        controller.settings_json = JSON.stringify({
            transcription_service: "whisper_cpp",
            whisper_cpp_model: "large-v3-turbo",
            summarization_service: "openai",
            openai_summarization_model: "gpt-5.6-luna",
            output_folder: "~/meetings",
            auto_title: true,
            auto_process_enabled: true
        })
        controller.meetings_json = JSON.stringify([
            { path: "/tmp/short", time_label: "2026-03-01_14-30", title: "Short", has_transcript: true, has_notes: false, duration_seconds: 120, audio_path: "/tmp/short/recording.mp3", transcript_path: "/tmp/short/transcript.md", notes_path: "/tmp/short/notes.md" },
            { path: "/tmp/long", time_label: "2026-03-02_09-00", title: "A long library entry with an intentionally verbose title", has_transcript: true, has_notes: true, duration_seconds: 2520, audio_path: "/tmp/long/recording.mp3", transcript_path: "/tmp/long/transcript.md", notes_path: "/tmp/long/notes.md" }
        ])
        controller.installs_json = JSON.stringify([
            { key: "whisper_cpp:model", status: "Downloading 42%" },
            { key: "ollama:model", status: "Ready" }
        ])
        controller.engine_status_json = JSON.stringify({
            base_dir: "/tmp/gravaai-data",
            payloads: [
                { name: "whisper.cpp engine (whisper-cli)", kind: "engine", path: "/tmp/gravaai-data/whisper.cpp", path_is_dir: true, size_bytes: 12300000, present: true },
                { name: "ggml-large-v3-turbo.bin", kind: "model", path: "/tmp/gravaai-data/whisper-cpp-models/ggml-large-v3-turbo.bin", path_is_dir: false, size_bytes: 1600000000, present: true },
                { name: "phi4-mini:latest", kind: "model", path: "/tmp/ollama-store", path_is_dir: true, size_bytes: 2500000000, present: true }
            ],
            whisper: { engine_installed: true, engine_path: "/tmp/gravaai-data/whisper.cpp", engine_size_bytes: 12300000, models_dir: "/tmp/gravaai-data/whisper-cpp-models", models_url: "https://example.invalid" },
            crispasr: { engine_installed: false, engine_path: "/tmp/gravaai-data/crisp-asr", engine_size_bytes: 0, models_dir: "/tmp/gravaai-data/crisp-asr-models", models_url: "https://example.invalid" },
            ollama: { installed: true, binary_path: "/tmp/gravaai-data/ollama/ollama", serving: true, host: "http://localhost:11434", models_dir: "/tmp/ollama-store", models: [{ name: "phi4-mini:latest", size: 2500000000 }] }
        })
        smokeTimer.start()
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 10
        Label { text: "QML smoke"; color: Theme.textPrimary; Layout.fillWidth: true }
        RecordingPill {
            recState: "recording"
            elapsedSeconds: 73
            countdownSeconds: 0
        }
        StackLayout {
            id: pages
            Layout.fillWidth: true
            Layout.fillHeight: true
            RecorderPage { id: recorderPage; controller: root.controller }
            LibraryPage { id: libraryPage; controller: root.controller }
            JobsPage { id: jobsPage; controller: root.controller }
            ModelsPage { id: modelsPage; controller: root.controller }
            DownloadsPage { id: downloadsPage; controller: root.controller }
            PromptsPage { id: promptsPage; controller: root.controller }
            GeneralPage { id: generalPage; controller: root.controller }
        }
    }
}
