import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import io.github.jmarceno.gravaai

Item {
    id: root
    required property AppController controller
    property var snapshot: ({})
    property var cfg: ({})
    property var meetings: []
    property var audioSources: []
    property var selectedDevices: []
    property string captureMode: "headphones"
    Layout.fillWidth: true
    Layout.fillHeight: true

    function updateSnapshot() {
        try { root.snapshot = JSON.parse(controller.snapshot_json) }
        catch (error) { root.snapshot = {} }
    }
    function updateCfg() {
        try { root.cfg = JSON.parse(controller.settings_json) } catch (e) { root.cfg = {} }
        try { root.meetings = JSON.parse(controller.meetings_json) } catch (e2) { root.meetings = [] }
        if (root.cfg.custom_devices) root.selectedDevices = root.cfg.custom_devices.slice()
    }
    function updateAudioSources() {
        try { root.audioSources = JSON.parse(controller.audio_sources_json) } catch (e) { root.audioSources = [] }
    }
    function isSelected(name) {
        return root.selectedDevices.indexOf(name) >= 0
    }
    function toggleDevice(name, checked) {
        var cur = root.selectedDevices.slice()
        var i = cur.indexOf(name)
        if (checked && i < 0) cur.push(name)
        if (!checked && i >= 0) cur.splice(i, 1)
        root.selectedDevices = cur
        root.controller.saveCustomDevices(JSON.stringify(cur))
    }
    function startCustom() {
        root.controller.setTitle(titleField.text)
        root.controller.saveCustomDevices(JSON.stringify(root.selectedDevices))
        root.controller.startCustomRecording(JSON.stringify(root.selectedDevices))
    }
    function timeLabel(seconds) {
        var s = Math.max(0, Math.floor(Number(seconds || 0)))
        var mm = Math.floor(s / 60)
        var ss = s % 60
        return (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss
    }
    function durationLabel(secs) {
        var s = Number(secs || 0)
        if (s <= 0) return ""
        if (s < 60) return s + "s"
        var m = Math.floor(s / 60)
        if (m < 60) return m + "m"
        var h = Math.floor(m / 60)
        return h + "h " + (m % 60) + "m"
    }
    function friendlyTime(path, timeLabel) {
        var t = String(timeLabel || "")
        var m = t.match(/(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})/)
        if (!m) return t
        return m[3] + "/" + m[2] + " · " + m[4] + ":" + m[5]
    }
    function transcriptionLabel() {
        var svc = cfg.transcription_service || "whisper_cpp"
        if (svc === "openai") return "openai · " + (cfg.openai_transcription_model || "whisper-1")
        if (svc === "crisp_asr") return "CrispASR · " + (cfg.crisp_asr_model || "nemotron-3.5-asr-0.6b-q8_0")
        return "whisper.cpp · " + (cfg.whisper_cpp_model || "large-v3-turbo")
    }
    function summaryLabel() {
        var svc = cfg.summarization_service || "openai"
        if (svc === "ollama") return "Ollama · " + (cfg.ollama_model || "qwen2.5:7b")
        return "openai · " + (cfg.openai_summarization_model || "gpt-5.6-luna")
    }
    function stateLabel() {
        var st = snapshot.state || "idle"
        if (st === "recording") return "Recording"
        if (st === "paused") return "Paused"
        if (st === "countdown") return "Processing"
        return "Ready"
    }
    function stateColor() {
        var st = snapshot.state || "idle"
        if (st === "recording") return Theme.danger
        if (st === "paused") return Theme.warning
        return Theme.statusGreen
    }
    function jobErrorText(j) {
        return j.error_msg || j.error || j.message || ""
    }
    function jobId(j) {
        return (j.job_id !== undefined) ? j.job_id : (j.id !== undefined ? j.id : -1)
    }
    function audioFor(m) {
        return m.audio_path || (m.path + "/recording.mp3")
    }
    function transcriptFor(m) {
        return m.transcript_path || (m.path + "/transcript.md")
    }
    function notesFor(m) {
        return m.notes_path || (m.path + "/notes.md")
    }
    function meetingTitle(m) {
        return (m.title && m.title.length > 0) ? m.title : (m.time_label || "Meeting")
    }
    function transcribe(m) {
        root.controller.transcribeMeeting(audioFor(m), transcriptFor(m), notesFor(m), meetingTitle(m))
    }
    function summarize(m) {
        root.controller.summarizeMeeting(audioFor(m), transcriptFor(m), notesFor(m), meetingTitle(m))
    }

    Component.onCompleted: { updateSnapshot(); updateCfg(); updateAudioSources(); controller.refreshAudioSources() }
    property Connections controllerConnection: Connections {
        target: root.controller
        function onSnapshot_jsonChanged() { root.updateSnapshot() }
        function onSettings_jsonChanged() { root.updateCfg() }
        function onMeetings_jsonChanged() { root.updateCfg() }
        function onAudio_sources_jsonChanged() { root.updateAudioSources() }
    }

    property FileDialog importDialog: FileDialog {
        title: "Import recording"
        nameFilters: ["Audio recordings (*.mp3 *.wav *.m4a *.ogg *.flac *.webm)", "All files (*)"]
        onAccepted: {
            var audio = selectedFile.toLocalFile()
            root.controller.importExisting(audio, "", "", "Imported recording")
        }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.implicitHeight
        clip: true
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: contentColumn
            width: root.width
            spacing: 14

            RowLayout {
                Layout.fillWidth: true
                spacing: 14

                // ---- Left: Recorder ----
                AppCard {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 2
                    Layout.alignment: Qt.AlignTop
                    ColumnLayout {
                        spacing: 10
                        RowLayout {
                            Layout.fillWidth: true
                            Label { text: "Recorder"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
                            StatusBadge {
                                labelText: root.stateLabel()
                                dotColor: root.stateColor()
                                pillBg: root.stateLabel() === "Ready" ? Theme.statusGreenBg : (root.stateLabel() === "Recording" ? Theme.dangerBg : Theme.warningBg)
                            }
                        }
                        Label { text: "MEETING TITLE"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; font.letterSpacing: 0.8 }
                        TextField {
                            id: titleField
                            Layout.fillWidth: true
                            implicitHeight: 40
                            placeholderText: "Optional — leave blank to auto-title later"
                            color: Theme.textPrimary
                            placeholderTextColor: Theme.textDim
                            font.pixelSize: 13
                            background: Rectangle {
                                radius: Theme.radiusSm
                                color: Theme.inputBg
                                border.color: titleField.activeFocus ? Theme.accent : Theme.borderSubtle
                                border.width: titleField.activeFocus ? 2 : 1
                            }
                        }
                        Label { text: "CAPTURE MODE"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; font.letterSpacing: 0.8 }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            Rectangle {
                                Layout.fillWidth: true
                                implicitHeight: 58
                                radius: Theme.radiusSm
                                color: root.captureMode === "headphones" ? Theme.accentSoft : Theme.inputBg
                                border.color: root.captureMode === "headphones" ? Theme.accent : Theme.borderSubtle
                                border.width: 1
                                RowLayout {
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 10
                                    Text { text: "🎧"; font.pixelSize: 18 }
                                    ColumnLayout {
                                        spacing: 1
                                        Label { text: "Headphones"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: "Mic + system audio"; color: Theme.textMuted; font.pixelSize: 11 }
                                    }
                                }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.captureMode = "headphones" }
                            }
                            Rectangle {
                                Layout.fillWidth: true
                                implicitHeight: 58
                                radius: Theme.radiusSm
                                color: root.captureMode === "speaker" ? Theme.accentSoft : Theme.inputBg
                                border.color: root.captureMode === "speaker" ? Theme.accent : Theme.borderSubtle
                                border.width: 1
                                RowLayout {
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 10
                                    Text { text: "🔈"; font.pixelSize: 18; color: Theme.textMuted }
                                    ColumnLayout {
                                        spacing: 1
                                        Label { text: "Speaker"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: "Microphone only"; color: Theme.textMuted; font.pixelSize: 11 }
                                    }
                                }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.captureMode = "speaker" }
                            }
                            Rectangle {
                                Layout.fillWidth: true
                                implicitHeight: 58
                                radius: Theme.radiusSm
                                color: root.captureMode === "custom" ? Theme.accentSoft : Theme.inputBg
                                border.color: root.captureMode === "custom" ? Theme.accent : Theme.borderSubtle
                                border.width: 1
                                RowLayout {
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 10
                                    Text { text: "🎛️"; font.pixelSize: 18 }
                                    ColumnLayout {
                                        spacing: 1
                                        Label { text: "Custom"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: "Choose devices"; color: Theme.textMuted; font.pixelSize: 11 }
                                    }
                                }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: { root.captureMode = "custom"; root.controller.refreshAudioSources() } }
                            }
                        }
                        Label {
                            text: "Headphones captures mic + system; Speaker is mic-only; Custom records every device you select below."
                            color: Theme.textMuted; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true
                        }
                        ColumnLayout {
                            visible: root.captureMode === "custom"
                            Layout.fillWidth: true
                            spacing: 6
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8
                                Label { text: "AUDIO DEVICES"; color: Theme.textDim; font.pixelSize: 10; font.bold: true; font.letterSpacing: 0.8; Layout.fillWidth: true }
                                Label { text: root.selectedDevices.length + " selected"; color: Theme.textMuted; font.pixelSize: 11 }
                                AppButton { text: "Refresh"; variant: "secondary"; implicitHeight: 30; onClicked: root.controller.refreshAudioSources() }
                            }
                            Label {
                                visible: root.audioSources.length === 0
                                text: "No audio devices found — check that PipeWire/PulseAudio is running, then press Refresh."
                                color: Theme.warning; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                            }
                            Repeater {
                                model: root.audioSources
                                delegate: ColumnLayout {
                                    required property var modelData
                                    property string devName: modelData.name || ""
                                    Layout.fillWidth: true
                                    spacing: 0
                                    AppCheckBox {
                                        text: (modelData.description || devName) + (modelData.is_monitor ? " (monitor)" : "")
                                        checked: root.isSelected(devName)
                                        onToggled: root.toggleDevice(devName, checked)
                                    }
                                    Label { text: devName; color: Theme.textDim; font.pixelSize: 10; elide: Text.ElideMiddle; Layout.fillWidth: true; leftPadding: 30 }
                                }
                            }
                            Label {
                                visible: root.audioSources.length > 0 && root.selectedDevices.length === 0
                                text: "Select at least one device to record."
                                color: Theme.warning; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                            }
                        }
                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: 62
                            radius: Theme.radiusSm
                            color: Theme.inputBg
                            border.color: Theme.borderSubtle
                            border.width: 1
                            RowLayout {
                                anchors.fill: parent
                                anchors.margins: 14
                                spacing: 16
                                Label {
                                    text: root.timeLabel(snapshot.elapsed)
                                    color: Theme.textPrimary; font.pixelSize: 26; font.bold: true
                                    font.family: "monospace"
                                }
                                Row {
                                    Layout.fillWidth: true
                                    Layout.alignment: Qt.AlignVCenter
                                    spacing: 3
                                    Repeater {
                                        model: [10, 18, 26, 14, 30, 20, 36, 22, 14, 28, 12, 24, 18, 32, 14, 26, 20, 10, 22, 16, 28, 12, 24, 18, 30, 14, 20, 26, 12, 18]
                                        delegate: Rectangle {
                                            width: 3
                                            height: modelData
                                            radius: 2
                                            anchors.verticalCenter: parent.verticalCenter
                                            color: (snapshot.state === "recording") ? Theme.danger : Theme.sliderTrack
                                            opacity: (snapshot.state === "recording") ? 0.95 : 1.0
                                        }
                                    }
                                }
                            }
                        }
                        Label {
                            visible: (snapshot.state === "countdown") || ((snapshot.status || "").length > 0)
                            Layout.fillWidth: true
                            text: snapshot.state === "countdown"
                                  ? "Processing starts in " + Number(snapshot.countdown || 0) + " seconds"
                                  : (snapshot.status || "")
                            color: Theme.textMuted
                            font.pixelSize: 12
                            wrapMode: Text.WordWrap
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            AppButton {
                                text: "●   Start recording"
                                variant: "danger"
                                visible: (snapshot.state || "idle") === "idle"
                                Layout.fillWidth: true
                                implicitHeight: 44
                                enabled: root.captureMode !== "custom" || root.selectedDevices.length > 0
                                opacity: enabled ? 1.0 : 0.45
                                onClicked: {
                                    if (root.captureMode === "custom") {
                                        root.startCustom()
                                    } else {
                                        root.controller.setTitle(titleField.text)
                                        root.controller.startRecording(root.captureMode === "speaker" ? "speaker" : "headphones")
                                    }
                                }
                            }
                            AppButton { text: "Pause"; variant: "secondary"; visible: snapshot.state === "recording"; Layout.fillWidth: true; onClicked: root.controller.pauseRecording() }
                            AppButton { text: "Resume"; variant: "teal"; visible: snapshot.state === "paused"; Layout.fillWidth: true; onClicked: root.controller.resumeRecording() }
                            AppButton { text: "Stop"; variant: "secondary"; visible: snapshot.state === "recording" || snapshot.state === "paused"; onClicked: root.controller.stopRecording() }
                            AppButton { text: "Cancel countdown"; variant: "secondary"; visible: snapshot.state === "countdown"; Layout.fillWidth: true; onClicked: root.controller.cancelCountdown() }
                            AppButton {
                                text: "Import recording"
                                variant: "secondary"
                                visible: (snapshot.state || "idle") === "idle"
                                Layout.preferredWidth: 170
                                implicitHeight: 44
                                onClicked: importDialog.open()
                            }
                        }
                        RowLayout {
                            visible: snapshot.state === "recording" || snapshot.state === "paused" || snapshot.state === "countdown"
                            Layout.fillWidth: true
                            spacing: 10
                            AppButton { text: "Save audio"; variant: "secondary"; visible: snapshot.state === "recording" || snapshot.state === "paused"; Layout.fillWidth: true; onClicked: root.controller.cancelAndSave() }
                            AppButton { text: "Discard"; variant: "secondary"; visible: snapshot.state === "recording" || snapshot.state === "paused" || snapshot.state === "countdown"; Layout.fillWidth: true; onClicked: root.controller.cancelAndDiscard() }
                            AppButton { text: "Import"; variant: "secondary"; visible: false; onClicked: importDialog.open() }
                        }
                    }
                }

                // ---- Right column ----
                ColumnLayout {
                    Layout.preferredWidth: 1
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignTop
                    spacing: 14

                    AppCard {
                        Layout.fillWidth: true
                        ColumnLayout {
                            spacing: 8
                            Label { text: "Processing pipeline"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true }
                            Label { text: (cfg.auto_process_enabled !== false) ? "Runs automatically after the recording stops." : "Automatic processing is off — audio is saved only."; color: Theme.textMuted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                            ColumnLayout {
                                spacing: 10
                                Layout.topMargin: 6
                                RowLayout {
                                    spacing: 10
                                    Rectangle {
                                        width: 26; height: 26; radius: 13
                                        color: "transparent"; border.color: Theme.accent; border.width: 1
                                        Label { anchors.centerIn: parent; text: "1"; color: Theme.accentStrong; font.pixelSize: 12; font.bold: true }
                                    }
                                    ColumnLayout {
                                        spacing: 1; Layout.fillWidth: true
                                        Label { text: "Transcription"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: root.transcriptionLabel(); color: Theme.textMuted; font.pixelSize: 11 }
                                    }
                                }
                                RowLayout {
                                    spacing: 10
                                    Rectangle {
                                        width: 26; height: 26; radius: 13
                                        color: "transparent"; border.color: Theme.warning; border.width: 1
                                        Label { anchors.centerIn: parent; text: "2"; color: Theme.warning; font.pixelSize: 12; font.bold: true }
                                    }
                                    ColumnLayout {
                                        spacing: 1; Layout.fillWidth: true
                                        Label { text: "Summary"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: root.summaryLabel(); color: Theme.textMuted; font.pixelSize: 11 }
                                    }
                                }
                                RowLayout {
                                    spacing: 10
                                    Rectangle {
                                        width: 26; height: 26; radius: 13
                                        color: "transparent"; border.color: Theme.textDim; border.width: 1
                                        Label { anchors.centerIn: parent; text: "3"; color: Theme.textMuted; font.pixelSize: 12; font.bold: true }
                                    }
                                    ColumnLayout {
                                        spacing: 1; Layout.fillWidth: true
                                        Label { text: "Auto-title"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true }
                                        Label { text: (cfg.auto_title !== false) ? "Generated from meeting notes" : "Auto-title is off"; color: Theme.textMuted; font.pixelSize: 11 }
                                        Label { text: "Output: " + (cfg.output_folder || "~/meetings"); color: Theme.textMuted; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
                                    }
                                }
                            }
                            AppButton {
                                text: "Configure services"
                                variant: "secondary"
                                Layout.fillWidth: true
                                Layout.topMargin: 6
                                onClicked: controller.selectPage("models")
                            }
                        }
                    }

                    AppCard {
                        Layout.fillWidth: true
                        ColumnLayout {
                            spacing: 8
                            Label { text: "Background jobs"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true }
                            Label {
                                visible: (snapshot.jobs || []).length === 0
                                text: "No background jobs running."
                                color: Theme.textMuted; font.pixelSize: 12
                            }
                            ColumnLayout {
                                visible: (snapshot.jobs || []).length > 0
                                spacing: 10
                                Layout.fillWidth: true
                                Repeater {
                                    model: (snapshot.jobs || []).slice(0, 3)
                                    delegate: ColumnLayout {
                                        required property var modelData
                                        spacing: 4
                                        Layout.fillWidth: true
                                        RowLayout {
                                            Layout.fillWidth: true
                                            spacing: 8
                                            Rectangle { width: 8; height: 8; radius: 4; color: Theme.accentStrong }
                                            Label { text: modelData.label || "Meeting"; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                        }
                                        Label { text: modelData.status_text || modelData.status || "Processing"; color: Theme.textMuted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                                        AppProgressBar {
                                            Layout.fillWidth: true
                                            indeterminate: (modelData.status || "processing") === "processing"
                                            value: 68
                                            visible: (modelData.status || "processing") === "processing"
                                        }
                                        RowLayout {
                                            spacing: 6
                                            AppButton { text: "Cancel"; variant: "secondary"; implicitHeight: 30; visible: modelData.status === "processing"; onClicked: root.controller.cancelJob(root.jobId(modelData)) }
                                            AppButton { text: "Retry"; variant: "secondary"; implicitHeight: 30; visible: modelData.status === "error"; onClicked: root.controller.retryJob(root.jobId(modelData)) }
                                            AppButton { text: "Open"; variant: "secondary"; implicitHeight: 30; visible: modelData.status === "done"; onClicked: root.controller.openJobFolder(root.jobId(modelData)) }
                                            AppButton { text: "Dismiss"; variant: "secondary"; implicitHeight: 30; visible: modelData.status !== "processing"; onClicked: root.controller.dismissJob(root.jobId(modelData)) }
                                        }
                                    }
                                }
                            }
                            Label { text: "Processing continues if this window is closed."; color: Theme.textDim; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        }
                    }
                }
            }

            // ---- Recent meetings ----
            AppCard {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 8
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: "Recent meetings"; color: Theme.textPrimary; font.pixelSize: 15; font.bold: true; Layout.fillWidth: true }
                        Button {
                            text: "View library"
                            flat: true
                            contentItem: Label { text: parent.text; color: Theme.accentStrong; font.pixelSize: 12; font.bold: true }
                            background: Rectangle { color: "transparent" }
                            onClicked: controller.selectPage("library")
                        }
                    }
                    Label {
                        visible: root.meetings.length === 0
                        text: "No meetings yet. Completed recordings will appear here."
                        color: Theme.textMuted; font.pixelSize: 12
                    }
                    Repeater {
                        model: root.meetings.slice(0, 3)
                        delegate: RowLayout {
                            required property var modelData
                            Layout.fillWidth: true
                            spacing: 8
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 1
                                Label { text: modelData.title && modelData.title.length > 0 ? modelData.title : (modelData.time_label || "Meeting"); color: Theme.textPrimary; font.pixelSize: 13; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                                Label { text: root.friendlyTime(modelData.path, modelData.time_label) + (modelData.duration_seconds ? " · " + root.durationLabel(modelData.duration_seconds) : ""); color: Theme.textMuted; font.pixelSize: 11 }
                            }
                            AppButton {
                                text: "Transcribe"
                                variant: "teal"
                                implicitHeight: 30
                                visible: !modelData.has_transcript
                                enabled: modelData.has_audio !== false
                                opacity: enabled ? 1.0 : 0.45
                                onClicked: root.transcribe(modelData)
                            }
                            AppButton {
                                text: "Summarize"
                                variant: "teal"
                                implicitHeight: 30
                                enabled: modelData.has_transcript || modelData.has_audio !== false
                                opacity: enabled ? 1.0 : 0.45
                                onClicked: root.summarize(modelData)
                            }
                            AppButton {
                                text: "Transcript"
                                variant: "secondary"
                                implicitHeight: 30
                                visible: modelData.has_transcript
                                onClicked: root.controller.openFile(root.transcriptFor(modelData))
                            }
                            AppButton {
                                text: "Notes"
                                variant: "secondary"
                                implicitHeight: 30
                                visible: modelData.has_notes
                                onClicked: root.controller.openFile(root.notesFor(modelData))
                            }
                            Button {
                                text: "›"
                                implicitWidth: 30; implicitHeight: 30
                                contentItem: Label { text: parent.text; color: Theme.textMuted; font.pixelSize: 16; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                background: Rectangle { radius: 8; color: parent.hovered ? Theme.cardBgRaised : "transparent" }
                                onClicked: root.controller.openMeetingFolder(modelData.path)
                            }
                        }
                    }
                }
            }
        }
    }
}
