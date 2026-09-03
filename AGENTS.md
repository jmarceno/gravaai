Memory (Graphiti): at session end, register an episode via the `graphiti-memory` MCP server (`add_memory`, group_id `lunatwo`) summarizing what changed and what was learned; whenever a task needs context from prior sessions, search Graphiti first (`search_nodes` / `search_memory_facts`). The Graphiti MCP server (`Graphiti Agent Memory` v1.29.1) answers at `http://192.168.0.59:8000/mcp` (remote MCP, Streamable HTTP). If the `graphiti-memory` tools are missing from the session tool catalog (e.g. after an app/MCP restart), the server itself is usually still up: shake hands directly — POST `initialize` (`protocolVersion` `2024-11-05`), capture the `mcp-session-id` response header, send it back on every follow-up call (`tools/list`, `tools/call`). Do not treat a missing tool catalog entry as a dead server; probe the URL first.

# AGENTS.md

Repository: https://github.com/jmarceno/gravaai
Hard fork: no upstream, no links back to the original repo.
Arch-only fork: `linux/` (Python/GTK4) only. Android, Debian, Fedora, and all
non-Arch packaging were removed. CI lives in `.gitea/` and runs on manual
dispatch only.

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

Current code is the Arch-only Python/GTK4 starting point described below — not
the Rust/Tauri target above.

---

## Git workflow — IMPORTANT

**Never push directly to `main`.** Always work on a feature branch and open a
pull request. CI does NOT run automatically — all `.gitea/` workflows are
`workflow_dispatch` (manual) only.

1. Create a branch from the latest `main`:
   ```bash
   git checkout main && git pull
   git checkout -b <descriptive-branch-name>
   ```
2. Commit changes on the branch.
3. Push the branch and open a PR targeting `main`:
   ```bash
   git push -u origin <descriptive-branch-name>
   ```
4. Manually dispatch the CI workflow from Gitea and wait for it to pass. If
   the run surfaces failures, or reviewers leave comments, validate each
   against the actual code — reviewers can be stale or wrong. Address the
   valid ones with commits on the same branch; reply to invalid/stale ones
   explaining why.
5. **Never merge a PR — merging is always the user's decision and action**,
   even when CI is green and all review comments are addressed. Stop when the
   PR is ready and report its URL.
6. After the user merges, releases are cut manually via the `Release` /
   `Auto Release` workflows in `.gitea/workflows/` (manual dispatch with a
   version input; Arch `v*` tags only — no Android tags).

**One PR per prompt:** create exactly one pull request per user request, even
when the work is large. Use multiple commits on the same branch for
reviewability instead of fanning out into many small PRs — only split when the
user explicitly asks.

This applies to all agents — no direct pushes to `main`, and no merges, under
any circumstances.

---

## Keep documentation in sync — IMPORTANT

Whenever a change affects user-facing behavior, features, architecture,
commands, conventions, or test boundaries, update the relevant docs **in the
same PR** so they never drift from the code:

- `README.md` — user-facing features, setup, and workflows (Linux/Arch only)
- `AGENTS.md` — architecture, commands, conventions, and test-coverage
  boundaries (this file; the only agent guide)

Before opening a PR, re-read these two files and reconcile anything the change
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
  UI), **extract the pure logic into a standalone function and test that**.
- Run the relevant suite before opening a PR: `pytest` (Linux).

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
  correctly on a fresh Arch system with no prior version present.

If a change genuinely cannot preserve compatibility, call it out explicitly and
provide a migration path — never silently break an existing installation.

---

## What this repo is

Arch-only Linux desktop app (Python, GTK4 + libadwaita) that records audio,
transcribes it, and generates structured notes. Hard fork of the original
monorepo — Android, Debian/Fedora packaging, docs, and all non-Arch code were
removed and will not return.

- `linux/` — GTK4 + libadwaita desktop applet (Python), Arch Linux only
  (x86_64 + arm64 via the Arch Linux ARM container image in CI)
- `.gitea/` — CI/release workflows (manual dispatch only; renamed from
  `.github/`)
- `scripts/` — dev-only Gemini pipeline test helpers (headless, no GTK)

On-disk recording format:
`YYYY/MonthName/DD/HH-MM[_title]/recording.mp3 + transcript.md + notes.md`.

App identity (post-fork):
- `APP_ID = "io.github.jmarceno.Gravaai"` (`config/defaults.py`)
- D-Bus: `io.github.jmarceno.Gravaai.Engine`
- Desktop file: `io.github.jmarceno.Gravaai.desktop` (with `StartupWMClass`)
- Repository: `https://github.com/jmarceno/gravaai`

---

## Commands

### Linux app

```bash
# Run
PYTHONPATH=linux/src python3 -m meeting_recorder

# All tests
pytest

# Single test file
pytest linux/tests/services/test_whisper_service.py

# Single test
pytest linux/tests/services/test_whisper_service.py::ClassName::test_name

# Lint + format (CI enforces both; config in pyproject.toml)
ruff check linux/
ruff format linux/

# Type check (CI enforces; strict on processing/, services/, config/)
mypy linux/src/meeting_recorder/processing linux/src/meeting_recorder/services linux/src/meeting_recorder/config
```

`pyproject.toml` sets `testpaths = ["linux/tests"]` and
`pythonpath = ["linux/src"]`, so `pytest` works from the repo root. It also
holds the ruff config (line length 100; `E402` ignored because PyGObject needs
`gi.require_version()` before `gi.repository` imports) and the mypy config
(strict mode on the headless `processing`/`services`/`config` packages — new
code there must be fully annotated; GTK-bound `ui`/`audio`/`detection` are
checked leniently).

### Install (Arch only)

```bash
linux/install.sh     # pacman only; fails on non-Arch
linux/uninstall.sh
```

`install.sh` installs system deps via pacman, creates a venv, copies `linux/src`,
writes the launcher + desktop entry (`io.github.jmarceno.Gravaai.desktop`), and
installs the hicolor icons.

---

## Linux architecture

**Two-process daemon/UI split:** The app runs as a GTK-free **daemon** plus an
on-demand GTK **window** child, so GTK/libadwaita is loaded only while a window
is open (idle-in-tray footprint drops from ~100 MB to ~20 MB).
`__main__.py` dispatches on `core/run_mode.py:resolve_run_mode(argv)`:
- **`--daemon`** (`daemon/app.py:Daemon`): always-on, `Gio`/`GLib` only — no
  GTK. Runs a GLib main loop and owns the recording lifecycle, job queue, call
  detection, and the system tray. The engine (`daemon/engine.py:Engine`) is the
  recording/job logic lifted out of `MainWindow`; it keeps a plain snapshot and
  fires `on_change`, which the daemon fans out to the tray (`ui/tray.py`, now
  driven by an `on_command` callback, not a window) and to the D-Bus service.
  **AI processing does not run in the daemon** — importing the Gemini SDK
  (`google.genai`) alone costs ~70 MB RSS and Python never unloads a module, so
  each job runs in a short-lived `--process` **child** (`daemon/processor.py`)
  that loads the heavy stack, writes transcript.md/notes.md, and exits (daemon
  idle stays ~40 MB). The child streams `STATUS:`/`RESULT:`/`ERROR:` protocol
  lines on stdout; `ProcessorLauncher` reads them on the GLib loop and
  `cancel_job` kills the child. `daemon/engine.py:idle_call` is a lazy `gi`
  import so the engine is importable headless.
- **`--window`** (`ui/window_app.py:WindowApp`): a short-lived
  `Adw.Application` (`NON_UNIQUE`, so it doesn't own the bus name) spawned by
  the daemon as a child. `MainWindow` is now a thin renderer: it fetches a
  `Snapshot` over D-Bus (`ui/engine_proxy.py:EngineProxy`), re-renders on
  `SnapshotChanged`/`Error`/`Output` signals, and forwards clicks back as
  method calls; the daemon keeps recording regardless. The close behavior is
  governed by the pure `core/window_close.py:resolve_close_action(cfg)` policy
  read fresh at close time: by default the window **hides**
  (`set_visible(False)`, vetoing the destroy) so the process stays resident
  and the daemon's existing present-path (`PresentWindow`) reopens it
  instantly, at the cost of ~100 MB staying in RAM once a window has been
  opened; when the user enables **Low memory mode** (Settings → General) the
  window instead **exits** on close so GTK memory is reclaimed (idle-in-tray
  ~20 MB) and the daemon respawns a fresh window on demand. Because the
  setting is only reachable while the window is visible and is re-read on each
  close, toggling it needs no restart or extra IPC. A spawned window must never
  outlive its daemon (else a hidden, kept-in-memory window would linger and
  double up on the *next* daemon's `PresentWindow` broadcast, one stray window
  per prior session): `EngineProxy` watches the Engine bus name and quits the
  window when it vanishes — covering a daemon crash — via the pure
  `core/daemon_watch.py:should_exit_on_owner_change` (act only after the name
  was seen owned, so a startup race can't kill the window early); and
  `Daemon.quit()` calls `EngineService.shutdown_window()` to `force_exit` the
  tracked child immediately on a clean quit.
- **no flag** (`client.py`): client mode — ensure the daemon is running (spawn
  `--daemon` detached if not), then call `OpenWindow`. This is what the
  app-menu launcher and the tray "Open" invoke.

The daemon↔window boundary is the `io.github.jmarceno.Gravaai.Engine` D-Bus
interface (`daemon/dbus_service.py`); the JSON snapshot payload is the pure
`core/wire.py` (`Snapshot`/`JobView`). The daemon spawns the window via
`Gio.Subprocess` (fork+exec, never a bare fork — it has threads, D-Bus
connections and a live ffmpeg child) and supervises a single window via
`daemon/window_supervisor.py` (spawn-vs-present). `utils/autostart.py` writes
the login entry with `--daemon` and migrates a legacy entry in place on daemon
startup, so upgraders get a tray-only login instead of a GTK window
(`core/commands.py` holds the shared action vocabulary;
`utils/logging_setup.py` the shared logging).

**Model/GPU installs run in the daemon**, not the window: Settings → Models
install/build/download requests are sent as `StartInstall(spec)` and each runs
in a short-lived `--install` child (`daemon/installer.py`) the daemon spawns
and tracks (`daemon/install_manager.py`), so an install survives the window
closing and a Whisper download's heavy `faster_whisper` import never bloats
the daemon. Installs are keyed by the pure
`core/install_spec.py:install_key` (per-model/per-vendor scoped, so different
models install concurrently while the same request dedups); the daemon streams
`InstallProgress(key,text)`/`InstallFinished(key,ok,message)` D-Bus signals to
the open Models page and `GetInstalls()` lets a reopened window re-attach to
in-flight installs (`ModelsPage.reflect_running_installs`). Read-only status
checks (is-installed / is-cached / ollama-reachable) still run in the window.

**Audio recording** (`audio/`):
- `recorder.py` runs a single `ffmpeg` subprocess reading PulseAudio/PipeWire
  sources directly (`-f pulse`); `mixer.py` builds the command — mic+system
  mode `amerge`s mic (left channel) and sink monitor (right channel) into a
  true-stereo MP3 with a `highpass=f=80` filter, preserving speaker separation
  for transcription. Device names are resolved once in `start()` via
  `devices.py` (`pactl`).
- Pause/resume works via **segments**: pause terminates ffmpeg cleanly (saving
  the current segment), resume spawns a new ffmpeg writing the next segment,
  and stop concatenates all segments with ffmpeg's concat demuxer so paused
  intervals are excluded. `stop()` blocks until ffmpeg exits and segments are
  merged; a monitor thread reports unexpected ffmpeg death via `on_error`.
- Two modes: mic+system (`Record (Headphones)`) and mic-only
  (`Record (Speaker)` — the monitor is skipped to avoid echo).

**Recording state machine** (`core/state_machine.py`): `State`
(IDLE/RECORDING/PAUSED/COUNTDOWN) plus the pure `can_transition()` legality
table — `RecordingController._set_state()` validates against it (logs an error
on an illegal jump). `core/job.py` holds the `Job` dataclass (`JobStatus`
enum, per-job `CancelToken`) and `actions_for_status()`, the pure policy for
which buttons a job row offers. `State` is re-exported from
`ui/main_window.py` for existing importers.

**Recording lifecycle** (`core/recording_controller.py`):
`RecordingController` owns the Recorder instance, the stop/processing
countdown, and the authoritative lifecycle `State`; `MainWindow` only renders
state changes (`_apply_state`) and forwards button clicks (`window._state` is a
read-through property — app.py/tray still read it). Callbacks:
`on_state`/`on_error`/`on_commit(PendingRecording)`/`on_saved`/`on_discarded`/
`on_countdown`; `on_timer` and `on_recorder_error` arrive on recorder worker
threads and the window wraps them with `idle_call`. GTK dependencies
(countdown scheduler, recorder factory, device validation) are injected, so the
whole lifecycle is unit-testable headless. `make_job_label()` and
`settings.api_key_error()` are the extracted pure helpers.

**Job queue & persistence** (`core/job_manager.py`): `JobManager` owns the job
list and persists every change to `$XDG_STATE_HOME/meeting-recorder/jobs.json`
(atomic tmp+rename; cancelled jobs excluded). On startup `load_persisted()`
re-offers interrupted work: jobs that were PROCESSING when the app died come
back as ERROR rows ("Interrupted…") with Retry (pure policy
`restore_status()`), ERROR jobs restore as-is, DONE jobs are pruned.
Main-thread only, like all job mutations.

**Background work** (`core/task_runner.py`): all off-main-thread work goes
through the app-wide `TaskRunner` (created in `app.py`, passed to
`MainWindow`) — never raw `threading.Thread`.
`submit(fn, *args, on_done=, on_error=, description=)` runs `fn` on a tracked
daemon thread and routes the result/exception back to the GTK main thread; a
worker exception with no `on_error` is still logged, and main-thread callbacks
are wrapped so their own exceptions are logged instead of being swallowed by
GLib. `app.do_shutdown()` calls `runner.shutdown(grace_seconds=10)`, which
joins running tasks and logs any it had to abandon. Workers must only *read*
job state; all mutations happen in the main-thread callbacks (this is what
makes `_Job` race-free without locks). `CancelToken` provides cooperative
cancellation. The main-thread scheduler is injectable, so the module is
unit-testable without GLib.

**AI processing** (`processing/`):
- `Pipeline` runs transcription then summarization as separate calls (a single
  dual-prompt call was removed because the model would cut transcription short
  to save output budget for notes). `Pipeline.run(cancel_token=)` takes an
  optional `CancelToken` and checks it **between stages**
  (`PipelineCancelled` is raised; an in-flight network call still completes but
  no further stage starts and nothing is written) — each `_Job` in the main
  window carries its own token, cancelled from the job row / tray.
- Transient network failures (timeouts, connection resets, 5xx, 429) are
  retried with exponential backoff via `core/retry.py:retry_on_transient()` —
  used around the Gemini upload/generate calls and the Ollama generate call.
  Permanent errors (bad key, 4xx, model errors) fail immediately.
- `config/settings.py:gemini_key_warning()` is a pure format check (keys start
  with "AIza") surfaced as an alert when saving Settings, so a mispasted key
  is caught at save time instead of as a failed job.
- `transcription.py` / `summarization.py` expose factory functions
  (`create_transcription_provider`, `create_summarization_provider`) that
  return a provider based on config.
- Providers: `providers/gemini.py`, `providers/whisper.py`,
  `providers/whisper_cpp.py`, `providers/ollama.py`. Each implements
  `.transcribe()` or `.summarize()` and optionally `.unload()` to free GPU
  VRAM.
- Before running a local Whisper engine (`whisper` or `whisper_cpp`), the
  pipeline evicts any loaded Ollama models from VRAM.

**Call detection** (`detection/`): `AudioWatcher` runs `pactl subscribe` on its
own daemon thread and calls back on new mic-capture streams (pure matcher
`is_call_start_event()`); if pactl dies it is **restarted with exponential
backoff** (1 s → 60 s cap, reset after a healthy minute). `CallDetector` wraps
it with a notification dedup window. Injectable
`spawn_fn`/`sleep_fn`/`monotonic_fn` make it unit-testable.

**Bare-bones, opt-in local engines (Arch only):** The base install is
**Gemini-only** — `linux/requirements.txt` carries no local-engine deps. Local
capabilities are installed on demand from **Settings → Models** (pacman-only
install paths):
- `whisper` (faster-whisper) — installed via `WhisperEngineInstaller` (pip
  into the app venv). CTranslate2-backed, so **NVIDIA/CPU only**.
  `providers/whisper.py:_detect_device()` probes CUDA, else CPU.
- `whisper_cpp` — a from-source whisper.cpp build. Arch toolchain install is
  `sudo pacman -Syu --noconfirm git cmake base-devel`
  (`services/whisper_cpp_service.py` holds the pure helpers
  `detect_gpu_backend()` and `build_cmake_command(backend)`, the
  `WhisperCppBuilder`, and `WhisperCppStatusChecker`/
  `WhisperCppModelDownloader` (GGML files)). The provider parses `whisper-cli`
  JSON via the pure `parse_whisper_cpp_output()`.
- GPU runtime installs are pacman-only: `services/system_installer.py` has
  `detect_gpu_vendor()`, `CudaInstaller` (`pacman -Syu --noconfirm cuda`, for
  NVIDIA), and `RocmInstaller`
  (`pacman -Syu --noconfirm rocm-hip-runtime rocblas`, for AMD); the Settings
  "GPU Acceleration" section picks the right one.
- **Installer security conventions** (`services/system_installer.py`): no
  `os.system` — commands are argv lists run without a shell and logged before
  execution; privilege elevation via `build_privileged_command()` (`pkexec`
  polkit dialog, `sudo` fallback) with only fixed snippets; the Ollama install
  script is downloaded over HTTPS to a temp file with its SHA-256 logged, then
  executed from disk — never `curl | sh`. Test seams are
  `which_fn`/`run_fn`/`capture_fn`/`fetch_fn`.

**Config:** `~/.config/meeting-recorder/config.json`, `chmod 600`. Empty string
for any prompt key = use built-in default (defined in `config/defaults.py`).
**API key storage:** when a D-Bus Secret Service is available (GNOME
Keyring/KWallet), `settings.save()` stores the Gemini key there via
`config/keyring_store.py:KeyringStore` and writes only the `@keyring` sentinel
to config.json; `settings.load()` resolves the sentinel back.
`settings.migrate_key_to_keyring()` runs once at startup (`app.do_startup`) to
move a legacy plaintext key. Without a keyring everything falls back to
plaintext-in-chmod-600 exactly as before. The `secretstorage` module is
injectable for tests.

**GTK4 / libadwaita toolkit notes:** The UI is **GTK4 + libadwaita** (`Adw`).
`app.py` is an `Adw.Application` (auto-inits libadwaita → Adwaita stylesheet +
light/dark portal). There is no blocking `Gtk.Dialog.run()` — message/confirm
dialogs use the async `Gtk.AlertDialog` and file/folder pickers use the async
`Gtk.FileDialog` (callbacks via `Gio.AsyncReadyCallback`). GTK4 removed
`Gtk.Container`, so `pack_start`/`add`/`get_children` are gone — `ui/` builds
with `append`/`set_child` and the shared helpers in `utils/gtk_compat.py`
(`iter_children`, `remove_all_children`). Visibility uses `set_visible()` (no
`show_all`); inline events use `Gtk.GestureClick`/`EventControllerKey`/
`EventControllerFocus`. Adwaita idioms:
`Adw.ApplicationWindow`+`Adw.ToolbarView`+`Adw.HeaderBar`+`Adw.ViewStack`/
`ViewSwitcher`, `Adw.PreferencesGroup` rows
(`ActionRow`/`SwitchRow`/`ComboRow`/`EntryRow`/`PasswordEntryRow`),
`Adw.ToastOverlay`/`Toast` for transient errors,
`.boxed-list`/`.pill`/`.flat` style classes, and `Adw.Clamp` for centred
content.

**UI** (`ui/`): `main_window.py` (recording controls; job rows rendered by
`ui/jobs_panel.py:JobsPanel` from the pure `actions_for_status()` policy;
errors surfaced via the pure `core/errors.py:error_presentation()` policy —
actionable configuration problems get a modal `Gtk.AlertDialog`,
transient/runtime failures get a toast; `present_window()` re-shows +
`unminimize()` + `present()`; the header-bar gear is a `Gtk.MenuButton` whose
menu offers **Preferences** → settings dialog and **About Meeting Recorder** →
an `Adw.AboutDialog`, falling back to `Adw.AboutWindow`/`Gtk.AboutDialog` on
older libadwaita — app identity for it lives in `core/app_info.py`
(description, repo/issue links, authorship) plus a GTK-free
`resolve_version()` that reads the installed pacman package version, returning
`None` on a source checkout; its pure parsing is unit-tested via an injected
`run_fn`), `settings_dialog.py` (a thin `Adw.Window` shell —
Cancel/ViewSwitcher/Save header, page instantiation, and the save flow; each
tab lives in its own module under `settings_pages/`:
`general.py`/`models.py`/`prompts.py` page classes expose `.widget` and
`.apply(cfg)`, with shared row helpers + `IdComboRow` in
`settings_pages/widgets.py` (re-exported from `settings_dialog` for
compatibility); `ModelsPage` takes the same injected-service seams the dialog
passes through; `compute_section_visibility()` is the pure Models-tab
visibility policy; `on_saved` callback runs the post-save reconfiguration
since the dialog is modeless), `model_row_grid.py` (`Adw.PreferencesGroup` of
model `ActionRow`s with the same setter API), `meeting_explorer.py` (past
meetings browser; `.boxed-list` rows; double-click-to-rename via
`GestureClick`), `tray.py` (system tray icon). The tray is a **pure-DBus
StatusNotifierItem** built on `Gio.DBusConnection` (no GTK widgets, no new
dependency) implementing `org.kde.StatusNotifierItem` +
`com.canonical.dbusmenu`; it registers with the session `StatusNotifierWatcher`
and re-registers if the host restarts. Pure helpers `icon_for_state()` and
`build_menu_model()` hold the icon/menu policy — `icon_for_state()` returns
the bundled icon basename per state (idle/recording/paused/processing), and
`tray.py` renders the matching custom PNGs from `assets/tray/` as a raw ARGB
`IconPixmap` (not a theme `IconName`), so the branded tray artwork shows on
every host and when running from source. The app/launcher/window icon ships in
`assets/icons/hicolor/` (scalable SVG + PNG sizes, named `meeting-recorder` —
the `Icon=` key the desktop file references) and is installed into the
system/user hicolor theme by the install script and the Arch packaging; at
startup `ui/window_app.py:_setup_app_icon()` also adds the bundled tree to the
GTK icon-theme search path and calls
`set_default_icon_name("meeting-recorder")` so the icon resolves when running
from source. The installed desktop file is named after the application id
(`io.github.jmarceno.Gravaai.desktop`, matching `APP_ID` in
`config/defaults.py`, with `StartupWMClass`) so the GNOME/Wayland shell (and
Dash to Panel) maps a running window to it and shows the app icon instead of a
generic one. `MainWindow.on_use_existing_clicked` delegates its
in-tree-reuse vs. copy decision to the pure
`utils/recording_import.py:resolve_existing_recording_target()`.

**Import convention:** Provider files use 3-dot relative imports
(`from ...config.defaults import …`). Files outside `meeting_recorder/` use
absolute imports (`from meeting_recorder.config.defaults import …`).

---

## Project overview

Linux desktop applet (Arch only) plus shared headless pieces:

- **Language:** Python
- **UI:** GTK4 + libadwaita (`Adw.Application`/`Adw.ApplicationWindow`,
  preference-row settings, toasts, dark-mode; async
  `Gtk.AlertDialog`/`Gtk.FileDialog` instead of blocking `run()`).
- **Base dependencies (`linux/requirements.txt`):** `google-genai`,
  `setproctitle` — Gemini-only, minimal.
- **Opt-in local engines (installed on demand from Settings → Models,
  pacman-only):** `faster-whisper` (NVIDIA/CPU) installed via pip;
  `whisper.cpp` built from source (`pacman -Syu --noconfirm git cmake
  base-devel` toolchain); CUDA via `pacman -Syu --noconfirm cuda`; ROCm via
  `pacman -Syu --noconfirm rocm-hip-runtime rocblas`.
- **System tray:** a pure-DBus StatusNotifierItem
  (`org.kde.StatusNotifierItem` + `com.canonical.dbusmenu`) built on
  `Gio.DBusConnection` — no GTK widgets and no extra dependency (Gio ships
  with PyGObject). Left-click focuses the window where the SNI host delivers
  `Activate`, otherwise opens the menu. GNOME needs the
  AppIndicator/KStatusNotifierItem extension to provide the SNI host. The tray
  shows branded per-state artwork (idle microphone / record-dot / pause /
  processing) bundled in `assets/tray/` and sent as a raw ARGB `IconPixmap` so
  it renders on every host and from source.
- **App icon:** the launcher/window icon ships in `assets/icons/hicolor/`
  (scalable SVG + PNG sizes, named `meeting-recorder` — the `Icon=` key) and
  is installed into the hicolor theme by `install.sh` and the Arch packaging;
  `ui/window_app.py:_setup_app_icon()` also registers the bundled tree on the
  GTK icon-theme search path and sets it as the default icon so it resolves
  from source. The installed desktop file is named after the application id
  (`io.github.jmarceno.Gravaai.desktop`, with `StartupWMClass`) so the
  GNOME/Wayland shell maps a running window to it and shows the app icon
  rather than a generic one.

**Linux runs as two processes (daemon/UI split):** a GTK-free **daemon**
(`--daemon`, `daemon/`) owns the recording engine, jobs, pipeline, call
detection and tray; the GTK **window** (`--window`, `ui/window_app.py`) is
spawned as a child on demand and renders a snapshot fetched over the
`io.github.jmarceno.Gravaai.Engine` D-Bus interface. Launching with no flag is
**client** mode (`client.py`): ensure the daemon is up, then open a window.
`__main__.py` dispatches via `core/run_mode.py`. By default the window hides
on close and stays resident for instant reopen (pure policy
`core/window_close.py:resolve_close_action`); an opt-in **Low memory mode**
setting instead exits the window on close so GTK is loaded only while visible
(~20 MB idle in tray vs. ~100 MB). A spawned window never outlives its daemon:
it watches the Engine bus name and exits when the daemon quits/crashes (pure
`core/daemon_watch.py:should_exit_on_owner_change`), and the daemon
`force_exit`s its window child on quit — so a hidden window can't linger and
double up on the next daemon's `PresentWindow`. Two more short-lived child
roles keep heavy/long work out of the daemon: `--process` (one AI
transcription+summarization job) and `--install` (one model/engine install),
both spawned and tracked by the daemon and streamed back over D-Bus, so they
survive the window closing and don't bloat the daemon.

The app supports Google Gemini for transcription/summarization, local Whisper
(`faster-whisper`, NVIDIA/CPU) or whisper.cpp (built from source) for
transcription, and local Ollama for summarization. Local engines are not in
the base install — they are installed on demand from Settings → Models,
keeping a fresh install Gemini-only. Arch x86_64 and arm64 (via Arch Linux
ARM) are covered by CI.

---

## Building and running

### Linux app (Arch)

**Running from source:**

1. Create a Python virtual environment:
   ```bash
   python3 -m venv .venv --system-site-packages
   ```
2. Install dependencies:
   ```bash
   .venv/bin/pip install -r linux/requirements.txt
   ```
3. Run the application:
   ```bash
   PYTHONPATH=linux/src python3 -m meeting_recorder
   ```

**Running tests:**

1. Install pytest:
   ```bash
   pip install pytest
   ```
2. Run the tests:
   ```bash
   pytest
   ```

**Install / uninstall (Arch only):**

```bash
linux/install.sh
linux/uninstall.sh
```

---

## Development conventions

### Continuous integration (Gitea, manual dispatch only)

CI lives in `.gitea/workflows/` (renamed from `.github/`). Nothing runs on
push/PR — every workflow is `workflow_dispatch`:

- `ci.yml` — manually dispatched: Python lint + type check (ruff, mypy),
  unit tests (`pytest` on 3.10 + 3.12), pacman build smoke tests
  (x86_64 on `archlinux:latest`, arm64 on `menci/archlinuxarm:latest`).
- `release.yml` — manually dispatched with a `version` input (also callable
  via `workflow_call`): builds the pacman `.pkg.tar.zst` in an Arch container
  and creates a Release with the Arch artifact + source tarball.
- `auto-release.yml` — manually dispatched with a `bump` input
  (patch/minor/major): tags `v*` and calls `release.yml` via `workflow_call`.

### Release process (Arch only)

Manual dispatch with a version input:

| Trigger | Workflow | Output |
|---|---|---|
| Manual (`version`, e.g. `1.2.0`) | `release.yml` | `.pkg.tar.zst` + source tarball attached to Release |
| Manual (`bump`) | `auto-release.yml` → `release.yml` | `v*` tag, then same as above |

Debian (`.deb`/apt-repo), Fedora (`.rpm`), and Android (`.apk`) pipelines were
removed with the non-Arch code.

### Repository layout (Arch-only fork)

```
linux/
├── src/meeting_recorder/  # GTK4 + libadwaita desktop app (Python)
├── tests/                 # Unit tests (pytest)
├── packaging/             # Arch PKGBUILD + launcher/icon assets only
│   ├── arch/              # PKGBUILD + install hook
│   └── usr/               # launcher + io.github.jmarceno.Gravaai.desktop
├── install.sh / uninstall.sh  # Arch/pacman only
└── requirements.txt / requirements.lock
.gitea/workflows/          # CI + release (manual dispatch only)
scripts/                   # headless Gemini pipeline test helpers
```

Removed vs. upstream: `android/`, `docs/`, `CLAUDE.md`, `GEMINI.md`,
`linux/packaging/DEBIAN/`, `linux/packaging/rpm/`,
`.gitea/workflows/release-android.yml`, all deb/rpm/apt/gh-pages steps.

---

## Test coverage boundaries

`linux/tests/core/test_task_runner.py` covers `TaskRunner` (result/error
routing, logging of unhandled worker and callback exceptions, graceful shutdown
with abandoned-task reporting, submit-after-shutdown) and `CancelToken`, using
an injected immediate scheduler instead of GLib.
`linux/tests/core/test_retry.py` covers `retry_on_transient` and the
`is_transient` classifier (backoff schedule, permanent-vs-transient, HTTP
status attributes). `linux/tests/core/test_state_machine.py` covers the
`can_transition` legality table (allowed/illegal/self-transitions,
exhaustiveness). `linux/tests/core/test_job.py` covers `Job` defaults,
per-job tokens, and the `actions_for_status` row policy.
`linux/tests/core/test_job_manager.py` covers JobManager persistence
round-trips, atomic writes, cancelled-job exclusion, and startup recovery
(interrupted→error+retry, done pruned, id collision avoidance,
corrupt/malformed state tolerated) plus the pure `restore_status` policy.
`linux/tests/core/test_errors.py` covers the `error_presentation`
dialog-vs-toast policy. `linux/tests/core/test_recording_controller.py` covers
the full recording lifecycle headless (start validation/failure paths,
pause/resume, stop with and without countdown, countdown tick/cancel,
cancel+save, cancel+discard with audio deletion, abort recovery) with fake
recorder/scheduler. `linux/tests/processing/test_pipeline.py` covers Pipeline
fail-fast and cancel-token guards.
`linux/tests/config/test_settings_validation.py` covers `gemini_key_warning`.
`linux/tests/config/test_keyring.py` covers `KeyringStore`
(roundtrip/replace/delete/unavailable/locked-collection) and the settings
keyring integration (sentinel on disk, plaintext fallback,
clear-deletes-secret, one-time migration) with an in-memory secretstorage fake.
`linux/tests/detection/test_audio_watcher.py` covers the pure
`is_call_start_event` matcher and the watcher's restart/backoff/stop behavior
with fake processes. Tests in `linux/tests/services/` cover `OllamaService`,
`WhisperService`, and `SystemInstaller` (Arch-only: `CudaInstaller` pacman
`cuda`, `RocmInstaller` pacman `rocm-hip-runtime rocblas`,
`WhisperEngineInstaller`, and `detect_gpu_vendor`) with mocks/temp dirs.
`linux/tests/services/test_whisper_cpp_service.py` covers
`detect_gpu_backend`, `build_cmake_command`, `WhisperCppBuilder` (pacman
toolchain + clone + cmake), and the GGML status/downloader.
`linux/tests/processing/providers/test_whisper_cpp.py` covers the pure
`parse_whisper_cpp_output`, the provider's injected-runner `transcribe` flow,
and the `whisper_cpp` factory wiring.
`linux/tests/processing/providers/test_ollama.py` covers
`OllamaProvider.summarize` error handling (server error field, unreachable
host, empty response, bounded timeout, transient-retry) via the injected
`http_open` hook. `linux/tests/ui/test_tray.py` covers the pure tray helpers
`icon_for_state`, `build_menu_model`, and `assign_menu_ids` (fresh,
never-reused dbusmenu ids across rebuilds — so a host caching items by id
can't merge stale props onto a reused id).
`linux/tests/ui/test_settings_visibility.py` covers
`compute_section_visibility` (the Models-tab section/separator policy).
`linux/tests/ui/test_existing_recording.py` covers
`resolve_existing_recording_target` (the import in-tree-reuse vs. copy
decision). For the daemon/UI split: `linux/tests/core/test_run_mode.py`
covers `resolve_run_mode` (daemon/window/client dispatch),
`linux/tests/utils/test_autostart.py` covers
`needs_autostart_migration`/`migrate_autostart_exec` (idempotent legacy-entry
rewrite preserving foreign keys), `linux/tests/core/test_wire.py` covers the
`Snapshot`/`JobView` JSON round-trip (job render fields, status_text,
empty/garbage/missing-key tolerance),
`linux/tests/core/test_window_close.py` covers `resolve_close_action` (the
low-memory-mode hide-vs-exit policy, default-hide, truthy/falsy tolerance),
`linux/tests/core/test_daemon_watch.py` covers
`should_exit_on_owner_change` (the window-outlives-daemon exit policy:
vanish-after-seen exits, startup-race and present cases don't),
`linux/tests/daemon/test_engine.py` covers the headless `Engine`
(snapshot/state naming, job-status-text tracking, job-row actions,
API-key/duplicate guards, and the processing-child lifecycle — launch,
done+path-adoption, cancel-kills-child, error) with a fake controller +
recording runner + injected fake `ProcessorLauncher` + temp JobManager, and
`linux/tests/daemon/test_window_supervisor.py` covers the window
spawn-vs-present decision. The `--process` child entry and the
`Gio.Subprocess`-based `ProcessorLauncher` pipe reading need a real subprocess
and are not unit-tested (the engine's use of the launcher is, via the fake).
For daemon-run installs: `linux/tests/core/test_install_spec.py` covers
`install_key` (per-kind/per-model/per-vendor scoping) and the spec JSON
round-trip/validation, and `linux/tests/daemon/test_install_manager.py`
covers the `InstallManager` bookkeeping (start/dedup, concurrent distinct
keys, progress-updates-status, finished-removes-and-notifies, bad-spec
rejection) with a fake launcher. The `--install` child dispatch, the
`Gio.Subprocess` `InstallLauncher`, and the `ModelsPage` install signal
routing need a real subprocess/bus/display and are not unit-tested (the
manager and install-key routing they rely on are).
`linux/tests/core/test_app_info.py` covers the pacman-only `resolve_version`
(name + version parsing, malformed/blank/unknown tolerated). GTK UI (the GTK4
widget construction in `ui/`, the async dialog callbacks, the window client's
D-Bus proxy, the daemon's D-Bus Engine service and main loop, and the tray's
D-Bus wiring) remains not unit-tested — pure decision logic is extracted into
testable helpers/services per the pattern above.
