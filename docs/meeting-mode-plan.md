# Meeting mode: bringing Gravaai's meeting features into Gravaai

Status: Phase 1 and Phase 2 implemented (2026-10-23); the Check pass with a
real long meeting is still open. Written 2026-10-23 after reading both trees
(`~/Projects/Gravaai` at `ca38addb`, `~/Projects/gravaai` at `e7e505d`).

## 1. What each app has today

| Capability | Gravaai (Electron/TS) | Gravaai (Rust/Qt) |
| --- | --- | --- |
| Audio capture | `parec` per device (mic, `@DEFAULT_MONITOR@`) in `electron/ipc/recording/cosmic.ts`, **only alongside screen capture**; tracks are `amix`ed into the MP4 and the PCM sidecars deleted | ffmpeg `-f pulse`, mic left / system right in one stereo MP3, `speechnorm` per channel, pause = segments + concat (`linux/src/audio/`) |
| Transcription | bundled `whisper-cli` v1.8.4 (CPU), `ggml-small` only, output = caption cues/SRT (`electron/ipc/captions/`) | whisper.cpp with `-di` stereo diarization → `[HH:MM:SS] **Me:** / **Remote:**` Markdown; OpenAI-compatible STT with 10-min chunking + timestamp shifting; CrispASR (experimental) |
| Summarization | none | Ollama or OpenAI-compatible chat; tuned prompt (Summary / Key points / Decisions / Action items / Open questions), language instruction, empty-section stripping, auto-title |
| Library | `.gravaai` projects, `EditorProjectData { videoPath, editor }`, dashboard grid | folder per meeting: `recording.mp3`, `transcript.md`, `notes.md`, `meeting.json` |

Worth porting (logic, rewritten in TS): mic/system channel separation + `-di`
labelling (transcription input only, never the audio people listen to), whisper JSON → Markdown formatter, OpenAI STT chunking and
`shift_timestamps`, the three prompts, `with_language_instruction`,
`drop_empty_sections`, auto-title, "summarize only from existing transcript".

Not worth porting: the daemon/D-Bus/child-process split, Qt UI, tray, call
detection, CrispASR, the Ollama-specific provider (Ollama exposes an
OpenAI-compatible `/v1`, so the generic client already covers it).

## 2. Project-type decision

Recommendation: **one project type, two views.**

- The `.gravaai` file gains an optional `meeting` block
  (`audioPath`, `transcriptPath`, `notesPath`, `language`, `title`, job state),
  and `videoPath` becomes optional.
- Project **with video** → the existing editor, plus a new "Transcript & notes"
  panel. This also gives demo videos transcripts/notes for free.
- Project **without video** (audio-only meeting) → a small Meeting view
  (audio player, transcript, notes, re-run buttons). It must never open the
  video editor: `VideoEditor`/`VideoPlayback` assume a video track everywhere,
  and making them tolerate audio-only would cost more than the whole meeting view.

So "meeting vs demo" is not a stored type; it is a recording-mode choice in the
HUD ("Record screen" on/off) and whatever files the project ends up having.
This avoids two parallel library/persistence code paths.

## 3. Local summarization model (≈4 GB budget)

This machine: Ryzen 7 3700X, RX 550/560 with 4 GB VRAM (Vulkan via RADV
works), 32 GB RAM but usually only ~4 GB free.

- **Runtime:** bundle `llama-server` from the official llama.cpp Linux Vulkan
  release (prebuilt, no compiler), started on demand on 127.0.0.1 and stopped
  after the job. It speaks the OpenAI chat API, so **local and API use the same
  client code**: "local" is just a base URL Gravaai manages itself.
- **Default model:** Qwen3.5-2B-Instruct Q4_K_M (~1.3 GB, Apache-2.0,
  non-thinking by default, long native context, good multilingual coverage for
  Portuguese meetings).
- **Quality option:** Qwen3.5-4B Q4_K_M (~2.7 GB, run with thinking off), or
  Phi-4-mini / SmolLM3-3B as alternatives. Offered as a dropdown with sizes,
  downloaded on first use like the whisper model.
- **Context:** cap at 16k tokens (about 1 h of talk). Longer transcripts go
  through map-reduce: summarize ~10k-token chunks into partial notes, then
  merge them with the same section prompt. Small models degrade badly on very
  long inputs, so chunking matters more than the advertised window.
- **Expected speed (estimate, verify on the box):** a 1 h meeting on Vulkan
  should take roughly 1–2 min; CPU fallback a few minutes. Whisper and the LLM
  never run at the same time.
- **API option:** any OpenAI-compatible endpoint (OpenAI, OpenRouter, Ollama,
  LM Studio…), base URL + model + key; key stored with Electron `safeStorage`.
  Used separately for transcription and/or summarization, as in Gravaai.

### Future: summaries that see the screen (not built now, designed for)

When a meeting has video, someone may share slides or a demo, and notes
should reflect it. That needs a vision-capable model through an API (heavy,
optional). Nothing of it is built in this plan, but these choices keep it a
drop-in addition instead of a rewrite:

- **Summarizer input is a context object, not a string.** The pipeline hands
  the summarizer `MeetingContext { transcript, language, durationMs,
  videoPath?, visuals?: { atMs, imagePath, ocrText? }[] }`. Today only
  `transcript` is filled; the prompt builder ignores what it can't use.
- **Providers declare capabilities** (`text`, `images`, `video`). The local
  Qwen3.5-2B is `text`; an API model can be configured as `images` or
  `video`. The pipeline picks what to send based on that, so adding visual
  context never touches the local path.
- **The chat client speaks OpenAI content parts** (`text` + `image_url`)
  from day one, even though only text is sent at first.
- **Stages are separate and composable** (transcribe → [visual context] →
  summarize → title), each writing its own file, so a "visual context" stage
  can slot in later: ffmpeg scene-change keyframes (`select='gt(scene,0.3)'`)
  timestamped and merged into the timeline, or the whole video sent to a
  model that accepts video.
- **Transcript stays timestamped** (`[HH:MM:SS]`) so screen moments can be
  aligned with what was said.

Transcription model: add a choice beyond `ggml-small` —
`large-v3-turbo-q5_0` (~550 MB) is the sweet spot for non-English meetings.
Optionally rebuild the bundled whisper with `GGML_VULKAN=ON` later; CPU works.

## 4. The plan (two big phases + a check)

### Phase 1 — Meeting engine (main process, no new screens yet)

1. **Audio-only recording on COSMIC.** In `cosmic.ts`, split the parec track
   handling out of the video path so a recording can start with zero video
   (no capture helper, no x264 encoder). Pause/resume keep working by
   dropping chunks, as today.
2. **Keep the channels apart, but never for listening.** What anyone hears
   (the MP4's audio track, the meeting view's player) is always the normal
   mixed audio, exactly as today. Only when the recording is a **meeting**
   (the HUD toggle, Phase 2) do we also keep the mic and system tracks as two
   mono sidecars (`<name>.mic.opus`, `<name>.system.opus`, with Gravaai's
   `highpass` + `speechnorm`). The mic-left/system-right stereo file whisper
   needs for Me/Remote labels is built in a temp dir at transcription time
   and deleted afterwards; it is never stored or played. Demo recordings keep
   no sidecars and behave exactly as now. Audio-only meetings store the mixed
   file for playback plus the two sidecars.
3. **Transcript to Markdown.** Reuse the existing whisper-cli runner and model
   download code; add `-di`, `-l auto`, `-oj` for meeting transcription and a
   port of `parse_whisper_cpp_output` → `transcript.md`. Model selection
   (small / large-v3-turbo-q5) with the same download/progress UI plumbing.
   Captions stay as they are — separate code path, same binary.
4. **LLM client + local server.** One OpenAI-compatible client (chat with
   content parts, audio transcription with Gravaai's 10-min chunking and
   timestamp shift), behind a provider interface with declared capabilities
   and a `MeetingContext` input (see "Future: summaries that see the screen").
   A `llamaServer.ts` that downloads/launches/stops the bundled
   `llama-server` and the GGUF model. Map-reduce for long transcripts.
5. **Pipeline + jobs.** Port prompts, language instruction, empty-section
   stripping, auto-title. Modes: full / transcribe-only / summarize-only.
   Jobs run in the main process with progress events, survive closing the
   editor window, and are recorded in the project so a crash shows "Retry".
6. **Settings.** New "Meeting" section in `appSettingsStore`: services
   (local/API) per stage, models, base URL/key, prompts (empty = default),
   auto-process after stop, auto-title.

Done when: a script/dev IPC call can record 2 min of audio-only, and produce
`transcript.md` with Me/Remote lines and `notes.md`, both locally and via API.

**Phase 1 status (2026-10-23): implemented, not yet committed.** Code in
`electron/ipc/meeting/` (+ `register/meeting.ts`, changes in
`recording/cosmic.ts`). Measured on this machine (Ryzen 3700X, RX 560):

- whisper large-v3-turbo-q5 (CPU build): ~0.45× real time → about 25–30 min
  per hour of meeting. The bundled whisper is CPU-only; a Vulkan build needs
  `glslc` (shaderc) installed. Open decision.
- Qwen3.5-2B via llama-server Vulkan: ~200 tok/s prompt, ~29 tok/s answer
  (CPU only: ~187 / ~19). A 1 h transcript (~11k tokens) ≈ 1.5 min.
- Qwen3.5 thinks by default under llama.cpp; local runs send
  `enable_thinking: false`. API runs send no `max_tokens` so hosted reasoning
  models are not cut off.
- Qwen3.5 GGUFs ship an `mmproj` (vision) file, so the future screen-context
  stage could even run locally.

### Phase 2 — Product: projects, HUD, views

1. **Project model.** `videoPath` optional + `meeting` block, bump
   `PROJECT_VERSION`, update `projectPersistence.ts` validation and
   `electron/ipc/project/manager.ts`. Files live beside the project:
   `<name>.meeting.opus`, `<name>.transcript.md`, `<name>.notes.md`.
2. **HUD.** A "Meeting" toggle with "Record screen" on/off; with screen off,
   skip the source picker and webcam. Mic + system audio default on. This
   toggle is the one place that decides whether speaker sidecars are kept
   (Phase 1 step 2); a demo recording never gets them.
   "Transcribe & summarize after stop" follows the setting.
3. **Meeting view** for audio-only projects: audio player with seek-from-
   timestamp, rendered transcript and notes (editable, saved back to `.md`),
   buttons: Transcribe, Summarize, Regenerate title, Open folder, Copy notes.
4. **Editor panel** for video projects: same transcript/notes component in a
   side panel; clicking a timestamp seeks the video. "Make captions from
   transcript" can come later; not in scope.
5. **Dashboard.** Meeting cards (no thumbnail → waveform/icon, duration,
   title, job status badge), filter by "Meetings", import audio file as a new
   meeting (Gravaai's import feature).

Done when: from the HUD you can record a meeting with and without screen, get
notes automatically, reopen it from the dashboard, and re-run each stage.

**As built.** No project schema change: a meeting is its media file plus
sidecars, marked by `kind: "meeting"` in `<base>.meeting.json`
(`electron/ipc/meeting/library.ts` lists, imports, renames and trashes them).
The dashboard has a Meetings section; the meeting view (`src/components/meeting/`)
is nested inside the dashboard modal so it always opens above it. The HUD hands
the meeting to the editor window through the main process
(`meeting-request-open` / `meeting-take-open-request`), because the two windows
load from different origins and do not share localStorage. The title is
regenerated with the notes ("Name meetings from their notes"), not by its own
button. Settings has a Meetings tab for services, models, API, prompt and
downloads.

Silence: whisper invents text on silent audio, so local transcription runs
with whisper's voice detection (`--vad`, Silero model, 885 KB, downloaded on
first use). A recording with no speech fails with "No speech was found", and
clears its old transcript, notes and any automatic title (`titleSource:
"auto"`); a title the user typed is never replaced. The API transcription
path has no voice detection yet.

### Check

Playwright pass on the UI flows plus a real 20–30 min Portuguese meeting
recording through local whisper + local Qwen3.5-2B and through the API path;
measure time and RAM/VRAM and pick the default model from the numbers.

## 5. Size and risks

- Phase 1 is the larger, riskier one (process management, downloads,
  chunking); Phase 2 is mostly UI over existing patterns.
- Biggest risk: audio-only projects leaking into code that assumes a video
  (thumbnails, recent projects, editor routing). Mitigation: route by
  "has video" in one place and never construct the editor without it.
- Second risk: 2B summaries being too shallow on long meetings. Mitigation:
  map-reduce plus an easy switch to the 4B model or the API.
- Prebuilt llama.cpp Vulkan binary depends on host Vulkan loader — fine on
  this machine; fall back to the CPU build if Vulkan init fails.
