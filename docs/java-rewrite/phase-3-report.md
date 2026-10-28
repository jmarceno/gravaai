# Gravaai Java rewrite — Phase 3 report

Status: **draft for reviewer** (P3.7). Plan: `docs/java-rewrite-plan.md` §7.3 (P3.1–P3.7),
template §7.4. Written 2026-10-28 by the parity-sweep teammate from the working tree; the tree
snapshot is commit `8c06405` **plus 134 uncommitted paths** (P3 work and the 2026-10-28 user-fix
set). Nothing was committed and no app/UI test was launched for this report (all numbers are read
from existing artifacts; the display policy is `:99` only, see `java/README.md`).

Companion documents: `phase-3-parity-sweep.md` (spec-by-spec parity, gaps), 
`phase-3-electron-removal-plan.md` (P3.6 preparation), `phase-3-skill-loop-evidence.md`
(P3.2 acceptance run), `phase-1-report.md` / `phase-2-report.md` (P1/P2 evidence), `java/parity/README.md` and `java/parity/golden/parity-deviation.md`.

---

## 1. What was built (P3.1–P3.7)

| Task | Status | Deliverables | Evidence |
|---|---|---|---|
| **P3.1 Auto captions** | 🟡 code complete, acceptance run pending | `services.captions` (extraction, silence split, phrase segmentation, merge, whisper models, `CaptionGenerator`, `AutoCaptionService`); `app.editor.AutoCaptionController` + wiring; captions inspector section and Transcript panel/cut (`core.TranscriptCut`, `ui.editor.transcript`); model download/select/delete | commit `06c40a5` "P3.1: auto-captions wired end to end"; `AutoCaptionControllerTest#generationAppliesCues`, `CaptionParserTest`, `CaptionSegmentTest`, `CaptionSilenceTest`, `CaptionMergeTest`, `TranscriptPanelTest`, `TranscriptWordsTest`, `TranscriptTimeMapTest`; suite green (§4). **Not yet demonstrated:** real-whisper output compared with Electron text on a fixture recording (manual, TODO), model download over the network |
| **P3.2 AI control + MCP** | ✅ loop PASS; residual tool gaps tracked | `services.agent` (service/dispatcher/planner/token/control server/MCP server/tool schemas/transcript/auto-zoom); `app.agent` write port (`AgentWiring`, `AgentEditorPort`, `AgentEditorViews`, `AgentFrameRenderer`, `AgentExportRunner`); `--mcp` bridge (`app.mcp.McpMain`); editor agent views/status/highlights (`ui.agent`) | **Acceptance loop PASS** against the packaged app through `gravaai --mcp`: `docs/java-rewrite/phase-3-skill-loop-evidence.md` (READ/PLAN/EDIT/LOOK/EXPORT/SAVE; `rev` honoured; `ffprobe`-validated 1920×1080 30 fps h264 export; frame diffs match a 1.8× centre crop). Tests: `AgentServiceTest`, `AgentDispatcherTest`, `AgentPlannerTest`, `AgentMcpServerTest`, `AgentControlTransportTest`, `AgentTokenTest`, `AgentToolSchemasTest`, `AgentToolSchemasMatchTsTest`, `AgentTranscriptTest`, `AgentEditorViewsTest` (7), `AgentSettingsViewTest`, `AgentStatusChipTest`, `AgentHighlightsControllerTest`. Residual gaps (documented in the evidence doc §7): agent `transcribe` job, contact-sheet frames (one PNG per requested time instead), agent export extension hooks (none in the scratch profile; the UI export path keeps them). `project close` and `auto_zoom` cursor telemetry were closed by the Lead after that run (`AgentEditorPort` close seam + `AgentAutoZoom.Sample` made public so the app planner context carries telemetry). `AgentWiring` was edited after the last green reactor run → re-run the suite |
| **P3.3 Extensions** | ✅ code + tests; manager UI manual | `services.extensions` (manifest parser, host, GraalJS API + canvas, render-hook pipeline, `ExtensionFrameOverlayHooks`); extension host wired to editor preview and exporter in `AppContext`; editor `ExtensionManager`; example `extension-examples/hello-overlay` | `ExtensionRenderHookPipelineTest` — *"the example extension's final hook draws in the editor preview composite"* and *"…in the export composite"*, plus *"no extensions registered leaves preview frames byte-identical"*; `ExtensionFrameOverlayHooksTest`, `ExtensionManifestParserTest`, `Canvas2DTest`, `CanvasColorTest`; acceptance "example extension runs and draws in preview and export" is **covered by tests**. Extension Manager UI has no automated test (manual) |
| **P3.4 Remaining screens/features** | 🟡 mostly done, several manual spots | Crop dialog (`CropEditorDialog`/`CropControl`, commit `8c06405`); custom fonts (`CustomFonts`, `AddCustomFontDialog`); tutorial help (`TutorialHelpDialog`); editor/dashboard announcements (`Announcements`, banner, dashboard view); recording-permissions page Linux parts (`RecordingPermissionsSettings`); project browser, shortcuts config, wallpaper/video-wallpaper gallery (Wispy Sky asset + loop, commit `65350cb`); webcam crop control (`WebcamCropMath`, `WebcamCropControl`); raw-card hover through the compositor; agent settings page | `CropEditorDialogTest#edgeDragClamps`, `CustomFontsTest`, `AnnouncementsTest`, `RecordingPermissionsSettingsTest`, `ShortcutsTest`, `EditorShortcutsStoreTest`, `ProjectFrameInputFactoryTest#videoWallpaperLoops`, `BackgroundRenderTest#imageWallpaper`, `WebcamCropMathTest`, `WebcamCropControlTest`, `ProjectCardHoverPreviewTest`; `EditorSnapshotTest` renders project browser/add-font/announcement/annotation-settings dialogs. Untested UI: tutorial/help, Extension Manager, shortcuts-config dialog, wallpaper gallery, Clips panel → see `phase-3-parity-sweep.md` Gaps B |
| **P3.5 Final parity sweep** | ✅ complete | `docs/java-rewrite/phase-3-parity-sweep.md` | All **29 `tests/ui/*.spec.ts` files** (104 declarations, 106 runtime titles) accounted for: **31 ✅ mapped, 36 🟡 mapped+manual, 11 🔵 manual, 4 ⛔ N/A**, 2 ⚠ gaps (Space play/pause; autosave) + 9 implemented-but-untested items. Deferred rail-clears-selections item verified (`EditorShellView` + `EditorRailSelectionTest#railClickClearsSelections`) |
| **P3.6 Delete Electron** | 🟡 not executed (approval gate) | `docs/java-rewrite/phase-3-electron-removal-plan.md` — file/asset inventory (1,108 tracked Electron files; 39 wallpapers + icons/announcements/assets already byte-identical in Java resources), safe-to-delete and never-delete lists, dangling-reference checklist, single-window note | Nothing deleted; snapshot recorded as `8c06405`. Needs reviewer approval of P3.1–P3.5 first |
| **P3.7 Final package, measure, report** | 🟡 report written, startup measured; final freeze pending | This document; measured build: `release/Gravaai-linux-x64.AppImage` (06:36, 121,219,576 B, includes the 2026-10-28 fix set) | §7 filled with 5 idle runs (mean **677 ms**, PASS ≤800) and 5 editor-open runs (window visible 740–953 ms, warm mean 757 ms; preview ready 1.67–2.29 s — known issue). Still pending: rebuild the final AppImage after P3.2b/recording freeze and re-run the locked full suite, regenerate `java/parity/ui-java/` after the single-window change, and the §6 manual gate |

---

## 2. Deviations from the plan

Each deviation names the decision rule used. Items carried from P1/P2 are referenced, not repeated.

| Area | Deviation | Reason / decision rule |
|---|---|---|
| **Single-window architecture** | The dashboard, editor and meeting view share **one primary stage**; only the HUD overlay/menu, countdown, capture picker and area border are separate OS windows. The plan described windows by subsystem (§5.4) and Electron had one main window + HUD overlays. | Lead policy 2026-10-28 after the user reported pop-up windows; matches Electron's `mainWindow` and plan §4.1 ("one process, no web engine"). Evidence: `AppContext` primary-stage hosting, `EditorWindow.createIn`, `EditorWindowHostedTest#hostsEditorInMainWindow`, `README` note, commit under work (uncommitted). `EditorWindowTest#opensEditorWindow` still opens a standalone stage for the legacy gate; the hosted path is the new default |
| **GraalJS 25 bean-property interop shim** | `services.extensions.ExtensionHostInterop` installs property proxies so `ctx.width`/`ctx.ctx.fillStyle` behave as documented. | GraalJS 25 polyglot host objects do not expose Java records/beans as JS properties the way the Electron extension docs assume; shim keeps `EXTENSIONS.md` API semantics (plan §5.18). Covered by the extension tests |
| **jqwik pinned to 1.9.3** | `java/pom.xml` pins `jqwik.version=1.9.3` instead of the newest 1.10.x. | 1.10.x ships an upstream AI-directed notice ("jqwik Anti-AI Affair"); 1.9.3 is the last clean line (comment in `java/pom.xml:42-45`). Rule: no prompt-injection-style dependency content |
| **`java/` stays a subdirectory** | Repo keeps the Maven reactor under `java/` instead of moving modules to the repo root. | Plan §7.3 P3.6 leaves this to the reviewer; keeping the path while Electron removal is pending avoids rewriting every doc/tool/CI path. Decision recorded in the removal plan; the repo must still build the AppImage with `cd java && ./mvnw -T1 -Pdist package` |
| **Preview audio via bundled ffmpeg CLI, 16-bit PCM** | Preview decodes with `ffmpeg -f s16le` converted to float; export keeps the float graph. Plan §5.7 prefers the in-process AVFilterGraph. | The bundled `--disable-everything` FFmpeg enables `pcm_s16le` but **not** `f32le`; requesting `f32le` produced silent preview in the packaged app (user report). CLI keeps preview audio available; the in-process filter graph remains the §5.7 remainder. Evidence: `FfmpegCliPcmSource` + `FfmpegCliPcmSourceTest#decodesAudioFixture` (peak > 0.01), `PlaybackAudioProducerTest#appliesClipTransitionFadeGain` |
| **Autosave not ported** | Java saves on explicit Save/Save As and prompts on unsaved close; Electron's debounced autosave/untitled-project creation was not ported. | Behaviour parity gap (sweep gap A2), **not yet an approved deviation**: plan §5.11 says "autosave/recovery (whatever exists today)". Reviewer must either accept this as a deviation or schedule the port |
| **Webcam crop control without live frame** | Inspector crop editor works from stored crop math/gestures; no live webcam frame in the inspector; crop-matched height auto-match deferred. | Inspector has no webcam frame source in JavaFX; a live frame would need another decoder path. `WebcamCropMathTest` / `WebcamCropControlTest` cover the maths and gestures; visual crop-in-context is manual |
| **Raw-card hover preview uses the compositor** | Dashboard raw recordings preview through the composited frame rather than playing the source video directly. | Consistent wallpaper/cursor/edit state with the editor preview, one decoder path; `ProjectCardHoverPreviewTest#rawCardHoverPreview` |
| **Smoke export as settings overrides, not a dev IPC channel** | `Mp4ExportSettings` reads smoke URL/format overrides from settings; the Electron dev smoke-export IPC route is gone. | Single process has no IPC; the dev smoke path only needs deterministic settings. `Mp4ExportSettingsTest#smokeOverridesMenu`, `#partialSmokeFallsBack` |
| **Meeting library/settings windows behind dev env flags** | `GRAVAAI_MEETING_LIBRARY`, `GRAVAAI_MEETING_SETTINGS`, `GRAVAAI_MEETING_OPEN` open meeting views for gates/screenshots. | Single-window hosting means the dashboard embeds the views; the flags are dev/gate entry points only (`MeetingHosts`, `AppContext`). Open question for the reviewer: keep or drop after P3.6 |
| **Golden suite enabled with recorded baselines** | The P2.4 acceptance deferred full-frame parity (`@Disabled`, plan thresholds against preview-capture references). Now the suite is enabled as a **regression baseline** (per-case floors, never loosened silently). | Lossless references + measured baselines (commit `d180df7`); plan thresholds cannot be met against preview screenshots without emulating preview CSS (documented in `java/parity/golden/parity-deviation.md`). The decision and the floors are explicit, not silent |
| **UI test display isolation** | Maven surefire runs UI tests on `:99` via `-Dgravaai.test.display=:99` (pom property), not `:0`. | Lead policy 2026-10-28: never open test windows on the user's desktop. Documented in `java/README.md` |

---

## 3. Appendix A/B status for Phase 3 scope

P1/P2 rows are in `phase-1-report.md` §"Appendix A/B status" and `phase-2-report.md` §3; the
P3.5 sweep is the behaviour-level record for all 29 Playwright specs. P3-scope rows:

| Appendix B source | Java | Status |
|---|---|---|
| `electron/ipc/captions/*`, `register/captions.ts` | `services.captions`, `app.editor.AutoCaptionController`, captions inspector + Transcript panel | ✅ ported; real-whisper text parity manual (TODO) |
| `electron/agent/*`, `register/agent.ts` | `services.agent` + `app.McpMain` / `app.agent` + `ui.agent` | ✅ ported + tested; **skill loop PASS** (`phase-3-skill-loop-evidence.md`); residual tool gaps listed there §7 |
| editor extension host + `ExtensionManager.tsx` | `services.extensions` (GraalJS, canvas, hooks), `ui.editor.ExtensionManager`, `AppContext` preview/export wiring | ✅ code + render-hook tests; manager UI manual |
| `src/components/permissions/*` (Linux parts) | `ui.dashboard.RecordingPermissionsSettings` | ✅ ported + `RecordingPermissionsSettingsTest` |
| `src/components/announcements/*`, `src/content/*` | `ui.dashboard.DashboardAnnouncementsView`, `ui.editor.Announcements`/`EditorAnnouncementBanner` | ✅ ported + `AnnouncementsTest`; banner rendering in snapshot/manual |
| custom fonts (P3.4) | `ui.editor.CustomFonts` / `AddCustomFontDialog` | ✅ `CustomFontsTest`; dialog in snapshot |
| tutorial help (P3.4) | `ui.editor.TutorialHelpDialog` | ✅ exists; no test (manual) |
| smoke export automation (dev) | `media.export.Mp4ExportSettings` smoke overrides | ✅ settings-override path tested; UI manual |
| `tests/ui/*.spec.ts` | `phase-3-parity-sweep.md` | ✅ all 29 specs accounted (mapped/manual/N/A); 2 behaviour gaps + 9 untested UI spots |
| Electron tree/`public` assets, configs, `scripts/` | `phase-3-electron-removal-plan.md` | 🟡 plan only, nothing deleted; wallpaper/icons/announcement assets already byte-identical in Java resources |
| `public/wasm/**` | N/A | N/A — browser-only, no desktop path uses it |
| everything in plan §2.3 (macOS/Windows/browser) | N/A | N/A per plan decisions |

Remaining manual appendix items are the P1/P2 manual gates (§6) plus the sweep's 11 manual
bullets (HUD visuals, header chrome, popovers, compact layouts, hover cover-fit, etc.).

---

## 4. Test results

Source: latest full reactor run, surefire XML under
`/media/jmarceno/scratch/java-targets/gravaai-{module}/surefire-reports` (files written
**06:39–06:41, 2026-10-28**). Parsed directly from the XML `tests/failures/errors/skipped`
attributes — no numbers were invented.

| Module | Tests | Failures | Errors | Skipped | Notes |
|---|---|---|---|---|---|
| `gravaai-core` | 323 | 0 | 0 | 0 | includes new webcam-crop tests |
| `gravaai-platform` | 68 | 0 | 0 | 3 | `PlatformSmokeTest` COSMIC X11/tray/FileManager checks (environment-gated) |
| `gravaai-media` | 487 | 0 | 0 | 3 | golden diagnostics/render dump/parity-table generators (write artifacts, not assertions) |
| `gravaai-services` | 609 | 0 | 0 | 1 | `ProjectExportTest#encodesAcceptanceFixture` (long fixture export, gated) |
| `gravaai-ui` | 438 | 0 | 0 | 0 | TestFX on `:99`; snapshots refreshed 06:41 in `java/parity/ui-java/` |
| `gravaai-app` | 30 | 0 | 0 | 1 | `RealProjectCompareDumpTest` dump tool (no assertion) |
| **Total** | **1,955** | **0** | **0** | **8** | 334 test classes in the tree |

Caveats (honest):

* The run predates the last `AgentWiring` edit (06:43) and any P3.2/P3.4 work after 06:41; the
  reactor is green **at that snapshot**, not necessarily at freeze. Re-run before P3.6:
  `cd java && ./mvnw -T1 test` (UI tests use `:99`).
* The regression tests added with the 2026-10-28 fix set (single-window, mic crash, preview
  audio, timeline playhead) **are included** in this run: `EditorWindowHostedTest`,
  `EditorTimelinePlayheadTest`, `MicLevelMeterServiceTest`, `FfmpegCliPcmSourceTest`,
  `PlaybackAudioProducerTest`; the P3.2 view tests (`AgentEditorViewsTest`) and `AppIconsTest` are
  in the same run.
* Golden suite is enabled (`GoldenFrameSuiteTest`), not part of the 3 media skips; skipped media
  tests are the diagnostic generators listed above.
* The module counts given in the shared task (core 322, media 484, services 607, app 30) were
  pre-fix estimates; the table above is the latest green artifact (323/487/609/30).

---

## 5. Golden/UI parity

**Golden frames.** Suite enabled: `java/parity/golden/manifest.json` (10 cases / 36 references),
rendered through the production compositor. Two gates:
`GoldenFrameSuiteTest#rendersFixturesAtReferenceSize` (dimensions + non-blank) and
`GoldenFrameSuiteTest#goldenFramesMatchReferences` (per-case recorded floors in
`java/parity/golden/baseline.json`). The floors are explicit and must not be loosened silently;
the plan thresholds (`meanAbsDiff <= 2.0`, `<= 0.5%` pixels) are still not met against the
lossless **preview-screenshot** references — justified and documented in
`java/parity/golden/parity-deviation.md` (preview CSS scaling, SF Pro vs bundled DM Sans, effect
spread). Extension hooks do not change frames when none are installed:
`CompositorRenderHooksTest#noHooksIsByteIdentical` and
`ExtensionRenderHookPipelineTest#noExtensionsIsByteIdentical`; the example extension draws on top
in preview and export (`ExtensionRenderHookPipelineTest`).

**UI parity.** `docs/java-rewrite/phase-1-ui.html` (30 screens) and
`docs/java-rewrite/phase-2-ui.html` are the side-by-side galleries; JavaFX captures in
`java/parity/ui-java/` were refreshed at 06:41 (editor shell/settings/dialogs, dashboard,
timeline/timeline-captions, transcript/transcript-cut, meeting, agent settings, crop). The
behaviour-level UI parity record is `phase-3-parity-sweep.md` (29 specs). **TODO:** a Phase-3
gallery page and the final capture set are not regenerated after the single-window changes.

---

## 6. Manual checklist

Open human gates carried by the plan; all are to run on the packaged AppImage on COSMIC, never on
`:0` for tests.

| Gate | Item | Status | Evidence / note |
|---|---|---|---|
| P1 | HUD drag by the dots (`_NET_WM_MOVERESIZE`), tray menu/panel | open | phase-1 manual gate §2/§3; synthetic X11 check only |
| P1 | Real source picking via COSMIC portal, countdown, area border click-through | open | phase-1 manual gate §4; service tests only |
| P1 | Real webcam recording + mic/system audio outputs, diagnostics | open | phase-1 manual gate §5 |
| P1 | Meetings record→transcript→notes (local + API path), model downloads | open | phase-1 manual gate §7 (API-key safeStorage gap documented) |
| P1 | Global shortcuts + control socket from another window | open | phase-1 manual gate §8; control-socket tests green |
| P1 | Live automation run (recording-saved → action) | open | phase-1 manual gate §9; engine tests green |
| P2 | 2-minute MP4 playback/A-V sync in `mpv`, GIF export | open | phase-2 report §6/§8 |
| P2 | 5 real projects side-by-side, undo/redo across commands | open | phase-2 report §6 |
| P2 | Idle re-measure of the editor startup budget | open | phase-2 report §8; §7 TODO here |
| P3 | **Real COSMIC recording end-to-end** (HUD → portal/source pick → capture → stop → editor) | **in progress** | being diagnosed (see §8); user report 2026-10-28 |
| P3 | **MCP skill loop** (read → plan → edit → look → export against the Java app) | **done — PASS** | `phase-3-skill-loop-evidence.md` (real packaged app + `--mcp`, `:99`; export validated with `ffprobe`; residual tool gaps in its §7) |
| P3 | Example extension in preview + export | done by tests | `ExtensionRenderHookPipelineTest` (both composites) |
| P3 | Parity-sweep manual spots | open | `phase-3-parity-sweep.md` Gaps A1–A3, B1–B9: Space shortcut, autosave decision, webcam-preview resize, header rename, Clips panel, wallpaper gallery, popover dismissal, HUD view internals, source-list view, focus guard, paused-motion combinations |

### 2026-10-28 user-reported fixes (verified in tree + latest green run)

| Report | Fix | Evidence |
|---|---|---|
| Pop-up windows when opening editor/dashboard/meeting | Single primary stage hosts dashboard, editor and meeting view; `EditorWindow.createIn` + `AppContext` scene swap, resources released on return; only HUD/picker/countdown/area border are separate stages | `EditorWindowHostedTest#hostsEditorInMainWindow` ("hosting the editor swaps the main window scene and creates no second window"); `README` single-window note; latest green app suite includes it |
| Microphone crash (HUD mic meter / switching microphones) | Pulse record stream moved to a shared arena; `read`/`close` serialized; `MicLevelMeterService` uses a one-session close guard so the native stream is never freed under a blocked read | `MicLevelMeterServiceTest#switchingMicrophonesIsSafe` ("switching microphones replaces the session without double-closing") and `MicLevelMeterServiceTest#closeWaitsForBlockedReadBeforeFreeing` ("closing during a blocked read never frees the stream under the reader"); latest green services suite |
| No preview audio in the packaged app | Preview PCM format fixed: bundled FFmpeg has `pcm_s16le` only (not `f32le`); source now decodes s16le → float, plus clip-transition fade gain in the preview producer | `FfmpegCliPcmSourceTest#decodesAudioFixture` ("decodes real audio from the bundled ffmpeg build (s16le muxer present)", peak > 0.01); `PlaybackAudioProducerTest#appliesClipTransitionFadeGain`; latest green media suite |
| Timeline playhead frozen while preview plays | `EditorSession.onPlayhead` subscribes to the engine time; `EditorWindow` coalesces it onto the FX thread and calls `TimelineView.setCurrentTimeMs` | `EditorTimelinePlayheadTest#seekDrivesTimelinePlayhead` ("an engine seek drives the timeline playhead"); latest green app suite |

The fixes are verified by the green suite above; they have **not** been re-verified on a freshly
packaged AppImage in this report (startup/package TODO below).

---

## 7. Startup numbers

Measured by the Lead on the 2026-10-28 fix build (AppImage
`release/Gravaai-linux-x64.AppImage`, 06:36, 121,219,576 bytes) on the isolated `:99` display with
a fresh profile per run (`java/tools/startup-check.sh`, mounted AppImage; `GRAVAAI_LAUNCHED_AT`
injected so the in-app `startup:` lines are wall-clock). This build includes the single-window,
microphone-crash, preview-audio and timeline-playhead fixes; P3.2b (`AgentWiring`) landed after it —
the numbers must be re-taken once on the final frozen AppImage (TODO). The AOT cache is accepted
(no `[aot] error` lines; the exit-after-ms hook logs "AOT training" only because the timer doubles
as the training exit).

**Idle (HUD) startup — 5 runs, budget: window visible ≤ 800 ms**

| Run | Window shown (ms) | First preview frame (ms) | Notes |
|---|---|---|---|
| 1 | 678 | n/a (HUD path logs none) | fresh profile, mounted AppImage |
| 2 | 739 | n/a | |
| 3 | 665 | n/a | |
| 4 | 640 | n/a | |
| 5 | 663 | n/a | |
| mean | **677 (PASS)** | — | median 665 |
| median | 665 | — | |

**Editor-open startup — 5 runs, same build (`GRAVAAI_EDITOR_OPEN` = 01-wallpaper-blur fixture)**

| Run | Window shown (ms) | Editor preview ready (ms) | Notes |
|---|---|---|---|
| 1 | 953 | 2294 | first run after rebuild (cold page cache) |
| 2 | 751 | 1858 | |
| 3 | 740 | 1669 | |
| 4 | 757 | 1843 | |
| 5 | 778 | 1766 | |
| mean | **796 (PASS, warm mean 757)** | 1886 | budget: window visible ≤ 800 ms; preview ≤ 900 ms is not met, see below |

Note: the 800 ms budget is about the **window being visible**; it passes on the warm runs and is
borderline on the first cold run (953 ms). First preview frame (1.67–2.29 s) exceeds the phase-2
baseline expectation and is recorded as a known issue (§8); it is dominated by media attach and
first compositor frame, not by window construction.

**TODO after the final freeze:** re-run both tables on the frozen AppImage (P3.2b/recording changes
included) and regenerate the packaged-app captures (`java/parity/ui-java/`) after the single-window
change.

---

## 8. Known issues and open questions for the reviewer

1. **COSMIC recording end-to-end is still being diagnosed** (user report 2026-10-28). Remaining
   work: the source-picker chain on COSMIC (HUD → native/portal picker → capture start), real
   capture start/stop, and the stop→import→editor path. There is no automated end-to-end test;
   service/source tests cover the pieces. This blocks the P3 acceptance "record on COSMIC" gate.
2. **Parity gaps from P3.5** (details + manual steps in `phase-3-parity-sweep.md`):
   * A1 — **Space play/pause is not wired** in Java: `Shortcuts.Action.PLAY_PAUSE` (default `" "`)
     exists and is editable/persisted, but no key handler consumes it.
   * A2 — **No autosave**: explicit save + unsaved-close prompt only; decide port vs recorded
     deviation (plan §5.11).
   * A3 — **HUD webcam preview parity**: Java resizes from one bottom-right handle with a fixed
     320×240 default and no persistence; React used four corners, 288×288, localStorage + storage
     fallback.
   * B1–B9 — implemented but untested: header inline rename, Clips panel, wallpaper gallery,
     annotation/audio ripple (only zoom asserted), export/popover dismissal, HUD view internals,
     source-list view, inspector focus guard, paused-motion combinations.
3. **P3.1 acceptance not yet demonstrated**: real-whisper captions compared with Electron output
   text on a fixture recording, and model download over the network. The pipeline, parser,
   segmentation and controller are tested with fixtures (`caption-speech.mp4`); the comparison is
   a manual TODO.
4. **P3.2 skill loop is verified PASS** (`phase-3-skill-loop-evidence.md`), including an
   `ffprobe`-validated export and frame-diff evidence. Residual gaps to decide (that doc §7):
   `project close` unsupported, agent `transcribe` job not wired, `auto_zoom` cannot use cursor
   telemetry, multi-frame contact sheets are one PNG per time, and the agent export path uses
   `FrameOverlayHooks.NONE` (no extensions in the loop profile). `AgentWiring` changed after the
   last green reactor run, so the full suite still needs one re-run.
5. **Golden parity remains a documented deviation**: baseline regression gate is green, but the
   plan thresholds against preview-screenshot references are not met. Do not loosen baselines
   without a reviewer decision; `parity-deviation.md` is the justification.
6. **P3.6 (Electron removal) not executed**: blocked on reviewer approval of P3.1–P3.5. The plan
   is ready (`phase-3-electron-removal-plan.md`), including the asset table (39 wallpapers
   byte-identical, announcement assets, icons), the 1,108 tracked files, and the never-delete
   list. Follow-up work after deletion: `README`/`CONTRIBUTING`/`THIRD_PARTY_NOTICES`/
   `TRANSLATION_GUIDE` updates, `.github` workflows manual-only, root `java/*` decision.
7. **Phase-3 UI gallery and final packaging**: the gallery was not regenerated after the
   single-window change, and the AppImage (06:34) predates the P3.2 edits. Rebuild with
   `cd java && ./mvnw -T1 -Pdist package` (sanctioned locked runner) and re-run the parity
   captures before P3.6 approval.
8. **Open questions**: (a) keep or drop the meeting dev env flags after single-window hosting;
   (b) whether the hosted editor should still expose a separate "editor window" mode for the
   legacy gate/test `EditorWindowTest`; (c) autosave deviation (A2); (d) Space shortcut (A1)
   and HUD webcam preview (A3) scope for P3.
9. **Test-environment policy**: UI tests default to `:99` via the Maven property; do not run them
   on `:0`. The latest suite numbers in §4 are from `:99` runs.

---

### TODO for the Lead (explicit)

1. §7 startup table — 5 idle AppImage runs (window shown + first preview frame), AOT/size.
2. Rebuild the AppImage after P3.2/P3.3/P3.4 freeze; re-run the full locked suite and update §4
   numbers if they change.
3. Regenerate the Phase-3 UI gallery (and `phase-2-ui.html`-style side-by-side) after the
   single-window change.
4. Approve P3.6 (Electron deletion) or send back; the removal plan is ready.
5. Decide login items: autosave deviation (A2), Space play/pause port (A1), HUD webcam preview
   resize (A3), meeting dev env flags.
6. Review `phase-3-skill-loop-evidence.md` (loop PASS) and decide its five residual agent gaps
   (`project close`, transcribe job, auto_zoom telemetry, contact sheet, extension hooks).
7. Run the recording end-to-end diagnosis on COSMIC and record the result in §6.
8. Run the P1/P2 manual gates on the final AppImage and record results/screenshots.
