# Editor preview (P2.5, task-17)

Compositor-backed preview surface, transport bar and scrubbing seams for the Java editor. Port of
`src/components/video-editor/layout/EditorPreviewPanel.tsx` (preview stage + transport) and
`src/components/video-editor/PlaybackControls.tsx`, driven by
`dev.gravaai.media.playback.PlaybackController` (plan §5.8/§5.9, P2.5).

## Classes

| Class | Role |
|---|---|
| `EditorPreviewView` | FX surface: `JavaFxPreviewSink` `PixelBuffer` image, subscribes to engine frames, restarts the renderer when the frame size changes, idle/repaint |
| `PreviewRenderer` | One `GlPreviewThread` + one `Compositor` (created/closed on the GL thread) + `JavaFxPreviewSink`; the engine's decode thread blocks in `render(FrameInput)` until the compositor drew, so decoder-owned `RgbaFrame`s stay valid |
| `PreviewFrameMapper` | `ProjectEditorState` + `PlaybackFrame` → `FrameInput`: background (stage 1), blur, padding/crop `LayoutSpec` (stage 2), `ZoomSpec` zoom regions + camera springs (stage 3), `FrameStyle` radius/shadow (stage 4), the bracketing cursor telemetry samples (stage 6, `CursorTrack`/`CursorSpec`) and the webcam overlay `WebcamSpec` (stage 7) |
| `PreviewBackgrounds` | Project wallpaper string → compositor `Background`: `Background.fromWallpaper` for CSS colours/gradients, bundled 1280x720 preview bytes as `Background.Image`; cached by identity because the compositor background cache keys on the instance |
| `PlaybackControllerPort` | Implements `EditorPlaybackPort` over the engine: status → FX `UiEvents`, seek/skip clamping, scrub, rate, engine-owned volume; the shell bar keeps using `EditorPlaybackPort` unchanged |
| `EditorPreviewSnapshot` | Dev harness: synthetic 60 Hz colour-bar engine, renders the shell and writes `parity/ui-java/editor-preview.png` |

`EditorPreviewPanel` keeps its two-argument constructor and the wallpaper/plate fallback; the
compositor surface only replaces the fallback once a controller is bound. Its seams for the rest of
the editor are `setPlaybackController(PlaybackController)`, `scrubTo(seconds)`,
`seekTo(seconds[, pause])`, `skip(deltaSeconds)`, and `previewView()` for diagnostics/snapshots.
`PlaybackControls` keeps `EditorPlaybackPort` and the public `formatTime(double)`.

## AppContext wiring contract

```java
// AppContext owns the engine (recording's PlaybackEngine) and the media load:
PlaybackController engine = PlaybackEngine.createDefault(frameSourceProvider);
engine.setMedia(PlaybackMediaLoader.load(videoPath, webcamPath, webcamOffsetMs,
        editorState.clipRegions(), webcamVisibleRanges));

// The transport bar takes the UI adapter; the preview surface takes the engine itself.
PlaybackControllerPort playbackPort = new PlaybackControllerPort(engine); // FX-thread dispatcher
EditorShellView shell = new EditorShellView(store, actions, playbackPort);
shell.setPreviewPlayback(engine);   // forwards to EditorPreviewPanel.setPlaybackController

// Project switch: engine.setMedia(...) again (the preview repaints and the bar re-reads status).
// Shutdown: shell.dispose() (stops the preview renderer) and engine.close().
// The timeline (P2.7) calls shell.preview().scrubTo(seconds) while dragging and
// shell.preview().seekTo(seconds, true) on release.
```

## Threading

- `PlaybackController` frame/status/time callbacks run on the engine's threads. The preview's
  `onFrame` runs the compositor synchronously (blocking the decode thread until the draw is done,
  as `PlaybackFrame` documents). Status/volume are hopped to FX by `PlaybackControllerPort`.
- The editor state is snapshotted into an immutable `PreviewScene` on the FX thread; the decode
  thread never reads the mutable `EditorStore`.
- The GL preview thread is separate from the FX thread; `JavaFxPreviewSink` drops a frame when FX
  has not presented the previous one (preview back-pressure).

## Verification

- `PreviewFrameMapperTest`, `PreviewBackgroundsTest`, `PlaybackControllerPortTest`,
  `EditorPreviewPanelTest` are part of the canonical UI suite (198 tests, all green).
- `EditorPreviewSnapshot` run (2.8 s, synthetic 320x180 colour bars): 73 compositor frames reached
  the surface (0 dropped), transport at 0:02 PLAYING, snapshot shows the wallpaper background,
  20% padded video plate with the stage-4 shadow and the animated bars.

## Deviations / follow-ups

- Zoom regions (stage 3) are not mapped yet (`ZoomSpec.NONE`); cursor samples are empty. Both
  belong to the zoom/cursor increment and will be filled by the same `PreviewFrameMapper`.
- Preview volume is delegated to the engine's `setVolume`/`volume` (recording added the default
  API); loop is engine-owned and not surfaced in the bar (the React editor bar has no loop button).
- Skip back/forward use ±5 s; the React version first jumps to the previous/next zoom keyframe.
  Keyframe skipping needs the timeline projection from P2.7 and is listed as a follow-up.
- `EditorPreviewSnapshot` is a development harness (like `EditorSnapshot`); it is not wired in the
  app.
## Increment 2 (zoom, cursor, real fixture)

- `PreviewFrameMapper.zoomSpecFor(state)` maps every core `ZoomRegion` to a media
  `ZoomRegionSpec` (id/start/end, `ZoomDepth` through its wire level, `ZoomFocus`, `ZoomMode`) and
  builds the `ZoomSpec` with `zoomSmoothness`, `connectZooms`, `zoomClassicMode`, the in/out
  duration overrides and the three camera spring multipliers.
- `PreviewFrameMapper.CursorTrack(samples, visible)` carries the recording's cursor telemetry in
  project seconds; `around(frameTime)` hands the compositor the previous/next samples it
  interpolates. `EditorPreviewPanel.setCursorTrack(...)` is the AppContext seam (RecordingService
  `getCursorTelemetry` → `CursorSample` list).
- `EditorPreviewSnapshot` now loads a real parity project fixture, builds `PlaybackMedia` through
  `PlaybackMediaLoader` (FFmpeg duration probe), runs the real `PlaybackEngine` and loads the cursor
  sidecar through `RecordingPaths`. `-Dgravaai.preview.fakeFrames=true` substitutes a synthetic
  `FrameSourceProvider` for UI snapshots while the FFmpeg decoder path is blocked by its
  decode-thread confinement bug (reported to recording/media-native).
## Increment 3 (readiness, cursor spec, timeline seek, real footage)

- `PreviewFrameMapper.cursorSpecFor(state)` maps every editor cursor setting into the media
  `CursorSpec` (stage 6): showCursor, `CursorStyle` wire value, dot radius
  (`DEFAULT_CURSOR_CONFIG.dotRadius (28) * cursorSize`), smoothing, the three spring multipliers,
  motion blur, click effect + parsed ARGB colour, effect scale/opacity/duration, click bounce
  amount/duration and sway. Verified against fixture 03 (tahoe-inverted, 28*2.5=70 px dot,
  echo #A855F7, 800 ms/350 ms, sway 0.4).
- `EditorPreviewView` uses `PreviewVideoReadiness` for first-frame readiness (ready when a decoded
  frame with known dimensions is composited; `SEEKING` keeps the previous frame; binding a new
  controller resets). `EditorPreviewPanel` shows the kit skeleton over the frame until ready and
  exposes `previewReadyProperty()`/`isPreviewReady()`.
- `EditorPreviewPanel.timelineSeek()` returns the `PlaybackSeek` seam for the timeline: a timeline
  click pauses then seeks (the React `handleTimelineSeek(time, {pause:true})` behaviour); the app
  wires it into `StoreTimelineCommands`.
- `EditorPreviewSnapshot` now runs the real FFmpeg decoder (no fake provider): fixture 03 plays,
  pauses at 1.15 s inside zoom region 2 and captures; the snapshot shows decoded footage, the
  compositor cursor overlay, wallpaper, radius, padding and shadow with the transport at 0:01/0:02.
## Webcam overlay (task-17 completeness)

- `PreviewFrameMapper.webcamSpecFor(state)` maps the editor webcam block into the media
  `WebcamSpec` (stage 7): enabled, position preset + legacy corner wire values, normalized
  position X/Y, size/width/height, mirror, roundness, shadow, margin, react-to-zoom, the webcam
  crop (`CropRegion` -> `LayoutCrop`) and the visible timeline ranges (`VisibleRange`), plus the
  webcam time offset. A missing webcam block becomes `WebcamSpec.DISABLED`.
- The engine already delivers `webcamFrame`/`webcamVisible` from the linked track
  (`PlaybackMedia.webcamPath`); the preview now carries the spec so the compositor draws the
  overlay. Verified against `07-webcam-bottom-right.gravaai` and re-shot
  `parity/ui-java/editor-preview.png` through the real decoder: the bottom-right rounded webcam
  bubble is composited over the real footage.
## Non-blocking engine hand-off (P2.11 first-preview)

The playback engine thread NEVER waits for the compositor:

- `EditorPreviewView.onFrame` -> `PreviewRenderer.submit(FrameInput)` ->
  `PreviewFrameQueue.submit`: the queue copies the source/webcam `RgbaFrame`s into queue-owned
  direct buffers and returns immediately, so the engine may reuse its decoder buffers (and its
  `setMedia` first-frame callback) without waiting for a draw. The GL thread polls the latest copy
  (`pollLatest`/`finishRendering`); two slots (one being drawn, one being filled) give latest-wins
  coalescing without ever blocking the producer. `JavaFxPreviewSink` keeps the existing FX
  back-pressure/drop behaviour.
- The transport bar no longer takes the engine lock on FX: `PlaybackControllerPort.state()` serves
  a cached `PlaybackState` refreshed from the engine's off-thread `onStatus`/`onTime` callbacks;
  actions update the cache optimistically and the next engine event corrects it. 
  `EditorPreviewView.requestRepaint()` and the panel's scrub/skip clamps use the cached status too.
- Diagnostics: `PreviewRenderer.framesSubmitted()/framesDrawn()` (queue) and
  `EditorPreviewView.framesSubmitted()/framesDrawn()/framesReceived()/framesDropped()`.
  Real-decoder fixture-07 run: 14 frames submitted, 12 drawn/received, 0 sink drops, 2.6 s total
  harness wall time (JVM + GL + decode + snapshot).
