# GravaAi

A meeting recorder that transcribes audio and generates structured notes using
any OpenAI-compatible API, fully local Whisper/CrispASR/Ollama engines, or a mix of both.

This repository holds the Linux desktop app (Rust, Qt 6/QML):

| Path | Contents |
|---|---|
| `linux/` | Rust daemon plus Qt 6/QML window (Linux) |
| `linux/assets/` | Tray artwork + hicolor app icons |
| `linux/packaging/appimage/` | AppImage build script + desktop entry |

---

## Linux App

### Features

- **Record** system audio + microphone simultaneously, or microphone only — each channel is automatically loudness-normalized during capture, so quiet microphones are boosted into healthy levels (up to 20 dB, applied independently to mic and system audio)
- **Transcribe** with any OpenAI-compatible endpoint, local whisper.cpp (timestamped transcript) or experimental local CrispASR (Nemotron 3.5 ASR)
- **Summarize** into structured Markdown notes with any OpenAI-compatible endpoint or local Ollama
- **Summarize from the library** — re-run summarization for any past meeting from the meetings browser
- **Local models** — run fully offline with no API key required
- **Customizable prompts** — edit transcription and summarization prompts in Settings
- **System tray** integration — a StatusNotifierItem (SNI) exposed over D-Bus; left-click opens the window where the host supports it, otherwise opens the menu
- **Graphical daemon + window pair** — the app always owns one StatusNotifier tray icon and one supervised Qt window. The daemon keeps recording, transcription, and model installs running while the window is hidden; it refuses to start when no graphical tray host is available, so there is never a usable headless instance.
- **Single-instance guarantee** — a D-Bus singleton guard prevents concurrent daemons and the UI claims a second guard before creating a window. Launching the AppImage repeatedly presents the existing instance instead of creating another one.
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
- The AppImage bundles `ffmpeg`, `ffprobe`, `pactl`, Qt 6/QML and their
  non-platform libraries, so these programs and toolkits are not required on
  the host. Source builds still need the Rust toolchain, Qt 6 development
  packages and the audio utilities.
  The Rust toolchain is only required to build from source (see below).

> **Look & theming:** Qt Quick Controls uses the built-in **Basic** style and
> GravaAI's own QML primitives. `linux/qml/Theme.qml` is the single palette
> source, based on the Lepramim identity and the supplied GravaAI mock.
- Delivery is a single **Type-2 AppImage** (`gravaai-<version>-<arch>.AppImage`)
  containing the daemon, `gravaai-ui`, Qt/QML modules/plugins, icons, FFmpeg,
  FFprobe and `pactl`. No host Qt, FFmpeg or pactl installation is needed.
- The host must provide a graphical Linux session: a compatible kernel/glibc,
  X11 or Wayland compositor, session D-Bus, a StatusNotifier/AppIndicator host,
  PipeWire/PulseAudio, Freedesktop portals and a notification service. Without
  the tray host the daemon exits before exporting its Engine service; no window,
  recording or notification can be started.

The base install is **cloud-only and minimal** — no local engines or GPU libraries are installed by default. Each local option below is installed **on demand** from **Settings → Models** when you choose it.

| Service | Requirement |
|---|---|
| **OpenAI-compatible** (transcription + summarization) | Base URL + API key for any `/v1`-style endpoint (OpenAI, Azure OpenAI, LiteLLM, llama.cpp server, …) — no local install |

> Your API key is stored in the system keyring (GNOME Keyring / KWallet) when one is available, falling back to a permission-restricted config file otherwise.
| **whisper.cpp** (local transcription) | Engine downloaded as an official prebuilt CPU binary on opt-in; GGML model downloaded from HuggingFace. No compiler or system packages needed |
| **CrispASR** (experimental local transcription) | Engine downloaded as an official prebuilt binary on opt-in (CPU / Vulkan / CUDA flavors); Nemotron GGUF model downloaded from HuggingFace. No compiler or system packages needed |
| **Ollama** (local summarization) | [Ollama](https://ollama.com) installed and running (`ollama serve`); uses NVIDIA/AMD GPU automatically |

### Installation

Download `gravaai-<version>-<arch>.AppImage` from the
[Releases](../../releases) page, make it executable, and run it — that single
file is the whole app (daemon, window, tray, bundled icons):

```bash
chmod +x gravaai-*-x86_64.AppImage
./gravaai-*-x86_64.AppImage
```

Optional: move it somewhere on your `PATH` (e.g. `~/.local/bin/`) or integrate
it with [AppImageLauncher](https://github.com/TheAssassin/AppImageLauncher) so
it appears in your application menu.

The AppImage ships the complete desktop runtime and cloud path. The local
transcription engines (whisper.cpp, experimental CrispASR), Ollama and model weights are intentionally
installed later, on demand, from **Settings → Models**; they remain outside the
base image so updates stay small and user data is preserved.

To uninstall (removes the AppImage when launched from one, desktop entries,
icons, autostart entry, engines, models, logs, config and the stored API key —
recordings are kept):

```bash
./gravaai-*.AppImage --uninstall
```

> **GNOME users:** The tray is a StatusNotifierItem (SNI), and GNOME has no built-in SNI host, so the icon needs the AppIndicator/KStatusNotifierItem extension to appear. Install your distro's package for it (e.g. on Arch `sudo pacman -S gnome-shell-extension-appindicator`), then enable it in the GNOME Extensions app and log out/in.
>
> Whether **left-click focuses the window** or **opens the menu** is decided by the SNI host: hosts that deliver the `Activate` action (e.g. KDE Plasma) focus the window, while the GNOME extension typically opens the menu on any click. KStatusNotifierItem-capable panels on XFCE, MATE, Cinnamon, KDE, LXQt, … show the icon natively without an extension.

### Running from Source (developers)

Building from source requires the Rust toolchain, Qt 6 development headers
(`qt6-base-dev`, `qt6-declarative-dev`, `qt6-tools-dev-tools`, `qt6-svg-dev` on
Ubuntu) and the audio utilities. This is only needed for development — regular
installs never compile anything:

```bash
cd gravaai
# Build the toolkit-free daemon and the Qt companion separately.
cargo build --manifest-path linux/Cargo.toml --no-default-features --bin gravaai
cargo build --manifest-path linux/Cargo.toml --features ui --bin gravaai-ui
./linux/target/debug/gravaai

# Pack a local AppImage (release build + assets):
./linux/packaging/appimage/build-appimage.sh
```

`gravaai` (no flag) is **client** mode: it starts the singleton daemon if
needed and asks it to present the supervised Qt window. `gravaai --daemon` is
the internal tray/engine role; it exits immediately if a graphical
StatusNotifier host is unavailable. `gravaai --window` remains a compatibility
trampoline, while the daemon normally launches the separate `gravaai-ui`
companion. Running `gravaai-ui` directly without a live daemon/tray is refused.

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
| **CrispASR** (experimental) | Runs locally via a prebuilt `crispasr` binary (Nemotron 3.5 ASR 0.6B Q8 default) | Engine + GGUF model downloaded in Settings → Models |

#### Summarization

| Service | How it works | Requires |
|---|---|---|
| **OpenAI-compatible** | Text sent to `/chat/completions` | Base URL + API key |
| **Ollama** | Runs locally via Ollama | Ollama running (`ollama serve`), model pulled in Settings → Models |

Mix and match freely — e.g. whisper.cpp for transcription + Ollama for summarization runs fully offline with no API key.

### First-Time Setup

Open the window from the tray icon, then use the sidebar.

1. **Models & services tab** — choose your transcription and summarization services and configure them:
   - *OpenAI-compatible*: set the base URL, paste your API key and choose models
   - *whisper.cpp*: pick an acceleration backend and install the engine (first time), then download a GGML model
   - *CrispASR* (experimental): pick a cpu/vulkan/cuda backend and install the engine (first time), then download a Nemotron model
   - *Ollama*: set host and click Download next to your preferred model — installing Ollama starts `ollama serve` automatically
   - The **Status** card on the same tab always shows what is installed and running: the whisper.cpp engine (path + size), downloaded GGML models, the CrispASR engine with its GGUF models, and the Ollama server (host, running state, pulled models with sizes)
2. **General tab** — set output folder, recording quality and background behavior
3. **Prompts tab** — optionally customize the transcription, summarization or title prompt (built-in defaults are shown)
4. **Downloads tab** — every payload the app downloaded in one list, with its exact location on disk and size (engine, GGML models, the Ollama runtime and Ollama models)

### Settings Reference

#### General tab

| Setting | Description |
|---|---|
| Start at system startup | Launch automatically on login |
| Enable call detection | Monitor for active calls and notify you to start recording |
| Low memory mode | Unload the window from memory when you close it (~20 MB vs. ~100 MB idle in the tray) at the cost of a brief delay when reopening. Off by default — enable on low-memory systems |
| Auto-process recordings | Automatically start transcription and summarization when a recording stops (on by default). When off, only the audio is saved — start processing manually from the Recorder dashboard or the Library |
| Output folder | Where recordings and notes are saved (default: `~/meetings`) |
| Recording quality | Audio bitrate preset (Very High / High / Medium / Low) |

#### Models & services tab

Choose the transcription/summarization services here, then configure them below.

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

**CrispASR (experimental, local)**

A third transcription option using the official prebuilt `crispasr` binary
with the Nemotron 3.5 ASR 0.6B model (Q8 default). All three GPU flavors are
selectable: CPU (~25 MB download), Vulkan (~60 MB) and CUDA (~206–271 MB);
`auto` picks CUDA on NVIDIA machines and CPU elsewhere. Like whisper.cpp it
runs as a short-lived CLI call inside the processing job and unloads Ollama
models first to free GPU memory — no persistent service to manage. Engine
hashes are not pinned yet on this experimental branch.

| Setting | Description |
|---|---|
| Acceleration backend | `auto`, `cpu`, `vulkan` or `cuda` (all three installable; Vulkan/CUDA Linux builds are x86_64-only) |
| Model | Nemotron quant to use (`nemotron-3.5-asr-0.6b-q8_0` default; Q4_K / F16 available) |
| Model list | Download status and one-click download for each Nemotron GGUF from HuggingFace |

**Ollama**

| Setting | Description |
|---|---|
| Ollama model | Free-text model name to use for local summarization (`phi4-mini` default) |
| Ollama host | Ollama server address (default: `http://localhost:11434`) |
| Model list | Download status and one-click download for each available model. A down server starts automatically for downloads when the Ollama binary is present and the host is local; a server the app started is stopped again on app exit (a pre-existing server is never touched). Installing Ollama itself also starts the server right away — no manual `ollama serve` |

#### Status card (Models & services)

The **Status** card shows the live state of the optional local engines: whether the
whisper.cpp engine is installed (with its path and size), which GGML models are
downloaded (with sizes), whether the CrispASR engine is installed (with its
path, size and GGUF models), and whether Ollama is installed and serving (host,
running state and pulled models with sizes). Click **Refresh** any time; the
card also refreshes on its own after every install finishes.

#### Downloads tab

One list of **everything the app downloaded**, each row with name, kind
(engine / model / runtime), exact location on disk and size:

| Payload | Location |
|---|---|
| whisper.cpp engine (`whisper-cli` + libraries) | `~/.local/share/gravaai/whisper.cpp/` |
| GGML models | `~/.local/share/gravaai/whisper-cpp-models/` |
| CrispASR engine (`crispasr` + libraries) | `~/.local/share/gravaai/crisp-asr/` |
| CrispASR (Nemotron GGUF) models | `~/.local/share/gravaai/crisp-asr-models/` |
| Ollama runtime | `~/.local/share/gravaai/ollama/` |
| Ollama models | Ollama's own store (`~/.ollama/models` by default) |

Use **Open folder** on a row (or **Open data folder** for the root) to inspect
the files in your file manager; **Refresh** re-scans sizes.

Available Ollama models:

| Model | Size | Notes |
|---|---|---|
| `phi4-mini` | ~3 GB | Lightest, good quality |
| `gemma3:4b` | ~4 GB | Good quality |
| `qwen2.5:7b` | ~5 GB | Very capable |
| `llama3.1:8b` | ~5 GB | Very capable |
| `gemma3:12b` | ~8 GB | Best quality, high RAM required |
| `jewelzufo/granite-4.0-h-350m-base-GGUF:Q8_0` | ~380 MB | Tiny, fast local notes |

#### Prompts tab

Customize the transcription, summarization and title prompts. Built-in defaults are shown on first open. **Reset defaults** restores them; saving a default stores it as "use built-in". The `{transcript}` placeholder in the summarization prompt is replaced with the transcript text.

Note: transcription prompts apply to the OpenAI-compatible service only — the local whisper.cpp and CrispASR engines do not use a prompt.

#### Library

Browse past meetings. Every row offers the actions that make sense for its
state: **Transcribe** (audio-only meetings), **Summarize** (uses the existing
transcript when there is one — the audio is never re-transcribed — otherwise it
runs the full transcribe + summarize pipeline), **Transcript** / **Notes** to
open the files, plus Rename and Open folder. Select rows to delete.

The Recorder dashboard shows the same actions on its recent-meetings card and
live background jobs with Cancel/Retry/Dismiss/Open actions. Both meeting lists
refresh by themselves when a background job finishes or a recording is saved —
no manual Refresh needed (the button is still there if you want one).

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

Application logs written to `/var/log/gravaai/` (fallback: `~/.local/share/gravaai/`):

```
app.log    — DEBUG and INFO messages
error.log  — WARNING and above
```

---

## Development

### Repository layout

```
linux/
├── src/                   # Rust library + daemon and Qt companion binaries
│   ├── main.rs            # daemon/client/compatibility role dispatch
│   ├── bin/gravaai-ui.rs  # Qt/QML window companion entry point
│   ├── config/            # defaults + settings + keyring
│   ├── core/              # state machine, jobs, recording controller, retry, wire format
│   ├── audio/             # ffmpeg recorder + mixer + pactl devices
│   ├── detection/         # call detection
│   ├── processing/        # pipeline + OpenAI-compatible / whisper.cpp / Ollama providers
│   ├── services/          # opt-in engine/model installers + model clients
│   ├── daemon/            # engine + D-Bus service + children + tray loop
│   ├── ui/                # toolkit-free tray/proxy helpers and isolated Qt bridge
│   ├── qml/               # Qt Quick shell, pages, components and Theme.qml
│   └── utils/             # autostart, AppImage exe resolution, logging, meetings, self-uninstall
├── assets/                # tray artwork + hicolor app icons
├── packaging/appimage/    # AppDir desktop entry + build-appimage.sh
└── Cargo.toml / Cargo.lock
```

### Running Linux checks

```bash
cargo fmt --check --manifest-path linux/Cargo.toml
cargo clippy --manifest-path linux/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path linux/Cargo.toml --no-default-features --lib
cargo test --manifest-path linux/Cargo.toml --features ui --lib
./linux/tests/qt_smoke.sh
./linux/packaging/appimage/build-appimage.sh   # when a fresh AppImage is needed
```

### Release

Releases are cut manually via the `Release` / `Auto Release` workflows:

| Trigger | Workflow | Output |
|---|---|---|
| Manual (`version`, e.g. `1.2.0`) | `release.yml` | AppImage(s) + source tarball attached to the Release |
| Manual (`bump`) | `auto-release.yml` → `release.yml` | `v*` tag, then same as above |

## License

MIT
