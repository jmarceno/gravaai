# Gravaai - Java implementation

This directory is the Java rewrite specified by `docs/java-rewrite-plan.md` (read it first; it is
the single source of truth).

## Build

```bash
cd java
./mvnw -T1 package            # compile + unit tests
./mvnw -T1 -Pdist package     # + jlink runtime, jpackage app image, AOT cache, AppImage
java/tools/phase-gate.sh      # tests + dist + startup budget + UI capture gallery
```

`./mvnw` picks the pinned JDK 27 toolchain when `JAVA_HOME` is not already one (`GRAVAAI_JAVA_HOME`
overrides). Maven itself is pinned to 3.9.16 by the wrapper. All Maven builds must go through
`java/tools/mvn-locked.sh` (flock + `-T1` + `MAVEN_OPTS=-Xmx1g`, plan §1.1.2).

## Running

```bash
# packaged app image / AppImage
java/dist/gravaai/bin/gravaai
java/dist/Gravaai-linux-x64.AppImage

# development flags
gravaai --ui-kit      # UI kit showcase (P1.3 visual gate)
gravaai --dashboard   # dashboard screen (P1.10 dev/screenshot entry)
gravaai --mcp         # MCP bridge over stdio (Phase 3, P3.2)
gravaai --no-tray     # start without the status tray (GRAVAAI_NO_TRAY=1 is the env form)
gravaai --toggle-recording   # any gravaai-ctl action; forwarded to a running instance
gravaai --help        # the headless CLI (record/export) and its flags
```

Only one Gravaai runs per user-data directory (`instance.lock`). A second launch forwards its
action, or `show` to bring the running window to the front, over the control socket and exits.

## Headless CLI

`record` and `export` run the real recording and export pipelines without starting JavaFX, so they
work from a script, over SSH, and while the app is open (they open no windows, so they are not a
second instance). `gravaai --help` documents every flag.

```bash
# record the first output for 30 s with system audio and the mic
gravaai record --duration 30 --system-audio --mic --out demo.mp4

# record until Ctrl-C (stops and finalises, like the HUD's stop button)
gravaai record --out demo.mp4

# render a project (or a raw recording) to 1080p MP4
gravaai export demo.gravaai --out demo.mp4 --preset 1080p

# a 720p GIF at 15 fps, with machine-readable progress
gravaai export demo.gravaai --out demo.gif --format gif --fps 15 --preset 720p --json
```

- **`--preset`** takes the export menu's quality tiers (`medium`, `good`, `high`, `source`); the
  resolution spellings are aliases, because the menu's quality *scales* the recording, so `1080p`
  means the full-size master for a 1080p source.
- **`--json`** writes one compact JSON object per line on stdout (`export_start`, `progress`,
  `done`/`error`) and keeps every human line on stderr. Without it the written file name goes to
  stdout and progress to stderr, so `out=$(gravaai export …)` works.
- **Exit codes:** `0` success, `1` the recording/export failed, `2` bad usage. Unknown flags are
  usage errors rather than silently ignored.
- Exports honour the app's export settings (quality, fps, captions sidecar) unless overridden on the
  command line, and render extension hooks when the user has extensions installed.
- Recording still uses the session's capture backend, so on KDE/GNOME the desktop's own
  screen-sharing dialog remains the picker.
- **Telemetry:** the recorder writes the same cursor and key sidecars as the HUD, so a scripted take
  exports with the cursor, zooms and keystrokes the app would generate.

Use a scratch profile for tests/screenshots: `GRAVAAI_USER_DATA=/tmp/... gravaai --ui-kit`.

There are no UI tests: the UI is checked by hand. `./mvnw -T1 test` runs the unit and backend tests.

The app is a single-window application: the dashboard, the editor and the meeting view share the
primary stage; only the HUD overlay/menu, countdown, capture picker and area border are separate
OS windows.
Stop the app with `pkill -x gravaai` only. SIGTERM/SIGINT close the tray and the session bus from a
JVM shutdown hook, so a stopped run unexports its item while the process still owns its bus name;
use `--no-tray` for the automated start/stop runs that never look at the panel.

## Layout

```
gravaai-core/      domain model, project format, settings stores, pure logic, i18n
gravaai-platform/  ProcessRunner, X11, D-Bus, trash, paths, sockets, native helpers
gravaai-media/     FFmpeg FFM bindings, EGL/GL, Skija compositor, playback, export
gravaai-services/  recording, projects, captions, meetings, automations, agent/MCP
gravaai-ui/        JavaFX kit, windows, editor, dashboard, timeline, dialogs
gravaai-app/       Main, AppContext, CLI, packaging (jlink/jpackage/AppImage)
native/            C helpers, FFmpeg/whisper build scripts
tools/             extraction/build/release tooling (bash; Node only for asset extraction/parity)
parity/            golden frames, UI reference screenshots, fixture projects
```

Dependency direction: `core <- platform <- media <- services <- ui <- app`. Never add a cycle.
Java package root: `dev.gravaai.<module>.<area>`.

## Platform and desktop-environment rules

A desktop environment or compositor difference is **one decision in one place**, never an `if`
inside a larger class:

* `platform/…/compositor/` owns the whole question. `SessionType` classifies
  `XDG_SESSION_TYPE`/`WAYLAND_DISPLAY`/`DISPLAY`; `DesktopEnvironment` is the full list of desktop
  names, each declared with its own `DesktopProfile` (capture backend, cursor backend, shortcut
  strategy); `DesktopSession` combines them. **No other code reads `XDG_CURRENT_DESKTOP` or
  `XDG_SESSION_TYPE`.** Adding a desktop is one enum constant plus its aliases.
* `core/…/xdg/` owns the freedesktop base-directory rules; nothing re-derives `$XDG_*_HOME` by hand.
* `core/…/os/HostOs` owns the host-OS question (`os.name`), for the few ported branches that still
  ask it.
* `platform/…/process/ExecutableLookup` owns `$PATH` lookup; `platform/…/filemanager/DesktopOpener`
  owns `xdg-open`. Tools are located through `NativeHelpers.payloadPath`/`payloadFile`, not through
  ad-hoc relative-path ladders.
* Desktop integration that can be absent (portal, tray, notifications, D-Bus) is grouped in
  `app/…/DesktopServices` and degrades to a null service, never to a failed start.

Concession: when a difference really is a state machine (which capture and pointer mechanism a
session gets), it is modelled as one — an explicit enum plus a profile — rather than as a chain of
conditionals.

## Machine rules (plan §1.1)

* Hardware encoding is supported and preferred for recording when the bundled ffmpeg can use it
  (VA-API, NVENC, QSV, AMF, Vulkan video, in that probe order); libx264 is the per-job fallback.
  Exports use hardware from 4K up only: on the weakest supported GPU it is slower in wall-clock
  below that (see AGENTS.md for the measurements). The dev payload (`build-ffmpeg.sh`) stays
  software-only by design; the release payload (`fetch-ffmpeg.sh`) ships the GPU encoders. No
  `-hwaccel` decode path.
* One build or test JVM at a time (`mvn-locked.sh`), build output on the scratch disk.
* Kill the app with `pkill -x gravaai` (never `pkill -f`).
