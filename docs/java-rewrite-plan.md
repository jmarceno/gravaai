# Gravaai — Java Rewrite Plan

Status: approved direction, ready for implementation.
Author of the plan: the reviewer. The implementer follows it, and the reviewer reviews each phase.
Date: 2026-10-27.

This document is the single source of truth for rewriting Gravaai from Electron + React +
TypeScript into modern Java. It is written for an implementer who did not take part in the
investigation. **Read all of it before writing code.** When something here conflicts with your
own idea, follow this document. When something here turns out to be wrong in practice, stop and
write the problem down for the reviewer (see §12). Do not quietly work around it.

---

## 0. How to use this document

- §1 holds the non-negotiable rules. Breaking one fails the phase.
- §2–§3 describe what exists today and what the spike proved.
- §4 is the target architecture: modules, threads, libraries and versions.
- §5 covers each subsystem in detail: what it does today, how it works in Java, and where the
  source is.
- §6 is the parity and test harness. This is how "one-to-one" gets verified.
- §7 lists the three phases, with ordered tasks and acceptance gates.
- §8 explains how to port a TypeScript file into Java.
- §9 covers risks and decision rules: what to do when X fails.
- The appendices map every IPC channel and every source directory to its Java replacement.

At the end of each phase, stop. Write `docs/java-rewrite/phase-N-report.md` (§7.4), and wait
for the reviewer. Do not start the next phase without written approval.

---

## 1. Non-negotiable rules

### 1.1 Machine safety (this machine has crashed before)
1. **Never run GPU or hardware video encode/decode tests.** That means no VA-API, no
   `-hwaccel`, no `h264_vaapi`, no VDPAU, no Vulkan video. A VA-API ffmpeg test hung the RX 560
   and forced a reboot. All video encoding is **software libx264**, and all decoding is
   software (libavcodec). OpenGL *rendering* is fine; the spike ran it.
2. **Cap memory and parallelism on every heavy command:**
   - Maven: always `-T1`, `MAVEN_OPTS="-Xmx1g"`.
   - Surefire/Failsafe: `forkCount=1`, `reuseForks=true`, `argLine=-Xmx1g`.
   - Never run more than one build or test JVM at a time.
   - Never write Python "edit scripts" that rewrite many files.
   - Prefix long builds with `nice`.
3. **Build output goes to the scratch disk**, never into the repo:
   `/media/jmarceno/scratch/java-targets/<module>`. Set this through `<build><directory>` in each
   POM (the spike's `pom.xml` shows how).
4. **Kill the app by exact process name:** `pkill -x gravaai`. Never use `pkill -f gravaai`:
   `-f` matches your own shell's command line and kills it.

### 1.2 Project rules
5. **No automatic CI.** GitHub workflows must be `workflow_dispatch` only. Never trigger on
   push, PR, merge or schedule.
6. **Deliverable = a native binary.** Every phase ends with a packaged app image and an
   AppImage the user can launch. Never hand the user a script to run.
7. **The UI is a faithful copy of the current React UI.** Copy it exactly: layout, spacing,
   colours, typography, icons, animations, wording. Do not invent, simplify or "improve". The
   previous Rust/QML attempt was deleted because its UI was invented and its buttons weren't
   wired to anything. Every control you build must be wired end to end before you call it done.
8. **Every editor feature has a visible indicator.** If a feature affects the timeline (zoom,
   speed, trims, clips, annotations, audio, captions), it must show on the timeline exactly as
   it does today. This is never a "nice to have".
9. **User data compatibility is mandatory.** The Java app must read and write the same data
   directory, the same JSON files and the same `.gravaai` project files as the Electron app. A
   user switching from Electron to Java must lose nothing (§5.2).
10. **Do not delete or change the Electron app** until Phase 3, task P3.6. The Java app lives in
   `java/` next to it.
11. **No preview or incubator Java features.** That means no `--enable-preview` and no Vector
   API. Use only final features of JDK 27.
12. Do not discuss licensing and do not give time estimates. Neither is needed.
13. Commit in small, coherent commits on a branch `java-rewrite`. Never push and never open
   PRs unless the user asks. Never commit build output.

---

## 2. What exists today (inventory)

The app is Gravaai: a screen recorder, video editor and meeting note-taker. It now targets only
**Linux on the COSMIC Wayland desktop** (see README).

| Area | Location | Size (non-test lines) |
|---|---|---|
| Electron main process ("brain") | `electron/` | 42.7k TS |
| React UI components | `src/components/` | 56.0k TSX/TS |
| Exporter + libs | `src/lib/` (exporter alone 24.8k) | 20.6k |
| Hooks | `src/hooks/` | 3.0k |
| Native helpers (all platforms) | `electron/native/` | 10.1k |
| **Linux native helpers** | `electron/native/{wayland-capture,wayland-cursor,gravaai-ctl}` | 816 C |
| UI tests (Playwright) | `tests/ui/*.spec.ts` + `tests/ui/bridge.ts` | 35 files |
| i18n | `src/i18n/locales/<11 locales>/*.json` | 8 namespaces |

### 2.1 Windows the app shows today (`electron/windows.ts`)
| Window | Created by | Renderer entry | Notes |
|---|---|---|---|
| HUD overlay | `createHudOverlayWindow` | `?windowType=hud-overlay` → `src/components/launch/HudWindow.tsx`, `LaunchWindow.tsx`, `RecordingControls.tsx` | transparent, frameless, "always on top" (does **not** actually stay on top on COSMIC today — status quo) |
| HUD menu | `createHudMenuWindow` | `?windowType=hud-menu` → `HudMenuWindow.tsx`, `launch/popovers/*` | mic / webcam / countdown menus, live camera preview, audio meters |
| Editor / dashboard | `createEditorWindow` | `VideoEditor.tsx`, `dashboard/*`, `meeting/*` | main window: dashboard (library), editor, meeting view |
| Source selector | `createSourceSelectorWindow` | `?windowType=source-selector` → `SourceSelector.tsx` | |
| Countdown | `createCountdownWindow` | `?windowType=countdown` → `countdown/CountdownOverlay.tsx` | |
| Capture picker (one per monitor) | `createCapturePickerWindows` | `capture-picker/CapturePicker.tsx` | "Pick screen" selects the monitor you click |
| Area recording border | `showAreaRecordingBorder` | inline | click-through |
| Tray | `electron/main.ts` (`Tray`) | — | menu: Show Overlay, Open Gravaai, Quit, plus stop recording |

### 2.2 Electron-only plumbing that disappears in Java
- `electron/preload.ts` (1223 lines) defines `window.electronAPI`: about 200 IPC channels
  (Appendix A). In Java these become direct method calls on services.
- `electron/mediaServer.ts` and `get-local-media-url` served local video to `<video>` over HTTP.
  Java decodes files directly.
- `rendererServer.ts`, `navigationPolicy.ts`, `permissionPolicy.ts`, `gpuSwitches.ts`,
  `normalize/smoke-electron-main-cjs` and the vite configs are all Chromium/Electron-specific.
  There's nothing to port.

### 2.3 Platform code that is NOT ported (macOS/Windows only)
`*.swift`, `electron/native/{wgc-capture,windows-capture,cursor-monitor,gpu-export-probe}`,
`electron/native/bin/darwin-*`, `ipc/recording/{mac.ts,windows.ts,windowsFallbacks.ts,macCompanionAudio.ts}`,
`ipc/windowsCaptureSelection.ts`, `ipc/windowsWindowControl.ts`, `windowsBroadcast.ts`,
`macosDistributionPolicy*`, `scripts/{build-windows-*,verify-macos-distribution,macos-distribution-policy}.mjs`,
the macOS accessibility/screen-recording permission channels, and the exporter routes
`native-static-layout` and `breeze-stream` (browser hardware encoders, Windows/mac only).
The Linux export route today is the WebCodecs one. Also skip the browser capture paths
(`getDisplayMedia`, `useScreenRecorder` browser recording, `browserScreenCapture.ts`): on
COSMIC the native helper path is the one in use. **Write each skipped file into the Appendix B
"N/A" list in your phase report, so the reviewer can see it was a decision and not an
oversight.**

---

## 3. What the spike proved (facts, 2026-10-27)

Spike code: `spike/javafx-cosmic/`. Use it as reference code for the GL/EGL, PixelBuffer, FFM,
X11 and packaging patterns.

| Finding | Detail |
|---|---|
| Toolchain | JDK 27.0.1 (Temurin), JavaFX 27, LWJGL 3.4.3, Maven 3.9.16. Toolchain in `/media/jmarceno/scratch/java-toolchain/`. |
| JavaFX window system | JavaFX 27 runs on **XWayland** (GTK3 X11 backend, Prism ES2 on GLX). It has no native Wayland support. |
| Editor window | Works, styled with JavaFX CSS. |
| Monitors | Both monitors detected correctly, including the rotated 1080×1920 one. |
| GL preview | An EGL **surfaceless** context (no window) with GL 4.6 core renders 1920×1080 into an FBO. Two PBOs give async readback into two `PixelBuffer`s (double buffering). **60 fps presented**, draw <0.1 ms, readback + copy 1–9 ms. |
| Startup (SSD or HDD makes no difference; files are in the page cache) | No AOT: editor visible at ~0.86 s, first preview frame ~0.91 s. **With AOT cache: editor ~0.58 s, first preview frame ~0.60 s.** JVM boot itself is 30 ms. |
| Always-on-top / placement | COSMIC ignores keep-above and reverts client positioning for XWayland windows. Electron has the same status quo, so this is **accepted**. Do not try to fix it in this rewrite. |
| Packaging | `jlink` (JDK + JavaFX jmods) + `jpackage --type app-image` gives an ELF launcher with the runtime included (109 MB). |

Gotchas found (each one cost time; don't rediscover them):
1. LWJGL's argument check throws an NPE for `eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, 0, …)`,
   because a null native display is "invalid" to LWJGL but required by the spec. Call that one
   entry point through **FFM** with the function pointer from `EGL.getCapabilities()`
   (see `GlPreviewRenderer.initEgl`).
2. JDK 27 needs `--enable-native-access=ALL-UNNAMED,javafx.graphics` in the launcher options.
3. `ProcessHandle.current().info().startInstant()` is off by about 0.9 s on this kernel. Measure
   startup with a launch timestamp passed in `-Dgravaai.launchedAt` (wall clock).
4. Start GL/EGL setup on its own thread **before** `Application.launch`, so it overlaps toolkit
   start-up. This saved about 300 ms.
5. AOT cache training must use exactly the launcher's classpath and options
   (`lib/app/*.cfg`). Otherwise the cache is rejected and even default CDS is lost.
6. LWJGL 3.4.3 prints `sun.misc.Unsafe` deprecation warnings. They are harmless; ignore them.
7. Under XWayland COSMIC re-parents client windows into frames. When searching the X tree for a
   window by title, descend into children (`X11Bridge.findWindow`).
8. `cosmic-screenshot --interactive=false --notify=false -s <dir>` takes a screenshot
   non-interactively. Use it for visual checks.

---

## 4. Target architecture

### 4.1 One process, no web engine
```
gravaai (jpackage launcher, JDK 27 runtime, AOT cache)
├── FX application thread ............ all JavaFX UI (windows, dialogs, timeline)
├── "gl-preview" platform thread ..... EGL surfaceless ctx + Skija: editor preview compositor
├── "gl-export" platform thread ...... separate EGL ctx + Skija: export compositor (only while exporting)
├── decode threads (platform) ........ libavcodec video/audio decode per open media
├── "audio-out" platform thread ...... libpulse-simple playback for the editor preview
├── service executors (virtual) ...... file IO, processes, HTTP, sockets, jobs
└── child processes .................. gravaai-wayland-capture, gravaai-wayland-cursor, ffmpeg (recording),
                                       parec, pactl, whisper-cli, llama-server, gio
```
Separate mode, `gravaai --mcp`: there's no JavaFX and no GL. It speaks MCP over stdio and forwards to the
running app through the agent socket (§5.16).

**Thread rules:**
- JavaFX nodes are touched only on the FX thread. Services publish events. The `UiEvents` adapter
  hops to the FX thread with `Platform.runLater`.
- A GL context is used only by the thread that created it. Never share a Skija `DirectContext`
  across threads.
- Use platform threads, not virtual threads, for anything that sits in native code for long
  (decode, GL, audio out). FFM downcalls pin virtual threads.
- Use virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for blocking IO and process
  waiting.

### 4.2 Repository layout
```
java/
  pom.xml                         parent POM (dependency + plugin management, build dir on scratch)
  mvnw, .mvn/wrapper/             Maven Wrapper (pins Maven 3.9.16)
  gravaai-core/                   domain model, project format, settings stores, pure logic, i18n. No JavaFX, no native.
  gravaai-platform/               ProcessRunner, X11Bridge, D-Bus (tray, notifications, portals, file manager),
                                  trash, paths, unix sockets, native helper locator
  gravaai-media/                  FFmpeg FFM bindings + wrapper API, GL/EGL context, Skija compositor,
                                  playback engine, audio out, export pipelines, thumbnails
  gravaai-services/               recording, library/projects, captions, meetings, automations, agent/MCP,
                                  global shortcuts, extensions host, settings services
  gravaai-ui/                     JavaFX: UI kit, windows, editor, dashboard, timeline, dialogs
  gravaai-app/                    Main, AppContext wiring, CLI args, packaging (jlink/jpackage/AOT/AppImage)
  native/                         C helpers moved from electron/native (wayland-capture, wayland-cursor, gravaai-ctl)
                                  + build scripts for helpers, FFmpeg and whisper runtime (bash)
  tools/                          one-time asset extraction scripts (icons) — Node is allowed here only
  parity/                         golden frames, UI reference screenshots, fixture projects
```
Dependency direction: `core ← platform ← media ← services ← ui ← app`. `core` never depends on
anything above it. Never create cycles.

Java package root is `dev.gravaai.<module>.<area>`, for example `dev.gravaai.media.ffmpeg` and
`dev.gravaai.ui.editor.timeline`.

The app is **non-modular (classpath)**, exactly like the spike. JavaFX lives in the jlink
runtime image as modules; the app jars and their libraries go on the classpath. Do not add
`module-info.java`.

### 4.3 Libraries (pin these exact versions; bump only with reviewer approval)
| Purpose | Library | Version |
|---|---|---|
| Runtime | Temurin JDK | 27.0.1 |
| UI | OpenJFX (jmods for jlink, `javafx-controls` provided for compile) | 27 |
| GL + EGL | LWJGL `lwjgl`, `lwjgl-opengl`, `lwjgl-egl` (+ `natives-linux` for lwjgl, lwjgl-opengl) | 3.4.3 |
| 2D on GPU (compositor, text, shapes, SVG, image encode) | Skija `io.github.humbleui:skija-linux-x64` + `skija-shared` | 0.143.18 |
| JSON | Jackson 3 `tools.jackson.core:jackson-databind` | 3.2.4 |
| MCP | `io.modelcontextprotocol.sdk:mcp` (stable) | 2.1.0 |
| D-Bus | `com.github.hypfvieh:dbus-java-core` + `dbus-java-transport-native-unixsocket` | 5.2.2 |
| Markdown (meeting notes) | `org.commonmark:commonmark` (+ `commonmark-ext-gfm-tables`, `-gfm-strikethrough`, `-task-list-items`) | latest 0.2x stable at P1 start, pinned |
| Extensions JS engine | `org.graalvm.polyglot:polyglot` + `js-community` (+ `org.graalvm.truffle:truffle-runtime` for JIT) | 25.4.4.1.1 (Phase 3) |
| Tests | JUnit Jupiter | 6.1.3 |
| Property tests (replaces fast-check) | jqwik | 1.10.2 |
| UI tests | TestFX `testfx-junit5` | 4.0.18 |
| FFmpeg (bundled shared libs + CLI built by us) | FFmpeg | 8.0.x (latest 8.0 point release at P1 start, pinned) |
| whisper.cpp runtime | existing `scripts/build-whisper-runtime.mjs` port | same pin as today |

If the MCP SDK needs Jackson 2 (`com.fasterxml.jackson`), that is fine: Jackson 2 and 3 live in
different packages. Use Jackson 3 in our own code.

**Do not add any other library without asking the reviewer.** In particular: no Spring, no DI
framework, no JNA, no Netty, no Guava, no Lombok, no logging framework (use `System.Logger` →
`java.util.logging`, §5.1).

### 4.4 Native boundary policy
- Use **FFM** (`java.lang.foreign`) for every C call we make ourselves: FFmpeg libs, libpulse-simple,
  Xlib, libc `ioctl`/`getuid`, EGL entry points that LWJGL mishandles.
- FFmpeg bindings are generated **once** with `jextract` (the JDK 27-compatible build from
  jdk.java.net/jextract). They are committed under `gravaai-media/src/main/java/dev/gravaai/media/ffmpeg/gen/`
  and regenerated only when FFmpeg is bumped. The build must not need jextract. Write the exact
  jextract command into `java/native/README.md`.
- Hand-written wrapper classes (§5.7) are the only code that touches the generated bindings. The
  rest of the app never sees a `MemorySegment` from FFmpeg.
- LWJGL handles GL and EGL (except gotcha 1). Skija handles 2D drawing.
- Existing C helpers stay C processes, talked to over stdin/stdout exactly as today. They are
  small, proven, and isolate Wayland protocol code from the JVM.

---

## 5. Subsystem designs

Each subsection gives: **Today** (where the logic lives), **Java** (what to build), and
**Notes/pitfalls**.

### 5.1 App bootstrap, lifecycle, logging, CLI
**Today:** `electron/main.ts`, `appPaths.ts`, `editorStartupView.ts`, `countdownController.ts`,
`saveBeforeClose.ts`, `recordingEditorNavigation.ts`.

**Java:** `gravaai-app`:
- `dev.gravaai.app.Main`:
  1. Record the launch time.
  2. Parse args.
  3. Single-instance check: if the control socket (§5.17) accepts a connection, forward the
     action/args and exit 0.
  4. Start the `gl-preview` thread (spike gotcha 4).
  5. Call `Application.launch(GravaaiApp.class)`.
  `--mcp` branches off before anything else and starts no JavaFX.
- `AppContext` builds every service by hand (constructor injection) and owns shutdown order:
  1. stop recording safely (finalise the manifest),
  2. stop jobs,
  3. close sockets,
  4. stop GL threads,
  5. `Platform.exit()`.
- Supported CLI flags: every flag `electron/main.ts` and `gravaai-ctl` use (`--mcp`, and
  `--<action>` launch actions from gravaai-ctl, such as start recording or meeting). Read
  `main.ts` and `gravaai-ctl/main.c` for the exact list. **Same names.**
- Logging: `System.Logger` backed by JUL, one `FileHandler` at
  `<userData>/logs/gravaai.log` (rotating, 5 × 2 MB) plus stderr. Never write logs to stdout in
  `--mcp` mode, because stdout carries JSON-RPC.
- Startup budget (enforced at every phase gate): dashboard/editor window **visible ≤ 0.8 s**
  and **first preview frame ≤ 0.9 s** after launch on this machine, measured as in the spike.

### 5.2 Paths, storage and data compatibility
**Today:** `electron/appPaths.ts`, `electron/ipc/constants.ts`, `appSettingsStore.ts`,
`ipc/settings/recordingPreferencesStore.ts`, `ipc/project/*`, `ipc/utils.ts`.

**Java:** `dev.gravaai.core.storage`:
- `userData` = `$XDG_CONFIG_HOME/Gravaai` (default `~/.config/Gravaai`). Confirm the exact folder
  name against a real install (`ls ~/.config`). Electron uses `productName`. Keep
  `adoptLegacyUserDataDir` behaviour ("Grava AI" → "Gravaai") and `LEGACY_PROFILE_DIR_RENAMES`
  path remapping for projects.
- Files and names stay **byte-compatible**:
  - `recent-projects.json` (max 16)
  - `shortcuts.json`
  - `recordings-settings.json`
  - `countdown-settings.json`
  - `app-settings.json`
  - `automations.json`
  - `agent.sock`
  - `agent-token`
  - `whisper/ggml-small.bin`
  - `recordings/` (`recording-<ts>.*`, `.recording-<ts>.session.json`)
  - `Projects/`
  - `*.preview.png` thumbnails
  - `*.gravaai-session.json`
  - `extensions/`
- JSON read rules: tolerate a UTF-8 BOM (`parseJsonWithByteOrderMark`) and treat invalid or
  missing files as defaults.
- JSON write rules: pretty-print with 2-space indent like `JSON.stringify(x, null, 2)`. Write
  atomically (temp + rename; port `ipc/project/atomicSave.ts`).
- **Unknown fields must survive a round trip.** Settings stores are `Map<String, JsonNode>`
  (like `Record<string, unknown>` today). Typed records sit *on top* of the map. Never drop keys
  you don't know.

### 5.3 Service layer (replaces IPC)
Each IPC channel group becomes one Java service with plain methods and listener registration.
Appendix A maps every channel. Rules:
- Request/response channels (`ipcMain.handle`) become methods returning a value or
  `CompletableFuture` when slow.
- Push channels (`webContents.send`, renderer `on*`) become `Subscription subscribe(Listener)` on
  the service. The UI wraps them with `UiEvents.onFx(listener)`.
- Fire-and-forget channels (`ipcRenderer.send`) become void methods.
- Results that were `{ success, error }` objects become a sealed `Result<T>` type
  (`Ok`/`Err` records) in `core`.
- Delete channels that only exist because of the process split, such as
  `hud-overlay-renderer-ready`, `capture-picker-ready` and `global-shortcut-listener-ready`.
  Note each one as "N/A: process split" in Appendix A of your report.

### 5.4 Windows (JavaFX stages)
Rules for every window:
- Reproduce size, style (frameless/transparent), title and behaviour from `windows.ts`
  (`createXxxWindow`). Read the function first.
- Transparent windows use `StageStyle.TRANSPARENT` + `Scene.setFill(Color.TRANSPARENT)` (proven).
- Status quo, accepted: always-on-top and client positioning do not work on COSMIC. Still call
  `setAlwaysOnTop(true)` (harmless). **Do not** write workarounds. Do not port the Windows/mac
  capture-protection logic.
- **Click-through** (area recording border; anywhere `setIgnoreMouseEvents(true)` is used
  permanently): add `X11Bridge.setInputShapeEmpty(window)`. It calls `XShapeCombineRectangles`
  from `libXext.so.6` with `ShapeInput` and zero rectangles. Size the HUD and HUD menu stages
  tightly to their content, so the transient `setIgnoreMouseEvents` toggling in `windows.ts`
  (L342–351, L557–591) is not needed. Record that decision.
- The HUD drag handle uses `X11Bridge.beginMove` (`_NET_WM_MOVERESIZE`), because COSMIC reverts
  client moves. This is already written in the spike, but it is **not yet verified by a human
  drag**: verify it in P1.
- Window-to-screen mapping: use `javafx.stage.Screen`. Positions are in X11 logical
  coordinates; the spike printed correct bounds for both monitors.
- Capture picker on multiple monitors: create one transparent undecorated stage per `Screen`,
  sized to its bounds, then call `setFullScreen(true)`. **Verify** that each lands on its own
  monitor under COSMIC. If they don't, see decision rule R3 (§9).

### 5.5 Platform services (`gravaai-platform`)
| Need | Today | Java |
|---|---|---|
| Run processes | `child_process` everywhere | `ProcessRunner`: `ProcessBuilder`, a drain thread for stderr (never leave a pipe undrained), optional line callbacks for stdout, timeouts, kill-tree on cancel (`ProcessHandle.descendants()`). |
| Native helper paths | `ipc/paths/binaries.ts`, `getPrebundledNativeHelperPath` | `NativeHelpers.path("gravaai-wayland-capture")` → `<appdir>/lib/native/linux-x64/<name>`; in development, `java/native/build/<name>`. |
| Tray | Electron `Tray` + `Menu` in `main.ts` | StatusNotifierItem over D-Bus (dbus-java): export `/StatusNotifierItem` with properties `Category`, `Id`, `Title`, `Status`, `IconPixmap` (ARGB32, network byte order; build it from `icons/icons/png/32x32.png`, with a recording variant like today's `recordingTrayIcon`) and `Menu` → `/MenuBar` implementing `com.canonical.dbusmenu` (`GetLayout`, `GetGroupProperties`, `Event`, `AboutToShow`, `LayoutUpdated` signal). Register with `org.kde.StatusNotifierWatcher.RegisterStatusNotifierItem`. Same menu items and order as `main.ts`. (The deleted QML attempt got an SNI tray showing on COSMIC, so the approach works.) |
| Notifications | Electron `Notification` | `org.freedesktop.Notifications.Notify` over D-Bus. |
| File dialogs | `dialog.showOpenDialog` / `showSaveDialog` | XDG portal `org.freedesktop.portal.FileChooser` (`OpenFile`/`SaveFile`; wait for the `Response` signal on the returned request path). Fallback: JavaFX `FileChooser`/`DirectoryChooser`. Keep the same filters and default paths as the Electron call sites. |
| Message boxes | `dialog.showMessageBox` (3 uses) | In-app dialog from the UI kit (§5.12). |
| Open URL / file | `shell.openExternal`, `shell.openPath` | Portal `OpenURI` (`OpenURI`/`OpenFile`), fallback `xdg-open`. |
| Reveal in folder | `shell.showItemInFolder` | `org.freedesktop.FileManager1.ShowItems`, fallback `xdg-open <parent>`. |
| Trash | `shell.trashItem` | `gio trash <path>`. If `gio` is missing, use a freedesktop Trash-spec implementation for the home filesystem. |
| Displays | `screen.*`, `ipc/monitorResolver.ts` | JavaFX `Screen` for geometry; `gravaai-wayland-capture --list` for output names (`OUTPUT name x y w h` lines). Match them by geometry exactly like `monitorResolver.ts`. |
| X11 extras | — | `X11Bridge` (from the spike, FFM on GTK's `Display*` via `gdk_x11_get_default_xdisplay`): `findWindow`, `keepAbove`, `beginMove`, `setInputShapeEmpty`. Call only on the FX thread. |

### 5.6 Recording (screen + audio + webcam + telemetry)
**Today:** `electron/ipc/recording/cosmic.ts` (843), `cosmicSession.ts` (309),
`micNoiseReduction.ts`, `audioFilters.ts`, `library.ts`, `storagePath.ts`, `thumbnail.ts`,
`importRecording.ts`, `diagnostics.ts`, `events.ts`; `register/recording.ts`; cursor
`ipc/cursor/*`; keyboard `ipc/keyboard/*`; renderer `src/hooks/useScreenRecorder.ts` (webcam
MediaRecorder parts), `launch/hooks/*`.

**Java:** `dev.gravaai.services.recording.CosmicRecorder`. This is a **line-by-line port of
`cosmic.ts`**. Keep the design, because it is proven and crash-safe:
1. **Video:** `gravaai-wayland-capture <output-name> 60` writes raw frames to stdout and
   `FORMAT w h rgba|bgra stride` to stderr. Pipe stdout into an **ffmpeg CLI** child with exactly
   today's args:
   `-f rawvideo -pix_fmt <fmt> -s WxH -r 60 -i pipe:0 -vf <filters> -c:v libx264 -preset ultrafast -crf 18 -g 120 -cluster_time_limit 2000 recording-<ts>.mkv`.
   Copy stdout → stdin in Java with a 4 MB direct buffer on a platform thread, or (better)
   connect the two processes with an OS pipe. Use `ProcessBuilder.startPipeline` when stderr of
   the first process is still captured separately. Verify `startPipeline` keeps stderr separate;
   otherwise copy in Java.
2. **Audio:** one `parec --raw --format=s16le --channels=2 --rate=48000 --latency-msec=20 -d <source>`
   per role (mic, system) writing raw PCM files, exactly as today. Same `pactl -f json list sources`
   discovery and `@DEFAULT_MONITOR@` handling. Record each track's `startedAtMs` (wall clock of the first
   data, 192 bytes/ms).
3. **Manifest:** write `.recording-<ts>.session.json` (`CosmicSessionManifest` v1, same fields)
   while recording, so crash recovery works. Remove it after a successful mux. Port
   `listRecoverableRecordings`, `recover`, `discard`.
4. **Pause/resume:** same `pauseState` semantics as `cosmic.ts`. Read how it trims, and port it
   exactly.
5. **Mux:** ffmpeg CLI with exactly today's args: `adelay` per track offset, `amix=…:normalize=0`,
   mic noise filters (`arnndn` with `electron/native/models/*.rnnn` → bundle under
   `lib/native/models/`), `-c:v copy -c:a aac -b:a 192k -movflags +faststart -shortest`, and the
   tolerant flags when recovering. Meeting (audio-only) recordings produce `.m4a`.
6. **Webcam:** in-process capture with **libavdevice v4l2** (`dev.gravaai.media.capture.WebcamCapture`).
   The same frames feed (a) the HUD/floating webcam preview through a `PixelBuffer` and (b)
   an in-process libx264 encoder writing `recording-<ts>-webcam.mkv`, aligned to the recording's
   `startedAtMs`. Check today's webcam file name/extension (`recording-<ts>-webcam.*`) in
   `useScreenRecorder.ts`/`sequenceWebcam.ts`. Keep the base name; the extension may change to
   `.mkv` only if every reader (editor, automations' "webcam track" lookup in
   `automations/triggers.ts`) is updated to accept it. Camera list:
   `/sys/class/video4linux/video*/name` + `index == 0` (skip metadata nodes). Device format
   choice: prefer MJPEG at the requested size/fps, falling back to YUYV.
7. **Cursor + keys telemetry:** `gravaai-wayland-cursor [--keys|--keys-only]` prints
   `POS:x:y`, `INTERACTION:mousedown:<button>`, `KEY:down|up:<code>`. Port `ipc/cursor/*` and
   `ipc/keyboard/*` (sampling at 33 ms, `CURSOR_TELEMETRY_VERSION = 2`, max samples, the
   telemetry JSON files next to the recording, and evdev key names from `evdevKeys.ts`).
8. **Events:** `RecordingService` publishes the same state machine as today
   (`recording-state-changed`, `recording-session-changed`, `recording-interrupted`,
   `recording-stop-requested`, countdown ticks). Map them through Appendix A.
9. **Mic level meter (HUD menu):** a libpulse-simple *record* stream (FFM) at 48 kHz mono
   float, opened only while the meter is visible. Compute RMS/peak exactly like
   `src/hooks/useAudioLevelMeter.ts` (same smoothing and scale).

### 5.7 Media layer — FFmpeg in-process (`gravaai-media`)
**Build FFmpeg** (`java/native/build-ffmpeg.sh`, ported from `scripts/build-minimal-ffmpeg.mjs`):
- FFmpeg 8.0.x with `--enable-shared --disable-static` for libs **and** the `ffmpeg`/`ffprobe`
  CLIs from the same build.
- Keep today's component selection (`--disable-everything` plus the explicit enables) and add
  what in-process editing needs:
  - demuxers: mov, matroska, webm, wav, mp3, ogg, image2
  - decoders: h264, hevc, vp8, vp9, av1 (libdav1d if already used, else the native one), aac,
    opus, mp3, pcm_*, png, mjpeg
  - encoders: libx264, aac, png, gif
  - muxers: mp4, matroska, gif, wav, ipod
  - filters: everything in `electron/ipc/ffmpeg/filters.ts` and the recording/mux filters, plus
    palettegen, paletteuse, atempo, aresample, afade, volume, adelay, amix, arnndn, scale, fps
  - indevs: v4l2
  - libs: swscale, swresample, avfilter, avdevice
  - external: libx264, libopus, libmp3lame
- **No `--enable-vaapi`, `--enable-vdpau` or `--enable-vulkan`** (rule 1). Use
  `--disable-autodetect` as today.
- Install into `lib/native/linux-x64/ffmpeg/{bin,lib}` in the app image, with RPATH `$ORIGIN/../lib`.

**Bindings and wrapper API** (`dev.gravaai.media.ffmpeg`), all `AutoCloseable`, each with an `Arena`:
- `FfmpegLibs`: loads the bundled `.so`s with `SymbolLookup.libraryLookup(path, Arena.global())`
  in dependency order (avutil, swresample, swscale, avcodec, avformat, avfilter, avdevice).
  Logs versions and redirects the av_log callback to `System.Logger` (upcall).
- `MediaProbe.probe(Path)` → record of duration, streams, fps, size, rotation (display matrix),
  colour info, audio channels/rate. Replaces `probe-native-video-metadata`, `ffmpeg/metadata.ts`
  and the `mp4box`/`web-demuxer` use.
- `VideoDecoder`: open file, `seekPrecise(timeUs)` (seek to the previous keyframe with
  `AVSEEK_FLAG_BACKWARD`, then decode forward to the target), `next()` → `DecodedFrame`
  (pts µs + planes). `thread_count = 0` (auto), `thread_type = FF_THREAD_FRAME`.
- `FrameConverter` (swscale) → RGBA into a reusable direct buffer at a requested size.
  `SWS_BILINEAR` for preview, `SWS_BICUBIC` for export. Set colourspace details
  (`sws_setColorspaceDetails`) from the stream: BT.709 vs BT.601 and limited vs full range. Port the
  decisions in `src/lib/exporter/videoColorSpace.ts`.
- `AudioDecoder` → float32 interleaved 48 kHz stereo through swresample.
- `FilterGraph`: wraps `avfilter_graph_parse_ptr` for audio graphs given as strings (§5.10).
- `VideoEncoder` (libx264) + `Muxer` (mp4 with `movflags=+faststart`, matroska).
- `AudioEncoder` (aac).
- Error handling: every negative return becomes `FfmpegException(code, av_strerror text)`.
  `AVERROR(e)` is `-e` and `AVERROR_EOF` is `FFERRTAG('E','O','F',' ')`. Compute these as Java
  constants, because they are macros jextract cannot see.

**Pitfalls:** `AVPacket`/`AVFrame` must be freed with `av_packet_free`/`av_frame_free` through
a pointer-to-pointer `MemorySegment`. Never keep a struct `MemorySegment` past its owner's
lifetime. Write one `try-with-resources` per decode loop. Unit-test each wrapper against fixture
media in `java/parity/fixtures/` (short clips you generate with our own ffmpeg CLI: 2 s of
1080p60 h264, 2 s of 720p30 vp9 webm, 48 kHz stereo aac, a rotated phone clip).

### 5.8 Compositor (the heart of preview and export)
**Today:** `src/lib/exporter/modernFrameRenderer.ts` (3644), `frameRenderer.ts` (2112),
`annotationRenderer.ts`, `captionRenderer.ts`, `keystrokeRenderer.ts`, `shadowProfile.ts`,
`nativeStaticLayoutGeometry.ts` (geometry helpers), and the preview path
`src/components/video-editor/VideoPlayback.tsx` (3101) + `videoPlayback/*` (zoom, cursor,
camera follow, motion, springs, sway, webcam sync, scene effects). Pixi.js + pixi-filters
(`motion-blur`, `zoom-blur`) + Canvas 2D.

**Java:** a single compositor used by **both** preview and export, so they can never disagree:
`dev.gravaai.media.compose.Compositor`.
- Context: an EGL surfaceless + GL 3.3 core context on its own thread (spike `GlPreviewRenderer`).
  On top of it, a Skija `DirectContext.makeGL()`. Render target: an FBO-backed
  `BackendRenderTarget.makeGL(w, h, 0, 8, fbo, FramebufferFormat.GR_GL_RGBA8)`, wrapped by
  `Surface.makeFromBackendRenderTarget(ctx, rt, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())`.
  **Verify the orientation with an asymmetric test image in a unit test.** Rows read back with
  `glReadPixels` must come out top-first (the spike's convention).
- After any raw GL call (PBO readback), call `directContext.resetGLAll()` before Skia draws again.
- Input per frame is a `FrameInput` record: the source video frame (RGBA image), the webcam frame,
  the cursor sample(s), time, and the resolved `ProjectEditorState`.
- Render order and math are **ported 1:1 from `modernFrameRenderer.renderFrame/renderOutput`**:
  1. Background: wallpaper image (cover fit) or colour/gradient, plus background blur
     (`ImageFilter.makeBlur`).
  2. Layout: aspect ratio, padding, crop (`CropRegion`), from `layoutUtils.ts`,
     `nativeStaticLayoutGeometry.ts` and `exportDimensions.ts`.
  3. Zoom/camera transform: `zoomTransform.ts`, `zoomRegionUtils.ts`, `cursorFollowCamera.ts`,
     springs in `motionSmoothing.ts`/`mathUtils.ts`, easing presets (`ZoomTransitionEasing`),
     connected zooms, classic mode.
  4. Video layer: the source frame drawn into the squircle mask (`drawSquircle*`) with rounded
     corners, plus a drop shadow (`shadowProfile.ts`).
  5. Motion blur while zooming: port the pixi `zoom-blur` and `motion-blur` fragment shaders to
     **SkSL `RuntimeEffect`**, using the same uniforms and the same `ZoomMotionBlurTuning` mapping.
  6. Cursor: `cursorRenderer.ts` styles (asset sets in `src/assets/cursors/{macos,tahoe,windows11,golden-gate,custom}`
     loaded with Skija `SVGDOM`/images), size, smoothing, sway, click bounce, click effects
     (spotlight/ripple/echo), cursor motion blur, loop mode, and the `cursorScale.ts` rules.
  7. Webcam overlay: layout presets, corner, size, roundness, shadow, mirror, zoom-reactive
     scale, crop (`webcamOverlay.ts`, `WebcamCropControl.tsx`, `ClipCameraLayout`).
  8. Annotations: text/image/figure (arrows from `ArrowSvgs.tsx`)/blur regions
     (`annotationRenderer.ts`, `AnnotationOverlay.tsx`).
  9. Captions: `captionRenderer.ts`, `captionLayout.ts`, `captionStyle.ts`, animations
     none/fade/rise/pop.
  10. Keystroke overlay: `keystrokeRenderer.ts`, `keystrokeOverlay.ts`.
  11. Clip transitions: fade-through-black (`clipTransitions.ts`).
  12. Extension render hooks (Phase 3, §5.18), at the "inside/outside scene transform" points.
- Text: Skija `Font`/`TextLine`/`Paragraph` with the same font families. Bundle DM Sans and load
  user custom fonts (`src/lib/customFonts.ts`). Canvas 2D `measureText` semantics, so port line
  breaking from `captionLayout.ts` exactly.
- Video frame into Skia: swscale to RGBA, then `Image.makeRaster(...)`, drawn on the GPU
  surface (Skia uploads it). Start with this. Use the YUV-in-shader path only under decision
  rule R2.
- Output: for preview, an async PBO readback into two `PixelBuffer`s (the spike pattern). For
  export, readback into the encoder's input (swscale RGBA → YUV420P).
- **Golden-frame tests** (§6.2) are the acceptance criterion for this whole subsection.

### 5.9 Preview playback engine
**Today:** `VideoPlayback.tsx`, `videoPlayback/clipPlayback.ts`, `playbackRate.ts`,
`previewVideoSource.ts`, `usePreviewVideoReady.ts`, `webcamSync.ts`, `audio/*`,
`hooks/useEditorPlaybackControls.ts`.

**Java:** `dev.gravaai.media.playback.PlaybackEngine`:
- Clocks: the **audio clock is master**. Audio out is libpulse-simple through FFM:
  `pa_simple_new(NULL, "Gravaai", PA_STREAM_PLAYBACK, NULL, "Editor preview", {FLOAT32LE, 48000, 2}, NULL, &attr{tlength≈40 ms}, &err)`,
  then `pa_simple_write`, `pa_simple_get_latency`, `pa_simple_flush` on seek or pause. Clock =
  samples written − latency. With no audio track, use a monotonic clock.
- Timeline mapping: project time ↔ source time across clips, trims and speed regions. Port
  `clipSequence.ts`, `clipTimeline.ts`, `videoTimelineSegments.ts` and `mediaTiming.ts`.
- Speed regions: audio goes through an `atempo` filter graph per region (chain atempo for
  <0.5 or >2). Check whether today's preview preserves pitch (`preservesPitch`); match it.
- Video: a decode-ahead queue (3–6 frames) per source on a decode thread. The compositor thread
  picks the frame whose pts ≤ clock and renders at display rate (vsync isn't available
  offscreen; target 60 Hz).
- Seek/scrub: cancel queues, `seekPrecise`, render one frame. Coalesce scrub requests (latest
  wins).
- Webcam track sync: port `webcamSync.ts` offsets.
- Loop, playback rate and keyboard shortcuts as today.

### 5.10 Export
**Today:** `modernVideoExporter.ts` (3838), `videoExporter.ts`, `gifExporter.ts`,
`audioTimelineProcessor.ts`, `offlineAudioProcessor.ts`, `audioRoutingEngine.ts`,
`clipTransitionAudio.ts`, `sourceTrackRoutingPolicy.ts`, `sourceAudioFallback.ts`,
`exportBitrate.ts`, `exportTuning.ts`, `exportSavePolicy.ts`, `finalizationTimeout.ts`,
`muxer.ts`, `types.ts`; UI in `video-editor/export/*`, `ExportSettingsMenu.tsx`,
`exportDimensions.ts`, `mp4ExportSettings.ts`, `GifOptionsPanel.tsx`, `FormatSelector.tsx`;
main-side `register/export.ts`, `exportCaptionSidecars.ts`, `ipc/export/*`.

**Java:** `dev.gravaai.media.export.ExportJob`, running on the `gl-export` thread with its own
compositor instance:
- MP4: for each output frame time, resolve the source time, decode, composite at the output
  size, read back, swscale to YUV420P, then libx264. Take bitrate, preset and tune from
  `exportBitrate.ts`/`exportTuning.ts` (port the numbers). Use frame rate and size presets from
  the UI. Mux to MP4 with `+faststart` into a temp file, then finalize/rename (port
  `exportSavePolicy.ts` and the `finalize-exported-video`/`discard-exported-temp` semantics).
- Audio: build **one libavfilter graph string** that reproduces the WebAudio routing: per-source
  trims, clip gaps, gain, fades, fade-black transitions, speed (`atempo`), extra audio regions
  (`AudioRegion`), source track settings (`SourceAudioTrackSettings`), and mixing
  (`amix normalize=0`). Encode aac 192k (check the bitrate today). Port the routing decisions
  from `audioRoutingEngine.ts`/`sourceTrackRoutingPolicy.ts` with their tests.
- GIF: composite frames at the GIF fps/size presets (`gifExporter.ts`), then `palettegen`
  (stats over the clip) and `paletteuse` (dither as today) in two passes.
- Progress and cancel: same progress model as `exportStatusModel.ts`/`exportProgressState.ts`.
  Cancel deletes temp files.
- Caption sidecars (`.srt`/`.vtt` next to the export): port `exportCaptionSidecars.ts`.
- After success, fire the automations "export finished" trigger (deduped as in
  `automations/triggers.ts`).

### 5.11 Editor model, persistence, undo
**Today:** `video-editor/types.ts` (680), `projectPersistence.ts` (1116, `PROJECT_VERSION = 2`,
normalisers), `editorHistory.ts`, `projectDirtyState.ts`, `editorPreferences.ts`,
`presets/*`, `state/*`, `project/*`, `hooks/*Commands.ts`, `clip*.ts`, `caption*.ts`,
`transcriptCut.ts`, `zoomSuggestionUtils.ts`, `useFreshRecordingAutoZoom.ts`;
main side `ipc/project/*` (manager, atomicSave, recentMetadata, thumbnails, trash, rename,
legacyProfilePaths, createUntitledProject).

**Java (`gravaai-core`):**
- Port every type as an **immutable `record`**, with `sealed interface` for unions
  (`AnnotationType`, etc.). Enums carry their JSON string: `@JsonValue` on a `wire()` method.
- `ProjectEditorState` and `EditorProjectData` mirror the TS fields one to one, with **the same
  JSON names**.
- Port `normalize*` functions exactly, including clamps, defaults and legacy conversions
  (`legacyBorderRadiusPixelsToPercent`, etc.). Port their tests first, then make them pass.
- Unknown JSON fields in a project are preserved: keep the raw `ObjectNode` and merge on save.
- `EditorStore`: holds the current `ProjectEditorState`, applies `Command`s (one class per
  `use*Commands` operation), and keeps undo/redo exactly like `editorHistory.ts`
  (same coalescing rules). It exposes JavaFX-free listeners; the UI adapts them.
- Dirty state, save, save-as, autosave/recovery (whatever exists today), recent projects,
  thumbnails (`*.preview.png` from the compositor at the poster time), and "save before close"
  flow.

### 5.12 UI (JavaFX)
**Design system first (P1):** `dev.gravaai.ui.kit`.
- Tokens: port every CSS variable and colour from `src/index.css`, `src/App.css`,
  `launch/launchTheme.css`, `LaunchWindow.module.css`, `SourceSelector.css`, `MarqueeText.css`
  and the HeroUI theme in use (`@heroui/styles`) into `gravaai.css` looked-up colours
  (`-g-bg`, `-g-surface`, …), as the spike did. Spacing and radii come from the Tailwind classes
  used. Write a table mapping Tailwind class → px in `ui/kit/README.md` and use it consistently.
- Fonts: DM Sans from `src/assets/fonts/dm-sans/`. JavaFX does not apply variable-font axes, so
  generate **static instances** (400, 500, 600, 700, plus italics) once with
  `fonttools varLib.instancer` and commit the TTFs. Load them with `Font.loadFont` at startup,
  before any scene.
- Icons: the UI uses `@phosphor-icons/react`, `@solar-icons/react` and `react-icons`. Write
  `java/tools/extract-icons.mjs` (Node, one-time, runs against the existing `node_modules`):
  find every imported icon (grep imports in `src/`), render each with
  `react-dom/server.renderToStaticMarkup`, and write `gravaai-ui/src/main/resources/icons/<lib>/<Name>.svg`.
  At runtime, `Icon.of("phosphor/Microphone", size, weight)` parses the SVG (paths, fill/stroke,
  opacity) into a `Group` of `SVGPath`s. Never hand-draw icons.
- Components (each as a reusable control with CSS classes, built to look exactly like the
  HeroUI/shadcn component it replaces; source in `src/components/ui/*`):
  Button (all variants), Toggle, ToggleGroup, Switch, Slider (`SliderControl.tsx` behaviour), Select,
  DropdownMenu, Popover, Tooltip (`HudTooltip.tsx` too), Dialog (in-window modal overlay with
  backdrop — **not** a separate OS window), Tabs, Accordion, Card, Input, Label, Separator,
  Skeleton (shimmer animation), Toast (in-window stack), ChoiceGroup, ColorPicker (`color-picker.tsx`),
  AudioLevelMeter, ContentClamp, MarqueeText.
- Animations: `motion/react` and CSS transitions become JavaFX `Transition`s/`Timeline`s with
  the **same durations and easing curves** (read them from the source; cubic-bezier →
  `Interpolator.SPLINE`).
- **Kit showcase window** (`gravaai --ui-kit`, dev only): every component in every state, next
  to a screenshot of the React original. This is the P1 visual gate.

**Screens:** port each React screen to a JavaFX view class with the same structure. Keep one
Java view per TSX component where practical, so the reviewer can diff them side by side.
- Dashboard (`dashboard/*`): sidebar, toolbar, filters, grid, project cards with hover preview,
  folders (colours), raw recordings, recoverable recordings banner, settings pages (general,
  global shortcuts, automations, agent), announcements, skeletons.
- Editor (`layout/*`, `VideoEditor.tsx`, `SettingsPanel.tsx` + `SettingsSections.tsx` +
  `SettingsRow.tsx`, `PlaybackControls.tsx`, `CropControl.tsx`, `WallpaperGrid.tsx`,
  `AnnotationSettingsPanel.tsx`, `ExportSettingsMenu.tsx`, `ProjectBrowserDialog.tsx`,
  `KeyboardShortcutsHelp.tsx`, `ShortcutsConfigDialog.tsx`, `TutorialHelp.tsx`,
  `AddCustomFontDialog.tsx`, `CaptionListPanel.tsx`, `TranscriptPanel.tsx`,
  `ExtensionManager.tsx`, `EditorAnnouncementBanner.tsx`).
- Timeline (`timeline/*`, `dnd-timeline` semantics): a custom `TimelineView extends Region` that
  draws rows on a `Canvas` (clips, zoom regions, speed regions, trims, annotations, audio
  regions + waveforms from `audio/waveform`, captions, agent highlights,
  `AgentTimelineHighlights.tsx`), plus playhead, ruler, scrolling and zoom. Interactions: select,
  move, resize edges, gap snapping, split, delete. Port the **behaviours in
  `tests/ui/timeline-*.spec.ts`, `clip-*.spec.ts`, `block-deletion.spec.ts`** as TestFX tests.
- Launch/HUD (`launch/*`), source selector, capture picker, countdown, meeting views
  (`meeting/*`), permissions page (`RecordingPermissionsSettings.tsx`: keep only the Linux parts).
- Markdown in meeting notes: commonmark-java AST → JavaFX `TextFlow`/`VBox` renderer that copies
  `MeetingMarkdown.tsx` styles.
- Emoji picker (`emoji-picker-react` in annotations): the emoji list comes from the package's
  data JSON. Render with the Noto Color Emoji font. If JavaFX can't show colour emoji on this
  system, rasterise them with Skija into `Image`s (decision rule R5).
- **i18n:** copy `src/i18n/locales/**` into `gravaai-ui/src/main/resources/i18n/` unchanged
  (the same files are the source of truth until Electron is deleted). `I18n.t("namespace:key.path", Map)`
  with `{{var}}` interpolation and i18next plural suffixes (`_one`/`_other`, if present). The
  language comes from the same setting as today (`I18nContext.tsx`). Keep `scripts/i18n-check.mjs`
  semantics as a JUnit test (every key present in every locale).

### 5.13 Captions (whisper)
**Today:** `electron/ipc/captions/*` (generate, segment, silence, parser, output, mergeSources,
audioCandidates, whisper, runtimeErrors), `register/captions.ts`, editor `captions/useAutoCaptionController.ts`,
`autoCaptionSource.ts`.

**Java:** `dev.gravaai.services.captions`. Port it 1:1. Audio extraction to 16 kHz mono WAV uses
the bundled ffmpeg CLI with today's filter args (`ipc/ffmpeg/filters.ts`). Run whisper with
exactly today's args: `-m <model> -f <wav> -osrt -of <base> -l <lang> -np -mc 0`, plus
`-ojf` for word timings. Port silence detection, segmentation and parsing with their tests.
Model download (`ggml-small.bin`) goes through `HttpClient` with progress and resume, the same
URL and the same location. Keep the bundled whisper runtime (`scripts/build-whisper-runtime.mjs`
→ `java/native/build-whisper.sh`).

### 5.14 Meetings
**Today:** `electron/ipc/meeting/*` (pipeline, jobs, library, assets, settings, transcript,
summarize, prompts, openaiClient, apiTranscription, llamaServer, context),
`register/meeting.ts`, UI `src/components/meeting/*`, `dashboard/useMeetingLibrary.ts`.

**Java:** `dev.gravaai.services.meeting`. Port it 1:1:
- Same pipeline: transcribe → summarise → title, each stage writing its own file next to the
  recording.
- Same job queue and events (`meeting-job-update`, `meeting-asset-progress`).
- Same `llama-server` lifecycle: started per job and stopped after, never at the same time as
  whisper. Same args (`--host`, `--port`, `--fit`, `--no-webui`, …).
- OpenAI-compatible client on `java.net.http.HttpClient` (streaming SSE parsed by hand if used
  today).
- **Prompts copied verbatim** from `prompts.ts`. Store them as resource text files, and add a test
  that compares them to the TS source until Electron is deleted.

### 5.15 Automations
**Today:** `electron/automations/*` (engine, store, triggers, actions, fileActions, duration),
`register/automations.ts`, UI `dashboard/AutomationsSettings.tsx`, `src/lib/automationTemplate.ts`.

**Java:** `dev.gravaai.services.automations`. Port it 1:1, including the guarantee that it
"never throws". Failures are isolated per action and reported once per rule through a
notification. Triggers stay fire-and-forget, with the same dedupe window. The duration probe uses
`MediaProbe`.

### 5.16 AI control (agent socket + MCP)
**Today:** `electron/agent/*` (controlServer, service, dispatch, jobs, token, toolSchemas,
bridge, capture, transport), `register/agent.ts`, editor side `video-editor/agent/*`
(applyAgentOps, ops, frames, frameCapture, views, jobs, ids, commit, help, settings,
useAgentBridge, AgentStatusChip, AgentTimelineHighlights), and the user skill
`gravaai-video-editing` (it depends on the tool names and schemas).

**Java:**
- `AgentControlServer`: unix socket `<userData>/agent.sock` (`UnixDomainSocketAddress` +
  `ServerSocketChannel`). It listens only while the setting `aiControlEnabled` is on. The first
  line of every connection must be the token from `agent-token`. After that it speaks
  newline-delimited JSON request/response, **the same protocol as `bridge.ts`/`controlServer.ts`**.
- `gravaai --mcp`: an MCP server over stdio using the MCP Java SDK
  (`StdioServerTransportProvider`), with tools **`inspect`, `edit`, `frames`, `project`, `job`**.
  Names, descriptions and JSON schemas must be **byte-identical** to `toolSchemas.ts`; generate
  them from a JSON resource and test them against the TS file. Each call is forwarded to the
  running app over `agent.sock`, with the same reconnect and timeout logic as `bridge.ts`
  (`job wait` up to 300 s).
- Ops (`cut_pauses`, `cut_fillers`, `zoom`, `auto_zoom`, `caption`, `split`, `undo`, … see
  `ops.ts`): port `applyAgentOps.ts` to work directly on `EditorStore` as commands, so undo and
  "revert_ai" work. `frames` renders PNGs with the compositor offscreen and needs no UI.
- Settings → AI control shows the MCP command. It changes from
  `gravaai --mcp --ozone-platform=headless` to `<appimage path> --mcp`. Update the text that
  copies the command.

### 5.17 Global shortcuts
**Today:** `electron/globalShortcuts/*` (cosmicShortcuts RON reader/writer, controlSocket,
dispatch), `gravaai-ctl` C helper, `register/settings.ts`, UI `GlobalShortcutsSettings.tsx`,
`src/lib/globalShortcuts.ts`.

**Java:** port the COSMIC RON splice logic **exactly** (`cosmicShortcuts.ts`): only Gravaai's
own entries are touched, every other byte of the user's file is preserved, and any parse error
aborts without writing. Port the fixtures in `__fixtures__` as tests. The control socket lives
at `$XDG_RUNTIME_DIR/gravaai/control.sock` (fallback `$TMPDIR/gravaai-<uid>/control.sock`), with
one action per line answered `ok`/`error`. This must match `gravaai-ctl`. Keep `gravaai-ctl` as-is
(C) and bundle it. The same socket also gives single-instance forwarding (§5.1).

### 5.18 Extensions (Phase 3)
**Today:** `EXTENSIONS.md` (the API contract), `ExtensionManager.tsx`, the extension host in the
renderer (find it: grep `gravaai-extension.json`), and `userData/extensions/`.

**Java:** `dev.gravaai.services.extensions` on GraalJS (polyglot + js-community + truffle-runtime).
- Same manifest format and permission model.
- Each extension gets its own `Context`, with no host access beyond the API object we inject.
- Render hooks receive a **Canvas 2D-compatible object implemented over Skija** (`Canvas2D`
  class: paths, fill/stroke, transforms, gradients, text, drawImage, globalAlpha, composite ops,
  shadows). Implement exactly the subset `EXTENSIONS.md` documents.
- Port events, settings panels (rendered by our UI kit from the declared schema), cursor
  effects, assets (frames, wallpapers, cursor styles) and lifecycle.
- If the JS engine misbehaves on JDK 27, apply decision rule R6.

### 5.19 Packaging and distribution
**Java:** `gravaai-app` profile `-Pdist`:
1. `jlink` builds the runtime from the JDK + JavaFX 27 jmods: `java.base`, `java.logging`,
   `java.net.http`, `jdk.unsupported`, `javafx.controls`, plus `java.desktop` only if something
   needs it. Use `--strip-debug --no-header-files --no-man-pages`.
2. `jpackage --type app-image --name gravaai` with the main jar, classpath libraries, icon
   (`icons/icons/png/256x256.png`) and launcher options
   `--enable-native-access=ALL-UNNAMED,javafx.graphics -XX:AOTCache=$APPDIR/gravaai.aot`.
3. Copy native files into `<image>/lib/native/linux-x64/`: helpers, ffmpeg bin + libs,
   whisper runtime, rnnoise models, llama-server if bundled today.
4. **AOT training run:** launch the image's `lib/runtime/bin/java` with the launcher's exact
   classpath and options (read `lib/app/gravaai.cfg`), plus `-XX:AOTCacheOutput=<image>/lib/app/gravaai.aot`
   and `-Dgravaai.exitAfterMs=4000`. This needs a display, which is fine on this machine.
5. Wrap as an AppImage (`appimagetool`, pinned version, downloaded into the scratch toolchain),
   with a `.desktop` file and icons from `icons/`. Name it `Gravaai-linux-x64.AppImage` like
   today.
6. Run the startup-budget check (§5.1) against the AppImage. The phase report must include the
   numbers.

---

## 6. Parity and test harness

### 6.1 Unit tests
- Port every `*.test.ts` that covers logic we keep into JUnit 6 + jqwik (fast-check →
  jqwik `@Property`). Keep test names readable, as `@DisplayName` with the original `it(...)`
  string.
- Test-first for pure logic: port the test, see it fail, port the code, see it pass.
- A test file that only covers dropped platform code (§2.3) gets listed in the report as N/A.

### 6.2 Golden frames (compositor parity)
- `java/parity/fixtures/projects/`: 8–12 fixture projects covering every compositor feature
  (wallpaper/blur, padding/crop/aspect, zoom with motion blur, every cursor style and click
  effect, webcam layouts, each annotation type, captions with each animation, keystrokes,
  fade transition). Use short generated source media.
- Reference frames come from the **current Electron app**: open each fixture and capture frames
  at listed times with the existing MCP `frames` tool (the app has it today) or the editor's
  frame capture. Store them as `parity/golden/<project>/<ms>.png`. **Generate them in P2 before
  porting the compositor.**
- The Java test renders the same project/time offscreen (EGL surfaceless works headless) and
  compares with per-channel tolerance. Start at mean abs diff ≤ 2.0 and max-diff pixels ≤ 0.5 %.
  Write a diff PNG on failure. Thresholds may be tuned only with reviewer approval.

### 6.3 UI screenshot parity
- Reference screenshots come from the React UI running in the browser harness
  (`npm run dev:ui`, `tests/ui/bridge.ts` stubs `electronAPI`). Write one Playwright script that
  visits every screen/state listed in §7 and saves `parity/ui/<screen>.png` at fixed window sizes.
- Java side: TestFX + `Node.snapshot` at the same sizes into `parity/ui-java/<screen>.png`.
- Pixel equality is not expected because font rasterisation differs. The reviewer compares the
  two side by side. The phase report must contain a side-by-side gallery
  (`docs/java-rewrite/phase-N-ui.html`, a simple static page with both images per row).

### 6.4 Manual end-to-end checklist (run on the AppImage, on COSMIC)
Each phase report includes this checklist, with ✅/❌ and notes, for that phase's features.
Take screenshots with `cosmic-screenshot --interactive=false --notify=false -s <dir>`.

---

## 7. Phases

Three phases. Each ends with a working, packaged app that someone can use for that phase's
scope. The Electron app stays in the repo and keeps working until P3.6. Both apps share the
same data directory. **Never run both at the same time** (socket and shortcut conflicts).

### 7.1 Phase 1 — Foundation, recording and meetings (no editor yet)
Goal: a Java Gravaai that starts fast and can do everything except edit and export. That covers
tray, HUD, source picking, countdown, recording with audio, webcam, cursor and keys, crash
recovery, the dashboard/library, meetings (record → transcript → notes), settings, global
shortcuts and automations. "Open in editor" shows the editor window with a placeholder message
"Editor arrives in Phase 2", so the Electron app is still used for editing in the meantime.

Ordered tasks:
- **P1.1 Build skeleton.** Create `java/` with the modules from §4.2, Maven Wrapper, the parent
  POM with pinned versions (§4.3), build dir on scratch, Surefire caps (§1.1), and the `-Pdist`
  packaging from §5.19. Start with an empty window, but the AOT step must work.
  *Done when* `./mvnw -T1 -Pdist package` produces an AppImage that shows a window, and the
  startup check runs.
- **P1.2 Core storage and settings.** §5.2: paths, legacy migration, every settings store,
  atomic JSON, BOM tolerance, unknown-key preservation, logging (§5.1), i18n loader + key-parity
  test, `Result` type, `UiEvents`. Port the related tests (`storagePath`, `recordingPreferencesStore`,
  `legacyProfilePaths`, `atomicSave`, …).
- **P1.3 UI kit.** §5.12: tokens, fonts, icon extraction, every component, showcase window.
  **Visual gate inside the phase:** produce the showcase gallery and stop for a reviewer look
  before building screens on top of it.
- **P1.4 Platform services.** §5.5: ProcessRunner, NativeHelpers, D-Bus connection, tray (SNI
  + dbusmenu), notifications, portal file chooser/OpenURI, FileManager1, trash, X11Bridge (from
  the spike) + `setInputShapeEmpty`.
- **P1.5 Native builds.** Move `electron/native/{wayland-capture,wayland-cursor,gravaai-ctl,models}`
  to `java/native/` by **copying**; Electron keeps its copy until P3.6. Write bash build scripts
  for them, for FFmpeg 8.0.x shared + CLI (§5.7) and for the whisper runtime. Generate the
  jextract FFmpeg bindings and write the wrapper basics (`FfmpegLibs`, `MediaProbe`,
  `VideoDecoder`, `FrameConverter`, PNG thumbnail through Skija) with fixture tests.
- **P1.6 Sources, picker, countdown, area border.** Port `register/sources.ts`,
  `capturePickerSources.ts`, `captureSelection.ts`, `sourceArea.ts`, `monitorResolver.ts`,
  `sourceListPicker.ts`, `SourceSelector.tsx`, `CapturePicker.tsx`, `CountdownOverlay.tsx`,
  `countdownController.ts`, and the area border (click-through via `setInputShapeEmpty`).
- **P1.7 HUD + HUD menu.** `HudWindow.tsx`, `LaunchWindow.tsx`, `RecordingControls.tsx`,
  `HudMenuWindow.tsx`, `launch/popovers/*`, `launch/hooks/*`, `HudTooltip.tsx`, `MarqueeText`,
  `floatingWebcamPreview.ts`. Include the mic level meter (§5.6.9), webcam preview (§5.6.6) and
  device lists. Verify the HUD drag on COSMIC by a human drag, which the reviewer will repeat.
- **P1.8 Recording service.** §5.6 steps 1–5, 7, 8: the full `cosmic.ts` port, crash recovery,
  telemetry, recording events, tray recording state, stop from tray, `recording-interrupted`
  handling, and recordings library listing/thumbnails/import (`library.ts`, `thumbnail.ts`,
  `importRecording.ts`).
- **P1.9 Webcam recording.** §5.6.6, in-process v4l2 → preview + libx264 MKV, aligned.
- **P1.10 Dashboard.** All of `dashboard/*` except the editor-only settings: projects grid
  (reads existing `.gravaai` files with the P2 model's *read-only* subset needed for cards:
  name, thumbnail, dates), folders, raw recordings, recoverable recordings banner, rename, trash,
  open folders, hover preview (play the recording muted through `VideoDecoder` + `FrameConverter`
  into an `ImageView`), skeletons, sidebar cards, announcements (`src/content/announcements`,
  `lib/announcement*`).
- **P1.11 Meetings.** §5.14 end to end, including UI (`MeetingView*`, `MeetingNotes`,
  `MeetingMarkdown`, `MeetingSettingsPanel`, `MeetingThumbnail`) and asset downloads.
- **P1.12 Global shortcuts.** §5.17, including the settings UI and the single-instance
  forwarding.
- **P1.13 Automations.** §5.15 + settings UI. Export triggers are stubbed until P2 and listed in
  the report.
- **P1.14 Package and measure.** AppImage, startup budget, manual checklist, report.

**P1 acceptance gate:**
- Record screen + mic + system audio + webcam on COSMIC; the produced files are byte-format
  compatible and open in the **Electron editor** correctly (cursor, keys and webcam aligned).
- Kill the app mid-recording (`kill -9`); on restart the recording is recoverable.
- Meetings: record → transcript → notes with a local model and with an API endpoint.
- Global shortcuts start and stop recording through COSMIC.
- Automations fire on recording/meeting events.
- Tray works.
- Dashboard lists the existing user projects and recordings.
- Startup budget met.
- UI parity gallery reviewed.
- All ported unit tests green.

### 7.2 Phase 2 — Editor and export
Goal: the full editor (everything that exists today except caption *generation*, transcript
panel/cut, AI control and extensions) plus MP4/GIF export, all in Java. After P2 the Electron
app is no longer needed for daily use.

Ordered tasks:
- **P2.1 Golden references first.** Build the fixture projects and capture the golden frames and
  UI reference screenshots from the Electron app (§6.2, §6.3). Commit them.
- **P2.2 Project model.** §5.11: all types, normalisers, persistence v2 with unknown-field
  preservation, legacy paths, recent projects, dirty state, undo/redo store, commands. Port all
  related tests. **Round-trip test:** load every `.gravaai` in the user's `Projects/` (read-only
  copies) → save to temp → compare JSON trees semantically. They must be equal apart from key
  order.
- **P2.3 Media playback layer.** §5.7 remainder (AudioDecoder, FilterGraph, encoder, muxer) +
  §5.9 (audio out via libpulse-simple, clocks, decode-ahead, seek, speed, webcam sync).
- **P2.4 Compositor.** §5.8 stage by stage, in the order listed. Each stage lands with its golden
  tests green before the next one starts.
- **P2.5 Preview panel.** The compositor drives `EditorPreviewPanel` through PixelBuffer;
  playback controls; scrubbing; full-screen preview if it exists today.
- **P2.6 Editor shell + settings panel.** `layout/*`, `SettingsPanel.tsx` and all sections,
  crop dialog, webcam crop, wallpaper grid, colour picker, presets menu, editor preferences,
  dialogs, keyboard shortcuts (`ShortcutsContext.tsx`, `shortcuts.ts`, `ShortcutsConfigDialog`,
  `KeyboardShortcutsHelp`), project browser, save/save-as/open, menu actions
  (`menu-load-project`, `menu-save-project(-as)`), and save-before-close.
- **P2.7 Timeline.** §5.12 timeline: every row, every indicator, every interaction and
  shortcut. Include auto-zoom suggestions from cursor telemetry (`zoomSuggestionUtils.ts`,
  `useFreshRecordingAutoZoom.ts`) and source audio tracks/waveforms. Port the timeline TestFX
  tests.
- **P2.8 Captions display/editing** (no generation): caption rendering in the compositor
  (already in P2.4), caption style settings, `CaptionListPanel` editing of existing cues,
  caption ops (`captionOps.ts`, `captionEditing.ts`, `captionTimeline.ts`).
- **P2.9 Export.** §5.10 MP4 + GIF + sidecars + UI (`export/*`, menus, dialogs, progress,
  error report) + automations export trigger.
- **P2.10 Wire the dashboard to the Java editor.** Recording → editor navigation
  (`recordingEditorNavigation.ts`, `editorStartupView.ts`, `switch-to-editor`), and remove the
  P1 placeholder.
- **P2.11 Package, measure, report.**

**P2 acceptance gate:**
- Golden-frame suite green.
- Every user project opens and looks identical to Electron (side-by-side screenshots of 5 real
  projects at 3 timestamps each).
- Round-trip save test green.
- MP4 export of a 2-minute project: plays in `mpv`, A/V in sync (start, middle, end), duration
  matches the Electron export within one frame, visually matches the golden frames at sampled
  times.
- GIF export works.
- Timeline behaviours pass.
- Undo/redo works across every command.
- Startup budget met with the editor opening a project.
- UI gallery reviewed.

### 7.3 Phase 3 — Captions generation, AI control, extensions, completion, Electron removal
Ordered tasks:
- **P3.1 Auto captions** (§5.13) + transcript panel + transcript cut (`TranscriptPanel.tsx`,
  `transcriptCut.ts`, `tests/ui/transcript-cut.spec.ts`, `caption-speed.spec.ts`).
- **P3.2 AI control + MCP** (§5.16), including agent UI (status chip, timeline highlights,
  agent settings). Verify with the `gravaai-video-editing` skill's read → plan → edit → look →
  export loop against the Java app.
- **P3.3 Extensions** (§5.18) + Extension Manager UI.
- **P3.4 Remaining screens and features.** Go through Appendix B line by line. Every directory
  and file is either ✅ ported or ⛔ N/A with a reason: custom fonts, tutorial help, smoke export
  automation (dev), announcements in the editor, recording permissions page (Linux parts),
  anything still missing.
- **P3.5 Final parity sweep.** Run every Playwright spec in `tests/ui/` as a behaviour list.
  Each one has a TestFX equivalent or a manual check in the report.
- **P3.6 Delete Electron.** Only after reviewer approval of P3.1–P3.5. Remove `electron/`,
  `src/`, `public/` (after moving wallpapers and other assets into Java resources), `index.html`,
  vite/vitest/playwright/tsconfig/biome configs, `package*.json`, `node_modules`, `dist*`,
  `release/`, Node-based `scripts/` (bash replacements live in `java/native/`), and
  `electron-builder.json5`. Move `java/*` to the repo root, or keep `java/`, as the reviewer
  decides at that time. Update README, CONTRIBUTING, THIRD_PARTY_NOTICES (only list what we
  bundle) and TRANSLATION_GUIDE (new i18n path). Make `.github` workflows manual-only (rule 5).
- **P3.7 Final package, measure, report.**

**P3 acceptance gate:**
- Captions generate on fixture recordings and match the Electron output text.
- The MCP skill loop works.
- An example extension runs and draws in preview and export.
- Appendix B fully accounted for.
- Electron removed; the repo builds the AppImage with `./mvnw -T1 -Pdist package` alone.
- Startup budget met.

### 7.4 Phase report template (`docs/java-rewrite/phase-N-report.md`)
1. What was built (task list with ✅/❌).
2. Deviations from this plan, each with its reason and the decision rule used.
3. Appendix A/B status for this phase's scope (ported / N/A + reason).
4. Test results (counts; failures listed).
5. Golden/UI parity results + link to the gallery.
6. Manual checklist with screenshots.
7. Startup numbers (5 runs) from the AppImage.
8. Known issues and open questions for the reviewer.

---

## 8. How to port a TypeScript file (method)

1. Read the TS file **and its test file** fully. Read the files it imports when behaviour
   depends on them.
2. Port the tests first (JUnit/jqwik), same cases and same expected values.
3. Port the code: same function names (camelCase stays camelCase), same order, same constants
   (copy numbers literally, including "magic" ones). Keep the comments that explain *why*.
4. TS → Java translation table:

| TS | Java |
|---|---|
| `interface X { … }` (data) | `record X(…)` |
| union of string literals | `enum` with `wire()` string + `@JsonValue`/`@JsonCreator` |
| discriminated union | `sealed interface` + records + Jackson `@JsonTypeInfo(use = NAME, property = "type")` |
| optional field `x?: T` | nullable component + normaliser default (never `Optional` in records) |
| `Record<string, T>` | `Map<String, T>` (`LinkedHashMap` to keep order) |
| `number` | `double` (or `long` when the TS clearly holds integers such as ms timestamps, ids, byte counts) |
| `Promise<T>` | `CompletableFuture<T>`, or a blocking method on a virtual thread |
| `setTimeout`/`setInterval` | `ScheduledExecutorService` (services) / `PauseTransition`, `Timeline` (UI) |
| React state/hooks | view-model class with JavaFX properties; `useEffect` cleanup → `Subscription.close()` |
| React component | JavaFX view class (`extends Region`/`StackPane`), CSS classes copied from Tailwind via the kit table |
| `Math.round` | `Math.round` (careful: JS rounds .5 up toward +∞; Java `Math.round` matches for doubles) |
| `Number.isFinite` | `Double.isFinite` |
| `JSON.stringify(x, null, 2)` | Jackson with `INDENT_OUTPUT` and 2-space indenter (test it) |

5. Do not "clean up" logic while porting. A behaviour change needs reviewer approval.
6. Make one commit per ported unit, with a message naming the TS source path.

---

## 9. Risks and decision rules

| ID | Situation | Rule |
|---|---|---|
| R1 | A JavaFX behaviour differs from Electron in a way that changes UX (focus, IME, drag-and-drop, scrolling feel) | Match the React behaviour with a custom control. If impossible, write it down for the reviewer. Never ship a silent difference. |
| R2 | Preview can't hold 60 fps for a 1080p60 source on this machine (measure compositor + readback per frame) | Step 1: render preview at panel size × render scale, not source size. Step 2: decode into YUV planes, upload three R8 textures, convert in SkSL (BT.709/601 + range from `videoColorSpace.ts`). Step 3: report to the reviewer. Never touch hardware decode (rule 1). |
| R3 | Capture-picker stages don't land one per monitor under COSMIC/XWayland | Use a single picker on the monitor under the pointer (`gravaai-wayland-cursor` position) and switch monitors on pointer move, so "Pick screen selects the monitor you click on" still holds. Report it. |
| R4 | A library version breaks on JDK 27 | Try the latest patch of the same major. If that fails, ask the reviewer. Never downgrade the JDK. |
| R5 | Colour emoji don't render in JavaFX | Rasterise emoji glyphs with Skija (Noto Color Emoji) into cached `Image`s. |
| R6 | GraalJS can't run on stock JDK 27 or is too slow per frame (>2 ms per hook) | Report to the reviewer with measurements before building more of the extension system. |
| R7 | `startPipeline` can't keep the capture helper's stderr separate | Copy helper stdout → ffmpeg stdin in Java with a 4 MB direct buffer on a platform thread. |
| R8 | Startup budget missed | Profile with `-Xlog:startuptime`, `-Xlog:class+load` and the in-app `startup:` log lines. Check the AOT cache is accepted (no `[aot] error` lines), move work off the start path (lazy services, GL in parallel), and defer non-visible windows. Report the numbers. |
| R9 | Anything in this plan is ambiguous | Read the TS source; the existing behaviour wins. If it's still ambiguous, ask the reviewer. Do not guess. |

---

## Appendix A — IPC channels → Java services

Legend: → request/response, ⇢ fire-and-forget, ⇠ push event. "N/A-split" means the channel only
exists because of the Electron process split and has no Java equivalent.

| Java service | Channels |
|---|---|
| `AppService` | app:getVersion, get-platform, get-linux-window-system (N/A, always X11/XWayland internally), get-window-chrome, window-chrome-changed⇠, get-window-fullscreen, window-fullscreen-changed⇠, open-external-url, reveal-in-folder, read-local-file (direct file read), get-asset-base-path / list-asset-directory (resources), get-local-media-url (N/A, direct decode) |
| `HudService` | hud-overlay-set-ignore-mouse (N/A, tight windows), hud-overlay-set-source-selection-active, hud-overlay-drag (X11 beginMove), hud-overlay-hide, hud-overlay-close, hud-overlay-renderer-ready (N/A-split), hud-menu-open, hud-menu-close, hud-menu-get-current, hud-menu-show⇠, hud-overlay-set-webcam-preview-visible, get/set-hud-overlay-capture-protection (N/A, Windows/mac), get-hud-overlay-mouse-passthrough-supported (N/A), show-recording-hud, get-editor-mode, editor-mode-changed⇠ |
| `SourceService` | get-sources, select-source, get-selected-source, selected-source-changed⇠, open-source-selector, show-source-highlight, pick-capture-target, complete-capture-pick, get-capture-picker-context, capture-picker-ready (N/A-split), is-native-windows-capture-available (N/A) |
| `RecordingService` | start-native-screen-recording, stop-native-screen-recording, pause/resume-native-screen-recording, recover-native-screen-recording, get-last-native-capture-diagnostics, start/stop-ffmpeg-recording (verify use on Linux), store-recorded-video, store-microphone-sidecar, get-recorded-video-path, set-recording-state, recording-state-changed⇠, recording-session-changed⇠, get/set-current-recording-session, recording-interrupted⇠, recording-stop-requested⇠, stop-recording-from-tray⇠, cancel-recording-import, import-recording, finish-recording-import, finish-recording-startup, list-recordings, get-recording-thumbnail, delete-recording-file, set-recordings-removed, list-recoverable-recordings, recover-recording, discard-recoverable-recording, mux-native-windows-recording (N/A), get-recording-audio-lab-config, get/set-mic-noise-reduction, get/set-recording-preferences, recording-preferences-changed⇠, choose-recordings-directory, get-recordings-directory, open-recordings-folder, get-current-video-path / set-current-video-path / clear-current-video-path |
| `CountdownService` | start-countdown, cancel-countdown, get-active-countdown, countdown-tick⇠, get/set-countdown-delay, countdown-delay-changed⇠ |
| `TelemetryService` | pause/resume-cursor-capture, set-cursor-scale, get/set-cursor-telemetry, cursor-state-changed⇠, hide-cursor, get-system-cursor-assets, get-keystroke-telemetry, get/set-keystroke-capture-mode |
| `PermissionsService` | show-recording-permissions, recording-permissions-requested⇠, get-screen-recording-permission-status, open-screen-recording-preferences, get-accessibility-permission-status, request-accessibility-permission, open-accessibility-preferences — all N/A on Linux except whatever the Linux page shows (check `RecordingPermissionsSettings.tsx`, e.g. the input group for keystrokes) |
| `ProjectService` | create-project-file, load-project-file, load-current-project-file, open-project-file-at-path, list-project-files, get-projects-directory, open-projects-directory, rename-library-project, trash-project-files, get-project-preview, project-library-thumbnail-ready⇠, set-has-unsaved-changes, request-save-before-close⇠, save-before-close-done, menu-load-project⇠, menu-save-project⇠, menu-save-project-as⇠, show-project-dashboard, switch-to-editor, open-video-file-picker, open-audio-file-picker |
| `ExportService` | export-stream-open/write/close, native-video-export-start/write-frame(s)/cancel, native-static-layout-export(+cancel, progress⇠) (N/A route), mux-exported-video-audio(+from-path), finalize-exported-video, discard-exported-temp, save-exported-video, get-video-audio-fallback-paths, get-export-hardware-info, probe-native-video-metadata (→ `MediaProbe`), generate-wallpaper-thumbnail. Most of these collapse into `ExportJob` (§5.10). |
| `CaptionService` | generate-auto-captions, get-whisper-model-status, download-whisper-model, delete-whisper-model, get-whisper-small-model-status, download-whisper-small-model, delete-whisper-small-model, whisper-small-model-download-progress⇠, open-whisper-executable-picker, open-whisper-model-picker |
| `MeetingService` | meeting-start-audio-recording, meeting-list, meeting-get-files, meeting-save-file, meeting-rename, meeting-delete, meeting-reveal, meeting-import, meeting-media-url (N/A, direct decode), meeting-run-job, meeting-cancel-job, meeting-list-jobs, meeting-job-update⇠, meeting-get-settings, meeting-set-settings, meeting-list-assets, meeting-download-asset, meeting-remove-asset, meeting-asset-progress⇠, meeting-request-open, meeting-take-open-request, meeting-open-requested⇠ |
| `AutomationService` | get-automations, save-automations, run-automation-on-file, choose-automation-folder |
| `ShortcutService` | get/set/reset-global-shortcuts, global-shortcut-action⇠, global-shortcut-listener-ready (N/A-split), get-shortcuts, save-shortcuts (editor shortcuts file) |
| `AgentService` | agent:get-config, agent:set-enabled, agent:regenerate-token, agent:get-status, agent:status-changed⇠, agent:request⇠/agent:response (in-process now), agent:capture-frame, agent:job-report, agent:ready / agent:gone (N/A-split) |

If you find a channel missing from this table, add it to the right service and list it in your report.

## Appendix B — Source directory → Java package

| Source | Java | Phase |
|---|---|---|
| `electron/main.ts`, `appPaths.ts`, `appSettingsStore.ts`, `ipc/constants.ts`, `ipc/utils.ts`, `ipc/state.ts`, `ipc/types.ts` | `app.Main`, `app.AppContext`, `core.storage` | P1 |
| `electron/windows.ts`, `hud*.ts`, `editorStartupView.ts`, `recordingEditorNavigation.ts`, `saveBeforeClose.ts`, `cursorHider.ts` | `ui.windows.*` | P1/P2 |
| `electron/countdownController.ts` | `services.recording.CountdownService` | P1 |
| `electron/ipc/recording/*` (Linux) | `services.recording` | P1 |
| `electron/ipc/cursor/*`, `ipc/keyboard/*` | `services.recording.telemetry` | P1 |
| `electron/ipc/capture*.ts`, `sourceArea.ts`, `monitorResolver.ts`, `sourceListPicker.ts`, `register/sources.ts`, `register/capturePicker.ts`, `register/sourceMapping.ts` | `services.sources` | P1 |
| `electron/ipc/project/*`, `register/project.ts` | `services.projects` + `core.project` | P1 (read) / P2 |
| `electron/ipc/ffmpeg/*`, `ipc/paths/*` | `media.ffmpeg`, `platform.NativeHelpers` | P1 |
| `electron/ipc/export/*`, `nativeVideoExport.ts`, `register/export*.ts` | `media.export` | P2 |
| `electron/ipc/captions/*`, `register/captions.ts` | `services.captions` | P3 |
| `electron/ipc/meeting/*`, `register/meeting.ts` | `services.meeting` | P1 |
| `electron/ipc/settings/*`, `register/settings.ts` | `services.settings` | P1 |
| `electron/automations/*`, `register/automations.ts` | `services.automations` | P1 |
| `electron/globalShortcuts/*` | `services.shortcuts` | P1 |
| `electron/agent/*`, `register/agent.ts` | `services.agent` + `app.McpMain` | P3 |
| `electron/native/{wayland-capture,wayland-cursor,gravaai-ctl,models}` | `java/native/*` | P1 |
| `src/components/ui/*` | `ui.kit` | P1 |
| `src/components/launch/*`, `countdown/*`, `capture-picker/*` | `ui.launch`, `ui.overlays` | P1 |
| `src/components/video-editor/dashboard/*`, `library/*` | `ui.dashboard` | P1 |
| `src/components/meeting/*` | `ui.meeting` | P1 |
| `src/components/permissions/*` | `ui.settings` (Linux parts) | P1 |
| `src/components/announcements/*`, `src/content/*`, `src/lib/announcement*` | `ui.announcements` | P1 |
| `src/components/video-editor/{types,projectPersistence,editorHistory,clip*,caption*,transcriptCut,zoomSuggestionUtils,...}.ts` | `core.project`, `core.editor` | P2 (captions gen/transcript: P3) |
| `src/components/video-editor/videoPlayback/*`, `VideoPlayback.tsx` | `media.playback`, `media.compose` | P2 |
| `src/lib/exporter/*` | `media.compose`, `media.export` | P2 |
| `src/components/video-editor/{layout,state,hooks,presets,project,export,audio,timeline}/*` and root TSX files | `ui.editor.*` | P2 |
| `src/components/video-editor/agent/*` | `services.agent.ops`, `ui.editor.agent` | P3 |
| `src/components/video-editor/ExtensionManager.tsx` + extension host | `services.extensions`, `ui.extensions` | P3 |
| `src/hooks/*` | absorbed into services/view-models (`useAudioLevelMeter` → §5.6.9; `useMicrophoneDevices`/`useVideoDevices` → device services) | P1 |
| `src/contexts/*` | `ui.I18n`, `ui.Shortcuts`, `ui.Theme` | P1/P2 |
| `src/i18n/locales/**` | resources `i18n/**` | P1 |
| `src/assets/**`, `public/wallpapers/**`, `public/app-icons/**`, `icons/**`, `branding/**` | resources / packaging | P1 |
| `public/wasm/**` | N/A (browser-only); check its contents and confirm | — |
| everything in §2.3 | N/A (platform) | — |

## Appendix C — Reference numbers

| Measurement (RX 550, COSMIC, SSD) | Value |
|---|---|
| JVM boot (`java -version`, jlink image) | 30 ms |
| Spike editor visible, no AOT | ~0.86 s |
| Spike editor visible, AOT cache | ~0.58 s |
| Spike first GL preview frame, AOT cache | ~0.60 s |
| 1080p GL draw (simple shader) | <0.1 ms CPU |
| 1080p PBO readback + copy into PixelBuffer | 1–9 ms |
| App image size (JDK + JavaFX + LWJGL) | 109 MB (expect ~200 MB with FFmpeg, Skija, whisper) |
