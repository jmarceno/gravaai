# HUD overlay + HUD menu (P1.7)

Port of `electron/windows.ts` (`createHudOverlayWindow`/`createHudMenuWindow`),
`electron/hudOverlayBounds.ts`, `src/components/launch/LaunchWindow.tsx`,
`HudWindow.tsx`, `RecordingControls.tsx`, `HudMenuWindow.tsx`, `launch/popovers/*`,
`launch/hooks/*` and `floatingWebcamPreview.ts` (plan §5.4, §5.6.6, §5.6.9, §7.1 P1.7).

## Classes

| Class | Role |
|---|---|
| `HudOverlayWindow` | transparent tight stage; bar + floating webcam preview; `GlobalShortcutHud`; X11 drag; capture hide/restore |
| `HudBar` | idle / recording / finalizing bar states |
| `HudMenuWindow` | full-display transparent menu window (mic / webcam / countdown) |
| `HudRecordingPort` | the recording seam the app implements (`AppContext`) |
| `HudShortcuts` | pure global-shortcut decision table (tested) |
| `RecordingTimer` | `useRecordingTimer.ts` elapsed/pause + mm:ss (tested) |
| `AudioLevelAnalyser` | `useAudioLevelMeter.ts` FFT/level maths (tested) |
| `MicLevelMonitor` | PulseAudio 48 kHz float stream → analyser → FX |
| `WebcamPreviewView` | `WebcamCapture` frames → mirrored JavaFX image |
| `HudWebcamPreview` | floating draggable/resizable webcam frame |
| `HudDevices` | microphone/camera enumeration for the menu |
| `HudShowcase` | dev harness (`-Dgravaai.hud.demo=recording|menu|finalizing`) |

## AppContext construction contract

```java
HudRecordingPort port = new AppHudRecordingPort(recordingService, sourceService,
        countdownService, recordingPreferencesStore); // app module adapter
HudOverlayWindow hud = new HudOverlayWindow(port);
hud.show();                                          // calls port.onHudReady() when subscribed
dispatcher.setHudProvider(() -> hud);                // GlobalShortcutDispatcher
// port.onHudReady() -> dispatcher.hudReady()
// RecordingService.HudHooks.hideForCapture()      -> hud.hideForCapture()
// RecordingService.HudHooks.restoreAfterCapture() -> hud.restoreAfterCapture()
// shutdown -> hud.dispose()
```

The port must publish state on the FX thread (`UiEventsFx.onFx` in the adapter) and use
`HudDevices.microphones()/cameras()` for the menu device lists.

## Window behaviour

- Transparent `StageStyle.TRANSPARENT` + `Scene` fill transparent; tight size around the bar
  (no Electron `setIgnoreMouseEvents` toggling, plan §5.4). Default screen: primary display; the
  dev/screenshot property `-Dgravaai.hud.screen=widest` selects the landscape display on this
  machine (portrait primary).
- The drag handle calls `dev.gravaai.ui.windows.X11Windows.beginMove` (window-manager move; COSMIC
  reverts client moves) and falls back to a client-side move without X11. The stage title
  `Gravaai HUD` is part of that lookup — do not rename it.
- `setAlwaysOnTop(true)` is requested; COSMIC ignores it (accepted status quo, plan §5.4).
- The HUD menu keeps Electron's full-display transparent window because the dimmed backdrop and
  click-outside dismissal need the whole display; the panel itself is content-height (520 px wide,
  max 82 vh / 720 px) with a scroll area.
- Capture protection: `hideForCapture()`/`restoreAfterCapture()` hide/show the HUD stage around
  the capture helper start (the Electron app hid the window for the same reason).

## Verification

- `RecordingTimerTest`, `AudioLevelAnalyserTest`, `HudShortcutsTest` (pure logic, green).
- `HudShowcase` harness screenshots (cosmic-screenshot):
  - idle bar: drag handle, meeting mode, source picker (MarqueeText), mic, webcam, countdown,
    record (danger + dot), separator, home, hide, close; bar pref size 609x64 like
    `java/parity/ui/hud-overlay.png`.
  - recording: home, blinking REC dot + mm:ss, disabled mic, pause/resume, stop square, hide,
    cancel.
  - finalizing: accent spinner + "Preparing recording" / "Opening the editor in a moment".
  - menu: dimmed backdrop, 520 panel, "System audio"/device rows with level meters,
    webcam preview and device rows, countdown options.
- Not yet verified with real hardware: live PulseAudio meters, camera preview, floating webcam
  preview, and real RecordingService start/stop (needs the AppContext port wiring).

## Deviations (report to the reviewer)

- JavaFX has no `focusable: false` window flag, so the HUD menu can take focus while open
  (Electron uses `focusable: false`). No workaround is attempted (status quo under COSMIC).
- The menu is a full-display window (not tight-sized) so the Electron backdrop/click-outside
  behaviour is preserved; the plan's "tight stage" instruction is applied to the HUD overlay.
- HUD text uses the English strings from `src/i18n/locales/en/launch.json`; the i18n loader is P1.2
  and screens wire it in P1.10, so the HUD literals are to be replaced when the locale plumbing
  lands (they are centralised in `HudBar`/`HudMenuWindow`).
