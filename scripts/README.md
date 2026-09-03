# Provider test scripts

Dev-only helpers to compare OpenAI-compatible models/endpoints for meeting
**transcription** and **summarization** on your own audio. They drive the
app's *real* processing pipeline headlessly (no GTK, no daemon) through the
same `--process` child the daemon spawns, so the output matches what the
applet would produce — only the endpoint/model is swappable per run.

## test-openai-compatible.sh

```bash
OPENAI_API_KEY=sk-... ./scripts/test-openai-compatible.sh <audio-file> [out-dir]
```

Optional env overrides: `OPENAI_BASE_URL` (default
`https://api.openai.com/v1`), `OPENAI_TRANSCRIPTION_MODEL` (default
`whisper-1`), `OPENAI_SUMMARIZATION_MODEL` (default `gpt-4o-mini`), `BIN`
(path to the `meeting-recorder` binary).

The script uses a sandboxed `$HOME` so your real config is never touched;
results land in `./tmp/<timestamp>/` (git-ignored).
