import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import io.github.jmarceno.gravaai

// Models & services. Local engines first: every step a job needs (engine,
// model, server) is a row that says whether it is ready and offers the one
// action that makes it ready. Every control saves immediately as a partial
// settings patch, so nothing is lost by forgetting a Save button.
Item {
    id: root
    required property AppController controller
    property var cfg: ({})
    property var installs: []
    property var status: ({})
    Layout.fillWidth: true
    Layout.fillHeight: true

    readonly property var sttServices: [
        { value: "whisper_cpp", label: "Local · whisper.cpp (recommended)" },
        { value: "crisp_asr", label: "Local · CrispASR Nemotron (experimental, English)" },
        { value: "openai", label: "Cloud · OpenAI-compatible API" }
    ]
    readonly property var chatServices: [
        { value: "ollama", label: "Local · Ollama" },
        { value: "openai", label: "Cloud · OpenAI-compatible API" }
    ]
    readonly property var whisperModels: [
        { value: "large-v3-turbo", label: "large-v3-turbo · ~1.6 GB · best speed/quality" },
        { value: "large-v3", label: "large-v3 · ~3 GB · most accurate, slow on CPU" },
        { value: "medium", label: "medium · ~1.5 GB · balanced" },
        { value: "small", label: "small · ~470 MB · fastest, lower accuracy" }
    ]
    readonly property var crispModels: [
        { value: "nemotron-3.5-asr-0.6b-q8_0", label: "Q8 · ~750 MB · default", file: "nemotron-3.5-asr-streaming-0.6b-q8_0.gguf" },
        { value: "nemotron-3.5-asr-0.6b-q4_k", label: "Q4_K · ~430 MB · smaller", file: "nemotron-3.5-asr-streaming-0.6b-q4_k.gguf" },
        { value: "nemotron-3.5-asr-0.6b-f16", label: "F16 · ~1.3 GB · full precision", file: "nemotron-3.5-asr-streaming-0.6b-f16.gguf" }
    ]
    readonly property var crispBackends: ["auto", "cpu", "vulkan", "cuda"]
    readonly property var ollamaModels: [
        { value: "phi4-mini", label: "phi4-mini · ~3 GB · light, good quality" },
        { value: "gemma3:4b", label: "gemma3:4b · ~4 GB · good quality" },
        { value: "qwen2.5:7b", label: "qwen2.5:7b · ~5 GB · very capable" },
        { value: "llama3.1:8b", label: "llama3.1:8b · ~5 GB · very capable" },
        { value: "gemma3:12b", label: "gemma3:12b · ~8 GB · best, needs lots of RAM" },
        { value: "granite4:350m", label: "granite4:350m · ~700 MB · tiny, fast" }
    ]
    readonly property var timeouts: [1, 2, 3, 5, 8, 10]

    function readData() {
        try { root.cfg = JSON.parse(controller.settings_json) } catch (error) { root.cfg = {} }
        try { root.installs = JSON.parse(controller.installs_json) } catch (error2) { root.installs = [] }
    }
    function readStatus() {
        try { root.status = JSON.parse(controller.engine_status_json) } catch (error) { root.status = {} }
    }
    function patch(values) {
        // Partial patch: the controller merges it onto the stored config.
        controller.saveSettings(JSON.stringify(values), true)
    }
    function indexOf(list, value) {
        for (var i = 0; i < list.length; i += 1)
            if ((list[i].value !== undefined ? list[i].value : list[i]) === value) return i
        return 0
    }
    function labels(list) {
        return list.map(function(x) { return x.label !== undefined ? x.label : String(x) })
    }
    function install(kind, model, backend, host) {
        controller.startInstall(JSON.stringify({ kind: kind, model: model || "", backend: backend || "", host: host || "" }))
        controller.refreshInstalls()
    }
    function installFor(key) {
        for (var i = 0; i < root.installs.length; i += 1)
            if (root.installs[i].key === key) return root.installs[i]
        return null
    }
    function installText(key) {
        var i = installFor(key)
        return i ? (i.status || i.text || "Working…") : ""
    }
    function payloadNamed(name) {
        var p = root.status.payloads || []
        for (var i = 0; i < p.length; i += 1)
            if (p[i].name === name && p[i].present !== false) return p[i]
        return null
    }
    function fmtSize(bytes) {
        var b = Number(bytes || 0)
        if (b <= 0) return ""
        if (b < 1048576) return Math.max(1, Math.round(b / 1024)) + " KB"
        if (b < 1073741824) return (b / 1048576).toFixed(1) + " MB"
        return (b / 1073741824).toFixed(2) + " GB"
    }
    readonly property bool statusKnown: !!root.status.base_dir
    readonly property string sttService: root.cfg.transcription_service || "whisper_cpp"
    readonly property string chatService: root.cfg.summarization_service || "openai"
    readonly property string whisperModel: root.cfg.whisper_cpp_model || "large-v3-turbo"
    readonly property string crispModel: root.cfg.crisp_asr_model || "nemotron-3.5-asr-0.6b-q8_0"
    readonly property string crispBackend: root.cfg.crisp_asr_backend || "auto"
    readonly property string ollamaModel: root.cfg.ollama_model || "phi4-mini"
    readonly property string ollamaHost: root.cfg.ollama_host || "http://localhost:11434"
    readonly property bool usesCloud: sttService === "openai" || chatService === "openai"

    function whisperModelFile() { return "ggml-" + root.whisperModel + ".bin" }
    function crispModelFile() { return root.crispModels[root.indexOf(root.crispModels, root.crispModel)].file }
    function ollamaHasModel() {
        var models = (root.status.ollama || {}).models || []
        for (var i = 0; i < models.length; i += 1) {
            var n = String(models[i].name || "")
            if (n === root.ollamaModel || n === root.ollamaModel + ":latest") return true
        }
        return false
    }

    Component.onCompleted: { readData(); readStatus() }
    property Connections controllerConnection: Connections {
        target: root.controller
        function onSettings_jsonChanged() { root.readData() }
        function onInstalls_jsonChanged() { root.readData() }
        function onEngine_status_jsonChanged() { root.readStatus() }
    }

    // One "is this ready?" row: state dot, title + detail, and an action.
    component StepRow: RowLayout {
        id: step
        property string title: ""
        property string detail: ""
        property bool ready: false
        property string busyText: ""
        property string actionText: ""
        signal action()
        Layout.fillWidth: true
        spacing: 12
        Rectangle {
            Layout.alignment: Qt.AlignVCenter
            width: 10; height: 10; radius: 5
            color: step.busyText.length > 0 ? Theme.warning : (step.ready ? Theme.statusGreen : Theme.textDim)
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 1
            Label { text: step.title; color: Theme.textPrimary; font.pixelSize: 13; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
            Label {
                text: step.busyText.length > 0 ? step.busyText : step.detail
                color: step.busyText.length > 0 ? Theme.warning : Theme.textMuted
                font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true
                visible: text.length > 0
            }
        }
        AppButton {
            visible: step.actionText.length > 0 && step.busyText.length === 0
            text: step.actionText
            variant: step.ready ? "secondary" : "primary"
            implicitHeight: 32
            onClicked: step.action()
        }
    }

    component FieldLabel: Label {
        color: Theme.textSecondary
        font.pixelSize: 12
        Layout.preferredWidth: 110
        Layout.alignment: Qt.AlignVCenter
        elide: Text.ElideRight
    }

    Flickable {
        id: flick
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.implicitHeight + 8
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: flick.contentHeight > flick.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff }
        ColumnLayout {
            id: contentColumn
            width: flick.width - 14
            spacing: 14

            // ---------------- Transcription ----------------
            AppCard {
                Layout.fillWidth: true
                RowLayout {
                    Layout.fillWidth: true
                    Label { text: "Transcription"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true; Layout.fillWidth: true }
                }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    FieldLabel { text: "Engine" }
                    AppComboBox {
                        Layout.fillWidth: true
                        model: root.labels(root.sttServices)
                        currentIndex: root.indexOf(root.sttServices, root.sttService)
                        onActivated: function(i) { root.patch({ transcription_service: root.sttServices[i].value }) }
                    }
                }

                // whisper.cpp
                ColumnLayout {
                    visible: root.sttService === "whisper_cpp"
                    Layout.fillWidth: true
                    spacing: 12
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FieldLabel { text: "Model" }
                        AppComboBox {
                            Layout.fillWidth: true
                            model: root.labels(root.whisperModels)
                            currentIndex: root.indexOf(root.whisperModels, root.whisperModel)
                            onActivated: function(i) { root.patch({ whisper_cpp_model: root.whisperModels[i].value }) }
                        }
                    }
                    Rectangle { Layout.fillWidth: true; height: 1; color: Theme.borderSubtle }
                    StepRow {
                        title: "whisper.cpp engine"
                        ready: !!(root.status.whisper || {}).engine_installed
                        detail: !root.statusKnown ? "Checking…" : (ready ? "Installed · " + root.fmtSize((root.status.whisper || {}).engine_size_bytes) : "Not installed — prebuilt CPU binary, no compiler needed")
                        busyText: root.installText("whisper_cpp_engine")
                        actionText: ready ? "Reinstall" : "Install"
                        onAction: root.install("whisper_cpp_engine", "", "auto", "")
                    }
                    StepRow {
                        property var payload: root.payloadNamed(root.whisperModelFile())
                        title: "Model " + root.whisperModel
                        ready: payload !== null
                        detail: !root.statusKnown ? "Checking…" : (ready ? "Downloaded · " + root.fmtSize(payload.size_bytes) : "Not downloaded")
                        busyText: root.installText("whisper_cpp_model:" + root.whisperModel)
                        actionText: ready ? "" : "Download"
                        onAction: root.install("whisper_cpp_model", root.whisperModel, "", "")
                    }
                    Label {
                        text: "The spoken language is detected automatically. Transcription runs on the CPU; large models take a while on long meetings."
                        color: Theme.textDim; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true
                    }
                }

                // CrispASR
                ColumnLayout {
                    visible: root.sttService === "crisp_asr"
                    Layout.fillWidth: true
                    spacing: 12
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FieldLabel { text: "Model" }
                        AppComboBox {
                            Layout.fillWidth: true
                            model: root.labels(root.crispModels)
                            currentIndex: root.indexOf(root.crispModels, root.crispModel)
                            onActivated: function(i) { root.patch({ crisp_asr_model: root.crispModels[i].value }) }
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FieldLabel { text: "Backend" }
                        AppComboBox {
                            Layout.fillWidth: true
                            model: root.crispBackends
                            currentIndex: Math.max(0, root.crispBackends.indexOf(root.crispBackend))
                            onActivated: function(i) { root.patch({ crisp_asr_backend: root.crispBackends[i] }) }
                        }
                    }
                    Rectangle { Layout.fillWidth: true; height: 1; color: Theme.borderSubtle }
                    StepRow {
                        title: "CrispASR engine"
                        ready: !!(root.status.crispasr || {}).engine_installed
                        detail: !root.statusKnown ? "Checking…" : (ready ? "Installed · " + root.fmtSize((root.status.crispasr || {}).engine_size_bytes) : "Not installed")
                        busyText: root.installText("crisp_asr_engine")
                        actionText: ready ? "Reinstall" : "Install"
                        onAction: root.install("crisp_asr_engine", "", root.crispBackend, "")
                    }
                    StepRow {
                        property var payload: root.payloadNamed(root.crispModelFile())
                        title: "Model " + root.crispModel
                        ready: payload !== null
                        detail: !root.statusKnown ? "Checking…" : (ready ? "Downloaded · " + root.fmtSize(payload.size_bytes) : "Not downloaded")
                        busyText: root.installText("crisp_asr_model:" + root.crispModel)
                        actionText: ready ? "" : "Download"
                        onAction: root.install("crisp_asr_model", root.crispModel, "", "")
                    }
                }

                Label {
                    visible: root.sttService === "openai"
                    text: "Uses the cloud service configured below."
                    color: Theme.textMuted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                }
            }

            // ---------------- Summarization ----------------
            AppCard {
                Layout.fillWidth: true
                Label { text: "Summary & notes"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    FieldLabel { text: "Engine" }
                    AppComboBox {
                        Layout.fillWidth: true
                        model: root.labels(root.chatServices)
                        currentIndex: root.indexOf(root.chatServices, root.chatService)
                        onActivated: function(i) { root.patch({ summarization_service: root.chatServices[i].value }) }
                    }
                }
                ColumnLayout {
                    visible: root.chatService === "ollama"
                    Layout.fillWidth: true
                    spacing: 12
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FieldLabel { text: "Model" }
                        AppComboBox {
                            Layout.fillWidth: true
                            model: root.labels(root.ollamaModels)
                            currentIndex: root.indexOf(root.ollamaModels, root.ollamaModel)
                            onActivated: function(i) { root.patch({ ollama_model: root.ollamaModels[i].value }) }
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FieldLabel { text: "Server" }
                        TextField {
                            id: hostField
                            Layout.fillWidth: true
                            implicitHeight: 38
                            text: root.ollamaHost
                            color: Theme.textPrimary
                            placeholderText: "http://localhost:11434"
                            placeholderTextColor: Theme.textDim
                            font.pixelSize: 13
                            leftPadding: 12
                            background: Rectangle { radius: Theme.radiusSm; color: Theme.inputBg; border.color: hostField.activeFocus ? Theme.accent : Theme.borderSubtle; border.width: hostField.activeFocus ? 2 : 1 }
                            onEditingFinished: if (text.trim() !== root.ollamaHost) root.patch({ ollama_host: text.trim() })
                        }
                    }
                    Rectangle { Layout.fillWidth: true; height: 1; color: Theme.borderSubtle }
                    StepRow {
                        property var o: root.status.ollama || {}
                        title: "Ollama runtime"
                        ready: (!!o.installed || !!o.serving) && !o.incomplete
                        detail: !root.statusKnown ? "Checking…"
                              : (o.incomplete ? "Incomplete install without GPU support — reinstall to fix slow summaries"
                              : (o.serving ? "Running at " + (o.host || root.ollamaHost)
                              : (o.installed ? "Installed — starts automatically when a job needs it" : "Not installed")))
                        busyText: root.installText("ollama")
                        actionText: o.incomplete ? "Reinstall" : (ready ? "" : "Install")
                        onAction: root.install("ollama", "", "", "")
                    }
                    StepRow {
                        title: "Model " + root.ollamaModel
                        ready: root.ollamaHasModel()
                        detail: !root.statusKnown ? "Checking…" : (ready ? "Downloaded" : "Not downloaded")
                        busyText: root.installText("ollama_model:" + root.ollamaModel)
                        actionText: ready ? "" : "Download"
                        onAction: root.install("ollama_model", root.ollamaModel, "", root.ollamaHost)
                    }
                }
                Label {
                    visible: root.chatService === "openai"
                    text: "Uses the cloud service configured below."
                    color: Theme.textMuted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                }
            }

            // ---------------- Cloud ----------------
            AppCard {
                visible: root.usesCloud
                Layout.fillWidth: true
                Label { text: "Cloud service (OpenAI-compatible)"; color: Theme.textPrimary; font.pixelSize: 16; font.bold: true }
                Label {
                    visible: !root.cfg.openai_api_key
                    text: "No API key set — cloud jobs will fail until you add one."
                    color: Theme.warning; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true
                }
                AppField {
                    Layout.fillWidth: true
                    label: "API key"
                    password: true
                    text: root.cfg.openai_api_key || ""
                    onEditingFinished: if (text !== (root.cfg.openai_api_key || "")) root.patch({ openai_api_key: text.trim() })
                }
                AppField {
                    Layout.fillWidth: true
                    label: "Base URL"
                    placeholderText: "https://api.openai.com/v1"
                    text: root.cfg.openai_base_url || ""
                    onEditingFinished: if (text !== (root.cfg.openai_base_url || "")) root.patch({ openai_base_url: text.trim() })
                }
                AppField {
                    visible: root.sttService === "openai"
                    Layout.fillWidth: true
                    label: "Speech-to-text model"
                    text: root.cfg.openai_transcription_model || "whisper-1"
                    onEditingFinished: if (text !== root.cfg.openai_transcription_model) root.patch({ openai_transcription_model: text.trim() })
                }
                AppField {
                    visible: root.chatService === "openai"
                    Layout.fillWidth: true
                    label: "Chat model"
                    text: root.cfg.openai_summarization_model || "gpt-5.6-luna"
                    onEditingFinished: if (text !== root.cfg.openai_summarization_model) root.patch({ openai_summarization_model: text.trim() })
                }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10
                    FieldLabel { text: "Timeout" }
                    AppComboBox {
                        Layout.fillWidth: true
                        model: root.timeouts.map(function(m) { return m + (m === 1 ? " minute" : " minutes") })
                        currentIndex: Math.max(0, root.timeouts.indexOf(Number(root.cfg.llm_request_timeout_minutes || 5)))
                        onActivated: function(i) { root.patch({ llm_request_timeout_minutes: root.timeouts[i] }) }
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Label { text: "Changes are saved automatically."; color: Theme.textDim; font.pixelSize: 11; Layout.fillWidth: true }
                AppButton { text: "Refresh status"; variant: "secondary"; implicitHeight: 30; onClicked: root.controller.refreshEngineStatus() }
            }
        }
    }
}
