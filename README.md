# Meeting Recorder

A meeting recorder that transcribes audio and generates structured notes using
any OpenAI-compatible API, fully local Whisper/Ollama engines, or a mix of both.

This repository holds the Linux desktop app (Rust, GTK4 + libadwaita):

| Path | Contents |
|---|---|
| `linux/` | GTK4 + libadwaita desktop app (Rust, Linux) |
| `linux/assets/` | Tray artwork + hicolor app icons |
| `linux/packaging/` | Arch PKGBUILD + launcher/icon assets only |

---

## Linux App

### Features

- **Record** system audio + microphone simultaneously, or microphone only
- **Transcribe** with any OpenAI-compatible endpoint or local whisper.cpp (timestamped transcript)
- **Summarize** into structured Markdown notes with any OpenAI-compatible endpoint or local Ollama
- **Summarize from the library** — re-run summarization for any past meeting from the meetings browser
- **Local models** — run fully offline with no API key required
- **Customizable prompts** — edit transcription and summarization prompts in Settings
- **System tray** integration — a StatusNotifierItem (SNI) exposed over D-Bus; left-click opens the window where the host supports it, otherwise opens the menu
- **Lightweight background daemon** — the app runs as a small GTK-free tray daemon, so recording, transcription, and model installs keep running in the background even with no window open. Closing the window returns to the tray; reopening (tray "Open" or the app icon) shows the current state (an in-progress model install still shows its progress).
- **Low memory mode** — by default the window stays loaded in the background so reopening is instant; enable Low memory mode (Settings → General) to unload it on close instead, so the tray daemon idles at roughly a fifth of the memory at the cost of a brief delay when you reopen
- **Call detection** — optionally monitor for active calls and get notified to start recording
- **Start at system startup** — optionally launch the tray daemon automatically on login

### Output Structure

Each recording session creates a folder:

```
~/meetings/
└── 2026/
    └── March/
        └── 04/
            └── 14-30_Standup/
                ├── recording.mp3
                ├── transcript.md
                └── notes.md
```

### Requirements

- Linux, x86_64 or arm64, with a system tray (or an AppIndicator extension) for the tray icon.
- These programs on your `PATH` (install them with your distro's packages —
  the app never installs system software, it tells you what is missing):
  `ffmpeg` (audio recording), `pactl` (audio device detection, PipeWire/PulseAudio),
  `curl` (engine/model downloads), `tar` (engine unpacking),
  plus the GTK4 + libadwaita runtime (`gtk4`, `libadwaita`, `libnotify`).
  The Rust toolchain is only required to build from source (see below).

> **Look & theming:** the app uses **libadwaita**, so it follows your system **light/dark** preference and renders in the Adwaita style. On non-GNOME desktops (KDE, XFCE, Cinnamon, …) it still runs perfectly but keeps the Adwaita look rather than matching a custom desktop theme — this is libadwaita's intended behavior.
- No runtime beyond system libraries: the app ships as a single static-ish binary (`meeting-recorder`).

The base install is **cloud-only and minimal** — no local engines or GPU libraries are installed by default. Each local option below is installed **on demand** from **Settings → Models** when you choose it.

| Service | Requirement |
|---|---|
| **OpenAI-compatible** (transcription + summarization) | Base URL + API key for any `/v1`-style endpoint (OpenAI, Azure OpenAI, LiteLLM, llama.cpp server, …) — no local install |

> Your API key is stored in the system keyring (GNOME Keyring / KWallet) when one is available, falling back to a permission-restricted config file otherwise.
| **whisper.cpp** (local transcription) | Engine downloaded as an official prebuilt CPU binary on opt-in; GGML model downloaded from HuggingFace. No compiler or system packages needed |
| **Ollama** (local summarization) | [Ollama](https://ollama.com) installed and running (`ollama serve`); uses NVIDIA/AMD GPU automatically |

### Installation

#### Option 1: native package

Download the package from the [Releases](../../releases) page.

**Arch / Manjaro (.pkg.tar.zst)**
```bash
sudo pacman -U meeting-recorder-*.pkg.tar.zst
# To uninstall:
sudo pacman -R meeting-recorder
```

The package installs a single `meeting-recorder` binary with
**only the OpenAI-compatible essentials**. The local transcription engine
(whisper.cpp, prebuilt download) and Ollama are installed later, on
demand, from **Settings → Models** — no compiler ever required.

#### Option 2: standalone binary

Download `meeting-recorder-v<version>-<arch>` from the
[Releases](../../releases) page, make it executable and put it on your
`PATH` — that single file is the whole app (daemon, window, tray):

```bash
chmod +x meeting-recorder-v*-x86_64
mkdir -p ~/.local/bin
cp meeting-recorder-v*-x86_64 ~/.local/bin/meeting-recorder
```

To uninstall (removes the binary, desktop entries, icons, autostart entry,
engines, models, logs, config and the stored API key — recordings are kept):

```bash
meeting-recorder --uninstall
```

Then launch either way:

```bash
meeting-recorder
# or from your application menu: "Meeting Recorder"
```

> **GNOME users:** The tray is a StatusNotifierItem (SNI), and GNOME has no built-in SNI host, so the icon needs the AppIndicator/KStatusNotifierItem extension to appear. Install your distro's package for it (e.g. on Arch `sudo pacman -S gnome-shell-extension-appindicator`), then enable it in the GNOME Extensions app and log out/in.
>
> Whether **left-click focuses the window** or **opens the menu** is decided by the SNI host: hosts that deliver the `Activate` action (e.g. KDE Plasma) focus the window, while the GNOME extension typically opens the menu on any click. KStatusNotifierItem-capable panels on XFCE, MATE, Cinnamon, KDE, LXQt, … show the icon natively without an extension.

### Running from Source (developers)

Building from source requires the Rust toolchain and is only needed for
development — regular installs never compile anything:

```bash
cd meeting-recorder
cargo build --manifest-path linux/Cargo.toml
./linux/target/debug/meeting-recorder
```

`meeting-recorder` (no flag) is **client** mode: it starts the background
daemon if needed and opens a window. To run the pieces directly, use
`meeting-recorder --daemon` (the GTK-free tray daemon) and
`meeting-recorder --window` (the GTK window, normally spawned by the daemon).

### Recording Modes

| Mode | What is captured | When to use |
|------|-----------------|-------------|
| **Record (Headphones)** | Microphone + system audio (calls, browser, etc.) | You're wearing headphones — no echo risk |
| **Record (Speaker)** | Microphone only | Laptop speakers — avoids loopback echo |

### Services

#### Transcription

| Service | How it works | Requires |
|---|---|---|
| **OpenAI-compatible** | Audio sent to `/audio/transcriptions` | Base URL + API key |
| **whisper.cpp** | Runs locally via a prebuilt whisper.cpp binary (default) | Engine + GGML model downloaded in Settings → Models |

#### Summarization

| Service | How it works | Requires |
|---|---|---|
| **OpenAI-compatible** | Text sent to `/chat/completions` | Base URL + API key |
| **Ollama** | Runs locally via Ollama | Ollama running (`ollama serve`), model pulled in Settings → Models |

Mix and match freely — e.g. whisper.cpp for transcription + Ollama for summarization runs fully offline with no API key.

### First-Time Setup

Open **Settings** (gear icon → **Preferences**, or the tray menu). The gear icon also has an **About Meeting Recorder** entry showing the app version and project links.

1. **General tab** — choose your transcription and summarization services; set output folder and recording quality
2. **Models tab** — configure the selected services:
   - *OpenAI-compatible*: set the base URL, paste your API key and choose models
   - *whisper.cpp*: pick an acceleration backend and install the engine (first time), then download a GGML model
   - *Ollama*: set host and click Download next to your preferred model
3. **Prompts tab** — optionally customize the transcription or summarization prompt

### Settings Reference

#### General tab

| Setting | Description |
|---|---|
| Transcription service | OpenAI-compatible (cloud) or whisper.cpp (local; default) |
| Summarization service | OpenAI-compatible (cloud; default) or Ollama (local) |
| Start at system startup | Launch automatically on login |
| Enable call detection | Monitor for active calls and notify you to start recording |
| Low memory mode | Unload the window from memory when you close it (~20 MB vs. ~100 MB idle in the tray) at the cost of a brief delay when reopening. Off by default — enable on low-memory systems |
| Auto-process recordings | Automatically start transcription and summarization when a recording stops (on by default). When off, only the audio is saved — start processing manually from Jobs or the Library |
| Output folder | Where recordings and notes are saved (default: `~/meetings`) |
| Recording quality | Audio bitrate preset (Very High / High / Medium / Low) |

#### Models tab

**OpenAI-compatible**

| Setting | Description |
|---|---|
| API key | Required when an OpenAI-compatible service is selected |
| Base URL | `https://api.openai.com/v1` by default; point it at any compatible endpoint |
| Transcription model | Free-text speech-to-text model name (`whisper-1` default; type any compatible name) |
| Summarization model | Free-text chat model name (`gpt-5.6-luna` default; type any compatible name) |
| Processing timeout | Max time to wait for a response (1–10 min) |

**whisper.cpp (local)**

A local transcription engine using the official prebuilt CPU binary. The engine is
**downloaded as an official prebuilt binary on opt-in** — no compiler, no
build toolchain, no system packages; until then the section shows an
**Install whisper.cpp engine** button. (Upstream ships no CUDA prebuilt for
Linux, so there is no GPU option: `auto` installs the CPU build, and an
explicit `cuda` choice explains this instead of downloading.)

| Setting | Description |
|---|---|
| Acceleration backend | `auto` (installs the CPU build) or force `cpu`. Upstream ships no CUDA prebuilt for Linux, so `cuda` is rejected with guidance. The detected hardware is shown next to the selector. |
| Model | Free-text GGML model name to use for local transcription (`large-v3-turbo` default) |
| Model list | Download status and one-click download for each available GGML model. Failures are shown inline on the row plus a dialog/notification with the reason |

Available whisper.cpp (GGML) models: `large-v3-turbo` (~1.6 GB), `large-v3` (~3 GB), `medium` (~1.5 GB), `small` (~470 MB).

**Ollama**

| Setting | Description |
|---|---|
| Ollama model | Free-text model name to use for local summarization (`phi4-mini` default) |
| Ollama host | Ollama server address (default: `http://localhost:11434`) |
| Model list | Download status and one-click download for each available model. Downloads require Ollama to be running (`ollama serve`) — the buttons disable with guidance while it is offline |

Available Ollama models:

| Model | Size | Notes |
|---|---|---|
| `phi4-mini` | ~3 GB | Lightest, good quality |
| `gemma3:4b` | ~4 GB | Good quality |
| `qwen2.5:7b` | ~5 GB | Very capable |
| `llama3.1:8b` | ~5 GB | Very capable |
| `gemma3:12b` | ~8 GB | Best quality, high RAM required |

#### Prompts tab

Customize the transcription and summarization prompts. Each has a **Reset to default** button. The `{transcript}` placeholder in the summarization prompt is replaced with the transcript text.

Note: transcription prompts apply to the OpenAI-compatible service only — the local whisper.cpp engine does not use a prompt.

### Workflow

1. Click **Record (Headphones)** or **Record (Speaker)** to start
2. The timer shows elapsed recording time; **Pause** / **Resume** as needed
3. Click **Stop** — a 5-second countdown begins when enabled in Settings (click **Cancel** to abort)
4. After stopping, transcription starts automatically when **Auto-process recordings** is on (default); when off, only the audio is saved and you start processing manually from Jobs or the Library
5. When done, links to the transcript and notes files appear in the window

### Noise Reduction (Optional)

If your microphone picks up too much ambient noise, enable PipeWire's WebRTC noise suppression:

**Temporary (current session only):**
```bash
pactl load-module module-echo-cancel aec_method=webrtc noise_suppression=true
```

**Permanent:**

Create `~/.config/pipewire/pipewire-pulse.conf.d/echo-cancel.conf`:
```
pulse.cmd = [
  { cmd = "load-module" args = "module-echo-cancel aec_method=webrtc noise_suppression=true" flags = [] }
]
```

Then restart PipeWire:
```bash
systemctl --user restart pipewire pipewire-pulse
```

### Logs

Application logs written to `/var/log/meeting-recorder/` (fallback: `~/.local/share/meeting-recorder/`):

```
app.log    — DEBUG and INFO messages
error.log  — WARNING and above
```

---

## Android App

### Features

- **Record** microphone audio (AAC/M4A) at a configurable quality, captured in a foreground service that survives brief interruptions
- **Transcribe** with Google Gemini
- **Summarize** into structured Markdown notes with Google Gemini
- **Auto-title** — generates a meeting title from the notes when none is provided
- **Generate from the library** — generate the transcript & notes, or regenerate notes, for any meeting from its detail screen
- **Recover failed recordings** — if processing fails, the raw audio is kept in your library so you can generate the transcript & notes later
- **Use Existing Recording** — import an external audio file and transcribe/summarize it
- **Silenced-mic warning** — if the system mutes the mic mid-recording (e.g. an answered call), the audio is kept and you're warned instead of getting a silent transcript
- **Do Not Disturb while recording** (optional) — silence notifications during capture
- **Meetings browser** — browse and read past transcripts and notes; rename or delete meetings
- **Audio playback** — play back recordings directly in the meeting detail view
- Recordings saved to `Documents/Meetings/` — same structure as the Linux app

### Requirements

- Android 12+ (API 31)
- Google Gemini API key — free from [aistudio.google.com](https://aistudio.google.com)
- "All files access" (`MANAGE_EXTERNAL_STORAGE`) permission — required to read/write `Documents/Meetings/`

### Installation

Download `meeting-recorder-android-*.apk` from the [Releases](../../releases) page, transfer it to your phone, and install it (enable **Install from unknown sources** in Settings if prompted).

### Output Structure

Recordings are saved to `Documents/Meetings/` on external storage, in the same dated hierarchy as the Linux app:

```
Documents/Meetings/
└── 2026/
    └── March/
        └── 04/
            └── 14-30_Standup/
                ├── recording.m4a
                ├── transcript.md
                └── notes.md
```

### First-Time Setup

1. Open the app and tap the **Settings** icon
2. Paste your Gemini API key and choose a model (`gemini-flash-latest` recommended)
3. Return to the main screen — grant **All files access** when prompted
4. Tap the microphone button to start recording

### Building from Source

```bash
# Requires Android SDK (API 35) and JDK 17
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Release build (requires signing credentials)
set -x KEYSTORE_PASSWORD your_store_pass
set -x KEY_ALIAS meetingrecorder
set -x KEY_PASSWORD your_key_pass
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

---

## Development

### Repository layout

```
linux/
├── src/                   # Rust app (single `meeting-recorder` binary)
│   ├── main.rs            # role dispatch: --daemon / --window / --process / --install / --uninstall / client
│   ├── config/            # defaults + settings + keyring
│   ├── core/              # state machine, jobs, recording controller, retry, wire format
│   ├── audio/             # ffmpeg recorder + mixer + pactl devices
│   ├── detection/         # call detection
│   ├── processing/        # pipeline + OpenAI-compatible / whisper.cpp / Ollama providers
│   ├── services/          # opt-in engine/model installers + model clients
│   ├── daemon/            # engine + D-Bus service + children + tray loop
│   ├── ui/                # GTK4 + libadwaita window + ksni tray + D-Bus proxy
│   └── utils/             # autostart, logging, meetings, filenames, self-uninstall
├── assets/                # tray artwork + hicolor app icons
├── packaging/             # Arch PKGBUILD + desktop entry only
└── Cargo.toml / Cargo.lock
```

### Running Linux checks

```bash
cargo fmt --check --manifest-path linux/Cargo.toml
cargo clippy --manifest-path linux/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path linux/Cargo.toml
```

### Release

Pushing a tag triggers the release workflows:

| Tag pattern | Workflow | Output |
|---|---|---|
| `v*` (e.g. `v1.2.0`) | `release.yml` | `.pkg.tar.zst` + source tarball attached to the Release |

## License

MIT
