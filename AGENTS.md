Memory (Graphiti): at session end, register an episode via the `graphiti-memory` MCP server (`add_memory`, group_id `gravaai`) summarizing what changed and what was learned; whenever a task needs context from prior sessions, search Graphiti first (`search_nodes` / `search_memory_facts`). The Graphiti MCP server (`Graphiti Agent Memory` v1.29.1) answers at `http://192.168.0.59:8000/mcp` (remote MCP, Streamable HTTP). If the `graphiti-memory` tools are missing from the session tool catalog (e.g. after an app/MCP restart), the server itself is usually still up: shake hands directly — POST `initialize` (`protocolVersion` `2024-11-05`), capture the `mcp-session-id` response header, send it back on every follow-up call (`tools/list`, `tools/call`). Do not treat a missing tool catalog entry as a dead server; probe the URL first.

# AGENTS.md

Repository: https://github.com/jmarceno/gravaai
Hard fork: no upstream, no links back to the original repo.
Linux desktop app: `linux/` (Rust/GTK4). The app is not tied to any distro —
it never installs system packages; when a helper program is missing it tells
the user (see `utils/dependencies.rs`).

> **Rust port (2026-09):** the app was migrated one-shot from Python/GTK4 to
> Rust/GTK4, keeping the daemon/UI architecture and all features. The cloud
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

Current code is the Rust/GTK4 app described below — not yet the
Rust/Tauri target above. (The GTK UI stays: Rust binds GTK4/libadwaita
natively via gtk-rs, so no Qt port was needed and the layout is unchanged.)

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
smoke-check the result before considering the work done. Clear a host IDE's
`APPIMAGE`/`APPDIR` when packaging or testing so Cursor (or similar) does not
leak into the packager — the script already `unset`s them; tests and
`utils::exe::own_appimage` only trust `$APPIMAGE` when `current_exe()` lives
under `$APPDIR`.

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
- When the meaningful logic is tangled with hard-to-test platform code (GTK
  UI, D-Bus wiring), **extract the pure logic into a standalone function and
  test that** (e.g. `tray_model`, `settings_visibility`,
  `parse_whisper_cpp_output`, `render_prompt`).
- Run the relevant suite before committing: `cargo test --manifest-path
  linux/Cargo.toml` (and `cargo clippy --all-targets -- -D warnings`).

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

Linux desktop app (Rust, GTK4 + libadwaita) that records audio,
transcribes it, and generates structured notes. It always runs as the
graphical daemon/window pair — headless use is forbidden (the internal
`--process` / `--install` child roles refuse to run outside the daemon, see
`core/run_mode.rs::child_allowed`).

- `linux/` — GTK4 + libadwaita desktop app (Rust, Linux)
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
# Run (debug)
cargo run --manifest-path linux/Cargo.toml

# Release build
cargo build --release --manifest-path linux/Cargo.toml

# Pack AppImage (builds --release unless SKIP_BUILD=1)
./linux/packaging/appimage/build-appimage.sh

# All tests
cargo test --manifest-path linux/Cargo.toml

# Single test (substring filter)
cargo test --manifest-path linux/Cargo.toml whisper_cpp

# Lint + format
cargo clippy --manifest-path linux/Cargo.toml --all-targets -- -D warnings
cargo fmt --check --manifest-path linux/Cargo.toml
```

`linux/Cargo.toml` is the crate root (`src/main.rs` single binary). The `ui`
cargo feature (default) pulls in GTK4/libadwaita; `cargo check
--no-default-features` builds the headless daemon/client stack without GTK.

### Install / uninstall (AppImage, no scripts)

Users download `gravaai-<version>-<arch>.AppImage`, mark it executable,
and run it. The AppImage carries the binary plus tray/icon assets; system
helpers (`ffmpeg`, `pactl`, `curl`, `tar`) and GTK4/libadwaita stay on the host
(same contract as before). Uninstall is built in — it removes the AppImage
file (when running from one), desktop entries, icons, autostart entry, engines,
models, logs, config and the stored API key, and keeps recordings:

```bash
./gravaai-*.AppImage --uninstall   # see utils/self_uninstall.rs
```

---

## Linux architecture

**Two-process daemon/UI split:** the app runs as a GTK-free **daemon** plus an
on-demand GTK **window** child, so GTK/libadwaita is loaded only while a window
is open. `main.rs` dispatches on `core/run_mode.rs:resolve_run_mode(argv)`:
- **`--daemon`** (`daemon/app.rs`): always-on async (tokio) event loop — no
  GTK. Owns the recording lifecycle, job queue, call detection, and the system
  tray. The engine (`daemon/engine.rs:Engine`) keeps a plain snapshot and
  reports mutations through `EngineHooks` (`on_change`/`on_error`/`on_output`),
  which the loop fans out to the tray (`ui/tray.rs`, a `ksni`
  StatusNotifierItem driven by an `on_command` callback) and to the D-Bus
  service as `SnapshotChanged`/`Error`/`Output` signals. **AI processing does
  not run in the daemon** — each job runs in a short-lived `--process`
  **child** (`daemon/processor.rs`) that loads the AI stack, writes
  transcript.md/notes.md, and exits (child protocol `STATUS:`/`RESULT:`/`ERROR:`
  lines on stdout; cancel kills the child). `Engine<R>` is generic over the
  recorder backend (`RecorderBackend` trait) so it is unit-testable with a
  fake; controller callbacks queue into an internal event list applied by
  `drain_events()` at the end of every engine method (worker threads marshal
  back as loop messages instead).
- **`--window`** (`ui/window_app.rs`): a short-lived `adw::Application`
  (`NON_UNIQUE`, so it doesn't own the bus name) spawned by the daemon as a
  child. `MainWindow` (`ui/main_window.rs`, built with `Rc::new_cyclic` for
  self-referential button callbacks) renders `Snapshot`s fetched over D-Bus
  (`ui/engine_proxy.rs:ProxyHandle`) and kept fresh by signal tasks that poll
  into the GTK loop; clicks become method calls (fire-and-forget, or blocking
  getters for snapshot/folders/installs). The close behavior is governed by the
  pure `core/window_close.rs:resolve_close_action(cfg)` policy read fresh at
  close time: by default the window **hides** so the daemon's present-path
  reopens it instantly; **Low memory mode** (Settings → General) **exits** on
  close so GTK memory is reclaimed and the daemon respawns a fresh window on
  demand. A spawned window never outlives its daemon: it polls the Engine bus
  name and quits when the daemon vanishes — covering a crash — via the pure
  `core/daemon_watch.rs:should_exit_on_owner_change` (act only after the name
  was seen owned, so a startup race can't kill the window early); and the
  daemon kills its window child on quit.
- **no flag** (`client.rs`): client mode — ensure the daemon is running (spawn
  `--daemon` detached via setsid if not), then call `OpenWindow`.

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
is local (`ensure_ollama_serving`, with readiness wait); remote hosts and
missing binaries fail with guidance instead. An auto-started server's pid is
recorded in `$XDG_STATE_HOME/gravaai/ollama-server.json` and the
daemon stops exactly that process on exit (verified via `/proc` cmdline) —
a pre-existing server has no record and is never interfered with. Install failures are
surfaced, never silent: the Models page writes the reason into the row
subtitle/tooltip (engine installs) or the model row (downloads) and shows an
`AlertDialog`; the daemon additionally emits a desktop notification so a
failure with no open window is still visible. Read-only status
checks (is-cached / ollama-reachable) still run in the window on worker
threads, hopping back via main-loop pollers.

**Audio recording** (`audio/`):
- `recorder.rs` runs a single `ffmpeg` subprocess reading PulseAudio/PipeWire
  sources directly (`-f pulse`); `mixer.rs` builds the command — mic+system
  mode `amerge`s mic (left channel) and sink monitor (right channel) into a
  true-stereo MP3 with a `highpass=f=80` filter, preserving speaker separation
  for transcription. Device names are resolved once in `start()` via
  `devices.rs` (`pactl`).
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
  `parse_whisper_cpp_output()`) and `providers/ollama.rs` (`/api/generate`
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
- **Installer security conventions** (`services/system_installer.rs`): no
  shell execution — commands are argv lists run without a shell and logged
  before execution; downloads are verified (pinned SHA-256 for the engine;
  size + logged SHA-256 for the Ollama script) — never `curl | sh`. No
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

**GTK4 / libadwaita toolkit notes (gtk-rs):** `ui/` builds with
`adw::ApplicationWindow`+`adw::ToolbarView`+`adw::HeaderBar`+`adw::ViewStack`/
`ViewSwitcher`, `Adw.PreferencesGroup` rows
(`ActionRow`/`SwitchRow`/`ComboRow`/`EntryRow`/`PasswordEntryRow`),
`Adw.ToastOverlay`/`Toast` for transient errors, `.boxed-list`/`.pill`/`.flat`
style classes, and `Adw.Clamp` for centred content. Async file/folder pickers
use `gtk::FileDialog`; message dialogs use `gtk::AlertDialog` /
`adw::AlertDialog`. GTK widgets are `!Send`, and glib 0.20 has no
`MainContext::channel` — worker threads only ever ship **owned data** over
`std::sync::mpsc`, and the main loop picks it up via `timeout_add_local`
pollers (see `window_app.rs` UiMsg bus, Models-page status checks). Enable the
gtk-rs version features matching the system libraries (`gtk/v4_18`,
`adw/v1_7` in `linux/Cargo.toml`).

**UI** (`ui/`): `main_window.rs` (recording controls; job rows rendered by
`jobs_panel.rs:JobsPanel` from the pure `actions_for_status()` policy;
errors surfaced via the pure `core/errors.rs:error_presentation()` policy —
actionable configuration problems get a modal dialog, transient/runtime
failures get a toast; `present_window()` re-shows + `unminimize()` +
`present()`; the header-bar gear is a `Gtk.MenuButton` offering
**Preferences** → settings dialog and **About GravaAi** → an
`Adw.AboutDialog` — app identity lives in `core/app_info.rs` plus a
`resolve_version()` that reads the installed distro package version (pacman
today, others later), returning
`None` on a source checkout), `settings_dialog.rs` (thin `Adw.Window` shell —
Cancel/ViewSwitcher/Save header, page instantiation, save flow; each tab lives
in its own module under `settings_pages/`:
`general.rs`/`models.rs`/`prompts.rs` page classes expose `.widget` and
`.apply(cfg)`, with shared row helpers + `IdComboRow` in
`settings_pages/widgets.rs`; `ModelsPage` routes daemon install signals to
progress/finished row updates (failures also surface an `AlertDialog` with the
reason) and re-attaches to in-flight installs on open; model names are
free-text `EntryRow`s (OpenAI STT/chat defaulting to `whisper-1` /
`gpt-5.6-luna`, whisper.cpp default `large-v3-turbo`, Ollama default
`phi4-mini`); `compute_section_visibility()` in `settings_visibility.rs` is
the pure Models-tab visibility policy; `on_saved` callback runs
`ReloadConfig`),
`model_row_grid.rs` (model `ActionRow`s with Download/Retry/progress states),
`meeting_explorer.rs` (past meetings browser; `.boxed-list` rows;
double-click-to-rename via `GestureClick`, AI re-summarize per meeting),
`tray.rs` (ksni StatusNotifierItem; a single branded `IconPixmap` from
`assets/tray/gravaai-*.png`, decoded with the `png` crate; recording /
paused / processing visuals are composed at runtime by `tray_icon` —
breathing opacity, grayscale pause bars, sweeping highlight — with an
embedded 48px fallback plus an `icon_name` theme fallback so the tray never
renders empty even when the artwork directory is missing). The app/launcher/window
icon ships in `assets/icons/hicolor/` and is bundled into the AppImage under
`usr/share/icons/hicolor/` (+ `usr/share/gravaai/`); at startup
`ui/window_app.rs:setup_app_icon()` also adds the bundled tree to the GTK
icon-theme search path and sets it as the default icon so it resolves from
source and from the AppImage mount. `MainWindow` import-existing delegates its
in-tree-reuse vs. copy decision to the pure `utils/recording_import.rs`.
Client→daemon spawn uses `utils::exe::persistent_exe()` (re-exec our own
AppImage when applicable so the FUSE mount outlives the short-lived client);
daemon→window/process/install children use `internal_exe()` to share the
daemon's mount.

**Import/crate convention:** one binary crate (`linux/Cargo.toml`,
`src/main.rs`) organized in modules (`config`, `core`,
`audio`, `detection`, `processing`, `services`, `daemon`, `ui`, `utils`).

---

## Project overview

Linux desktop app:

- **Language:** Rust (single binary crate, edition 2021)
- **UI:** GTK4 + libadwaita via gtk-rs (`adw::Application`/
  `adw::ApplicationWindow`, preference-row settings, toasts, dark-mode; async
  `gtk::AlertDialog`/`gtk::FileDialog` instead of blocking dialogs).
- **Cloud AI:** single OpenAI-compatible provider (no SDK; plain
  `POST /audio/transcriptions` + `POST /chat/completions` over reqwest with
  rustls).
- **Opt-in local engines (installed on demand from Settings → Models,
  prebuilt downloads — never source builds, no compiler needed):**
  whisper.cpp engine binary (CPU prebuilts for x86_64/aarch64 — no Linux CUDA
  upstream) + GGML models from HuggingFace; Ollama summarization via its local
  HTTP API (installed via the official script).
- **System tray:** a `ksni` StatusNotifierItem — no GTK widgets and no extra
  system dependency. Left-click opens the window where the SNI host delivers
  `Activate`, otherwise opens the menu. GNOME needs the
  AppIndicator/KStatusNotifierItem extension to provide the SNI host. The tray
  uses one branded logo (`assets/tray/gravaai-*.png`) sent as a raw ARGB
  `IconPixmap`; recording (slow breathing opacity), paused (grayscale + pause
  bars), and processing (side-to-side highlight sweep) are composed at runtime
  by `tray_icon` and pushed on a daemon anim tick, with an embedded 48px
  fallback plus an `icon_name` theme fallback so it never renders empty when
  the artwork directory is missing (e.g. a stripped payload).
- **Delivery:** Type-2 AppImage (`linux/packaging/appimage/`) bundling the
  binary + assets; GTK4/libadwaita/ffmpeg/pactl remain host dependencies.
- **App icon:** the launcher/window icon ships in `assets/icons/hicolor/`
  (scalable SVG + PNG sizes, named `gravaai` — the `Icon=` key) and
  is bundled into the AppImage; `setup_app_icon()` also registers the bundled
  tree on the GTK icon-theme search path so it resolves from source and from
  the AppImage mount.

**Linux runs as cooperating processes from one binary:** a GTK-free **daemon**
(`--daemon`) owns the recording engine, jobs, installs, call detection and
tray; the GTK **window** (`--window`) is spawned as a child on demand and
renders a snapshot fetched over the `io.github.jmarceno.GravaAi.Engine` D-Bus
interface. Launching with no flag is **client** mode: ensure the daemon is up,
then open a window. Two more short-lived child roles keep heavy/long work out
of the daemon: `--process` (one AI transcription+summarization job) and
`--install` (one model/engine install), both spawned and tracked by the daemon
and streamed back as `STATUS:`/`RESULT:`/`ERROR:` protocol lines, so they
survive the window closing and don't bloat the daemon.

The app supports any OpenAI-compatible endpoint for transcription/
summarization (model names are free-text; chat default `gpt-5.6-luna`), local
whisper.cpp (prebuilt binary download, the default transcription backend) for
transcription, and local Ollama for summarization. A Settings → General
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
   Rust (`rust`/`cargo`), GTK4 + libadwaita dev files (`gtk4`, `libadwaita`,
   `libnotify`), audio tools (`ffmpeg`, `pactl` via PipeWire/PulseAudio),
   `curl`, `tar`.
2. Build and run:
   ```bash
   cargo run --manifest-path linux/Cargo.toml
   # or: cargo build --release --manifest-path linux/Cargo.toml
   #      ./linux/target/release/gravaai
   ```

**Running checks:**

```bash
cargo fmt --check --manifest-path linux/Cargo.toml
cargo clippy --manifest-path linux/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path linux/Cargo.toml
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
- `core/job.rs` — row `actions_for_status` policy, `CancelToken`.
- `core/job_manager.rs` — persistence round-trips, cancelled-job exclusion,
  startup recovery (interrupted→error+retry, done pruned, id collision
  avoidance, corrupt state tolerated), plus the pure `restore_status` policy.
- `core/errors.rs` — dialog-vs-toast `error_presentation` policy.
- `core/recording_controller.rs` — full lifecycle headless (start/pause/
  resume, stop with and without countdown, countdown tick/cancel,
  cancel+save, cancel+discard) with a fake recorder.
- `core/install_spec.rs`, `core/run_mode.rs`, `core/window_close.rs`,
  `core/daemon_watch.rs`, `core/wire.rs`, `core/app_info.rs`,
  `core/commands.rs` — key/install-key JSON round-trips, mode dispatch,
  close policy, owner-watch policy, snapshot round-trip + tolerant parsing,
  pacman version parsing (AppImage VERSION file is read at runtime).
- `processing/pipeline.rs` — fail-fast without audio, cancel-before-start.
- `processing/providers/openai_compat.rs` — `{transcript}` prompt rendering +
  append-fallback, verbose-JSON segment extraction, clear auth errors.
- `processing/providers/whisper_cpp.rs` — pure `parse_whisper_cpp_output`
  plus the injected-runner transcribe flow.
- `processing/providers/ollama.rs` — unreachable-host tolerance.
- `config/settings.rs` — key presence/URL warnings, effective-prompt
  fallback.
- `audio/mixer.rs`, `audio/devices.rs`, `audio/recorder.rs` — stereo command
  layout, monitor naming, segment naming.
- `detection/audio_watcher.rs` — `is_call_start_event` matcher.
- `utils/` — `sanitize_title`/output-path layout/job labels (`filename`),
  autostart entry management (`autostart`), AppImage-aware exe resolution
  that ignores host IDE `APPIMAGE`/`APPDIR` (`exe`), scan/rename/metadata
  (`meeting_scanner`), in-tree-reuse vs. copy (`recording_import`),
  uninstall target plan + removal (`self_uninstall`).
- `services/` — SHA-256 helper (`system_installer`), engine asset table +
  backend detection + verified download/extract/smoke-test
  (`whisper_cpp_service`, including auto→cpu routing, cuda rejection, and
  missing-binary diagnostics naming archive contents), Ollama prefix-match +
  unreachable tolerance + automatic `ollama serve` startup for pulls and
  summarization (`ollama_service`: local-host gating, readiness wait,
  pid-record ownership with verified stop-on-exit, install guidance when no
  binary/remote host).
- `daemon/` — child protocol parsing (`processor`), stderr tail buffer
  (`child_io`), window spawn-vs-present (`window_supervisor`), install
  dedup/progress/finished routing (`install_manager`), headless `Engine`
  snapshot/lifecycle/child-event handling with a fake backend (`engine`,
  including recording without an API key and auto-process-off saves audio only).
- `ui/` — pure tray policy (`tray_model`: appearance priority, per-state menus,
  never-reused menu ids), runtime pixmap effects (`tray_icon`: breathe /
  pause bars / processing sweep), Models-tab visibility (`settings_visibility`),
  bundled artwork PNG decoding plus embedded fallback so the tray never
  renders empty (`tray`).

The `--process`/`--install` child entry points, the `Gio`-free zbus service
and tray wiring, and the GTK widget construction need a real
subprocess/bus/display and are not unit-tested (the engine/manager/key logic
they rely on is, via fakes).
