# Phase 3 parity sweep — `tests/ui/*.spec.ts` → Java evidence

Status: **complete for review** (P3.5). Written 2026-10-28 against the working tree while other agents
were landing P3 work; `java/` was being edited concurrently (see *"What this sweep could not verify"*).

Scope: all **29 Playwright spec files** in `tests/ui/` (**104 `test()` declarations**, 106 runtime
cases once the two `for` loops expand), each mapped to a Java test class + method (`@DisplayName`), a manual check, or an
explicit ⛔ N/A reason. Plan: `docs/java-rewrite-plan.md` §7.3 P3.5 / §8. Evidence conventions follow
`docs/java-rewrite/phase-1-report.md` and `phase-2-report.md`.

Method: titles extracted from the specs with a script and grouped into behaviour bullets; Java
evidence found by searching the tree's **331 test classes / 1,882 `@Test` methods** (`@DisplayName`
index built from `java/*/src/test/**`, then grep by topic). Every `TestClass#method` cited below was
checked against the tree; nothing was run (read-only sweep, other agents building). Manual steps
that need a display are written for the isolated `:99` display or the packaged AppImage per the
Lead's display policy.

Legend:

* ✅ **mapped** — a Java behaviour test covers the same behaviour.
* 🟡 **mapped + manual** — core logic is tested; the JavaFX/visual part is a manual check.
* 🔵 **manual** — no automated Java equivalent; a concrete manual step is given.
* ⛔ **N/A** — intentionally not ported (reason given).
* ⚠ **gap** — no Java equivalent and no N/A reason; listed in [Gaps found](#gaps-found).

**Window expectations note (Lead architecture update).** The target Java architecture is a **single
primary window** that hosts the dashboard and the editor (matching Electron's one `mainWindow`), with
only HUD, capture picker, countdown and update/overlay stages as separate windows. Playwright cases
that boot a `?windowType=editor|home`, switch startup wireframes, or assert separate editor/dashboard
windows are therefore read against that single-window expectation; where the current tree still
opens a separate dashboard window this is marked "in flight" rather than mapped.

---

## Summary

| # | Spec (tests) | Status | Behaviours | Key Java evidence | Notes |
|---|---|---|---|---|---|
| 1 | `automations.spec.ts` (3) | 🟡 mapped+manual | 3 | `AutomationServiceTest#runsOnFile`, `AutomationsTest#templatesAreUsable`, `AutomationStoreTest#writesNormalised` | Automations settings panel flow is manual; engine/store fully tested |
| 2 | `block-deletion.spec.ts` (8) | 🟡 mapped+manual | 4 | `TimelineViewTest#deletesSelectedClipsWithKeys`, `ZoomCommandsTest#deletesZoom`, `AudioCommandsTest#deletesAudioRegion`, `CaptionCommandsTest#retimesSplitsMergesDeletes`, `EditorRailSelectionTest#railClickClearsSelections` | Typing-in-input guard and inspector buttons manual |
| 3 | `camera-permissions.spec.ts` (1) | 🟡 mapped+manual | 1 | `WebcamRecordingServiceTest#namesTheWebcamFileNextToTheRecording`; `HudOverlayWindow` gating code | Editor-mode gating is Electron-shell-specific ⛔; camera lifecycle manual |
| 4 | `caption-speed.spec.ts` (1) | 🟡 mapped+manual | 4 | `CompositorCaptionTest#drawsActiveCaption`, `TranscriptTimeMapTest#mapsSourceToTimeline`, `CaptionListPanelTest#editsTextWordAware` | Inline preview caption editor + selection colour manual |
| 5 | `capture-picker.spec.ts` (17) | 🟡 mapped+manual | 5 | `CapturePickerGeometryTest#findsTheFrontmostWindow`, `CapturePickerServiceTest#opensAFreshPickerEvenWhenAnAreaIsSelected`, `SourceServiceTest#doesNotOpenTheSelectorDuringRecording` | Picker geometry/service tested; HUD label/toast/disabled states manual |
| 6 | `clip-captions-and-background.spec.ts` (2) | 🟡 mapped+manual | 3 | `ClipPresentationTest#reservesSeam`, `#clipsCaptionPreviews`, `TimelineViewTest#addsCaptionFromEmbeddedGhost` | Background section fade animation manual |
| 7 | `clip-cut-delete.spec.ts` (1) | ✅ mapped | 1 | `ClipCommandsTest#selectsMiddleSectionAfterTwoCuts`, `StoreTimelineAdaptersTest#twoCutsThenMiddleDelete` | Also playback/source mapping |
| 8 | `clip-origin.spec.ts` (1) | 🟡 mapped+manual | 2 | `TimelineViewTest#keepsLoneClipPackedAtZero`, `StoreTimelineViewTest#loneClipStaysPackedInStore` | Saved-payload + export-dialog padding manual |
| 9 | `clip-sequence.spec.ts` (3) | 🟡 mapped+manual | 3 | `TimelineDndEngineTest#insertsAAfterB`, `#allowsRestoringTrimmedClip`, `StoreTimelineAdaptersTest#reordersClips` | Annotation/audio ripple implemented but only zoom is asserted (gap B4) |
| 10 | `clips-polish.spec.ts` (2) | 🟡 mapped+manual | 2 | `TimelineModelTest#buildsVariantLabels`, `AnnotationOverlayControllerTest#dragCommitsPosition` | Width-conditional zoom paint manual |
| 11 | `controls.spec.ts` (1) | ⛔ N/A | 1 | `ThemeCssTest#tokensDeclared` (styling only) | HeroUI is a React library; JavaFX uses native controls |
| 12 | `desktop-windows.spec.ts` (3) | 🟡 mapped+manual | 3 | `CountdownServiceTest#cancelsActiveCountdownImmediately` | Update toast ⛔ (no Java updater); compact layout manual |
| 13 | `editor-layout.spec.ts` (4) | 🟡 mapped+manual | 4 | `EditorPreferencesTest#loadsStoredControlPreferences`, `ZoomCommandsTest#addsAndSelectsZoom` | Header breadcrumb/rename manual (rename untested, gap B1) |
| 14 | `editor-refinements.spec.ts` (3) | 🟡 mapped+manual | 3 | `TimelineViewTest#drawsFilmstripThumbnails`, `CompositorFilmstripProviderTest#rendersFrameAndDisposes` | Popovers/dialog dismissal manual |
| 15 | `editor.spec.ts` (2) | 🟡 mapped+manual | 2 | `EditorWindowTest#opensEditorWindow`, `EditorSnapshotTest#rendersEditorShell`, `GifExportSettingsTest#loopMapping` | Theme/tool/export-dialog visuals manual |
| 16 | `hud-layout.spec.ts` (5) | 🟡 mapped+manual | 5 | `RecordingTimerTest#formats`, `#pauseAccounting`, `MotionTest#durations` | HUD view internals untested; webcam resize differs (gap A3) |
| 17 | `linux-capture.spec.ts` (4) | 🟡 mapped+manual | 4 | `CapturePickerSourcesTest#keepsLinuxWindowSelectionOnItsSourceList`, `SourceServiceTest#waylandStartsUnselectedAndPreparesThePortalOnRequest` | Source-list window UI and COSMIC consent manual |
| 18 | `paused-motion.spec.ts` (3) | 🟡 mapped+manual | 3 | `PlaybackEngineTest#pausedSeekRendersOneFrame`, `CompositorMotionBlurTest#zoomStepProducesRadialBlur` | Paused-seek→resume and scrub+paused-blur combinations manual |
| 19 | `playback-shortcut.spec.ts` (1) | ⚠ gap | 1 |none | Space handler missing in Java (gap A1) |
| 20 | `project-dashboard.spec.ts` (12) | 🟡 mapped+manual | 10 | `DashboardLibraryFiltersTest#filtersBySearchAndSort`, `DashboardModelTest#persistsFolders`, `ProjectCardHoverPreviewTest#hoverMountsAndExitUnmounts` | Autosave is a gap (A2); pixel layout manual |
| 21 | `recording-startup-cancellation.spec.ts` (1) | 🟡 mapped+manual | 2 | `SourceServiceTest#doesNotChangeSourcesIfRecordingStartsDuringValidation`, `CountdownServiceTest#cancelsActiveCountdownImmediately` | Native warm-up discard untested |
| 22 | `startup-loading.spec.ts` (8) | ⛔ N/A | 1 | `EditorSkeletonWindow` (code, not a test) | Electron/JS boot wireframes do not exist in Java |
| 23 | `timeline-gap-snapping.spec.ts` (3) | ✅ mapped | 3 | `TimelineViewTest#resizesZoomEndSnappingToCut`, `#zoomDragSnapsEndKeepingDuration`, `ClipPresentationTest#softSnapsWithinEightPixels` | |
| 24 | `timeline-interactions.spec.ts` (1) | 🟡 mapped+manual | 1 | `TimelineViewTest#rendersEveryRow`, `#zoomLaneHeightAdaptsToExtraLanes`, `TimelineLayoutTest#fitsMinimumViewport` | Volume popover/playhead cap visuals manual |
| 25 | `timeline-presentation.spec.ts` (2) | 🟡 mapped+manual | 2 | `TimelineViewTest#drawsFilmstripThumbnails`, `TimelineModelTest#exposesClipSpeed`, `PreviewVideoReadinessTest#becomesReadyAfterEverySourceChange` | Skeleton/rail centering manual |
| 26 | `transcript-cut.spec.ts` (1) | ✅ mapped | 1 | `TranscriptPanelTest#generatesCaptions`, `#selectsAndSeeksWords`, `#marksActiveWord`, `#cutsWordAndUndoes`, `#cutsFillerWords` | |
| 27 | `videos-library.spec.ts` (8) | 🟡 mapped+manual | 7 | `RecordingImportControllerTest#commitsOneImport`, `#addsAutoZooms`, `#cancelAfterCompletedImportStillCommits`, `RecordingLibraryTest#listsRecordingsAndStagesReversibleRemovals` | Clips panel UI untested (gap B2); URL-retry ⛔ |
| 28 | `wallpaper.spec.ts` (2) | 🟡 mapped+manual | 2 | `PreviewBackgroundsTest#bundledImage`, `ProjectFrameInputFactoryTest#videoWallpaperLoops` | Gallery add/remove/persist manual (gap B3) |
| 29 | `webcam-defaults.spec.ts` (1) | 🟡 mapped+manual | 1 | `PreviewFrameMapperTest#webcamSpecFromFixture`, `WebcamOverlayTest#cornerRadius`, `ProjectPersistenceNormalizerTest#migratesLegacyWebcamRadius` | Saved-69 preference path untested |

Totals: the 104 declared test cases (106 runtime titles) group into **84 behaviour bullets** — **31 ✅ mapped,
36 🟡 mapped+manual, 11 🔵 manual, 4 ⛔ N/A** (the eight `startup-loading` titles collapse into one
N/A group), **2 ⚠ gaps**, plus 9 implemented-but-untested behaviours in [Gaps B](#b-implemented-but-untested-coverage-gaps--add-tests-no-code-change-expected).

---

## Spec-by-spec mapping

Each bullet is one behaviour group from the spec. Citations are `TestClass#method` as they exist in
`java/*/src/test/**` (`@DisplayName` quoted where the method name is not self-explanatory). "Manual"
steps are written to be runnable on the packaged AppImage (phase-1 gate conventions) or on `:99`.

### 1. `automations.spec.ts` (3 tests) — 🟡 mapped + manual

* Add a template, edit name/condition, run the rule on a file → ✅ `AutomationServiceTest#runsOnFile`
  ("runAutomationOnFile runs the rule on the chosen file"), `AutomationsTest#templatesAreUsable`,
  `AutomationStoreTest#writesNormalised`.
* A pending edit is saved before "Run now" (debounce flush) → 🟡 the service reads the persisted
  store (`AutomationServiceTest#runsOnFile`) but the panel's flush-before-run is UI code with no test:
  manual = Dashboard → Settings → Automations → New rule → rename → immediately Run now on a file…
  and assert the new name is used.
* Load saved rules, validation hints, save-failure alert, delete confirmation → 🟡
  `AutomationsTest#ruleIssues` ("automationRuleIssues reports issues the UI should surface"),
  `AutomationServiceTest#refusesDisallowedAction`, `AutomationStoreTest#emptyForCorruptJson`;
  alerts/dialogs manual.

### 2. `block-deletion.spec.ts` (8 tests) — 🟡 mapped + manual

* Clip keyboard delete (Backspace/Delete) with undo → ✅ `TimelineViewTest#keyboardSplitsAndDeletes`,
  `TimelineViewTest#deletesSelectedClipsWithKeys`,
  `StoreTimelineAdaptersTest#undoesAndRedoesDeletion`, `EditorHistoryTest#movesSnapshotsThroughUndoAndRedo`.
* Delete a zoom after using its inspector; audio after inspector focus; caption after the playhead
  leaves its span → ✅ `ZoomCommandsTest#deletesZoom`, `AudioCommandsTest#deletesAudioRegion`,
  `CaptionCommandsTest#retimesSplitsMergesDeletes`, `CaptionListPanelTest#deletesSelectedCue`,
  `StoreTimelineAdaptersTest#deletesSelectedKind`.
* Annotation deletion without deleting while typing + delete from its inspector → 🟡
  `AnnotationCommandsTest#updatesFigureAndBlurThenDeletes`; focus guard manual = add an annotation,
  type in its text field, press Backspace (annotation must survive), then use Delete Annotation.
* Inspector Delete buttons and tool navigation leaving the annotation inspector → 🟡
  `EditorRailSelectionTest#railClickClearsSelections` proves the rail clears clip/zoom/annotation/
  audio/caption selection; the buttons themselves are manual (display :99 / desktop).

### 3. `camera-permissions.spec.ts` (1 test) — 🟡 mapped + manual

* Editor mode never opens the camera, an active recorder preview is released, and a `devicechange`
  does not reopen it → 🟡 Java opens the camera only for a recording: HUD preview requires
  `recording() && webcamEnabled() && floatingWebcamPreview()` (`HudOverlayWindow`),
  `WebcamRecordingServiceTest#namesTheWebcamFileNextToTheRecording`,
  `WebcamRecordingServiceTest#buildsTheEncoderCommand`; the Electron "editor mode" IPC gate itself is
  shell-specific → ⛔ N/A. Manual = open editor with webcam enabled, check `lsof /dev/video*` is
  empty; start a recording and stop it (camera released), then unplug/replug the camera.

### 4. `caption-speed.spec.ts` (1 test) — 🟡 mapped + manual

* Captions render in the preview at the active cue → ✅ `CompositorCaptionTest#drawsActiveCaption`,
  `CompositorCaptionTest#hidesInactiveCaptions`.
* Cues stay synchronised when clip speed is 1x / 2x / 4x (spans retime, seek to cue midpoint shows
  the caption, outside its span it disappears) → ✅ `TranscriptTimeMapTest#mapsSourceToTimeline`
  ("maps source words to the timeline at 1x, 2x and 4x"),
  `TranscriptTimeMapTest#mapsTimelineToSource`, `CaptionTimelineTest#retimesWithinClip`,
  `PlaybackEngineTest#rateScalesTimelineAdvance`.
* Inline caption editing in the preview (double-click focuses, Enter/Tab commits, Escape discards) →
  🟡 `CaptionListPanelTest#editsTextWordAware`, `CaptionListPanelTest#retimesFromTimecodeFields`; the
  preview-overlay editor is a manual check.
* Selected caption border uses the accent colour → 🔵 manual (visual style check).

### 5. `capture-picker.spec.ts` (17 tests) — 🟡 mapped + manual

* Hover highlights the frontmost window and a click picks it; desktop click picks the screen; a drag
  draws an area; Escape cancels; the selection has no recording toolbar → ✅
  `CapturePickerGeometryTest#findsTheFrontmostWindow`,
  `CapturePickerGeometryTest#findsNothingOverTheDesktop`,
  `CapturePickerGeometryTest#normalizesDragsThatGoUpOrLeft`,
  `CapturePickerGeometryTest#clampsRectanglesToTheOverlay`,
  `CapturePickerGeometryTest#resizesFromEdgesAndCorners`, `SourceAreaTest#roundTripsAnAreaRectangle`;
  the overlay UI itself is manual.
* Platforms without area capture fall back to a screen; a previously drawn area reopens in window
  picking; a drawn area can return to window/screen picking without closing → ✅
  `CapturePickerServiceTest#opensAFreshPickerEvenWhenAnAreaIsSelected`,
  `SourceAreaTest#clipsAnAreaToItsDisplay`, `SourceAreaTest#acceptsOnlyWindowsThePickerOffered`.
* HUD keeps/starts the selected source and opens the picker only on source/Record click; a newer
  selected-source event wins over the startup response; a failed pick shows a toast but cancellation
  stays silent; Record stays disabled while picking → 🟡 `SourceServiceTest#keepsExplicitSelectionWithinTheSession`,
  `SourceServiceTest#rejectsUnavailableSources`, `SourceServiceTest#doesNotChangeSourcesIfRecordingStartsDuringValidation`;
  HUD label/toast/disabled-state visuals manual.
* Changing source cancels the countdown and discards native warm-up before the picker opens → 🟡
  `CountdownServiceTest#cancelsActiveCountdownImmediately`,
  `CapturePickerServiceTest#rejectsAPendingSelectionIfRecordingStarts`; native-warm-up discard manual.
* Recording and pause expose no interactive source picker → ✅ `SourceServiceTest#doesNotOpenTheSelectorDuringRecording`,
  `CapturePickerServiceTest#doesNotOpenAnyPickerDuringRecording`; HUD hiding visual manual.

### 6. `clip-captions-and-background.spec.ts` (2 tests) — 🟡 mapped + manual

* Split clips share one 24 px seam grip, hide the inner resize handles, clicking the gutter seeks to
  the cut on the next clip, and trimming starts from the visible edge → ✅
  `ClipPresentationTest#reservesSeam`, `ClipPresentationTest#doesNotInsetSingleClip`,
  `ClipPresentationTest#jumpsPlayheadAcrossSplitGutter`,
  `ClipPresentationTest#seeksToCutFromEitherSide`, `TimelineViewTest#resizesClipEdge`.
* Captions live inside the filmstrip, follow the add-ghost target, keep their duration when dragged
  and truncate their text preview → ✅ `ClipPresentationTest#clipsCaptionPreviews`,
  `TimelineViewTest#addsCaptionFromEmbeddedGhost`, `StoreTimelineViewTest#addsCaptionFromStrip`,
  `CaptionTimelineTest#projectsReusedFootage`.
* Background panel swaps (Color/Gradient/Video/Image) use a section enter animation without sliding
  or overlap → 🟡 mapping is tested (`PreviewFrameMapperTest#sceneMapping`,
  `PreviewFrameMapperTest#colourAndGradientBackgrounds`), the JavaFX transition is manual.

### 7. `clip-cut-delete.spec.ts` (1 test) — ✅ mapped

* Two cuts select the middle clip; Delete removes it; the outer spans join; the playhead/source time
  mapping stays continuous and playback continues → ✅ `ClipCommandsTest#selectsMiddleSectionAfterTwoCuts`
  ("selects the middle section after two cuts so Delete removes it"),
  `StoreTimelineAdaptersTest#twoCutsThenMiddleDelete`,
  `ClipSplitsTest#keepsMappingContinuous`, `ClipsTest#mapsExactCut`,
  `PlaybackEngineTest#playAdvancesTimelineAndMapsSourceTimes`.

### 8. `clip-origin.spec.ts` (1 test) — 🟡 mapped + manual

* Clicking/dragging a lone clip and pressing its resize edge without movement keeps it at 0 and never
  trims source footage → ✅ `TimelineViewTest#keepsLoneClipPackedAtZero`,
  `TimelineViewTest#ignoresClipClickWithoutMovement`,
  `TimelineViewTest#ignoresResizePressWithoutMovement`,
  `StoreTimelineViewTest#loneClipStaysPackedInStore`.
* The saved project keeps `startMs: 0, endMs: 6000, sourceStartMs: 0` and the export dialog restores
  its padding → 🟡 save shape is covered by `ProjectDocumentTest#rewritesTypedFields` and
  `ClipsTest#mapsKeptTimelineTime`; the exact save payload + export-dialog padding are manual.

### 9. `clip-sequence.spec.ts` (3 tests) — 🟡 mapped + manual

* Left-trimming keeps both gutter edges on one timestamp, can reveal footage again, and the visible
  clip body maps to the full media span → ✅ `TimelineDndEngineTest#allowsRestoringTrimmedClip`,
  `#allowsRestoringFinalClip`, `#allowsRestoringFirstClipBeforeZero`,
  `ClipSpanChangesTest#advancesSourceInPoint`, `ClipSpanChangesTest#clampsAtSourceEof`,
  `ClipPresentationTest#roundTripsAllMediaTime`.
* Dragging the first clip past the middle inserts it between its neighbours and keeps the sequence
  packed → ✅ `TimelineDndEngineTest#insertsAAfterB`, `#insertsBBeforeA`,
  `#insertsMiddleClipAfterFinal`, `TimelineViewTest#dragsClipIntoSequence`,
  `StoreTimelineAdaptersTest#reordersClips`.
* Clip deletion + undo ripples connected annotations and imported audio together with the clips → 🟡
  `ClipCommands.applySequence` ripples zoom, annotation and audio regions and
  `StoreTimelineAdaptersTest#undoesAndRedoesDeletion` covers undo, but
  `ClipCommandsTest#packsSequenceAfterCut` only asserts the zoom ripple (gap B4: annotation/audio
  ripple untested).

### 10. `clips-polish.spec.ts` (2 tests) — 🟡 mapped + manual

* Narrow zoom blocks keep the `×` multiplier and hide the mode/icon until they are wide enough → 🟡
  `TimelineModelTest#buildsVariantLabels`, `TimelineViewTest#rendersEveryRow`; the width-conditional
  paint is a manual check (drag a zoom to 28 px / 180 px).
* An annotation dragged into the canvas background survives undo → 🟡
  `AnnotationOverlayControllerTest#dragCommitsPosition`,
  `AnnotationCommandsTest#changesAnnotationFields`,
  `EditorHistoryTest#movesSnapshotsThroughUndoAndRedo`; the combined drag+undo flow is manual.

### 11. `controls.spec.ts` (1 test) — ⛔ N/A

* HeroUI switch/select/slider/radio/tabs/dialog/menu/toast keep editing, focus, keyboard and overlay
  behaviour, with no page errors → ⛔ **N/A**: HeroUI is a React component library with ARIA/browser
  focus semantics; JavaFX uses native controls. Styling parity is the kit's job
  (`ThemeCssTest#tokensDeclared`, `#componentClassesDeclared`), and keyboard/focus smoke is manual on
  `:99`/desktop.

### 12. `desktop-windows.spec.ts` (3 tests) — 🟡 mapped + manual, 1 N/A

* Countdown can be cancelled from its button → 🟡 `CountdownServiceTest#cancelsActiveCountdownImmediately`,
  `CountdownServiceTest#settlesWhenTheWindowExits`; the countdown stage + button are manual
  (phase-1 gate §4).
* The update window fits the desktop toast and can be dismissed → ⛔ **N/A**: the Java app has no
  update UI/updater; packaging/updates are out of Phase 3 scope (plan §5.19).
* On a 1280×800 desktop the editor inspector and playback controls fit, and a motion preset can be
  selected → 🔵 manual layout check (per the single-window architecture note: one primary window).

### 13. `editor-layout.spec.ts` (4 tests) — 🟡 mapped + manual

* Advanced controls preserve values and remember each section's view; hex colour persists after
  Tab/Escape → ✅ `EditorPreferencesTest#loadsStoredControlPreferences`,
  `#savesAllEditorControls`, `#preservesLastValidCustomAspectInputs`; per-section view memory is a
  manual UI check.
* Header breadcrumb fits long names, native chrome offsets, two vertical separators, centred preview
  and compact widths → 🔵 manual; the window-chrome padding itself is Electron-specific ⛔.
* Header name edits in place and saves on blur, Escape discards → 🟡 implemented in
  `EditorHeaderView#startRename` → `actions.renameProject`, but there is no automated test
  (coverage gap B1); manual = click the project name, type, Tab, reopen; type, Escape.
* New zoom blocks default to 1.8× → ✅ `ZoomCommandsTest#addsAndSelectsZoom` (defaults to
  `ZoomDepth.THREE` = 1.8).

### 14. `editor-refinements.spec.ts` (3 tests) — 🟡 mapped + manual

* Clip filmstrip decodes distinct source frames, zoom blocks use the available height, no timeline
  axis → ✅ `TimelineViewTest#drawsFilmstripThumbnails`,
  `TimelineViewTest#requestsMissingFilmstripFrames`,
  `CompositorFilmstripProviderTest#rendersFrameAndDisposes`,
  `TimelineViewTest#zoomLaneHeightAdaptsToExtraLanes`; axis removal is visual/manual.
* Advanced controls, webcam defaults and captions have consistent layouts; buttons fit; upload/
  replace/remove stack without overlap → 🔵 manual (inspector layout; no responsive-layout test).
* Populated project/preset popovers fit long names and dismiss outside; export grid and crop dialog
  dismiss on outside click → 🟡 preset service behaviour is tested
  (`EditorPresetsServiceTest#savesPreset`, `#rejectsDuplicateNames`), but the popover/dismissal UI has
  no test → manual.

### 15. `editor.spec.ts` (2 tests) — 🟡 mapped + manual

* Editor loads the video, tools switch sections, dark/light switch, export grid offers GIF+Loop, Home
  opens the library, crop dialog and 1:1 aspect work, an annotation can be added (bold toggle +
  undo), and the inspector width stays stable → 🟡 `EditorWindowTest#opensEditorWindow` (real window
  with preview + timeline), `EditorSnapshotTest#rendersEditorShell`,
  `GifExportSettingsTest#loopMapping`, `ExportSettingsStateTest#toJsonKeys`,
  `AnnotationCommandsTest#mergesStylePatch`,
  `EditorHistoryTest#movesSnapshotsThroughUndoAndRedo`; theme switching, dialog visuals and inspector
  width are manual.
* Recorder opens device/source popovers, sets a 5 s countdown, hides "More", and Home opens the
  dashboard → 🔵 manual (`HudMenuWindow`; `CountdownDelayStoreTest#writesAndBroadcastsTheDelay` covers
  delay persistence). With the single-window architecture, "Home opens the dashboard" is a view swap
  in the primary window.

### 16. `hud-layout.spec.ts` (5 tests) — 🟡 mapped + manual

* HUD source label marquee respects `prefers-reduced-motion` on hover → 🟡 `MarqueeText` is ported and
  `MotionTest#durations` pins the 10 s marquee; the reduced-motion preference is not wired/tested (the
  class comment says the screen must honour it) → manual reduce-motion check.
* HUD dividers are vertically centred, icon sizes/style and Home opens the dashboard → 🔵 manual
  (phase-1 gate §3; snapshots `hud-overlay.png`/`hud-idle.png`).
* Recording HUD uses uniform 36×36 controls and a readable timer → ✅ timer logic
  `RecordingTimerTest#formats`, `RecordingTimerTest#pauseAccounting`, `RecordingTimerTest#resets`;
  control sizing is manual (snapshot `hud-recording.png`).
* New-recording mode starts the webcam with an editor open and releases it on Home → 🟡 Java only
  starts the HUD webcam preview for a recording (`HudOverlayWindow` condition); editor/Home coupling
  is single-process and manual (phase-1 gate §3).
* Floating webcam preview resizes from its corners, remembers the size, falls back when storage is
  unavailable, and passes clicks through → 🟡 `HudWebcamPreview` resizes from one bottom-right handle
  with a fixed 320×240 default and no persistence, versus React's four corners/288 px/localStorage
  (gap A3); manual resize check.

### 17. `linux-capture.spec.ts` (4 tests) — 🟡 mapped + manual

* Linux lists screens and windows and selects the requested window → ✅
  `CapturePickerSourcesTest#keepsLinuxWindowSelectionOnItsSourceList`,
  `DisplayOutputResolverTest#sortsDisplaysLikeTheSourceList`,
  `SourceListPickerTest#keepsTheSourceListPendingUntilSelected`.
* A closed Linux window keeps the picker open, shows the error, and refresh clears it → ✅
  `SourceServiceTest#rejectsUnavailableSources`,
  `CaptureSelectionTest#revalidatesCosmicOutputs`; the alert/refresh UI is manual.
* The source list closes on Escape, Cancel or a recording start without selecting → 🟡
  `SourceListPickerTest#settlesCancellationWhenDismissed`,
  `CapturePickerServiceTest#doesNotOpenAnyPickerDuringRecording`; key/cancel wiring manual.
* Dismissing the Wayland portal cleans up without a second prompt or alert → 🟡
  `SourceServiceTest#waylandStartsUnselectedAndPreparesThePortalOnRequest`,
  `CaptureSelectionTest#validatesThePortalSentinel`; the COSMIC consent dialog is a manual gate item
  (phase-1 gate §4).

### 18. `paused-motion.spec.ts` (3 tests) — 🟡 mapped + manual

* Auto-zoom resumes from a paused seek without keeping the old full-zoom focus → 🟡
  `PlaybackEngineTest#pausedSeekRendersOneFrame`,
  `CursorFollowCameraTest#startsFromRegionFocus`, `SceneMotionTest#neutralWhenNoZoom`; the paused
  seek → resume sequence needs a manual premium-frame check.
* Pause/resume preserves cursor/camera motion without a decoder seek → 🟡
  `PlaybackEngineTest#pausedSeekRendersOneFrame` (one frame at the target),
  `PlaybackEngineTest#scrubbingCoalescesLatest`; "no seek on resume" is not asserted directly → manual.
* Paused cursor/camera blur survives scrubbing and holds its frame → 🟡
  `CompositorMotionBlurTest#zoomStepProducesRadialBlur`,
  `CompositorMotionBlurTest#connectedHandoffProducesDirectionalBlur`; paused+scrub+blur is manual.

### 19. `playback-shortcut.spec.ts` (1 test) — ⚠ gap

* Space toggles playback once per press (held-key repeats ignored, no debounce delay) including when
  the play/pause button or the timeline has focus → ⚠ **no Java handler**: `Shortcuts.Action.PLAY_PAUSE`
  exists with default `" "` and is persisted/listed (`EditorWindow#shortcuts/#saveShortcuts`,
  `ShortcutsConfigDialog`), but no key handler consumes Space: `TimelineView#keyAction` handles
  delete/Tab/Ctrl+A/Z/C only and `EditorWindow`/`PlaybackControls` install no scene accelerator. No
  test exists. See gap A1.

### 20. `project-dashboard.spec.ts` (12 tests) — 🟡 mapped + manual, 1 gap

* Home opens with a loading state while saving, Settings opens, Escape closes, and it returns to the
  library → 🟡 `DashboardModelTest#clearsTransientStateOnSectionChange`,
  `DashboardSnapshotTest#rendersDashboardScene`; loading/Escape UI manual.
* Search/sort/open/back, sort by name, no-result + clear search, folder create/rename/colour/assign/
  "N more folders", delete selection, Record new opens the HUD, compact Import → ✅
  `DashboardLibraryFiltersTest#filtersBySearchAndSort`,
  `DashboardLibraryFiltersTest#sortsByCreationTime`, `#filtersByFolder`, `#reportsActiveFilters`,
  `DashboardModelTest#persistsFolders`, `#trashesProjectAndKeepsRecording`, `#selectsEntries`,
  `DashboardLibraryStoreTest#movesFolderReferences`; pixel layout is manual.
* Empty library explains itself; empty filtered library does not prompt for a first recording → 🟡
  `DashboardModelTest#listsProjectsAndFilters`,
  `DashboardLibraryFiltersTest#usesElectronTimeWindows`; empty-state copy/UI manual.
* Autosave creates one untitled project, stays idle without periodic saves, refreshes the preview on
  exit and shows no saved toast → ⚠ **gap A2**: Java has explicit save (`EditorSession#save`,
  `#saveAs`) plus an unsaved-close prompt (`EditorWindow`), and no debounced autosave/untitled-project
  creation. Poster generation exists (`ProjectManagerTest#generatesMissingPosterOnce`).
* Creation sort, independent folders/shared settings, precise caption spacing (48 px avatar/caption),
  circular initials, settings rows → 🟡 `DashboardLibraryFiltersTest#sortsByCreationTime`,
  `DashboardModelTest#persistsFolders`; exact pixel layout manual.
* Solar navigation selection/icons, circular initials, Raw sources (search, sort, reveal, rename,
  preview, remove) → ✅ `DashboardModelTest#mapsRawLibrary`, `#renamesAndHidesRawFiles`,
  `DashboardLibraryFiltersTest#filtersByTimeWindow`; raw library UI manual.
* Project hover plays a muted five-second preview and stops on exit → ✅
  `ProjectCardHoverPreviewTest#hoverMountsAndExitUnmounts`,
  `ProjectCardHoverPreviewTest#passStopsAloneAndRestarts`,
  `ProjectCardHoverPreviewTest#rawCardHoverPreview`.
* Home opens normally without a current recording → 🔵 manual (app wiring; single-window view swap).
* Folder actions stay inside narrow project cards without clipping → 🔵 manual layout check at
  800/1280/1440 px.
* Sidebar cards, separate Import, and shortcut settings edit/save → 🟡
  `ShortcutsTest#mergesWithDefaults`, `EditorShortcutsStoreTest#writesAndReadsBack`; dialog UI manual.

### 21. `recording-startup-cancellation.spec.ts` (1 declaration / 2 cases) — 🟡 mapped + manual

* Changing source during startup releases mic/cursor capture before picking; the recorder never
  starts and Record is enabled again → 🟡
  `SourceServiceTest#doesNotChangeSourcesIfRecordingStartsDuringValidation`,
  `CapturePickerServiceTest#rejectsAPendingSelectionIfRecordingStarts`,
  `CountdownServiceTest#cancelsActiveCountdownImmediately`; the native warm-up discard
  (`HudRecordingPortAdapter` cancel path deletes the webcam take) is untested → manual.
* Browser-capture path also cancels the countdown before opening the picker → 🟡 same countdown/
  picker evidence + manual.

### 22. `startup-loading.spec.ts` (8 tests) — ⛔ N/A

* Editor/Home wireframes painted before JavaScript loads; wireframe/skeleton held while the editor
  module loads; dashboard skeleton during session lookup; wireframe switches on reload/startupView →
  ⛔ **N/A**: these assert the Electron renderer's HTML/CSS boot wireframe and dynamic module
  skeleton, which have no counterpart in a single-process Java app. The Java equivalent is a native
  skeleton stage (`EditorSkeletonWindow` + kit `Skeleton`) shown until the real content is ready,
  evidenced by startup numbers (phase-2 report §7) and snapshots, not a TestFX test. With the
  single-window architecture the `startupView=home` distinction disappears.

### 23. `timeline-gap-snapping.spec.ts` (3 tests) — ✅ mapped

* Zoom ends snap to clip cuts, release outside the magnet, and a zoom can span the gutter → ✅
  `TimelineViewTest#resizesZoomEndSnappingToCut`,
  `ClipPresentationTest#softSnapsWithinEightPixels`,
  `ClipPresentationTest#snapsToOtherBlockEdges`, `ClipPresentationTest#roundTripsAllMediaTime`.
* A caption lands exactly where its mapped hover preview appears → ✅
  `TimelineViewTest#addsCaptionFromEmbeddedGhost`, `StoreTimelineViewTest#addsCaptionFromStrip`.
* Dragging a zoom soft-snaps its end to the cut without changing duration, and undo restores both
  edges → ✅ `TimelineViewTest#zoomDragSnapsEndKeepingDuration`,
  `StoreTimelineViewTest#movesAndResizesZoomInStore`.

### 24. `timeline-interactions.spec.ts` (1 test) — 🟡 mapped + manual

* Zoom hover/add ghost, adaptive lanes (zoom lane halves beside annotation/audio, full height again
  after deletion), clip edge grips outside the true bounds, preview volume popover, playhead cap hover
  expansion, no axis, and no scroll overflow at 800/1000 px → 🟡
  `TimelineViewTest#rendersEveryRow`, `TimelineViewTest#addsZoomFromLaneGhost`,
  `TimelineViewTest#zoomLaneHeightAdaptsToExtraLanes`, `TimelineViewTest#resizesClipEdge`,
  `TimelineLayoutTest#fitsMinimumViewport`, `PlaybackControllerPortTest#stateAndTransport`; volume
  popover placement and playhead-cap visuals are manual.

### 25. `timeline-presentation.spec.ts` (2 tests) — 🟡 mapped + manual

* Editor uses skeletons until media opens → 🟡
  `PreviewVideoReadinessTest#becomesReadyAfterEverySourceChange`,
  `PreviewVideoReadinessTest#retainsPreviewThroughTransientDrops`; the skeleton visual is manual.
* Filmstrips have persistent handles, conditional speed badges, centred rail navigation and
  accent-coloured zoom blocks → ✅ `TimelineModelTest#exposesClipSpeed`,
  `ClipPresentationTest#reservesSeam`, `TimelineViewTest#drawsFilmstripThumbnails`; rail centring is
  visual/manual.

### 26. `transcript-cut.spec.ts` (1 test) — ✅ mapped

* Generate captions from the empty state, Shift-click word ranges, Escape clears, the word under the
  playhead is marked while playing, cutting a word ripples footage + captions and undo restores
  them, and filler words are selected in one click and cut → ✅
  `TranscriptPanelTest#generatesCaptions`, `#selectsAndSeeksWords`, `#marksActiveWord`,
  `#cutsWordAndUndoes`, `#cutsFillerWords`; helpers
  `TranscriptWordsTest#buildsCutRangesWithTrailingGap`, `#selectsFillers`,
  `TranscriptTimeMapTest#mapsSourceToTimeline`.

### 27. `videos-library.spec.ts` (8 tests) — 🟡 mapped + manual, 1 N/A

* Clips panel supports selection, remove-all + undo, search, thumbnails, timeline drag insertion,
  auto-zoom on/off and batch cancel → 🟡 `RecordingLibraryTest#listsRecordingsAndStagesReversibleRemovals`,
  `RecordingImportControllerTest#commitsOneImport`, `#addsAutoZooms`,
  `#cancelAfterCompletedImportStillCommits`, `#cancelBeforeFirstImportIsSilent`,
  `RecordingThumbnailsTest#decodesOneStillAndReusesTheCache`,
  `FreshRecordingAutoZoomTest#suggestsForReadyRecording`; the new `RecordingLibraryPanel` UI has no
  test (gap B2) → manual.
* Generating captions leaves the current media session intact and shows the cues → ✅
  `AutoCaptionControllerTest#generationAppliesCues` (cues land in the store and captions turn on);
  the "media session untouched" assertion is manual.
* Preview recovers once when a local video URL fails to load → ⛔ **N/A**: the Java preview opens the
  file directly through FFmpeg; there is no media-server URL to fail/retry. Failure handling is
  `PlaybackMediaLoaderTest#rejectsMissingInputAndProbeFailures`.
* Dashboard project list scrolls vertically with long names → 🔵 manual layout check.
* HUD source controls have no default gray fill or outline → 🔵 manual (kit CSS; `ThemeCssTest` covers
  token presence).
* Clips shows original filenames with ellipsis and real still previews → 🔵 manual (`RecordingLibraryPanel`).
* Selected recordings drag together; imported click zooms on/off → ✅
  `RecordingImportControllerTest#commitsOneImport`, `#addsAutoZooms`,
  `FreshRecordingAutoZoomTest#suggestsForReadyRecording`; multi-select batch ordering is manual.

### 28. `wallpaper.spec.ts` (2 tests) — 🟡 mapped + manual

* Wallpaper tiles add/select/remove custom backgrounds without nested controls, remove on hover/keyboard,
  re-add the same file, and persist across reload → 🟡 `EditorWallpapers` + `WallpaperGrid` implement
  the gallery; resolution/mapping tested by `PreviewBackgroundsTest#bundledImage`,
  `PreviewBackgroundsTest#hexColour`, `PreviewBackgroundsTest#gradient`,
  `PreviewFrameMapperTest#colourAndGradientBackgrounds`; add/remove interaction + persistence have no
  test (gap B3) → manual.
* Video wallpapers share the gallery's add/remove controls → 🟡 `ProjectFrameInputFactoryTest#videoWallpaperLoops`,
  and the bundled `wispysky.mp4` asset is present under `gravaai-ui/src/main/resources/ui/editor/wallpapers/`;
  gallery interaction manual.

### 29. `webcam-defaults.spec.ts` (1 declaration / 2 cases) — 🟡 mapped + manual

* A new recording's webcam uses 100 % roundness by default (or the saved roundness, e.g. 69) and the
  mask radius matches → ✅ default/mapping via `PreviewFrameMapperTest#webcamSpecFromFixture`
  (roundness 100), `WebcamOverlayTest#cornerRadius` ("roundness percent becomes a squircle radius via
  sqrt strengthening"), `ProjectPersistenceNormalizerTest#migratesLegacyWebcamRadius`; the saved-69
  preference path is not directly asserted → manual.

---

## Gaps found

### A. Behaviour gaps (parity work for the Lead)

| # | Spec | Behaviour missing in Java | Java file | Action |
|---|---|---|---|---|
| A1 | `playback-shortcut.spec.ts` | Space toggles play/pause exactly once per press (held-key repeats ignored), from the play button and from the timeline. `Shortcuts.Action.PLAY_PAUSE` is defined/defaulted and editable but nothing consumes it. | `gravaai-ui/.../editor/timeline/TimelineView.java` (`keyAction`), `gravaai-app/.../editor/EditorWindow.java`, `gravaai-ui/.../editor/PlaybackControls.java` | Add a scene-level Space binding (consume repeat, no debounce) + TestFX test mirroring the Playwright cases. |
| A2 | `project-dashboard.spec.ts` | Debounced autosave that creates one untitled project, does not save while idle, and refreshes the preview on exit without a "saved" toast. Java only has explicit save + unsaved-close prompt. | `gravaai-app/.../editor/EditorSession.java` (`save`/`saveAs`), `gravaai-app/.../editor/EditorWindow.java` (close dialog) | Decide port vs accepted deviation; if deviation, record it in the Phase 3 report deviations table (plan §5.11 says "autosave/recovery (whatever exists today)"). |
| A3 | `hud-layout.spec.ts` | Floating webcam preview resizes from all four corners, remembers its size, and falls back gracefully when storage is blocked. | `gravaai-ui/.../launch/HudWebcamPreview.java` | Java has one bottom-right handle and a hard-coded 320×240 default with no persistence. Port four-corner resize + preference persistence, or record as deviation. |

### B. Implemented but untested (coverage gaps — add tests, no code change expected)

| # | Spec | Behaviour | Java implementation | Missing test |
|---|---|---|---|---|
| B1 | `editor-layout.spec.ts` | Header project name edits in place, saves on blur/Tab, Escape discards. | `EditorHeaderView#startRename` → `EditorViewActions#renameProject` | No TestFX test. |
| B2 | `videos-library.spec.ts` | Clips panel: selection, remove all, undo, search, filenames/ellipsis, thumbnails, batch progress. | `gravaai-ui/.../editor/clips/RecordingLibraryPanel.java`, `ClipImportOverlay.java` (landed during this sweep) | No test references the panel. |
| B3 | `wallpaper.spec.ts` | Wallpaper gallery add/select/remove custom image + video tiles, hover/keyboard removal, persistence. | `EditorWallpapers`, `WallpaperGrid`, `EditorInspectorPanel` (Scene tab) | Only resolution/mapping helpers are tested. |
| B4 | `clip-sequence.spec.ts` | Deletion ripples annotations and audio (not only zooms) and undo restores all. | `ClipCommands.applySequence` (ripples zoom, annotation, audio) | `ClipCommandsTest#packsSequenceAfterCut` asserts the zoom ripple only. |
| B5 | `editor.spec.ts`, `editor-refinements.spec.ts` | Export popover format grid / Loop switch / Escape close; project browser and presets popovers dismiss outside; crop dialog outside-click. | `ExportMenu`, `ProjectBrowserDialog`, `ShortcutsConfigDialog`, `CropEditorDialog` | No UI test for menu/popover dismissal. |
| B6 | `hud-layout.spec.ts`, `desktop-windows.spec.ts` | HUD bar/menu/countdown layout: dividers, 36×36 controls, source label marquee reduced motion, countdown Cancel button, webcam preview lifecycle. | `HudBar`, `HudMenuWindow`, `HudOverlayWindow`, `MarqueeText`, countdown overlay | No HUD view test (services/kit only). |
| B7 | `linux-capture.spec.ts` | Source-list window: error alert on a stale window, Refresh, Escape/Cancel/recording close, Wayland portal dismissal. | `SourceListPicker` + capture-picker stage | Service-level only; no view test. |
| B8 | `block-deletion.spec.ts` | Typing in an inspector text field must not delete the selected block; inspector Delete buttons. | `EditorInspectorPanel`, annotation/zoom/audio inspector sections | No focus-guard/button test. |
| B9 | `paused-motion.spec.ts` | Paused-seek resume resets auto-zoom focus; blur held through scrubbing; no decoder seek on resume. | `PlaybackEngine`, `Compositor`, `SceneMotion` | Combination not asserted; manual only. |

---

## Deferred item verified: rail click clears timeline selections

The known deferred item — React `EditorShell.tsx` `setActiveSection` clears clip, zoom, annotation,
audio and caption selections before switching inspector section — **is implemented in Java**:
`EditorShellView` (rail `onSelect`) calls `store.clearSelectionsExcept(null, null, null, null, null)`
and closes the Clips panel, mirroring `src/components/video-editor/layout/EditorShell.tsx:298-306`.
A dedicated test landed during this sweep: `EditorRailSelectionTest#railClickClearsSelections`
("selecting an inspector section clears clip, zoom, annotation, audio and caption selections"),
which sets all five selections, selects `EditorSidebarRail.Section.CURSOR` and asserts every
selection is null. It is DISPLAY-gated like the other TestFX tests — run it on the isolated display
(`DISPLAY=:99`, `-Dgravaai.test.display=:99`), not on the user's desktop. Not executed by this
read-only sweep; the assertion and implementation were verified statically.

---

## What this sweep could not verify

* **No tests were executed** (read-only task; other agents were building/testing concurrently). All
  citations are static: the class and method exist in the tree and their `@DisplayName` matches the
  quoted behaviour. Test *results* are in the phase reports, not re-measured here.
* The tree was changing during the sweep: `EditorHeaderView`, `EditorInspectorPanel`,
  `EditorShellView`, the dashboard package and the new Clips panel (`RecordingLibraryPanel`,
  `ClipImportOverlay`) were being edited, and `EditorRailSelectionTest` /
  `RecordingPermissionsSettingsTest` appeared mid-sweep. Re-run this checklist's "Gaps B" against the
  final tree before P3.6/Electron removal.
* Display-dependent manual items (HUD drag, COSMIC consent/portal dialogs, webcam/camera lifecycle,
  window chrome, compact layouts, screenshot comparisons) still need a human pass on the packaged
  AppImage; per the Lead's display policy use `:99` or the desktop, never open windows on `:0`.
* The single-window migration (dashboard + editor one primary stage) is **in flight**; summary rows
  that assert a separate dashboard window are read against that target and are not verified here.
* `startup-loading.spec.ts` and the Electron boot-line behaviours are intentionally not reproducible
  in Java (N/A), so their acceptance rests on the startup measurements + skeleton snapshots, not on a
  Playwright-equivalent test.