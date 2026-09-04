Memory (Graphiti): at session end, register an episode via the `graphiti-memory` MCP server (`add_memory`, group_id `gravaai`) summarizing what changed and what was learned; whenever a task needs context from prior sessions, search Graphiti first (`search_nodes` / `search_memory_facts`). The Graphiti MCP server (`Graphiti Agent Memory` v1.29.1) answers at `http://192.168.0.59:8000/mcp` (remote MCP, Streamable HTTP). If the `graphiti-memory` tools are missing from the session tool catalog (e.g. after an app/MCP restart), the server itself is usually still up: shake hands directly — POST `initialize` (`protocolVersion` `2024-11-05`), capture the `mcp-session-id` response header, send it back on every follow-up call (`tools/list`, `tools/call`). Do not treat a missing tool catalog entry as a dead server; probe the URL first.

# AGENTS.md

Repository: https://github.com/jmarceno/gravaai
Hard fork: no upstream, no links back to the original repo.
Linux desktop app: `linux/` (Rust, Qt 6/QML). The app is not tied to any distro —
it never installs system packages; when a helper program is missing it tells
the user (see `utils/dependencies.rs`).

> **Qt cutover (2026-09):** the app was migrated one-shot from the former
> toolkit to Rust with a Qt 6/QML companion, keeping the daemon/UI architecture
> and all features. The cloud
> provider is a single **OpenAI-compatible** service
> (`processing/providers/openai_compat.rs`). No Python code or toolchain
> references remain — see "Project overview".

---

## Long-term target (GravaAI vision — we will get there in the long run)

A meeting recorder, transcriber and organizer for Linux.
Packed as an AppImage
Written in Rust, with a webui that uses tauri for packing it. See item B.

A. It captures
- Video of a region
- ALL audio (input and output)
- Both are saved separately

B. It does it with high previleges so it can avoid wayland issues or detection by other tools. If to do that we need to drop tauri for a more barebones way of doing ui, we can do it.
C. It does it, in a way that if it hangs, not much is lost
D. It shows a list of all recordings
E. In the recordings list the user can:
- Click a button to transcribe it using whisper or other LLM powered transcription that can identify different people talking
- Click a button to summarize - summarizes from transcription, so clicking here also transcript. Summarization also list todos and other things that would go on meeting notes prepared by a professional executive secretary.
- User can choose to also send the video to summarization (not in first version). Video is send in smaller pieces to the AI service can handle it

F. It has a configuration screen where the user can set up:
- Input to record (uses the default input if not configured)
- Output to record (uses the default output if not configured)
- Configure the OpenAI compatible service to use for transcription
- Configure the OpenAI compatible service to use for summarization
- Create new recording configurations - See Workflow
- Configure the default shortcut to start and stop recording
- Configure the default directory to save the recordings

G. Workflow:
1. User clicks setup recording at the tray icon context menu
2. User selects the desired configuration OR set new default the area to record. It can be in any monitor
3. This configuration saved as new default
4. User clicks start recording at the tray icon context menu, or press a configured shortcut
5. When done, User clicks stop recording at the tray icon context menu, or press a configured shortcut

Current code is the Rust/Qt 6 app described below. The daemon and Qt window are
separate executables in one AppImage; the persistent D-Bus, recording and
on-disk contracts remain unchanged.

---

## Git workflow — IMPORTANT

**Local commits only: never push, never merge.** There is
exactly one branch. Commit directly on it.

1. Commit changes locally.
2. Releases are cut manually via the `Release` / `Auto Release` workflows in
   `.gitea/workflows/` (version input; `v*` tags; AppImage artifacts).

---

## AppImage builds — IMPORTANT

**Always compile and build the appimage when done with changes that require a
new build.**

Delivery is the Type-2 AppImage produced by
`linux/packaging/appimage/build-appimage.sh` (release artifact
`gravaai-<version>-<arch>.AppImage`). After code changes that need a
fresh binary, run the script (it builds `--release` unless `SKIP_BUILD=1`) and
smoke-check the result before considering the work done.

### Host IDE AppImages (Cursor / OpenCode) — always check `APPIMAGE` / `APPDIR`

Agent sessions often run **inside** another AppImage:

- **Cursor** is distributed as an AppImage and exports `APPIMAGE` / `APPDIR`
  (and related vars) into every integrated terminal and agent shell.
- **OpenCode** is likewise an AppImage and does the same.

Those variables refer to the **host IDE**, not GravaAI. Treating them as
ours would re-exec Cursor/OpenCode, point autostart/uninstall at the wrong
file, or confuse packaging. Whenever you work on AppImage delivery, spawn
paths, autostart, uninstall, asset lookup, or smoke-tests:

1. **Never trust `$APPIMAGE` / `$APPDIR` alone.** Confirm the running binary
   actually lives under `$APPDIR` (see `utils::exe::own_appimage` /
   `own_appimage_from`). If `current_exe()` is outside `$APPDIR`, ignore the
   host exports.
2. **Clear host exports when packaging or testing our AppImage** so they do
   not leak into `appimagetool` or into a child that should only see GravaAI's
   mount. `build-appimage.sh` already `unset`s `APPIMAGE` / `APPDIR` / `OWD` /
   `ARGV0`; do the same in ad-hoc shell checks (`unset APPIMAGE APPDIR …`).
3. **Unit tests must not mutate process env** for these vars (parallel tests
   would race the host IDE). Prefer pure helpers that take paths as arguments.

```bash
./linux/packaging/appimage/build-appimage.sh           # version from Cargo.toml
./linux/packaging/appimage/build-appimage.sh 1.2.0     # explicit version
```

---

## Keep documentation in sync — IMPORTANT

Whenever a change affects user-facing behavior, features, architecture,
commands, conventions, or test boundaries, update the relevant docs **in the
same commit** so they never drift from the code:

- `README.md` — user-facing features, setup, and workflows (Linux only)
- `AGENTS.md` — architecture, commands, conventions, and test-coverage
  boundaries (this file; the only agent guide)

Before committing, re-read these two files and reconcile anything the change
made inaccurate (new screens/services, renamed flows, new settings, new tests,
changed defaults). Treat doc updates as part of "done," not a follow-up.

---

## Keep tests meaningful — IMPORTANT

For every change, add or update tests when doing so is meaningful — treat it as
part of "done," not a follow-up. "Meaningful" means the test would actually
catch a regression in the behavior you changed:

- New or changed logic with a testable contract (parsing, decisions, data
  transforms, repository/IO, API request/response handling) → add or update
  unit tests that cover the new behavior and its edge cases.
- Fixing a bug → add a test that fails without the fix, so it can't silently
  regress.
- When the meaningful logic is tangled with hard-to-test platform code (Qt/QML
  UI, D-Bus wiring), **extract the pure logic into a standalone function and
  test that** (e.g. `tray_model`, `settings_visibility`,
  `parse_whisper_cpp_output`, `render_prompt`).
- Run the relevant suites before committing: core tests with
  `cargo test --manifest-path linux/Cargo.toml --no-default-features --lib`,
  the Qt-feature tests with `cargo test --manifest-path linux/Cargo.toml
  --features ui --lib`, and `cargo clippy --all-targets --all-features --
  -D warnings`.

Skip new tests only when a change genuinely has no testable behavior (docs,
comments, pure formatting, trivial constant tweaks) — and say so briefly
rather than silently omitting them.

---

## Never break user space — IMPORTANT

Backward compatibility is not optional. Every change must satisfy **both** of
these:

- **Existing installs keep working.** A user who already has an older version
  installed must be able to upgrade without their setup breaking — don't
  invalidate existing config, stored API keys, on-disk recordings/metadata, or
  packaging state. When a format or default has to change, ship a migration or
  a compatible fallback rather than a breaking change.
- **Clean installs still work.** The change must also install and run
  correctly on a fresh Linux system with no prior version present.

If a change genuinely cannot preserve compatibility, call it out explicitly and
provide a migration path — never silently break an existing installation.

---

## What this repo is

Linux desktop app (Rust, Qt 6/QML) that records audio,
transcribes it, and generates structured notes. It always runs as the
graphical daemon/window pair — headless use is forbidden (the internal
`--process` / `--install` child roles refuse to run outside the daemon, see
`core/run_mode.rs::child_allowed`).

- `linux/` — Rust daemon plus Qt 6/QML window (Linux)
  (x86_64 + arm64)
- `.gitea/` — release workflows

On-disk recording format:
`<output>/YYYY-MM-DD_HH-MM[_title]/recording.mp3 + transcript.md + notes.md`.

App identity:
- `APP_ID = "io.github.jmarceno.GravaAi"` (`config/defaults.rs`)
- D-Bus: `io.github.jmarceno.GravaAi.Engine`
- Desktop file: `io.github.jmarceno.GravaAi.desktop` (with `StartupWMClass`)
- Repository: `https://github.com/jmarceno/gravaai`

---

## Commands

### Linux app

```bash
# Build the toolkit-free daemon and Qt companion separately (debug)
cargo build --manifest-path linux/Cargo.toml --no-default-features --bin gravaai
cargo build --manifest-path linux/Cargo.toml --features ui --bin gravaai-ui
# Run the client; it requires a graphical StatusNotifier host and starts the pair.
./linux/target/debug/gravaai

# Release build (the AppImage script performs these two builds automatically)
cargo build --release --manifest-path linux/Cargo.toml --no-default-features --bin gravaai
cargo build --release --manifest-path linux/Cargo.toml --features ui --bin gravaai-ui

# Pack AppImage (builds --release unless SKIP_BUILD=1)
./linux/packaging/appimage/build-appimage.sh

# Core tests (no Qt)
cargo test --manifest-path linux/Cargo.toml --no-default-features --lib
# Complete Rust/UI-feature tests
cargo test --manifest-path linux/Cargo.toml --features ui --lib

# Single test (substring filter)
cargo test --manifest-path linux/Cargo.toml whisper_cpp

# Lint + format
cargo clippy --manifest-path linux/Cargo.toml --all-targets --all-features -- -D warnings
cargo fmt --check --manifest-path linux/Cargo.toml
```

`linux/Cargo.toml` is the crate root for one shared library plus two binaries:
`gravaai` (default, daemon/client and internal children) and `gravaai-ui`
(`required-features = ["ui"]`, Qt/QML only). The `ui` feature contains only
the CXX-Qt bridge and window code; `cargo check --no-default-features` proves
the daemon has no Qt dependency.

### Install / uninstall (AppImage, no scripts)

Users download `gravaai-<version>-<arch>.AppImage`, mark it executable,
and run it. The AppImage carries both executables, Qt/QML, the platform
plugins, tray/icon assets, FFmpeg/FFprobe and `pactl`; only legitimate platform
services (kernel/glibc, compositor, session bus, audio server, portals and
notification/tray hosts) remain on the host. Uninstall is built in — it removes the AppImage
file (when running from one), desktop entries, icons, autostart entry, engines,
models, logs, config and the stored API key, and keeps recordings:

```bash
./gravaai-*.AppImage --uninstall   # see utils/self_uninstall.rs
```

---

## Linux architecture

**Two-process daemon/UI split:** one AppImage contains a toolkit-free
`gravaai` daemon/client binary and a Qt-only `gravaai-ui` companion. `main.rs`
dispatches on `core/run_mode.rs::resolve_run_mode(argv)`:
- **`--daemon`** (`daemon/app.rs`): an always-on Tokio event loop owns the
  recording lifecycle, jobs, call detection, installs and the `ksni`
  StatusNotifierItem. It claims `DAEMON_LOCK_NAME`, requires a registered
  StatusNotifier host before exporting `ENGINE_NAME`, and exits without side
  effects when no graphical tray is available. The engine snapshot is fanned
  out as `SnapshotChanged`/`Error`/`Output` signals. AI processing and installs
  stay in short-lived `--process`/`--install` children; their protocol is
  `STATUS:`/`RESULT:`/`ERROR:` and all work remains daemon-owned.
- **`gravaai-ui`** (`ui/qt/`): the only window process. It claims the UI
  singleton, verifies the daemon/tray owner, loads the embedded QML module with
  a fail-fast `objectCreated` hook and a five-second ready watchdog. The
  `AppController` sends only owned commands to a Tokio worker; D-Bus, file,
  network and model I/O never run on the Qt thread. Closing hides by default;
  Low memory asks the companion to exit. A daemon-owner watch exits the UI when
  the daemon disappears, and the daemon terminates the child gracefully on
  Quit.
- **`--window`** is a compatibility trampoline to `gravaai-ui`; **no flag**
  (`client.rs`) starts the singleton daemon if necessary and calls `OpenWindow`.
  `WindowSupervisor` guarantees one child and presents it instead of spawning a
  second one.

The daemon↔window boundary is the `io.github.jmarceno.GravaAi.Engine` D-Bus
interface (`daemon/dbus_service.rs`, zbus 5 `#[interface]`); the JSON snapshot
payload is `core/wire.rs` (`Snapshot`/`JobView`, serde, tolerant parsing). The
daemon spawns the window as a detached child process and supervises a single
window via `daemon/window_supervisor.rs` (spawn-vs-present).
`utils/autostart.rs` writes the login entry with `--daemon`.

**Model/GPU installs run in the daemon**, not the window: Settings → Models
install/build/download requests are sent as `StartInstall(spec)` and each runs
in a short-lived `--install` child (`daemon/installer.rs`) the daemon spawns
and tracks (`daemon/install_manager.rs`), so an install survives the window
closing. Installs are keyed by the pure
`core/install_spec.rs:install_key` (per-model/per-vendor scoped, so different
models install concurrently while the same request dedups); the daemon emits
`InstallProgress(key,text)`/`InstallFinished(key,ok,message)` D-Bus signals
to the open Models page and `GetInstalls()` lets a reopened window re-attach
to in-flight installs (`reflect_running_installs`). An Ollama model download
starts `ollama serve` automatically when the binary is present and the host
is local (`ensure_ollama_serving`, with readiness wait); the same auto-start
runs right after a fresh Ollama runtime install, so "Install Ollama" leaves a
working server behind (remote hosts and missing binaries still fail with
guidance). An auto-started server's pid is
recorded in `$XDG_STATE_HOME/gravaai/ollama-server.json` and the
daemon stops exactly that process on exit (verified via `/proc` cmdline) —
a pre-existing server has no record and is never interfered with. Install failures are
surfaced, never silent: the Models page writes the reason into the row
subtitle/tooltip (engine installs) or the model row (downloads) and shows an
`AlertDialog`; the daemon additionally emits a desktop notification so a
failure with no open window is still visible. Read-only status
checks run in the window on worker threads: `controller::engine_status_json`
(`utils/payloads.rs`) walks `~/.local/share/gravaai` and makes one short
Ollama `/api/tags` probe, refreshed at startup, when the Models/Downloads page
is opened, after every finished install and after Settings are saved.

**Audio recording** (`audio/`):
- `recorder.rs` runs a single `ffmpeg` subprocess reading PulseAudio/PipeWire
  sources directly (`-f pulse`); `mixer.rs` builds the command — mic+system
  mode `amerge`s mic (left channel) and sink monitor (right channel) into a
  true-stereo MP3 with a `highpass=f=80` + per-channel `dynaudnorm` filter
  (realtime-safe loudness normalization that lifts quiet microphones; each
  channel is normalized independently, boost capped at 20 dB), preserving
  speaker separation for transcription. Device names are resolved once in
  `start()` via `devices.rs` (`pactl`).
- Pause/resume works via **segments**: pause terminates ffmpeg cleanly
  (SIGTERM, saving the current segment), resume spawns a new ffmpeg writing
  the next segment, and stop concatenates all segments with ffmpeg's concat
  demuxer so paused intervals are excluded. `stop()` blocks until ffmpeg exits
  and segments are merged; a monitor thread reports unexpected ffmpeg death
  via `on_error`.
- Two modes: mic+system (`Record (Headphones)`) and mic-only
  (`Record (Speaker)` — the monitor is skipped to avoid echo).

**Recording state machine** (`core/state_machine.rs`): `State`
(IDLE/RECORDING/PAUSED/COUNTDOWN) plus the pure `can_transition()` legality
table. `core/job.rs` holds `Job` (`JobStatus` enum, per-job `CancelToken`)
and `actions_for_status()`, the pure policy for which buttons a job row
offers.

**Recording lifecycle** (`core/recording_controller.rs`):
`RecordingController<R: RecorderBackend>` owns the recorder instance, the stop
countdown, and the authoritative lifecycle `State`. Callbacks
(`on_state`/`on_error`/`on_commit`/`on_saved`/`on_discarded`/`on_countdown`/
`on_stopped`); the blocking recorder stop runs on a `TaskRunner` worker and
`on_stopped` lets the engine delay the processor launch until the file is
fully written (`awaiting_file`). The countdown is tick-driven by the owner
(`countdown_tick()` + injected `request_tick`). When
`auto_process_enabled` is off, `Engine::stop()` saves the audio only
(`cancel_and_save`) and never launches the processor — manual runs (job
Retry, Library re-summarize, Use Existing) still process explicitly. Fully
unit-tested headless with a fake recorder.

**Job queue & persistence** (`core/job_manager.rs`): `JobManager` owns the job
list and persists every change to `$XDG_STATE_HOME/gravaai/jobs.json`
(atomic tmp+rename; cancelled jobs excluded). On startup `load_persisted()`
re-offers interrupted work: jobs that were PROCESSING when the app died come
back as ERROR rows ("Interrupted…") with Retry (pure policy
`restore_status()`), ERROR jobs restore as-is, DONE jobs are pruned.

**Background work** (`core/task_runner.rs`): all off-loop work goes through
the app-wide `TaskRunner` — never raw `thread::spawn` for tracked work.
`submit(work, description, on_done, on_error)` runs `work` on a tracked thread
and routes the result through the injected main-thread scheduler; a worker
exception with no `on_error` is still logged, and main-thread callbacks are
panic-guarded. `shutdown()` joins running tasks with a bounded grace period.
The scheduler is injectable (immediate in tests, channel-posting in the
daemon), and `CancelToken` provides cooperative cancellation.

**AI processing** (`processing/`):
- `Pipeline` runs transcription then summarization as separate calls.
  `run(token)` checks cancellation **between stages** (an in-flight request
  still completes but no further stage starts and nothing is written).
  `PipelineMode` (mirrored by `core::job::JobMode`, persisted in jobs.json
  with a `full` default for older files) selects the stages: `Full`
  (transcribe+summarize), `TranscribeOnly` (transcript only, no notes) and
  `SummarizeOnly` (uses the existing transcript file, never re-transcribes).
  The engine picks the mode for Library actions: Summarize on a meeting with
  a transcript on disk → `SummarizeOnly`, the Library Transcribe button →
  `TranscribeOnly`, everything else → `Full`. The `--process` child receives
  the mode as an optional `--transcribe-only` / `--summarize-only` flag
  before the positional paths (`processor::parse_process_args`).
- Transient network failures (timeouts, connection resets, 5xx, 429) are
  retried with exponential backoff via `core/retry.rs` — used around the
  OpenAI-compatible and Ollama calls. Permanent errors (bad key, 4xx, model
  errors) fail immediately with actionable messages.
- `config/settings.rs:api_key_warning()` is a soft presence/format check
  surfaced when saving Settings, so a missing key is caught at save time
  instead of as a failed job.
- `transcription.rs` / `summarization.rs` expose factory functions returning
  `Box<dyn TranscriptionProvider>` / `Box<dyn SummarizationProvider>` based on
  config. The single cloud provider is
  `providers/openai_compat.rs:OpenAiCompatProvider` (`POST
  {base_url}/audio/transcriptions` multipart for transcription, `POST
  {base_url}/chat/completions` for summarization/titling, Bearer auth,
  `{transcript}` prompt rendering with append-fallback). The pipeline also
  auto-starts `ollama serve` before Ollama summarization when the server is
  down (ownership + stop-on-exit as above). Local providers:
  `providers/whisper_cpp.rs` (`whisper-cli` subprocess run with
  `LD_LIBRARY_PATH` pointed at its bundled `.so` libraries, plus the pure
  `parse_whisper_cpp_output()`), `providers/crisp_asr.rs` (experimental
  `crispasr --backend nemotron` subprocess writing a `-ojf` JSON sidecar that
  is parsed into the same `[HH:MM:SS]` format, `--gpu-backend` forwarded for
  explicit backends, Ollama models unloaded first like the whisper path) and
  `providers/ollama.rs` (`/api/generate`
  with retry, `/api/ps` eviction helpers).

**Call detection** (`detection/`): `AudioWatcher` runs `pactl subscribe` on
its own thread and calls back on new mic-capture streams (pure matcher
`is_call_start_event()`); if pactl dies it is **restarted with exponential
backoff** (1 s → 60 s cap, reset after a healthy minute). `CallDetector`
wraps it with a notification dedup window.

**Bare-bones, opt-in local engines:** The base install is
**cloud-only** — the binary carries no local-engine runtimes, and installing
one never needs a compiler: everything arrives prebuilt. Local capabilities
are installed on demand from **Settings → Models**:
- `whisper_cpp` — an official upstream whisper.cpp binary release, downloaded
  and SHA-256-verified by `services/whisper_cpp_service.rs`
  (`WhisperCppEngineInstaller`; pinned release tag + per-asset hashes in
  `config/defaults.rs`; CPU prebuilts for x86_64/aarch64 — upstream ships no
  CUDA prebuilt for Linux, so `resolve_backend()` routes `auto` to `cpu` and
  rejects explicit `cuda` before any download). Lands in
  `~/.local/share/gravaai/whisper.cpp/` with a `--help` smoke test;
  GGML models download from HuggingFace via the status/downloader helpers.
- `crisp_asr` (experimental third transcription backend) — an official
  upstream CrispASR binary release, downloaded by
  `services/crisp_asr_service.rs` (`CrispAsrEngineInstaller`; pinned release
  tag in `config/defaults.rs`, engine hashes still TODO so the installer
  streams without verification and logs a warning — must be pinned before
  merging to main). All three flavors are installable on x86_64
  (`cpu`/`vulkan`/`cuda`; `auto` picks CUDA on NVIDIA, CPU elsewhere —
  Vulkan stays explicit-only; aarch64 is CPU-only). Lands in
  `~/.local/share/gravaai/crisp-asr/` with a `--version` smoke test;
  Nemotron GGUF models (Q8 default) download from HuggingFace into
  `~/.local/share/gravaai/crisp-asr-models/`. The weights must be CrispASR's
  own `cstr/` conversion — third-party GGUFs of the same base model miss the
  tensors the `nemotron` backend requires and transcribe to silence (a
  regression test pins the download URL). Transcription runs as a
  short-lived CLI call inside the `--process` child, so there is no
  persistent service to start/stop — GPU memory is freed by unloading Ollama
  models first, exactly like the whisper.cpp path.
- **Installer security conventions** (`services/system_installer.rs`): no
  shell execution — commands are argv lists run without a shell and logged
  before execution; downloads are verified (pinned SHA-256 for the engine;
  `sha256sum.txt` for the Ollama archive) — never `curl | sh`. Tar extraction
  is path- and link-safe (relative symlinks like versioned `.so` names are
  kept; absolute/escaping targets rejected). Engine and GGML downloads
  stream to disk with progress instead of buffering multi-GB files in RAM. No
  privilege escalation and no system packages anywhere in the install path.

**Config:** `~/.config/gravaai/config.json`, `chmod 600`. Empty string
for any prompt key = use built-in default (defined in `config/defaults.rs`).
Clean-install defaults: transcription `whisper_cpp`, summarization `openai`
(chat model `gpt-5.6-luna`), auto-process on. Existing installs keep their
stored values on upgrade (unknown keys ignored, missing keys keep defaults).
**API key storage:** when a D-Bus Secret Service is available (GNOME
Keyring/KWallet), `settings::save()` stores the key there via the `keyring`
crate and writes only the `@keyring` sentinel to config.json;
`settings::load()` resolves the sentinel back.
`settings::migrate_key_to_keyring()` runs once at startup to move a plaintext
key into the keyring. Without a keyring everything falls back to plaintext-in-
chmod-600 exactly as before.

**Qt/QML toolkit notes:** `ui/qt/` contains the CXX-Qt `AppController`, the
`QQmlApplicationEngine` bootstrap helper and the Rust worker bridge. The QML
module is registered as `io.github.jmarceno.gravaai` and loads `ApplicationWindow`
from the embedded resource tree. Every page/component that touches the bridge
declares `required property var controller`; timers, `Connections`, dialogs and
file pickers are named properties so they cannot be interpreted as a default
property. `Theme.qml` is the sole color source and `QT_QUICK_CONTROLS_STYLE=Basic`
keeps rendering deterministic across desktops. The root helper observes
`objectCreated`, calls native `QCoreApplication::quit/exit`, and records QML
startup failures in `window-qt.log` (1 MiB plus one backup).

**UI pages and integration:** `qml/pages/` implements Recorder (dashboard with
recording, live processing-pipeline, background-jobs and recent-meetings
cards), Library, Models & Services (with a live Status card), Downloads
(payload inventory with paths/sizes), Prompts and General. There is no About
page and no Local-tools section. `JobsPage.qml` is retained as a tested
building block but is not in the sidebar navigation — jobs are managed from
the Recorder dashboard. The meeting lists (Recorder recent card + Library)
refresh themselves when a background job finishes or a recording is saved
(`controller::MeetingRefreshTracker` on `SnapshotChanged`) and when the
Library page is opened; the Library and recent card expose Transcribe and
Summarize actions (mode-aware, see below). `AppController`
keeps the exact snake_case property contract and explicit camelCase invokables;
its Tokio worker handles D-Bus, filesystem, portals, network and Lepramim
desktop-entry operations. The daemon's `ui/tray.rs` remains toolkit-free and
composes the branded icon (`tray_icon`) with an embedded fallback. The app icon,
QML resources and both binaries are bundled in the AppImage; `utils::exe`
resolves the companion and helpers only from the current mount before PATH.

**Import/crate convention:** one binary crate (`linux/Cargo.toml`,
`src/main.rs`) organized in modules (`config`, `core`,
`audio`, `detection`, `processing`, `services`, `daemon`, `ui`, `utils`).

---

## Project overview

Linux desktop app:

- **Language:** Rust (one shared binary crate with daemon and UI targets,
  edition 2021)
- **UI:** Qt 6/QML through CXX-Qt 0.10.0, isolated in `gravaai-ui`; Basic
  Controls style, embedded QML module and native fail-fast startup helper.
- **Cloud AI:** single OpenAI-compatible provider (no SDK; plain
  `POST /audio/transcriptions` + `POST /chat/completions` over reqwest with
  rustls).
- **Opt-in local engines (installed on demand from Settings → Models,
  prebuilt downloads — never source builds, no compiler needed):**
  whisper.cpp engine binary (CPU prebuilts for x86_64/aarch64 — no Linux CUDA
  upstream) + GGML models from HuggingFace; Ollama summarization via its local
  HTTP API (installed from a pinned, checksum-verified archive).
- **System tray:** a `ksni` StatusNotifierItem. The daemon claims a private
  singleton before registering it and refuses to run when no SNI host is
  registered. Left-click opens the window where the SNI host delivers
  `Activate`, otherwise opens the menu. GNOME needs the
  AppIndicator/KStatusNotifierItem extension to provide the SNI host. The tray
  uses one branded logo (`assets/tray/gravaai-*.png`) sent as a raw ARGB
  `IconPixmap`; recording (slow breathing opacity), paused (grayscale + pause
  bars), and processing (side-to-side highlight sweep) are composed at runtime
  by `tray_icon` and pushed on a daemon anim tick, with an embedded 48px
  fallback plus an `icon_name` theme fallback so it never renders empty when
  the artwork directory is missing (e.g. a stripped payload).
- **Delivery:** Type-2 AppImage (`linux/packaging/appimage/`) bundling the
  daemon, `gravaai-ui`, Qt/QML/plugins, FFmpeg/FFprobe, `pactl`, assets and
  non-platform libraries. Only kernel/glibc, compositor, session D-Bus,
  PipeWire/PulseAudio, portals and tray/notification services remain on host.
- **App icon:** the launcher/window icon ships in `assets/icons/hicolor/`
  (scalable SVG + PNG sizes, named `gravaai` — the `Icon=` key) and
  is bundled into the AppImage and exposed through `XDG_DATA_DIRS` from the
  current mount.

**Linux runs as cooperating processes:** `gravaai` owns the singleton daemon,
recording engine, jobs, installs, call detection, tray and D-Bus service;
`gravaai-ui` is the one supervised Qt window child. `--window` remains a
compatibility trampoline and no-flag client mode ensures the daemon then calls
`OpenWindow`. Two short-lived children keep heavy work out of the daemon:
`--process` and `--install`, both tracked and streamed with
`STATUS:`/`RESULT:`/`ERROR:` lines. The UI exits when the daemon owner vanishes.

The app supports any OpenAI-compatible endpoint for transcription/
summarization (model names are free-text; chat default `gpt-5.6-luna`), local
whisper.cpp (prebuilt binary download, the default transcription backend) for
transcription, experimental local CrispASR (Nemotron 3.5 ASR, all three
cpu/vulkan/cuda flavors installable) as a third transcription option,
and local Ollama for summarization (including the tiny
`jewelzufo/granite-4.0-h-350m-base-GGUF:Q8_0` option). A Settings → General
**Auto-process recordings** toggle (on by default) controls whether stopping a
recording auto-starts transcription/summarization or saves audio only for
manual processing. Local engines are not in the base install —
they are installed on demand from Settings → Models. x86_64 and arm64
are supported.

---

## Building and running

### Linux app

**Running from source (developers only — regular installs never compile):**

1. Install the toolchain + dependencies with your distro's packages:
   Rust (`rust`/`cargo`), Qt 6 development files/tools (`qt6-base-dev`,
   `qt6-declarative-dev`, `qt6-tools-dev-tools`, `qt6-svg-dev` on Ubuntu),
   audio tools (`ffmpeg`, `pactl` via PipeWire/PulseAudio) and `curl` only for
   bootstrapping appimagetool during a local package build.
2. Build and run:
   ```bash
   cargo build --manifest-path linux/Cargo.toml --no-default-features --bin gravaai
   cargo build --manifest-path linux/Cargo.toml --features ui --bin gravaai-ui
   ./linux/target/debug/gravaai
   # or build both release binaries with the AppImage script:
   # ./linux/packaging/appimage/build-appimage.sh
   ```

**Running checks:**

```bash
cargo fmt --check --manifest-path linux/Cargo.toml
cargo clippy --manifest-path linux/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path linux/Cargo.toml --no-default-features --lib
cargo test --manifest-path linux/Cargo.toml --features ui --lib
./linux/tests/qt_smoke.sh
```

**Install / uninstall (AppImage, no scripts):**

Users run the release AppImage directly. Uninstall is built in:

```bash
./gravaai-*.AppImage --uninstall
```

---

## Development conventions

### Release process

Releases are manual with a version input:

| Trigger | Workflow | Output |
|---|---|---|
| Manual (`version`, e.g. `1.2.0`) | `release.yml` | AppImage(s) + source tarball attached to Release |
| Manual (`bump`) | `auto-release.yml` → `release.yml` | `v*` tag, then same as above |

### Repository layout

```
linux/
├── src/                   # Rust app (single binary crate)
├── assets/                # tray artwork + hicolor app icons
├── packaging/appimage/    # AppDir desktop entry + build-appimage.sh
└── Cargo.toml / Cargo.lock
.gitea/workflows/          # release workflows
```

---

## Test coverage boundaries

Unit tests live next to the code (`#[cfg(test)]` modules) and run with
`cargo test`:

- `core/task_runner.rs` — result/error routing, logging of unhandled worker
  and callback panics, graceful shutdown, submit-after-shutdown.
- `core/retry.rs` — transient classifier (5xx/429/timeout/connect sniffing),
  retry-then-succeed, permanent-fails-immediately.
- `core/state_machine.rs` — `can_transition` legality table.
- `core/job.rs` — row `actions_for_status` policy, `CancelToken`, `JobMode`
  serialization defaults.
- `core/job_manager.rs` — persistence round-trips, cancelled-job exclusion,
  startup recovery (interrupted→error+retry, done pruned, id collision
  avoidance, corrupt state tolerated), mode round-trip + legacy
  (mode-less) jobs.json loading, plus the pure `restore_status` policy.
- `core/errors.rs` — dialog-vs-toast `error_presentation` policy.
- `core/recording_controller.rs` — full lifecycle headless (start/pause/
  resume, stop with and without countdown, countdown tick/cancel,
  cancel+save, cancel+discard) with a fake recorder.
- `core/install_spec.rs`, `core/run_mode.rs`, `core/window_close.rs`,
  `core/daemon_watch.rs`, `core/wire.rs`, `core/app_info.rs`,
  `core/commands.rs` — key/install-key JSON round-trips (including the
  `crisp_asr_engine` / `crisp_asr_model` kinds), mode dispatch,
  close policy, owner-watch policy, snapshot round-trip + tolerant parsing,
  pacman version parsing (AppImage VERSION file is read at runtime).
- `processing/pipeline.rs` — fail-fast without audio, cancel-before-start,
  pipeline modes (`SummarizeOnly` requires an existing transcript file and
  skips transcription, `TranscribeOnly` routes to the transcribe stage and
  writes nothing when cancelled).
- `processing/providers/openai_compat.rs` — `{transcript}` prompt rendering +
  append-fallback, verbose-JSON segment extraction, clear auth errors.
- `processing/providers/whisper_cpp.rs` — pure `parse_whisper_cpp_output`
  plus the injected-runner transcribe flow.
- `processing/providers/crisp_asr.rs` (experimental) — pure
  `parse_crisp_asr_output` (JSON segments + plain-text/log-line fallback),
  `--gpu-backend` flag mapping, injected-runner transcribe flow reading the
  `-ojf` sidecar, missing-engine/model guidance.
- `processing/providers/ollama.rs` — unreachable-host tolerance.
- `config/settings.rs` — key presence/URL warnings, effective-prompt
  fallback.
- `audio/mixer.rs`, `audio/devices.rs`, `audio/recorder.rs` — stereo command
  layout, monitor naming, segment naming.
- `detection/audio_watcher.rs` — `is_call_start_event` matcher.
- `utils/` — `sanitize_title`/output-path layout/job labels (`filename`),
  autostart entry management (`autostart`), AppImage-aware exe resolution
  that ignores host IDE `APPIMAGE`/`APPDIR` (`exe`), scan/rename/metadata +
  `has_audio` (`meeting_scanner`), payload inventory + dir sizes + status
  JSON shape (`payloads`, including the `crispasr` engine/model rows), in-tree-reuse vs. copy (`recording_import`),
  uninstall target plan + removal (`self_uninstall`).
- `services/` — SHA-256 helper (`system_installer`), engine asset table +
  backend detection + verified download/extract/smoke-test
  (`whisper_cpp_service`, including auto→cpu routing, cuda rejection, and
  missing-binary diagnostics naming archive contents),
  CrispASR asset table + backend detection + download/extract/smoke-test
  (`crisp_asr_service`, including all-three-flavor resolution, x86_64-only
  Vulkan/CUDA guidance, and Nemotron model paths), Ollama prefix-match +
  unreachable tolerance + automatic `ollama serve` startup for pulls and
  summarization + `/api/tags` name/size parsing (`ollama_service`: local-host
  gating, readiness wait, pid-record ownership with verified stop-on-exit,
  install guidance when no binary/remote host).
- `daemon/` — child protocol parsing + `--process` mode-flag split
  (`processor`), stderr tail buffer (`child_io`), window spawn-vs-present
  (`window_supervisor`), install dedup/progress/finished routing
  (`install_manager`), headless `Engine` snapshot/lifecycle/child-event
  handling with a fake backend (`engine`, including recording without an API
  key, auto-process-off saves audio only, and Library job mode selection:
  summarize-only with a transcript, transcribe-only via Transcribe).
- `ui/` — pure tray policy (`tray_model`: appearance priority, per-state menus,
  never-reused menu ids), runtime pixmap effects (`tray_icon`: breathe /
  pause bars / processing sweep), Models-tab visibility (`settings_visibility`,
  now a 4-tuple with the CrispASR section),
  bundled artwork PNG decoding plus embedded fallback so the tray never
  renders empty (`tray`), the notification gate that suppresses alerts
  unless the StatusNotifier registration is live (`notifications`), and the
  Qt controller library payload (`qt/controller`: meetings JSON carries
  resolved audio/transcript/notes paths + `has_audio`, validated file-open
  allow-list, data-folder allow-list, the snapshot-driven
  `MeetingRefreshTracker` that keeps the meeting lists fresh, AppImage-safe
  opener environment, portal `file://` percent-encoding).

The `--process`/`--install` child entry points, the D-Bus service/tray host and
the Qt scene need real subprocess/bus/display integration and are covered by
the QML/offscreen and AppImage smoke gates rather than ordinary unit tests.
`linux/tests/qt_smoke.sh` runs qmllint/qmlimportscanner, loads every page at
1332×820 and 960×640, and verifies direct UI refusal without a daemon/tray.
The engine/manager/key logic they rely on is unit-tested via fakes.
