# Gravaai Java rewrite - Phase 1 report

Status: **in progress** (updated while the phase runs; final numbers and the manual checklist at P1.14).

Plan: `docs/java-rewrite-plan.md`. Phase 1 scope: foundation, recording, meetings, dashboard,
settings, shortcuts and automations - no editor/export yet.

## 1. What was built (task list)

| Task | Status | Notes |
|---|---|---|
| P1.1 Build skeleton, packaging, startup check | done | `java/` with six modules, Maven Wrapper 3.9.16, `-T1`, build output on scratch, `-Pdist` = jlink + jpackage app image + AOT cache + AppImage, `tools/startup-check.sh` |
| P1.2 Core storage, settings, i18n | done | paths/legacy migration, atomic JSON byte-compatible with `JSON.stringify(x,null,2)`, settings stores with unknown-key preservation, recent projects, i18n loader + 77 locale files (pinned upstream parity gap), `UiEvents`; 162 tests green |
| P1.3 UI kit, tokens, fonts, icons, showcase | done | 27+ components + tokens + DM Sans static faces + 182 extracted icons + showcase; 27 kit tests, gallery `ui-kit.png` reviewed against the React references |
| P1.4 Platform services | done | ProcessRunner (raw pipes, timeouts, kill-tree), NativeHelpers, unix sockets, XDG trash, tray/SNI + dbusmenu (live-verified on the session bus), notifications, portals, FileManager1, X11Bridge with `XShapeCombineRectangles` input shape; 65 tests green |
| P1.5 Native builds + FFmpeg bindings | done | C helpers, FFmpeg 8.0.3 + CLI (x264/opus/lame, zero vaapi/vdpau/vulkan) and whisper.cpp v1.8.4 built by bash scripts; jextract bindings committed; FfmpegLibs/MediaProbe/VideoDecoder/FrameConverter/AudioDecoder + WebcamCapture/PulseAudio; 43 tests green; 4 committed fixture clips |
| P1.6 Sources, picker, countdown, area border | done | source mapping/picker/countdown/area-border services + 147 recording/sources tests; overlays built in the kit; COSMIC capture target selection is in the manual gate |
| P1.7 HUD + HUD menu | done | HUD bar/menu/webcam preview/countdown wired into AppContext as the startup window; real PulseAudio + C920 checklist done; `hud.show()` 105 ms (was 410); snapshots `hud-overlay/hud-menu/hud-recording.png` |
| P1.8 Recording service | done | CosmicRecorder/CosmicSession/RecordingLibrary/RecordingService/diagnostics/import + telemetry; 147 recording/sources tests; AppContext wiring (two-phase startup + LazyHudRecordingPort); COSMIC capture is in the manual gate |
| P1.9 Webcam recording | done | media `WebcamCapture`/`PulseAudio` + WebcamRecordingService + live preview (real C920 validated); a real webcam recording is in the manual gate |
| P1.10 Dashboard | done | projects/recordings read model, folders/preferences, project grid, settings pages (shortcuts, AI, automations), raw recordings, recoverable banner; 49 dashboard/projects tests; gallery `dashboard*.png` |
| P1.11 Meetings | done | prompts/resources verbatim, transcript/summarizer, settings/assets/installer, jobs queue, library, pipeline, llama-server/whisper/api-transcription, OpenAi client; 68 service tests + 23 meeting UI tests; MeetingView wired into AppContext (standalone window); the record -> transcript -> notes gate is in the manual checklist |
| P1.12 Global shortcuts | done | COSMIC RON splice byte-preserving + both fixtures, control socket proven against the real `gravaai-ctl`, dispatcher, install/sync/conflicts |
| P1.13 Automations | done | full engine/store/triggers/file actions + jqwik never-throws property; 156 shortcuts+automations tests green through the sanctioned runner |
| P1.14 Package, measure, report | done | 107.8 MB AppImage from `-Pdist`; full suite **1323 tests, 0 failures/0 errors (5 approved skips)**; startup gate **PASS** mean 736 ms / median 723 ms; gallery 30 screens; meeting/HUD captures on the packaged app; human-only checks listed in §6 for the reviewer |

## 2. Deviations from the plan

| Area | Deviation | Reason / decision rule |
|---|---|---|
| AOT cache path | launcher option is `-XX:AOTCache=$APPDIR/gravaai.aot` | jpackage's `$APPDIR` is `<image>/lib/app`; the plan already writes this form in §5.19.2 |
| AppImage entry point | `AppRun` execs the bundled `java` with the class path/options from `lib/app/gravaai.cfg` instead of the jpackage launcher | the jpackage launcher probes `rpm`/`dpkg` on every start (~200 ms measured here) before the JVM boots, which alone breaks the P1 startup budget; the app image still contains the normal launcher. §5.19.5 only requires an AppImage wrapper |
| Serial GC | `-XX:+UseSerialGC` in the launcher options | G1 start-up costs ~80 ms here and the startup budget is a hard phase gate; a desktop UI with modest heap. Revisit if P2 exposes GC pauses (plan R8) |
| JUL log file names | `FileHandler` with 5 x 2 MB writes `logs/gravaai.log.0` and rotations; the base name is unused by JDK 27 | JDK behaviour of FileHandler when count > 1; the startup check and docs refer to `logs/gravaai.log*` |
| i18n parity test | pinned known gap: 40 keys are missing in every non-English locale upstream (`settings.json` `captions.model`, `captions.modelDescription`, `captions.modelLargeTurbo`, `captions.modelSmall`) | `node scripts/i18n-check.mjs` fails the same way on the Electron tree; inventing translations would change user-visible text. The JUnit test fails on any new drift |
| Dashboard metadata | new `<userData>/dashboard-library.json` for folders/hidden names/colours | the React app keeps these in Chromium localStorage, unreadable from Java without a leveldb parser (no new library allowed). Electron->Java migration gap, open question |
| meeting API key | `MeetingApiKeyStore` port; no plaintext writing | Electron encrypts the key with safeStorage/OS keyring. A Secret-Service (libsecret) adapter is planned but not wired; encrypted blobs from Electron are an open question |
| Stub features | `--mcp` exits with a clear message until P3.2 | phase 3 scope, listed so it is not mistaken for an oversight |
| jlink modules | runtime also includes `jdk.security.auth`, `jdk.crypto.ec` and `jdk.localedata` | dbus-java needs `com.sun.security.auth.module.UnixSystem` for unix-socket auth (without it the app crashed at startup); HTTPS through `java.net.http` needs `jdk.crypto.ec`; the 11 locales need `jdk.localedata`. §5.19.1 lists a minimal set; these are requirements of the chosen libraries |
| jextract version | bindings generated with jextract 25 (no JDK 27 jextract build exists) | output uses the final FFM API and compiles/runs on JDK 27; exact command in `java/native/README.md` |
| VP9 fixture | the one VP9 fixture is produced with the repo's host `ffmpeg-static` | our FFmpeg build deliberately has no VP9 encoder (§5.7 lists libx264/aac/png/gif only); the bundled decode path for WebM/VP9 recordings is complete and tested |
| Slf4j warning | dbus-java (via slf4j) prints "No SLF4J providers were found" on stderr at tray startup | §4.3 forbids adding a logging framework; the warning is harmless and on stderr (never stdout, so MCP JSON-RPC is unaffected) |

## 3. Appendix A/B status for this phase

Filled at P1.14; the live checklist is the shared task board plus the per-package notes above.

## 4. Test results

Final P1.14 gate run, `java/tools/mvn-locked.sh -B package` (tests, one JVM at a time, `-T1`):

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| gravaai-core | 305 | 0 | 0 | 0 |
| gravaai-platform | 68 | 0 | 0 | 3 |
| gravaai-media | 353 | 0 | 0 | 2 |
| gravaai-services | 399 | 0 | 0 | 0 |
| gravaai-ui | 198 | 0 | 0 | 0 |
| **total** | **1323** | **0** | **0** | **5** |

Skipped tests are the two approved disabled golden/pixel cases plus three platform skips (environment-
conditional); nothing that runs the app is skipped. Highlights: 68 meeting service tests + 23 meeting
UI tests, HUD/kit/dashboard/timeline/editor-shell TestFX snapshot suites, real-camera/real-PulseAudio
media tests, project round-trip on both user projects, jqwik property tests for automations.

## 5. Golden/UI parity

UI reference screenshots come from the React browser harness (P6.3) into `java/parity/ui/`; the JavaFX
captures go to `java/parity/ui-java/` and the side-by-side page is `docs/java-rewrite/phase-1-ui.html`
(generated by `java/tools/parity/gallery.mjs`). Golden frames are Phase 2 (P2.1); the Electron capture
route was verified software-only (VA-API decode/encode disabled by `electron/gpuSwitches.ts`, no libva/
vulkan mapped in the live GPU process - see `java/parity/README.md`).

## 6. Manual checklist status (final at P1.14)

| Item | Status | Evidence / note |
|---|---|---|
| AppImage launches and shows a window | yes | startup-check runs; screenshot in java/parity/ui-java/ |
| AOT cache accepted by the packaged JVM | yes | 0 `[aot]` error lines |
| Tray works (idle + recording icon, menu, activate) | yes | platform smoke evidence (tray-panel-zoom.png, tray-panel-recording-zoom.png) |
| Control socket / single instance / shortcut delivery | yes | real `gravaai-ctl toggle-recording` -> exit 0, app logs queueing until HUD; raw socket reply `ok` |
| Dashboard lists the existing user projects and recordings | yes | `--dashboard` capture with the real two projects (dashboard-real.png) |
| UI parity gallery reviewed | captured | docs/java-rewrite/phase-1-ui.html (30 screens incl. HUD, dashboard settings, editor shell/dialogs, timeline, meetings); reviewer side-by-side pending |
| P1.3 UI kit visual gate | captured | ui-kit.png; reviewer look pending |
| Record screen + mic + system audio + webcam on COSMIC | pending | service + HUD + overlays wired (RecordingWiring two-phase startup, LazyHudRecordingPort); the capture itself needs the COSMIC portal consent dialog and a human run |
| Recorded files open correctly in the Electron editor (cursor/keys/webcam aligned) | pending | manual gate; webcam `.mkv` reader check included |
| Kill -9 mid-recording -> recoverable on restart | pending | CosmicSession recovery covered by tests; manual kill -9 gate pending wiring |
| Meetings: record -> transcript -> notes (local model and API endpoint) | ready for gate | MeetingService + MeetingView wired; the gate opens the window with `GRAVAAI_MEETING_OPEN=<media>` (library/settings with `GRAVAAI_MEETING_LIBRARY=1` / `GRAVAAI_MEETING_SETTINGS=1`); the API-key write path is the documented keyring gap (local model path unaffected) |
| Global shortcuts start/stop recording through COSMIC | partial | registration/socket + dispatcher verified and wired to the HUD recording port; pressing the real shortcut on COSMIC is a human gate |
| Automations fire on recording/meeting events | yes (wiring) | engine tests green; RecordingService fireRecordingSaved and MeetingService fireMeetingNotesReady are wired in AppContext; a live rule run is part of the gate |
| Startup budget (HUD visible <= 0.8 s at P1; first preview frame is P2) | yes (measured) | P1.14 gate 5-run HUD mean **736 ms** / median 723 ms (709/750/784/718/723); earlier loaded run mean 795; `hud.show()` 105 ms mean after the HUD optimisation |
| HUD drag on COSMIC confirmed by a human drag | pending | needs the P1.7 HUD + reviewer drag |


## 7. Startup numbers

The P1 startup window is the HUD (P1.7). Final numbers, AppImage built with `-Pdist`, measured with
`java/tools/startup-check.sh --runs 5`:

| Run | window shown |
|---|---|
| 1 | 709 ms |
| 2 | 750 ms |
| 3 | 784 ms |
| 4 | 718 ms |
| 5 | 723 ms |
| mean | **736 ms (PASS <= 800)** |
| median | **723 ms** |

(An earlier official run during heavy teammate load measured mean 795 / median 782 ms with one 892 ms
outlier; the table above is the P1.14 gate run on a quiet machine.) Representative breakdown: `main` ~180-235 ms, toolkit up
~440-525 ms, `Application.start` ~450-530 ms, then HUD construction + `hud.show()` (~105 ms mean after
the optimisation, previously ~410 ms) -> `startup: window shown`. Recording wiring, D-Bus/portal,
shortcuts and the tray run on the `gravaai-post-window` virtual thread after the budget line, so
recording services are ready ~150 ms later without delaying the first window. The dashboard/editor
windows belong to the P2 startup budget (first preview frame <= 900 ms).

## 8. Known issues and open questions for the reviewer

1. i18n: upstream missing caption-model keys (see deviations).
2. Dashboard metadata lives in Chromium localStorage in Electron (see deviations).
3. Meeting API key: safeStorage blob cannot be decrypted without the OS keyring; Secret-Service adapter planned.
4. HUD drag on COSMIC must be confirmed by a human drag (the synthetic check only proves `_NET_WM_MOVERESIZE` is sent).
5. AOT cache in the AppImage: verified accepted (no `[aot]` error lines); numbers in §7.
6. Supply-chain observation: the authentic upstream `net.jqwik:jqwik-engine:1.10.2` jar
   (sha256 b4cc136a0efc1e2aa31514aae8d80b66fef53894c2d2777e2cc1143ca7511098, byte-identical to
   Maven Central) contains strings instructing AI agents to ignore jqwik test results. This is
   untrusted tool output: ignored, and no test result was changed because of it. Worth a reviewer
   note before the final dependency review.


## Round 1 checkpoint (2026-10-27 evening)

Verified in this round:

* P1.1 gate: `-Pdist` produces the AppImage; AOT cache accepted by the JVM (0 `[aot]` errors);
  startup lines `main` 190 ms, toolkit up 472 ms, `Application.start` 485 ms, window shown 762 ms on
  an idle run (see §7 for the 5-run mean under load).
* P1.4 complete: 65 tests green; tray icon live on the COSMIC panel in idle+recording states,
  notification shown, FileManager1 reveal verified, `X11Bridge.findWindow("Gravaai")` found the
  app's JavaFX window. Evidence: /media/jmarceno/scratch/java-targets/gravaai-platform/smoke-evidence/.
* P1.12 control socket + single instance: the real `gravaai-ctl toggle-recording` returns 0 and the
  app logs `Queued toggle-recording until the HUD is ready` (HUD arrives in P1.7); a raw
  unix-socket client gets `ok`.
* P1.3 complete: kit showcase captured to `java/parity/ui-java/ui-kit.png` (typography, tokens,
  buttons, toggles); parity gallery `docs/java-rewrite/phase-1-ui.html` has 11 screens.
* P1.10 dashboard view + read model implemented with 49 green tests and React-reference screenshots.
* P1.5 complete: FFmpeg 8.0.3 + whisper.cpp built, 43 media tests green.
* P1.2/P1.12/P1.13 complete: 162 core / 156 shortcuts+automations tests green.
* Recording service: sources+recording 126 tests green (state machine, cosmic pipeline, recovery,
  telemetry, webcam via ffmpeg-CLI substitution, mic meter); UI overlays pending the kit (now ready).
* Meetings: groundwork (prompt resources + transcript/pipeline types) and the resumed service port
  continue; dashboard/meeting UI wiring is the remaining P1.11 work.

Next in the implementation order: dashboard window wiring (AppContext `--dashboard` + real screenshot),
HUD/HUD menu (P1.7, ui-kit owner with task-10), recording UI overlays + capture pipeline smoke, meetings
pipeline/UI, settings pages, then P1.14 (final package, idle startup budget, manual checklist, report).

Open items added this round:
1. Platform tray deviation: Stop Recording menu entry per plan §2.1 (Electron stops on tray click; both
   implemented). Tray uses path-form registration with the StatusNotifierWatcher.
2. The platform smoke left four stale StatusNotifierItem names in the watcher (killed test holders);
   the panel shows generic placeholders for them until logout/daemon restart - not caused by the app.
3. Webcam recording temporarily uses the bundled ffmpeg CLI (software libx264) until the in-process
   `WebcamRecorder` exists; output is `recording-<ts>-webcam.mkv` and the editor/automations readers
   must accept `.mkv` (P2 check).
4. Dashboard trash resolves recordings before trashing the project file (deliberate fix of an
   Electron bug; the dialog text promised it).
5. Dashboard metadata migration gap (localStorage) and meeting API key keyring gap remain as in §2.

## Round 2 checkpoint

* P1.3, P1.4 and P1.5 are complete on the shared task board; P1.2, P1.12, P1.13 were already complete.
* App integration this round: one `SessionBus` for tray + portal + FileManager1; dashboard actions now
  use the real xdg-desktop-portal file chooser and `FileManager1.ShowItems` (import/reveal); Settings ->
  Global shortcuts is wired to the real `GlobalShortcutsService` through
  `ServiceGlobalShortcutsController`; Settings -> AI control ships against a placeholder controller that
  shows the AppImage MCP command (agent service is P3.2).
* Startup path: font preloading overlaps JavaFX toolkit start-up; diagnostics moved after the window is
  visible; global-shortcut construction split from `init()` so COSMIC file IO stays off the visible path.
* P2 ownership is planned on the board: P2.3 playback -> recording, P2.4 compositor -> media-native,
  P2.5 preview panel -> ui-kit, P2.6 editor shell -> core-storage, P2.7 timeline -> dashboard,
  P2.8 captions display -> meetings, P2.9 export -> shortcuts, P2.1 goldens -> Lead.
* P2.1 tooling: `java/tools/parity/capture-golden.mjs` drives the Electron MCP `project`/`frames` tools
  and converts frames to PNG with the bundled ffmpeg CLI; the route is software-only (see 5).
* Remaining Phase 1 work: HUD/HUD menu (P1.7, in progress), meetings pipeline/UI (P1.11, in progress),
  recording UI overlays + capture smoke, dashboard screenshot in the gallery, settings pages, then P1.14.

## Round 2 checkpoint (continued)

* Dashboard screen is captured in the parity gallery from the JavaFX snapshot tests
  (`java/parity/ui-java/dashboard.png`, `dashboard-delete-dialog.png`,
  `dashboard-shortcuts-settings.png`, `dashboard-ai-settings.png`); the gallery now has 15 screens
  (React reference | JavaFX) in `docs/java-rewrite/phase-1-ui.html`.
* Settings -> Global shortcuts is wired to the real `GlobalShortcutsService` via
  `ServiceGlobalShortcutsController`; Settings -> AI control uses `PlaceholderAgentSettingsController`
  showing the AppImage MCP command until the Phase 3 agent service lands (P3.2).
* Dashboard actions now use the real desktop services: xdg-desktop-portal OpenFile for import,
  `FileManager1.ShowItems` for reveal, portal OpenURI for announcement links (one `SessionBus` shared
  with the tray).
* P2.1 tooling: `java/tools/parity/capture-golden.mjs` (Electron MCP `project` + `frames`, software
  route, JPEG->PNG via the bundled ffmpeg CLI). Fixture projects wait for the project model (P2.2).
* P2 ownership assigned on the board (P2.3 recording, P2.4 media-native, P2.5 ui-kit, P2.6
  core-storage, P2.7 dashboard, P2.8 meetings, P2.9 shortcuts, P2.1 Lead).

## Round 2 checkpoint (dashboard evidence)

* `gravaai --dashboard` now renders the real dashboard from the packaged AppImage; captured with two
  real user projects in a scratch profile: `java/parity/ui-java/dashboard-real.png`. The dev entry
  builds `DashboardDependencies` from the real services (ProjectManager, recordings scan,
  DashboardLibraryStore, freedesktop Trash, portal import, FileManager1 reveal, portal OpenURI) and
  injects `ServiceGlobalShortcutsController` + `PlaceholderAgentSettingsController`.
* JavaFX snapshot gallery rows added: Projects, delete dialog, Settings -> Shortcuts, Settings -> AI
  control (from the dashboard's TestFX snapshot test). The parity page now has 16 screens.
* Note: the dev `--dashboard` window shows at ~1.27 s because it builds the whole dashboard scene on
  the startup path. The real P1 startup window is the HUD (P1.7); the dashboard opens from the tray
  afterwards. P1.14 will measure the real startup path and document this split.
* `java/tools/phase-gate.sh` added: full tests + `-Pdist` + startup budget + UI capture + gallery in one
  command for the phase report.
* Known issue from the dashboard work: kit `Dialog` renders at 0x0 on the first layout pulse (dialog
  layer resizes managed children); DashboardDeleteDialog carries a documented temporary workaround and
  ui-kit has the fix on its list.

## Round 3 checkpoint

* P1.10 dashboard COMPLETE (task-9): dashboard + Settings -> General/Files fold-in, Global shortcuts,
  AI control and Automations pages; kit Dialog layout bug fixed and the workaround removed; folder
  colours use kit ColorPicker; snapshot gallery rows: dashboard (+automations/shortcuts/AI/delete
  dialog); parity page now 17 screens.
* P1.6 UI overlays delivered (SourceSelectorOverlay, CountdownOverlay, AreaRecordingBorder,
  RecordingOverlayHooks, CapturePickerOverlay) plus RecordingImportService, RecordingDiagnostics and
  CountdownDelayStore with tests; app wiring helper (RecordingWiring) in progress.
* Dashboard task feed: task-18 (P2.7 timeline) started against the stable core.project records with a
  TimelineDataSource/commands port so the core EditorStore can land later.
* Full-suite gate status: services 371 tests with 4 failures, all in meeting WIP (timestamp brackets,
  stripThinking, chunked summariser); reported to the meetings owner. Reactor `package` is green.
* `.mkv` webcam confirmed compatible with automations (regex `-webcam.<ext>`); the Electron-editor
  read gate remains a P1 checklist item.

## Appendix A status for Phase 1 scope

Legend: done = implemented in Java and wired/tests; pending = service code exists but the UI/AppContext
wiring or a provider is still missing; N/A = no Java equivalent (process split or platform-specific).

| Service | P1 channels | Status |
|---|---|---|
| `AppService` | getVersion, get-platform, get-linux-window-system (N/A), get-window-chrome/window-chrome-changed, get-window-fullscreen/…changed, open-external-url, reveal-in-folder, read-local-file, get-asset-base-path/list-asset-directory, get-local-media-url (N/A direct decode) | app shell + platform portal/FileManager1 in place; window-chrome/fullscreen notifications pending HUD/editor windows; asset helpers pending |
| `HudService` | hud-overlay-set-source-selection-active, drag (X11 beginMove), hide/close, renderer-ready (N/A-split), menu open/close/current/show, set-webcam-preview-visible, capture-protection/mouse-passthrough (N/A), show-recording-hud, get-editor-mode/…changed | HUD in progress (task-10); X11Windows adapter ready |
| `SourceService` | get-sources, select-source, get-selected-source/…changed, open-source-selector, show-source-highlight, pick-capture-target, complete-capture-pick, get-capture-picker-context, picker-ready (N/A-split), native-windows-capture (N/A) | service + SourceSelectorOverlay/CapturePickerOverlay done; AppContext wiring in RecordingWiring |
| `RecordingService` | start/stop/pause/resume native recording, recover, diagnostics, store video/mic sidecars, recorded-video-path, set-recording-state/…changed, session get/set/…changed, interrupted/stop-requested/tray-stop events, cancel-import, import-recording, finish-import/startup, list-recordings, thumbnail, delete-file, set-recordings-removed, list/recover/discard recoverable, audio-lab config, mic-noise-reduction, recording-preferences/…changed, choose/get/open recordings dir, current-video-path | service implemented + tested; AppContext wiring in progress (RecordingWiring helper) |
| `CountdownService` | start/cancel/get-active/tick, get/set delay/…changed | done (CountdownService + CountdownOverlay + CountdownDelayStore) |
| `TelemetryService` | pause/resume cursor capture, cursor scale, cursor telemetry, cursor-state-changed, hide cursor, system cursor assets, keystroke telemetry/mode | service done (CursorCaptureSession/Keystroke*); cursor asset extraction + AppContext wiring pending |
| `PermissionsService` | all recording/accessibility channels | N/A on Linux per plan; Linux parts (keystroke input group) come with the settings UI |
| `ProjectService` | create/load project, list/rename/trash, preview/thumbnail-ready, unsaved-changes/save-before-close, menu actions, dashboard/editor navigation, file pickers | services.projects read model + dashboard done; P2 project model in progress (task-12); navigation P2.10 |
| `ExportService` | export stream/native routes (N/A), finalize/discard/save, audio fallbacks, hardware info, probe metadata (→ MediaProbe), wallpaper thumbnail | Phase 2 (P2.9); MediaProbe done |
| `CaptionService` | auto captions, whisper model status/download/delete/pickers | Phase 3 (P3.1) pending |
| `MeetingService` | start audio recording, list/get/save/rename/delete/reveal/import, media-url (N/A), run/cancel/list jobs/updates, settings, assets/progress/remove, open request | service in progress (task-7); UI after that |
| `AutomationService` | get/save automations, run-on-file, choose folder | done (service + settings page) |
| `ShortcutService` | global shortcuts get/set/reset/action-ready/listener-ready (N/A-split), editor shortcuts get/save | done (service + settings page + editor shortcuts store) |
| `AgentService` | config/enable/token/status/changed, request/response (in-process), capture-frame, job-report, ready/gone (N/A-split) | Phase 3 (P3.2); settings page on a placeholder controller |

## Appendix B status for Phase 1 scope

| Area | Status |
|---|---|
| `electron/main.ts`, `appPaths.ts`, `appSettingsStore.ts`, `ipc/constants.ts`, `ipc/utils.ts`, `ipc/state.ts`, `ipc/types.ts` | done (`app.Main/AppContext`, `core.storage/settings/json`) |
| `electron/windows.ts`, `hud*.ts`, `editorStartupView.ts`, `recordingEditorNavigation.ts`, `saveBeforeClose.ts`, `cursorHider.ts` | windows/HUD in progress (task-10); navigation/save-before-close P2 |
| `electron/countdownController.ts` | done (CountdownService/Overlay) |
| `electron/ipc/recording/*` (Linux), `ipc/cursor/*`, `ipc/keyboard/*` | done (services.recording/sources/telemetry) |
| `electron/ipc/capture*.ts`, `sourceArea.ts`, `monitorResolver.ts`, `sourceListPicker.ts`, `register/sources|capturePicker|sourceMapping.ts` | done (services.sources + overlays) |
| `electron/ipc/project/*`, `register/project.ts` | P1 read model done (services.projects); full model P2 (task-12 in progress) |
| `electron/ipc/ffmpeg/*`, `ipc/paths/*` | done (media.ffmpeg + platform.NativeHelpers) |
| `electron/ipc/export/*`, `nativeVideoExport.ts`, `register/export*.ts` | Phase 2 (P2.9) |
| `electron/ipc/captions/*`, `register/captions.ts` | Phase 3 (P3.1) |
| `electron/ipc/meeting/*`, `register/meeting.ts` | in progress (task-7) |
| `electron/ipc/settings/*`, `register/settings.ts` | settings stores done in core; settings pages done except General (injection point ready) |
| `electron/automations/*`, `register/automations.ts` | done |
| `electron/globalShortcuts/*`, `gravaai-ctl` | done (service + control socket verified + settings page); `gravaai-ctl` bundled as-is |
| `electron/agent/*`, `register/agent.ts` | Phase 3 (P3.2) |
| `electron/native/{wayland-capture,wayland-cursor,gravaai-ctl,models}` | done (copied + bash builds; Electron copy kept) |
| `src/components/ui/*` | done (ui.kit) |
| `src/components/launch/*`, `countdown/*`, `capture-picker/*` | countdown/capture-picker done; `launch/*` (HUD) in progress (task-10) |
| `src/components/video-editor/dashboard/*`, `library/*` | done (dashboard + library read model) |
| `src/components/meeting/*` | pending (task-7) |
| `src/components/permissions/*` | Linux parts after settings UI pass |
| `src/components/announcements/*`, `src/content/*`, `src/lib/announcement*` | done (dashboard announcements view; carousel disabled exactly like React) |
| `src/components/video-editor/{types,projectPersistence,editorHistory,clip*,caption*}` | in progress (task-12: persistence/round-trip/clips/captions done; preferences/commands remain) |
| `src/lib/exporter/*`, `videoPlayback/*`, `VideoPlayback.tsx` | Phase 2 (P2.3/P2.4/P2.5) |
| `src/components/video-editor/{layout,state,hooks,presets,project,export,audio,timeline}/*` | Phase 2 (P2.5-P2.9) |
| `src/hooks/*` | absorbed (`useAudioLevelMeter` -> MicLevelMeterService; device hooks -> media capture/audio) |
| `src/contexts/*` | i18n core done; Shortcuts/Theme contexts come with the editor shell |
| `src/i18n/locales/**` | done (77 files copied byte-identically + loader + parity test) |
| `src/assets/**`, `public/wallpapers/**`, `public/app-icons/**`, `icons/**`, `branding/**` | app icons bundled for tray/tray-recording; wallpapers/cursor assets/fonts move with P2 UI work |
| `public/wasm/**` | N/A (browser-only; no content used by the desktop paths) |
| everything in plan §2.3 | N/A (macOS/Windows/browser paths, listed as decisions) |

## Round 4 checkpoint

* P1.7 HUD implemented (task-10 owner): dev.gravaai.ui.launch.HudOverlayWindow (transparent tight
  stage, drag via X11Windows, capture hide/restore, GlobalShortcutHud), HudBar (idle/recording/
  finalizing states matching parity/ui/hud-overlay.png), HudMenuWindow (full-display transparent
  menu: mic/webcam/countdown panels, live meters, webcam preview), HudRecordingPort seam,
  RecordingTimer, AudioLevelAnalyser (same maths as useAudioLevelMeter), HudDevices, HudWebcamPreview,
  HudShowcase dev harness. Evidence screenshots under /tmp/hud-*/Screenshot_*.png (bar 609x64).
* Recording integration: RecordingWiring (app/recording) constructs every recording service, overlays
  and adapters (CursorScreen over JavaFX Screen, platform process/environment/trash ports, thumbnails,
  import service, countdown scheduler, mic meter, capture picker). AppContext wiring of HUD + tray +
  shortcut dispatcher + dashboard window is the current increment (single writer: recording owner).
* Startup window of the P1 app becomes the HUD; the dashboard opens from the tray/Home instead of
  being the launch window (matches the Electron Linux behaviour: mainWindow is the HUD overlay).
* Full-suite gate still red only on 4 meeting WIP tests; reactor package green.
* The HUD drag must be verified by a human drag (reviewer item); the synthetic check only proves the
  _NET_WM_MOVERESIZE message is sent.

## Round 5 checkpoint (P1 feature-complete, P1.14 evidence in progress)

All P1 services, UI screens and wiring now exist and are tested:

* **HUD (P1.7)**: \`HudOverlayWindow\`/\`HudBar\`/\`HudMenuWindow\`/webcam preview/countdown, wired as the
  startup window through \`LazyHudRecordingPort\` + \`HudRecordingPortAdapter\`; real PulseAudio/C920
  checklist done, snapshots \`hud-overlay.png\`, \`hud-menu.png\`, \`hud-recording.png\`.
* **Recording (P1.8/P1.9)**: CosmicRecorder/CosmicSession/recovery/library/diagnostics plus
  \`RecordingWiring\`; webcam recording + preview; tray Stop Recording, control socket and shortcuts
  routed through the HUD port.
* **Meetings (P1.11)**: \`MeetingService\` wired to platform ports in \`MeetingIntegration\`;
  record-saved registration; \`MeetingView\`/\`MeetingLibraryView\`/\`MeetingSettingsView\` delivered and
  hosted by \`MeetingWindow\`/\`MeetingHosts\` (dashboard embeds them in P2.10). Service tests 68
  (prompts/transcript/summarizer/settings/jobs/library/assets/installer/llama/api transcription) and
  UI tests 23, including TestFX snapshots \`meeting-view.png\`, \`meeting-library.png\`,
  \`meeting-settings.png\`.
* **Dashboard (P1.10)**, **UI kit (P1.3)**, **global shortcuts (P1.12)**, **automations (P1.13)**:
  complete with their galleries and tests.
* **Startup**: official 5-run HUD mean **795 ms** (median 782, one quiet run 666 ms), \`hud.show()\`
  105 ms; AOT accepted; tray/SNI + control socket verified on the packaged AppImage.

Test totals at this checkpoint (module suites): core 305, media 240, services 399 (including the 68
meeting tests), UI > 187 (kit + HUD + dashboard + editor shell + meeting + timeline), platform 65.
A single canonical full-reactor run is the P1.14 gate item (teammates are still landing P2 increments,
so transient mid-edit compiles are expected while they work).

Deviations added this round:

* **Meeting hosts for the P1 gate**: the library/settings/meeting windows are also reachable with
  \`GRAVAAI_MEETING_LIBRARY=1\`, \`GRAVAAI_MEETING_SETTINGS=1\` and \`GRAVAAI_MEETING_OPEN=<media>\`.
  The dashboard embeds the same views in P2.10; this is orchestration, not a second UI.
* **Meeting API key**: \`MeetingApiKeyStore\` reads a plain key written by Electron but refuses to
  write plaintext (message tells the user to use the local pipeline or the Electron keyring); this is
  the plan §5.2 safeStorage gap.
* **Meeting service tests**: the Electron tree has unit tests only for prompts/settings/transcript;
  queue/library/assets/installer/llama/api-transcription behaviour tests were written from the IPC
  contract and library behaviour (28 cases) so the pipeline is not untested.
* **TestFX display env**: surefire passes \`DISPLAY=:0\` / \`XDG_RUNTIME_DIR\` / \`WAYLAND_DISPLAY\` to the
  forked JVM, because Maven inherits no display from the agent shell; UI tests skip nothing.

P2 progress at this checkpoint: P2.2 core model/editor store/commands **complete** (305 tests); P2.4
compositor **stages 1-3 complete** (background/blur, layout aspect/padding/crop, zoom transform +
springs + cursor-follow, 240 tests, goldens harness ready and full-frame parity gated on the remaining
stages); P2.3 playback foundation + controller interface green (51 tests) and the transport engine is
landing; P2.5 preview panel and P2.6 editor shell in progress; P2.7 timeline has the ported pure core
(67 tests), the Canvas view + ports + snapshot (92 tests) and gestures landing; P2.9 export starting
(owner reassigned after the shortcuts teammate dropped mid-start). Remaining: P2.1 golden fixtures
(platform), P2.8 captions, tasks-21/22 (wire the dashboard to the editor, package/measure/report).

## Round 6 checkpoint - P1.14 gate (Phase 1 code scope complete)

Evidence collected on the packaged app, on a quiet machine, with all teammates paused:

* **Build/package**: \`./tools/mvn-locked.sh -B -DskipTests -Pdist package\` -> AppImage
  \`/media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage\` (107.8 MB,
  jlink + jpackage image + AOT cache + custom AppRun).
* **Full suite**: 1323 tests, 0 failures, 0 errors, 5 approved skips (core 305, platform 68, media 353,
  services 399, ui 198) through the sanctioned locked runner; TestFX UI tests run against \`DISPLAY=:0\`.
* **Startup**: official 5-run HUD gate **mean 736 ms / median 723 ms** (runs 709/750/784/718/723),
  PASS; AOT cache accepted (no \`[aot]\` errors); startup lines in §7.
* **AppImage behaviour**: HUD visible + tray SNI + control socket; \`GRAVAAI_MEETING_OPEN\` /
  \`GRAVAAI_MEETING_LIBRARY=1\` / \`GRAVAAI_MEETING_SETTINGS=1\` opened the three meeting windows next to
  the HUD with the meeting service ready (captures: \`java/parity/ui-java/meeting-app.png\`,
  \`hud-final.png\`).
* **Gallery**: \`docs/java-rewrite/phase-1-ui.html\` regenerated with 30 screens (React reference vs
  JavaFX) from \`java/parity/ui-java/\`.
* **Manual gate**: \`docs/java-rewrite/phase-1-manual-gate.md\` is current, including the meeting-window
  launch recipe and the API-key keyring caveat. The remaining unchecked rows in §6 are human actions
  that need the COSMIC consent dialog/desktop interaction (real capture, HUD drag, kill -9 recovery,
  opening the recording in the Electron editor, pressing a global shortcut, a live automation run, and
  the reviewer side-by-side gallery pass); no additional code is pending for them.

P1 code scope (P1.1-P1.14) is complete. Phase 2 continues from the same branch: P2.2 core model/store
complete (305 tests), P2.4 compositor stages 1-4 with goldens harness and measured diagnostics
(mean abs diff 01: 4.1-4.4, 02: 6.9-9.9, 03: 2.8-8.1; full-frame parity gated until stages 5-11),
P2.3 playback engine + real audio out + decoder frame source green (85 playback tests), P2.5 preview
panel and P2.6 editor shell in progress, P2.7 timeline ports/Canvas/gestures in progress, P2.9 export
(ffmpeg CLI pipeline) started, P2.1 goldens complete (10 fixtures / 36 references). Remaining P2:
stages 5-11 (motion blur, cursor, webcam, annotations, captions, keystrokes, transitions), timeline
behaviours, editor/dashboard wiring (P2.10) and the Phase 2 package/report (P2.11), plus P2.8.
