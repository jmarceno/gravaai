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
gravaai --toggle-recording   # any gravaai-ctl action; forwarded to a running instance
```

Only one Gravaai runs per user-data directory (`instance.lock`). A second launch forwards its
action, or `show` to bring the running window to the front, over the control socket and exits.

Use a scratch profile for tests/screenshots: `GRAVAAI_USER_DATA=/tmp/... gravaai --ui-kit`.

UI tests run on an isolated virtual display by default (`gravaai.test.display=:99`) so a test run
never pops windows on the desktop you are using. Start the display once per session:

```bash
Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp &
cd java && ./mvnw -T1 test          # UI tests use :99 transparently
./mvnw -T1 test -Dgravaai.test.display=:0   # only when you want windows on :0
```

The app is a single-window application: the dashboard, the editor and the meeting view share the
primary stage; only the HUD overlay/menu, countdown, capture picker and area border are separate
OS windows.
Stop the app with `pkill -x gravaai` only.

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

## Machine rules (plan §1.1)

* Software libx264 only; never GPU/VA-API/VDPAU/Vulkan video, never `-hwaccel`.
* One build or test JVM at a time (`mvn-locked.sh`), build output on the scratch disk.
* Kill the app with `pkill -x gravaai` (never `pkill -f`).
