#!/usr/bin/env bash
# test-openai-compatible.sh — drive the real processing pipeline headlessly.
#
# Runs one transcription+summarization job through the same `--process` child
# the daemon uses, against any OpenAI-compatible endpoint. Useful for comparing
# models/endpoints on your own audio without opening the GUI.
#
# Usage:
#   OPENAI_API_KEY=sk-... ./scripts/test-openai-compatible.sh <audio> [out-dir]
#
# Env overrides (all optional except the key):
#   OPENAI_API_KEY            Bearer token (required unless the endpoint needs none)
#   OPENAI_BASE_URL           Default: https://api.openai.com/v1
#   OPENAI_TRANSCRIPTION_MODEL Default: whisper-1
#   OPENAI_SUMMARIZATION_MODEL Default: gpt-4o-mini
#   BIN                       meeting-recorder binary (default: linux/target/debug/meeting-recorder)
set -euo pipefail

AUDIO="${1:?usage: $0 <audio-file> [out-dir]}"
OUT_DIR="${2:-$(pwd)/tmp/$(date +%Y-%m-%d_%H-%M-%S)}"
BIN="${BIN:-$(dirname "$0")/../linux/target/debug/meeting-recorder}"
BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
STT_MODEL="${OPENAI_TRANSCRIPTION_MODEL:-whisper-1}"
CHAT_MODEL="${OPENAI_SUMMARIZATION_MODEL:-gpt-4o-mini}"

if [ ! -x "$BIN" ]; then
    echo "Building debug binary first…" >&2
    cargo build --manifest-path "$(dirname "$0")/../linux/Cargo.toml"
fi

# Sandboxed HOME so the test never touches the real config — but note the API
# key is then read from this temp config, not from your keyring.
SANDBOX="$(mktemp -d)"
mkdir -p "$SANDBOX/.config/meeting-recorder" "$OUT_DIR"
cat > "$SANDBOX/.config/meeting-recorder/config.json" <<EOF
{
  "transcription_service": "openai",
  "summarization_service": "openai",
  "openai_api_key": "${OPENAI_API_KEY:-}",
  "openai_base_url": "$BASE_URL",
  "openai_transcription_model": "$STT_MODEL",
  "openai_summarization_model": "$CHAT_MODEL",
  "output_folder": "$OUT_DIR"
}
EOF
chmod 600 "$SANDBOX/.config/meeting-recorder/config.json"

AUDIO_ABS="$(realpath "$AUDIO")"
WORK="$OUT_DIR/manual-run"
mkdir -p "$WORK"
cp "$AUDIO_ABS" "$WORK/recording.mp3"

echo "Input : $AUDIO_ABS" | tee "$OUT_DIR/run-info.txt"
echo "Models: $STT_MODEL / $CHAT_MODEL @ $BASE_URL" | tee -a "$OUT_DIR/run-info.txt"
HOME="$SANDBOX" "$BIN" --process \
    "$WORK/recording.mp3" "$WORK/transcript.md" "$WORK/notes.md" \
    | tee -a "$OUT_DIR/run-info.txt"
echo "Wrote: $WORK/transcript.md $WORK/notes.md"
rm -rf "$SANDBOX"
