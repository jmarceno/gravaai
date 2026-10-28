# Phase 1 manual gate (run on COSMIC, on the AppImage)

Plan §6.4 / §7.1 acceptance gate. Everything here is run by a human on the machine; the phase report
must quote the results with screenshots. Machine rules from plan §1.1 apply: never run GPU/VA-API/
VDPAU/Vulkan video, one JVM/build at a time, kill the app with `pkill -x gravaai` only, never run the
Electron app and the Java app together.

## 0. Build and launch

```bash
cd java && ./mvnw -T1 -Pdist package
/media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage &
```

Expected: HUD pill appears (idle), tray item appears in the COSMIC panel, `~/.config/Gravaai/` is used
as the profile. Capture: `cosmic-screenshot --interactive=false --notify=false -s /tmp/phase1`.

The P1 meeting screens are standalone windows until P2.10 embeds them in the dashboard. For the gate
they are opened with environment variables on the packaged app (log line `meeting services ready`):

```bash
GRAVAAI_MEETING_OPEN=/path/to/recording.m4a \
GRAVAAI_MEETING_LIBRARY=1 GRAVAAI_MEETING_SETTINGS=1 \
  /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage &
```

Expected: "Gravaai - Meeting", "Gravaai - Meetings" and "Gravaai - Meeting settings" windows next to
the HUD (`meeting-app.png` in this repo was captured exactly this way).

## 1. Startup budget (P1 gate)

Stop every build/test JVM, then:

```bash
cd java && ./tools/startup-check.sh --runs 5 \
  /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage
```

Expected: `window shown` mean ≤ 800 ms (the HUD is the startup window in P1), AOT accepted (no
`[aot] error` lines). Record the five numbers in the report.

## 2. Tray

* icon idle (app icon) and while recording (rec-button).
* menu: Show Overlay, Open Gravaai, [Stop Recording while recording], Quit.
* primary click stops a recording first, otherwise opens the dashboard.
* capture the panel zoomed with cosmic-screenshot; platform smoke evidence is reusable.

## 3. HUD and HUD menu

* idle HUD: display name, mic/webcam/countdown buttons, Record.
* click Mic/Webcam/Countdown: HUD menu window opens with the matching panel; mic meter animates while
  speaking (real PulseAudio); camera preview shows the real camera.
* **drag the HUD by its dots** - required human check (COSMIC reverts client moves; the drag goes
  through `_NET_WM_MOVERESIZE`). Note the resulting position in the report.
* record start/stop/pause/resume from the HUD; timer counts; the tray icon switches to recording.

## 4. Source picking, countdown, area border

* HUD "Pick screen" -> click a monitor/window (COSMIC click-pick through gravaai-wayland-capture);
  the chosen source name appears on the HUD.
* countdown 3-2-1 overlay then recording starts.
* countdown cancel by click/Escape.
* area recording border (when an area source is used): dashed border, click-through (clicks reach the
  app underneath).

## 5. Recording outputs (P1 gate)

Record screen + mic + system audio + webcam for ~10 s, then stop. Expected files next to each other in
`~/.config/Gravaai/recordings/`: `recording-<ts>.mp4` (h264/aac, software libx264), `.meeting-mic.opus`
and `.meeting-system.opus` when a meeting runs, `recording-<ts>-webcam.mkv`, `.cursor.json`, `.keys.json`
and the `.gravaai-session.json` manifest.

* open the `.mp4` in the **Electron editor**: playback works, cursor/keys align with the video,
  webcam track is found (`.mkv` accepted by the Electron reader).
* open the GStreamer/mpv check: `mpv --no-config recording-<ts>.mp4`.
* verify no hardware decode was used: the app never passes `-hwaccel` and the ffmpeg build has no
  vaapi/vdpau/vulkan (plan §1.1.1).

## 6. Crash recovery (P1 gate)

Start a recording, then `kill -9 <pid of the app>` (`pidof java` - the AppImage runs the bundled
runtime; never `pkill -f`). Relaunch the app. Expected: the recoverable-recordings banner shows the
interrupted session, `Recover` produces a playable `.mp4`, `Discard` removes it.

## 7. Meetings (P1 gate)

Launch with the meeting envs from section 0.

* **library**: import a recording (or use a recording already in the profile): the library lists it
  with duration/status badges; rename, Show files and Delete work.
* **job**: open the meeting, click "Transcribe and write notes" (or Transcribe / Write notes
  separately). Progress + stage messages appear; cancel works; when it finishes `.transcript.md`,
  `.notes.md` and `.meeting.json` sit next to the recording and the notes pane renders Markdown with
  clickable timestamps; the meeting shows Notes/Transcript-ready badges.
* **models**: the Download manager in Meeting settings downloads the whisper runtime/model and the
  Qwen models with progress and Remove works (this is the only step that needs the network).
* **API path**: the API-key *write* path is the documented §5.2 safeStorage gap (the field reports the
  keyring message instead of writing plaintext). Either run the local-model path for the gate, or set
  the key in the Electron app first and verify the Java adapter reads it; record the result in the
  report.
* **record a meeting**: a native meeting recording is registered as a meeting when it stops (the
  recording-saved hook) and the auto-process switch (Meeting settings) starts the job. Run this with
  the recording flow from section 5; the audio-only variant keeps per-speaker tracks.

## 8. Global shortcuts (P1 gate)

Settings -> Global shortcuts: set Toggle Recording, then press it from another window on COSMIC.
Expected: recording starts/stops (through the HUD). `gravaai-ctl toggle-recording` from a terminal
also works (control socket, exit 0).

## 9. Automations (P1 gate)

Create a rule (e.g. on recording saved: copy path / notify), record and stop. Expected: the action
runs once, a notification appears for failures only, and export triggers are stubbed until P2.

## 10. Dashboard (P1 gate)

Open Gravaai: existing user projects and recordings are listed (real profile), thumbnails render,
search/filter/sort work, rename and trash work, folders/colours persist in
`<userData>/dashboard-library.json`, recoverable banner appears after a crash test.

## 11. UI parity gallery review

Open `docs/java-rewrite/phase-1-ui.html` (React reference | JavaFX) and review every row: layout,
spacing, colours, typography, icons and wording must match (plan rule 7).

## 12. Report

Fill `docs/java-rewrite/phase-1-report.md` §6 with ✅/❌ + notes + screenshot paths, and update
§7 with the five startup numbers.

