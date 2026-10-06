import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt.labs.platform as Platform
import io.github.jmarceno.gravaai

// Recorder dashboard. Layout is responsive: the side column (pipeline +
// background jobs) sits next to the recorder when there is room and moves
// below it otherwise, so nothing is squeezed into a too-narrow column.
Item {
    id: root
    required property AppController controller
    property var snapshot: ({})
    property var cfg: ({})
    property var meetings: []
    property var audioSources: []
    property var selectedDevices: []
    property string captureMode: "headphones"
    readonly property bool wide: width >= 760
    Layout.fillWidth: true
    Layout.fillHeight: true

    readonly property var captureModes: [
        { value: "headphones", label: "Mic + system", hint: "Records your microphone and everything the computer plays. Use with headphones; your voice and the other participants are kept on separate channels." },
        { value: "speaker", label: "Mic only", hint: "Records only the microphone. Use when the call plays through speakers, to avoid echo." },
        { value: "custom", label: "Custom", hint: "Records exactly the devices you select below." }
    ]

    function updateSnapshot() {
        try { root.snapshot = JSON.parse(controller.snapshot_json) }
        catch (error) { root.snapshot = {} }
    }
    function updateCfg() {
        try { root.cfg = JSON.parse(controller.settings_json) } catch (e) { root.cfg = {} }
        if (root.cfg.custom_devices) root.selectedDevices = root.cfg.custom_devices.slice()
    }
    function updateMeetings() {
        try { root.meetings = JSON.parse(controller.meetings_json) } catch (e2) { root.meetings = [] }
    }
    function updateAudioSources() {
        try { root.audioSources = JSON.parse(controller.audio_sources_json) } catch (e) { root.audioSources = [] }
    }
    function isSelected(name) { return root.selectedDevices.indexOf(name) >= 0 }
    function toggleDevice(name, checked) {
        var cur = root.selectedDevices.slice()
        var i = cur.indexOf(name)
        if (checked && i < 0) cur.push(name)
        if (!checked && i >= 0) cur.splice(i, 1)
        root.selectedDevices = cur
        root.controller.saveCustomDevices(JSON.stringify(cur))
    }
    function start() {
        root.controller.setTitle(titleField.text)
        if (root.captureMode === "custom") {
            root.controller.saveCustomDevices(JSON.stringify(root.selectedDevices))
            root.controller.startCustomRecording(JSON.stringify(root.selectedDevices))
        } else {
            root.controller.startRecording(root.captureMode)
        }
    }
    function timeLabel(seconds) {
        var s = Math.max(0, Math.floor(Number(seconds || 0)))
        var h = Math.floor(s / 3600)
        var mm = Math.floor((s % 3600) / 60)
        var ss = s % 60
        var core = (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss
        return h > 0 ? h + ":" + core : core
    }
    function durationLabel(secs) {
        var s = Number(secs || 0)
        if (s <= 0) return ""
        if (s < 60) return s + "s"
        var m = Math.floor(s / 60)
        if (m < 60) return m + " min"
        return Math.floor(m / 60) + "h " + (m % 60) + "m"
    }
    function friendlyTime(timeLabel) {
        var m = String(timeLabel || "").match(/(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})/)
        if (!m) return String(timeLabel || "")
        return m[3] + "/" + m[2] + " · " + m[4] + ":" + m[5]
    }
    function transcriptionLabel() {
        var svc = cfg.transcription_service || "whisper_cpp"
        if (svc === "openai") return "Cloud · " + (cfg.openai_transcription_model || "whisper-1")
        if (svc === "crisp_asr") return "CrispASR · " + (cfg.crisp_asr_model || "nemotron")
        return "whisper.cpp · " + (cfg.whisper_cpp_model || "large-v3-turbo")
    }
    function summaryLabel() {
        var svc = cfg.summarization_service || "openai"
        if (svc === "ollama") return "Ollama · " + (cfg.ollama_model || "phi4-mini")
        return "Cloud · " + (cfg.openai_summarization_model || "gpt-5.6-luna")
    }
    readonly property string recState: snapshot.state || "idle"
    function stateLabel() {
        if (recState === "recording") return "Recording"
        if (recState === "paused") return "Paused"
        if (recState === "countdown") return "Starting processing"
        return "Ready"
    }
    function stateColor() {
        if (recState === "recording") return Theme.danger
        if (recState === "paused" || recState === "countdown") return Theme.warning
        return Theme.statusGreen
    }
    function jobErrorText(j) { return j.error_msg || j.error || j.message || "" }
    function jobId(j) { return (j.job_id !== undefined) ? j.job_id : (j.id !== undefined ? j.id : -1) }
    function jobFor(m) {
        var jobs = snapshot.jobs || []
        for (var i = 0; i < jobs.length; i += 1)
            if (jobs[i].audio_dir === m.path && jobs[i].status === "processing") return jobs[i]
        return null
    }
    function audioFor(m) { return m.audio_path || (m.path + "/recording.mp3") }
    function transcriptFor(m) { return m.transcript_path || (m.path + "/transcript.md") }
    function notesFor(m) { return m.notes_path || (m.path + "/notes.md") }
    function meetingTitle(m) { return (m.title && m.title.length > 0) ? m.title : "Meeting " + friendlyTime(m.time_label) }

    Component.onCompleted: { updateSnapshot(); updateCfg(); updateMeetings(); updateAudioSources(); controller.refreshAudioSources() }
    property Connections controllerConnection: Connections {
        target: root.controller
        function onSnapshot_jsonChanged() { root.updateSnapshot() }
        function onSettings_jsonChanged() { root.updateCfg() }
        function onMeetings_jsonChanged() { root.updateMeetings() }
        function onAudio_sources_jsonChanged() { root.updateAudioSources() }
    }

    property Platform.FileDialog importDialog: Platform.FileDialog {
        title: "Import recording"
        nameFilters: ["Audio recordings (*.mp3 *.wav *.m4a *.ogg *.flac *.webm)", "All files (*)"]
        onAccepted: {
            var audio = decodeURIComponent(String(file).replace(/^file:\/\//, ""))
            root.controller.importExisting(audio, "", "", "Imported recording")
        }
    }

    Flickable {
        id: flick
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.height + 8
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: flick.contentHeight > flick.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff }

        ColumnLayout {
            id: contentColumn
            width: flick.width - 14
            spacing: 14

            GridLayout {
                Layout.fillWidth: true
                columns: root.wide ? 2 : 1
                columnSpacing: 14
                rowSpacing: 14

                // ---------------- Recorder ----------------
                AppCard {
                    Layout.fillWidth: true
                    Layout.preferredWidth: root.wide ? 3 : 1
                    Layout.alignment: Qt.AlignTop
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Recorder"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
                        StatusBadge {
                            labelText: root.stateLabel()
                            dotColor: root.stateColor()
                            pillBg: root.recState === "idle" ? Theme.statusGreenBg : (root.recState === "recording" ? Theme.dangerBg : Theme.warningBg)
                        }
                    }

                    // Timer + live input level.
                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 84
                        radius: Theme.radiusSm
                        color: Theme.inputBg
                        border.color: Theme.borderSubtle
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 16
                            anchors.rightMargin: 16
                            spacing: 16
                            Label {
                                text: root.timeLabel(root.snapshot.elapsed)
                                color: Theme.textPrimary; font.pixelSize: 28; font.bold: true
                                font.family: "monospace"
                            }
                            AudioLevelMeter {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignVCenter
                                audioLevel: Number(root.snapshot.audio_level || 0)
                                recState: root.recState
                            }
                        }
                    }

                    ColumnLayout {
                        visible: root.recState === "idle"
                        Layout.fillWidth: true
                        spacing: 10
                        AppField {
                            id: titleField
                            Layout.fillWidth: true
                            label: "Meeting title (optional)"
                            placeholderText: "Leave blank to name it from the notes"
                        }
                        Label { text: "What to record"; color: Theme.textSecondary; font.pixelSize: 12 }
                        // Segmented control: equal-width, short labels, never overflows.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 6
                            Repeater {
                                model: root.captureModes
                                delegate: Button {
                                    id: modeButton
                                    required property var modelData
                                    property bool active: root.captureMode === modelData.value
                                    Layout.fillWidth: true
                                    Layout.preferredWidth: 1
                                    implicitHeight: 38
                                    onClicked: {
                                        root.captureMode = modelData.value
                                        if (modelData.value === "custom") root.controller.refreshAudioSources()
                                    }
                                    background: Rectangle {
                                        radius: Theme.radiusSm
                                        color: modeButton.active ? Theme.accentSoft : (modeButton.hovered ? Theme.cardBgRaised : Theme.inputBg)
                                        border.color: modeButton.active ? Theme.accent : Theme.borderSubtle
                                        border.width: 1
                                    }
                                    contentItem: Label {
                                        text: modeButton.modelData.label
                                        color: modeButton.active ? Theme.textPrimary : Theme.textSecondary
                                        font.pixelSize: 13
                                        font.bold: modeButton.active
                                        horizontalAlignment: Text.AlignHCenter
                                        verticalAlignment: Text.AlignVCenter
                                        elide: Text.ElideRight
                                    }
                                }
                            }
                        }
                        Label {
                            text: root.captureModes[Math.max(0, ["headphones", "speaker", "custom"].indexOf(root.captureMode))].hint
                            color: Theme.textMuted; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true
                        }

                        ColumnLayout {
                            visible: root.captureMode === "custom"
                            Layout.fillWidth: true
                            spacing: 4
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8
                                Label { text: root.selectedDevices.length + " device(s) selected"; color: Theme.textMuted; font.pixelSize: 11; Layout.fillWidth: true }
                                AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 28; onClicked: root.controller.refreshAudioSources() }
                            }
                            Label {
                                visible: root.audioSources.length === 0
                                text: "No audio devices found — check that PipeWire/PulseAudio is running, then press Refresh."
                                color: Theme.warning; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                            }
                            Repeater {
                                model: root.audioSources
                                delegate: AppCheckBox {
                                    required property var modelData
                                    Layout.fillWidth: true
                                    text: (modelData.description || modelData.name) + (modelData.is_monitor ? "  (system audio)" : "")
                                    checked: root.isSelected(modelData.name)
                                    onToggled: root.toggleDevice(modelData.name, checked)
                                }
                            }
                        }
                    }

                    Label {
                        visible: root.recState === "countdown" || (root.recState !== "idle" && (root.snapshot.status || "").length > 0)
                        Layout.fillWidth: true
                        text: root.recState === "countdown"
                              ? "Processing starts in " + Number(root.snapshot.countdown || 0) + " s"
                              : (root.snapshot.status || "")
                        color: Theme.textMuted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                    }

                    // Primary controls.
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        AppButton {
                            visible: root.recState === "idle"
                            text: "Start recording"
                            variant: "danger"
                            Layout.fillWidth: true
                            implicitHeight: 44
                            enabled: root.captureMode !== "custom" || root.selectedDevices.length > 0
                            onClicked: root.start()
                        }
                        AppButton {
                            visible: root.recState === "idle"
                            text: "Import file…"
                            variant: "secondary"
                            implicitHeight: 44
                            onClicked: root.importDialog.open()
                        }
                        AppButton { visible: root.recState === "recording"; Layout.preferredWidth: 1; text: "Pause"; variant: "secondary"; Layout.fillWidth: true; implicitHeight: 44; onClicked: root.controller.pauseRecording() }
                        AppButton { visible: root.recState === "paused"; Layout.preferredWidth: 1; text: "Resume"; variant: "teal"; Layout.fillWidth: true; implicitHeight: 44; onClicked: root.controller.resumeRecording() }
                        AppButton { visible: root.recState === "recording" || root.recState === "paused"; Layout.preferredWidth: 1; text: "Stop"; variant: "danger"; Layout.fillWidth: true; implicitHeight: 44; onClicked: root.controller.stopRecording() }
                        AppButton { visible: root.recState === "countdown"; text: "Don't process"; variant: "secondary"; Layout.fillWidth: true; implicitHeight: 44; onClicked: root.controller.cancelCountdown() }
                    }
                    RowLayout {
                        visible: root.recState === "recording" || root.recState === "paused"
                        Layout.fillWidth: true
                        spacing: 8
                        AppButton { text: "Stop, save audio only"; variant: "secondary"; Layout.fillWidth: true; Layout.preferredWidth: 1; implicitHeight: 34; onClicked: root.controller.cancelAndSave() }
                        AppButton { text: "Discard"; variant: "secondary"; Layout.fillWidth: true; Layout.preferredWidth: 1; implicitHeight: 34; onClicked: root.controller.cancelAndDiscard() }
                    }
                }

                // ---------------- Side column ----------------
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.preferredWidth: root.wide ? 2 : 1
                    Layout.alignment: Qt.AlignTop
                    spacing: 14

                    AppCard {
                        Layout.fillWidth: true
                        Label { text: "After recording"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true }
                        Label {
                            text: (root.cfg.auto_process_enabled !== false) ? "These run automatically when you stop." : "Automatic processing is off — only the audio is saved."
                            color: Theme.textMuted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                        }
                        Repeater {
                            model: [
                                { n: "1", title: "Transcript", detail: root.transcriptionLabel() },
                                { n: "2", title: "Notes", detail: root.summaryLabel() },
                                { n: "3", title: "Title", detail: (root.cfg.auto_title !== false) ? "Named from the notes" : "Off" }
                            ]
                            delegate: RowLayout {
                                required property var modelData
                                Layout.fillWidth: true
                                spacing: 10
                                Rectangle {
                                    width: 24; height: 24; radius: 12
                                    color: "transparent"; border.color: Theme.accent; border.width: 1
                                    Label { anchors.centerIn: parent; text: modelData.n; color: Theme.accentStrong; font.pixelSize: 11; font.bold: true }
                                }
                                ColumnLayout {
                                    spacing: 0
                                    Layout.fillWidth: true
                                    Label { text: modelData.title; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                    Label { text: modelData.detail; color: Theme.textMuted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                                }
                            }
                        }
                        AppButton {
                            text: "Models & services"
                            variant: "secondary"
                            Layout.fillWidth: true
                            implicitHeight: 34
                            onClicked: root.controller.selectPage("models")
                        }
                    }

                    AppCard {
                        Layout.fillWidth: true
                        Label { text: "Background jobs"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true }
                        Label {
                            visible: (root.snapshot.jobs || []).length === 0
                            text: "Nothing running."
                            color: Theme.textMuted; font.pixelSize: 12
                        }
                        Repeater {
                            model: (root.snapshot.jobs || []).slice(0, 4)
                            delegate: ColumnLayout {
                                id: jobRow
                                required property var modelData
                                spacing: 4
                                Layout.fillWidth: true
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 8
                                    Rectangle { width: 8; height: 8; radius: 4; color: jobRow.modelData.status === "error" ? Theme.danger : (jobRow.modelData.status === "done" ? Theme.statusGreen : Theme.warning) }
                                    Label { text: jobRow.modelData.label || "Meeting"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                }
                                Label {
                                    text: jobRow.modelData.status === "error"
                                          ? (root.jobErrorText(jobRow.modelData) || "Failed")
                                          : (jobRow.modelData.status === "done" ? "Done" : (jobRow.modelData.status_text || "Processing…"))
                                    color: jobRow.modelData.status === "error" ? Theme.dangerStrong : Theme.textMuted
                                    font.pixelSize: 11
                                    wrapMode: Text.WordWrap
                                    maximumLineCount: 4
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                                AppProgressBar {
                                    Layout.fillWidth: true
                                    indeterminate: true
                                    visible: jobRow.modelData.status === "processing"
                                }
                                Flow {
                                    Layout.fillWidth: true
                                    spacing: 6
                                    AppButton { text: "Cancel"; variant: "secondary"; implicitHeight: 28; visible: jobRow.modelData.status === "processing"; onClicked: root.controller.cancelJob(root.jobId(jobRow.modelData)) }
                                    AppButton { text: "Retry"; variant: "secondary"; implicitHeight: 28; visible: jobRow.modelData.status === "error"; onClicked: root.controller.retryJob(root.jobId(jobRow.modelData)) }
                                    AppButton { text: "Open"; variant: "secondary"; implicitHeight: 28; visible: jobRow.modelData.status === "done"; onClicked: root.controller.openJobFolder(root.jobId(jobRow.modelData)) }
                                    AppButton { text: "Dismiss"; variant: "secondary"; implicitHeight: 28; visible: jobRow.modelData.status !== "processing"; onClicked: root.controller.dismissJob(root.jobId(jobRow.modelData)) }
                                }
                            }
                        }
                        Label { text: "Jobs keep running when this window is closed."; color: Theme.textDim; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                    }
                }
            }

            // ---------------- Recent meetings ----------------
            AppCard {
                Layout.fillWidth: true
                RowLayout {
                    Layout.fillWidth: true
                    Label { text: "Recent meetings"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
                    AppButton { text: "View library"; variant: "secondary"; implicitHeight: 30; onClicked: root.controller.selectPage("library") }
                }
                Label {
                    visible: root.meetings.length === 0
                    text: "No meetings yet. Completed recordings will appear here."
                    color: Theme.textMuted; font.pixelSize: 12
                }
                Repeater {
                    model: root.meetings.slice(0, 3)
                    delegate: RowLayout {
                        id: row
                        required property var modelData
                        property var job: root.jobFor(modelData)
                        Layout.fillWidth: true
                        spacing: 8
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1
                            Label { text: root.meetingTitle(row.modelData); color: Theme.textPrimary; font.pixelSize: 13; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                            Label {
                                text: row.job ? (row.job.status_text || "Processing…")
                                      : root.friendlyTime(row.modelData.time_label) + (row.modelData.duration_seconds ? " · " + root.durationLabel(row.modelData.duration_seconds) : "")
                                color: row.job ? Theme.warning : Theme.textMuted
                                font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true
                            }
                        }
                        AppButton {
                            visible: row.modelData.has_notes
                            text: "Notes"
                            variant: "secondary"
                            implicitHeight: 30
                            onClicked: root.controller.openFile(root.notesFor(row.modelData))
                        }
                        AppButton {
                            visible: !row.modelData.has_notes
                            text: row.modelData.has_transcript ? "Summarize" : "Process"
                            variant: "teal"
                            implicitHeight: 30
                            enabled: row.job === null && (row.modelData.has_transcript || row.modelData.has_audio !== false)
                            onClicked: root.controller.summarizeMeeting(root.audioFor(row.modelData), root.transcriptFor(row.modelData), root.notesFor(row.modelData), root.meetingTitle(row.modelData))
                        }
                        AppButton {
                            text: "Folder"
                            variant: "secondary"
                            implicitHeight: 30
                            onClicked: root.controller.openMeetingFolder(row.modelData.path)
                        }
                    }
                }
            }
        }
    }
}
