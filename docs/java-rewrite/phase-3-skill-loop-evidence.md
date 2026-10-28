# Phase 3 P3.2 acceptance — `gravaai-video-editing` skill loop against the Java app

Task: `task-6` (P3.2 acceptance criterion: *"Verify with the `gravaai-video-editing` skill's
read → plan → edit → look → export loop against the Java app."*)

Verdict: **PASS** — all five loop stages ran against the real packaged Java app through the real
`gravaai --mcp` bridge, the exported file validates with `ffprobe`, and the edit is visible in
both the agent's frames and the exported video.

| Stage | Result | Evidence |
|---|---|---|
| READ (`inspect` overview/timeline/settings) | **PASS** | MCP responses below; `rev` returned and honoured |
| PLAN (agent-side, no tool call) | **PASS** | one batch: `zoom 0.5–1.5 s depth 3 focus 0.5,0.5` + `text "Skill loop" 0.2–1.4 s` |
| EDIT (one atomic batch) | **PASS** | `zoom z1 …, added text a1 …`; `rev 180680687 → 180680688`; one undoable store change |
| LOOK (`frames`) | **PASS** | 3 PNGs at 0.2/1.0/1.8 s; zoom matches a 1.8× centre crop of the pre-edit frame; title localised to its band (numbers in §6) |
| EXPORT (`job start/wait`) | **PASS** | `job-1 export done`, 756 349 B, 60 frames, `h264 1920×1080 30 fps yuv420p`, 2.000 s (`ffprobe`) |
| SAVE (`project save`) | **PASS** (extra) | file contains the zoom + annotation |

Reference skill: `gravaai-video-editing` (session skill catalog) — `inspect`, `edit`, `frames`,
`project`, `job`; times on the edited timeline; one batch per intent with the `rev` from the last
read; `STALE` on concurrent changes.

## 1. State before this task (P3.2a) and what P3.2b added

The packaged app initially shipped a **read-only** agent editor port
(`java/gravaai-app/src/main/java/dev/gravaai/app/agent/AgentWiring.java`). A first probe against
the then-fresh packaged binary returned:

```json
{"name":"edit","arguments":{"ops":[{"op":"zoom","from":0.5,"to":1.5,"depth":3,"focus":{"x":0.5,"y":0.5}}],"label":"skill loop: zoom","rev":1}}
→ {"result":{"content":[{"type":"text","text":"dev.gravaai.services.agent.AgentDispatcher$AgentDispatchError: The edit tool is not wired to the Java editor yet (P3.2b)."}],"isError":true}}
```

`frames` and `job` failed the same way, `project {action:"save"|"open"}` returned a static blurb,
and `inspect {view:"timeline"}` returned the overview text. That is the P3.2b gap this task
closed (Lead widened the write scope to `java/gravaai-app/src/main/java/dev/gravaai/app/agent/**`
plus its tests).

New app-side wiring (all in `dev.gravaai.app.agent`, plan §5.16):

| File | Ported from | Responsibility |
|---|---|---|
| `AgentEditorPort.java` | `video-editor/agent/useAgentBridge.ts` | tool dispatch; per-session seeded `rev` (bumped by every `EditorStore` change); `STALE` check; one `AgentPlanner` batch committed as a single undoable store change; `revert_ai` checkpoint stack; project open/new/save/save_as; export job lifecycle |
| `AgentEditorViews.java` | `video-editor/agent/views.ts` | `inspect` overview/timeline/transcript/settings text (short ids `c1/z1/a1/…`, windows, `rev`) |
| `AgentFrameRenderer.java` | `video-editor/agent/frames.ts` + `frameCapture.ts` | offscreen compositor frames (`ProjectFrameInputFactory` + `CompositorExportFrameProvider`), PNG per requested time |
| `AgentExportRunner.java` | `video-editor/agent/jobs.ts` | headless export honouring `format`/`quality`/`fps`/`captionsSidecar`, publishing through `ExportRunner` |
| `AgentWiring.java` | — | constructs the port with the app's `EditorSession`/`ProjectExporter`/open-project seams and the shared `AgentJobRegistry`; marshals timeline highlights to the FX thread |
| `AgentEditorViewsTest.java` | `views.test.ts` (subset) | 7 headless tests: overview counts/rev, timeline short ids + window, annotations, transcript, settings flattening, export destination validation |

Semantics kept from the React source: reads always end with `rev`; `edit` is atomic and rejects a
stale `rev` with a fresh overview; history ops (`undo`/`redo`/`revert_ai`) must be alone in their
batch; `revert_ai` restores timeline **and** settings; `dryRun` reports without applying; export
paths must be absolute (`mp4`→`.mp4`, `gif`→`.gif`).

## 2. Exact commands

All commands were run from `/home/jmarceno/Projects/gravaai`. Only display **`:99`** (Xvfb) was used.

```bash
# 1) Build the app jars (serialized with the other agents' Maven builds).
cd java && flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 -pl gravaai-app -am -DskipTests package
#    Output: /media/jmarceno/scratch/java-targets/gravaai-*/**  (build dir is on the scratch disk)

# 2) Fresh runnable app image without touching the shared dist:
cp -a /media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai /tmp/skill-loop-dist
cp /media/jmarceno/scratch/java-targets/gravaai-app/gravaai-app.jar /tmp/skill-loop-dist/lib/app/
for m in core media platform services ui; do
  cp /media/jmarceno/scratch/java-targets/gravaai-$m/gravaai-$m-1.4.0.jar /tmp/skill-loop-dist/lib/app/
done
#    The copy also drops the stale `-XX:AOTCache` option from lib/app/gravaai.cfg (class files
#    changed). To refresh the shared packaged image instead, run (do not run while others build):
#    cd java && flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 -Pdist -DskipTests package

# 3) Scratch profile + fixture copy (never the real profile).
mkdir -p /tmp/skill-loop-profile /tmp/skill-loop-project /tmp/skill-loop-evidence
printf '{"aiControlEnabled": true}' > /tmp/skill-loop-profile/app-settings.json   # Settings → AI control
cp java/parity/fixtures/projects/01-wallpaper-blur.gravaai /tmp/skill-loop-project/
#    The fixture's videoPath is already absolute:
#    /home/jmarceno/Projects/gravaai/java/parity/fixtures/media/h264-1080p60.mp4 (2.000 s)

# 4) App (editor window on the isolated display).
nohup env DISPLAY=:99 GRAVAAI_USER_DATA=/tmp/skill-loop-profile \
  GRAVAAI_EDITOR_OPEN=/tmp/skill-loop-project/01-wallpaper-blur.gravaai \
  /tmp/skill-loop-dist/bin/gravaai > /tmp/skill-loop-app.log 2>&1 &
#    Readiness: /tmp/skill-loop-profile/agent.sock + agent-token appear, log prints
#    "AI control wired: LISTENING" and "startup: editor preview ready at <ms> ms".

# 5) MCP bridge over stdio (same profile; NOT the editor-open env).
/tmp/skill-loop-dist/bin/gravaai --mcp

# 6) One-shot loop driver (newline-delimited JSON-RPC; initialize → tools/list → calls).
python3 /tmp/skill-loop-evidence/run_skill_loop.py
#    Raw single-call driver: python3 /tmp/skill-loop-evidence/mcp_driver.py '[{"tool":"inspect","args":{}}]'

# 7) Export validation.
java/native/build/ffmpeg/bin/ffprobe -v error \
  -show_entries format=format_name,duration,size,bit_rate \
  -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt \
  -of json /tmp/skill-loop-evidence/skill-loop-export.mp4
java/native/build/ffmpeg/bin/ffmpeg -v error -y -ss 1.0 -i /tmp/skill-loop-evidence/skill-loop-export.mp4 \
  -frames:v 1 /tmp/skill-loop-evidence/export-frame-1.0.png
```

Handshake against the packaged bridge:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"gravaai-skill-loop-acceptance","version":"1.0"}}}
→ result: protocolVersion "2025-06-18", serverInfo {"name":"Gravaai","version":"1.4.0"}, instructions, tools capability
tools/list → ["inspect","edit","frames","project","job"]
```

## 3. The loop, as executed (trimmed MCP traffic)

Full run of `/tmp/skill-loop-evidence/skill-loop-transcript.json` (0 failing calls).
`rev` 180680687 after a cold open of the fixture.

### 3.1 READ

```json
{"name":"inspect","arguments":{}}
→ "project: 01-wallpaper-blur
   path: /tmp/skill-loop-project/01-wallpaper-blur.gravaai
   source: /home/jmarceno/Projects/gravaai/java/parity/fixtures/media/h264-1080p60.mp4 (2.00s)
   edited: 2.00s • aspect 16:9
   clips 1 • zooms 0 • speed 0 • annotations 0 • audio 0 • caption cues 1
   webcam off • cursor on • wallpaper /wallpapers/tahoe-light.jpg
   rev 180680687"

{"name":"inspect","arguments":{"view":"timeline"}}
→ "window 0.00–2.00s of 2.00s
   clips:
     c1 0.00–2.00 src 0.00–2.00 1.00x
   zooms:
     (none in window)
   annotations:
     (none in window)
   captions: 1 cues — inspect view:"transcript" for words
   rev 180680687"

{"name":"inspect","arguments":{"view":"settings","topic":"zoom"}}
→ "zoomMotionBlur=0.0  (0-2)
   zoomInDurationMs=200.0 … zoomSmoothness=0.5 … webcam.reactToZoom=true
   rev 180680687"

{"name":"frames","arguments":{"at":[0.2,1.0],"width":640}}
→ "frames at 0.20s, 1.00s (640px wide) • edited 2.00s • rev 180680687"
   images: /tmp/skill-loop-evidence/baseline-00.png, baseline-01.png   (pre-edit baseline)
```

### 3.2 PLAN

Read the timeline: one 2 s clip `c1`; no zooms/annotations. Plan for "tighten the middle and title
it": one zoom covering the middle second (0.5–1.5 s, depth 3 = 1.8×, centre focus) and a bold
`Skill loop` title for 0.2–1.4 s at the top centre. Both in **one** batch, labelled so the undo
entry reads `AI: skill loop zoom + title`.

### 3.3 EDIT

```json
{"name":"edit","arguments":{"ops":[
   {"op":"zoom","from":0.5,"to":1.5,"depth":3,"focus":{"x":0.5,"y":0.5}},
   {"op":"text","from":0.2,"to":1.4,"text":"Skill loop","x":0.5,"y":0.15,"w":0.6,"h":0.12,
    "style":{"fontSize":48,"bold":true}}],
 "label":"skill loop zoom + title","rev":180680687}}
→ "zoom z1 0.50–1.50s depth 3, added text a1 at 0.20–1.40s
   rev 180680688 • edited 2.00s"
```

Re-read (the skill's "re-read after every batch"): `zooms 1 • annotations 1`, `(unsaved changes)`,
`rev 180680688`. The `STALE` contract was checked separately — replaying the same batch with the
old `rev 180680687` returned `isError: true` with
`"STALE: the project changed since rev 180680687 (now rev 180680688); nothing was applied. Re-read and retry."`
and the fresh overview, without touching the project.

### 3.4 LOOK

```json
{"name":"frames","arguments":{"at":[0.2,1.0,1.8],"width":640}}
→ "frames at 0.20s, 1.00s, 1.80s (640px wide) • edited 2.00s • rev 180680688"
   images: after-00.png, after-01.png, after-02.png (640×360 PNG)
```

The images came back as MCP `image/png` content from the offscreen compositor (no UI capture, no
window needed beyond the running editor). See §6 for the pixel comparison against the baseline.

### 3.5 EXPORT

```json
{"name":"job","arguments":{"action":"start","kind":"export",
  "options":{"format":"mp4","quality":"source","path":"/tmp/skill-loop-evidence/skill-loop-export.mp4"}}}
→ "mp4 export started: /tmp/skill-loop-evidence/skill-loop-export.mp4
   job-1 export running — job {action:\"wait\", id:\"job-1\"} reports progress"

{"name":"job","arguments":{"action":"wait","kind":"export","timeout":180}}
→ "job-1 export done — done (4.1s)
   {\"path\":\"/tmp/skill-loop-evidence/skill-loop-export.mp4\",\"bytes\":756349,
    \"frames\":60,\"format\":\"mp4\",\"durationSeconds\":2.0}"

{"name":"inspect","arguments":{"view":"jobs"}}
→ "job-1 export done 100% done {…same result…}"
```

`:99` window note: the export ran in the app process (compositor on its offscreen GL thread); no
extra window was opened.

### 3.6 SAVE (extra contract check)

```json
{"name":"project","arguments":{"action":"save"}}
→ "saved /tmp/skill-loop-project/01-wallpaper-blur.gravaai"
```

The saved fixture copy now carries `"zoomRegions":[{"id":"zoom-1","startMs":500,"endMs":1500,
"depth":3,"focus":{"cx":0.5,"cy":0.5},"mode":"manual"}]` and the corresponding text annotation
`annotation-1` (`"Skill loop"`, `position 20,9`, `size 60×12`, `fontWeight "bold"`).

## 4. Export validation

`ffprobe` on `/tmp/skill-loop-evidence/skill-loop-export.mp4` (sha256
`fcf9fe053c0922319c2d71b547f71f982e96fa9854693758ad5fe116184e13eb`):

| Field | Value |
|---|---|
| container | `mov,mp4,m4a,3gp,3g2,mj2` |
| duration | 2.000000 s |
| size | 756 349 B (≈ 3.03 Mbit/s) |
| stream | h264, 1920×1080, 30/1 fps, yuv420p |

`quality:"source"` therefore produced full-resolution 1080p at 60 frames for the 2 s timeline,
matching the editor defaults for a 1080p60 source. Extracted frames: `export-frame-0.2.png`,
`export-frame-1.0.png`, `export-frame-1.8.png` (all 1920×1080).

## 5. Where the evidence lives

| Artifact | Path |
|---|---|
| MCP transcript (trimmed calls/responses) | `/tmp/skill-loop-evidence/skill-loop-transcript.json` |
| Drivers | `/tmp/skill-loop-evidence/mcp_driver.py`, `run_skill_loop.py` |
| Pre-edit frames (LOOK baseline) | `/tmp/skill-loop-evidence/baseline-00.png` (0.2 s), `baseline-01.png` (1.0 s) |
| Post-edit frames | `/tmp/skill-loop-evidence/after-00.png` (0.2 s), `after-01.png` (1.0 s), `after-02.png` (1.8 s) |
| Exported video | `/tmp/skill-loop-evidence/skill-loop-export.mp4` |
| Export frames + probe | `/tmp/skill-loop-evidence/export-frame-*.png`, `ffprobe-export.json` |
| Pixel comparison | `/tmp/skill-loop-evidence/comparison.txt`, `checksums.txt` |
| App log | `/tmp/skill-loop-app.log` |
| Scratch profile (retained) | `/tmp/skill-loop-profile` (AI control on) |
| Runnable app used | `/tmp/skill-loop-dist` (packaged image + freshly built jars) |

## 6. Frame comparisons (mean absolute RGB difference, `PIL`)

Agent frames are 640×360; exported frames are 1920×1080 and were LANCZOS-resized to 640×360 for
comparison.

**A. Title annotation at 0.20 s (before → after)**

| Region | Mean abs diff |
|---|---|
| whole frame | 0.42 / 0.40 / 0.39 |
| title band (y 5–25 %) | 2.09 / 2.02 / 1.93 |
| rest of frame (y 30–100 %) | 0.00 / 0.00 / 0.00 |

The title changed exactly its band and nothing else. Visually: white bold `Skill loop` at the top
centre of `after-00.png`; absent in `baseline-00.png`.

**B. Zoom at 1.00 s (before → after)**

| Comparison | Mean abs diff |
|---|---|
| whole frame (unmatched) | 22.93 / 25.33 / 28.18 |
| baseline centre crop ×1.4 | 8.17 / 7.00 / 6.97 |
| baseline centre crop ×1.5 | 7.12 / 5.98 / 6.00 |
| **baseline centre crop ×1.8** | **2.09 / 2.19 / 2.51** |
| baseline centre crop ×2.2 | 9.95 / 8.43 / 8.31 |

Depth 3 = 1.8× (`ZoomDepth.THREE.scale()`), and the edited frame is the ×1.8 centre crop of the
pre-edit frame — the compositor applied exactly the requested zoom, not a re-render artifact.

**C. Same edit through LOOK and EXPORT**

| Time | agent frame vs ffmpeg frame of export |
|---|---|
| 0.20 s | 3.95 / 4.14 / 4.40 |
| 1.00 s | 6.86 / 5.75 / 5.76 |
| 1.80 s | 23.64 / 26.65 / 29.76 |

And the exported file itself carries the edit: export @1.00 s vs pre-edit baseline centre crop ×1.8
= 5.91 / 4.84 / 4.85, versus 25.10 / 27.67 / 30.80 against the raw baseline.

The 1.80 s difference is expected and not a defect: `frames` renders **one frame per request with a
fresh compositor** (the same capture semantics as the committed golden references —
`GoldenRenderer`: "the electron frames tool seeks and renders one frame per case, so the zoom
springs initialise to their target and no prior-frame motion-blur history exists"), while the
export renders **sequentially**, so at 1.8 s (0.3 s after the zoom region ends) the camera spring
is still finishing its zoom-out. Measured on the export: the padding/wallpaper only reappears at
≈1.95 s; the settled `frames` capture is the honest "what the project looks like at 1.8 s" view.
Both are correct for their purpose and both show the zoom at 1.0 s.

## 7. Defects found, fixed, and remaining gaps

Found and fixed in this task (all inside the granted scope):

1. **P3.2a read-only agent editor port → P3.2b wired** (`AgentWiring` + 4 new `app/agent` classes).
   `edit`, `frames`, `project` (open/new/save/save_as) and export jobs now execute against the open
   `EditorSession`; `inspect` views are formatted.
2. **Timeline highlights applied off the FX thread.** The first run of the new port logged
   `IllegalStateException: Not on FX application thread … com.sun.javafx.collections.VetoableListDecorator.clear`
   (UI thread check) from `AgentEditorPort.edit → ui.applyHighlights → AgentHighlightsController`.
   `AgentWiring` now marshals `ui.applyHighlights` with `Platform.runLater` and swallows failures
   from a disposed overlay; after the fix the same loop logs **0** such exceptions.

Remaining gaps (deliberately not widened beyond the task; Lead's call):

1. **`project {action:"close"}` is not supported** — the single-window app hosts the editor on the
   primary stage and `AgentWiring.create` has no close/navigate seam; the tool returns a clear
   error. Opening another project (`open`/`new`, `force:true` saves first) works.
2. **`job start kind:"transcribe"` is not wired** — the Java whisper/caption controller exists but
   is not exposed through the agent job port; the tool returns a clear error. The loop for this
   fixture did not need transcription (its project already carries a caption cue).
3. **`auto_zoom` cannot use cursor telemetry** — `AgentPlanner.Context`'s public constructor takes
   `List<AgentAutoZoom.Sample>`, but `AgentAutoZoom` is package-private in `gravaai-services`, so
   the app can only build a context with empty telemetry; `auto_zoom` therefore reports no
   telemetry. Fix belongs in `services/agent` (make the sample type public or add a public context
   factory taking the media `CursorSample` list) — reported to the Lead instead of editing
   services.
4. **Multi-frame contact sheet deviation** — React tiles >1 frame into one image with per-tile
   captions; the Java port returns one PNG per requested time (MCP-valid, `at` ≤ 9). Same
   information, more image tokens; a later `frames.ts` port can add the sheet.
5. **Agent export does not install extension render hooks** — the app's `ProjectExporter.runTo`
   path passes the extension host hooks; the agent path builds its own pipeline to honour
   `quality`/`fps` and passes `FrameOverlayHooks.NONE`. With no extensions in the scratch profile
   the output is identical; enabling extensions later needs a `Supplier<FrameOverlayHooks>` seam
   from `AppContext`.
6. **Export options coverage** — `format`, `quality`, `fps`, `path`, `captionsSidecar` are honoured;
   `gifLoop`/`gifSizePreset`/`publishDestination` fall back to the editor's saved export
   preferences.
7. **Undo scope** — like the React source, `Ctrl+Z`/`undo` covers timeline arrays (the existing
   `EditorHistory` snapshot shape); `revert_ai` is the full-state revert. Both are documented in
   `inspect help`.

## 8. Tests

```bash
cd java && flock /tmp/gravaai-mvn.lock ./mvnw -o -q -pl gravaai-app -am \
  -Dtest=AgentEditorViewsTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
# surefire: tests="7" errors="0" skipped="0" failures="0"
```

`AgentEditorViewsTest` covers the pure halves: overview counts/rev/source durations, timeline
short ids + `from`/`to` window, annotation lines, transcript words with cue/word ids, settings
flattening (timeline arrays skipped), and export destination/format validation (absolute path,
`.mp4`/`.gif` pairing, unknown format). The real `edit`/`frames`/`job` execution is evidenced by
§3–§6 against the running app.

## 9. Cleanup

* App launched for this task was stopped by PID tree; no app/MCP process is left running.
* The scratch profile `/tmp/skill-loop-profile` and the evidence under `/tmp/skill-loop-evidence`
  are retained (deliberately — they are the acceptance artifacts).
* No fixture, no real profile (`~/.config/Gravaai`), and no other module was modified.
* No commit was made. Working-tree changes are listed in §7 and limited to
  `java/gravaai-app/src/main/java/dev/gravaai/app/agent/**` and
  `java/gravaai-app/src/test/java/dev/gravaai/app/agent/**` (plus this document).
