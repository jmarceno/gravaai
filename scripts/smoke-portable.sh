#!/usr/bin/env bash
# Test clean install, extraction reuse, bundled audio and all QML pages.
set -euo pipefail
PROJECT_ROOT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$PROJECT_ROOT/scripts/lib/sanitize-host-env.sh"
sanitize_host_env
RUN_PATH="${1:?Usage: smoke-portable.sh path/to/GravaAi-portable.run}"
RUN_PATH="$(realpath "$RUN_PATH")"
[[ -x "$RUN_PATH" ]] || exit 1
SANDBOX="$(mktemp -d -t gravaai-smoke-XXXXXX)"
trap 'rm -rf "$SANDBOX"' EXIT
export XDG_CACHE_HOME="$SANDBOX/cache" XDG_CONFIG_HOME="$SANDBOX/config"
export XDG_DATA_HOME="$SANDBOX/data" XDG_STATE_HOME="$SANDBOX/state"
export XDG_RUNTIME_DIR="$SANDBOX/runtime"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"
"$RUN_PATH" --version
ROOT="$("$RUN_PATH" --portable-root)"
[[ -x "$ROOT/bin/gravaai" && -x "$ROOT/bin/gravaai-ui" && -f "$ROOT/.portable-marker" ]]
MTIME="$(stat -c %y "$ROOT/.portable-marker")"
"$RUN_PATH" --version >/dev/null
[[ "$(stat -c %y "$ROOT/.portable-marker")" == "$MTIME" ]]
for binary in gravaai gravaai-ui ffmpeg ffprobe pactl; do
  OUT="$(env -u LD_LIBRARY_PATH ldd "$ROOT/bin/$binary" 2>&1 || true)"
  if grep -q 'not found' <<<"$OUT"; then echo "$OUT" >&2; exit 1; fi
done
if readelf -d "$ROOT/bin/gravaai" | grep -Eqi 'Qt|gtk|adwaita'; then
  echo 'Daemon unexpectedly links a GUI toolkit' >&2; exit 1
fi
LD_LIBRARY_PATH="$ROOT/lib" "$ROOT/bin/ffmpeg" -hide_banner -demuxers 2>/dev/null | grep -E ' D[[:space:]]+pulse[[:space:]]' >/dev/null
LD_LIBRARY_PATH="$ROOT/lib" "$ROOT/bin/ffmpeg" -v error -f lavfi -i sine=frequency=440:duration=1 "$SANDBOX/recording.mp3"
LD_LIBRARY_PATH="$ROOT/lib" "$ROOT/bin/ffprobe" -v error -show_entries format=duration "$SANDBOX/recording.mp3"
env HOME="$SANDBOX/home" "$RUN_PATH" install
ENTRY="$XDG_DATA_HOME/applications/io.github.jmarceno.GravaAi.desktop"
[[ -f "$ENTRY" ]]
grep -Fq "Exec=$RUN_PATH" "$ENTRY"
SHOTS_ARGS=()
if [[ -n "${GRAVAAI_QML_SHOTS:-}" ]]; then
  mkdir -p "$GRAVAAI_QML_SHOTS"
  SHOTS_ARGS=(--smoke-shots="$(realpath "$GRAVAAI_QML_SHOTS")")
fi
for size in 1332:820 960:640; do
  LOG="$SANDBOX/qml-$size.log"
  if ! QT_QPA_PLATFORM=offscreen GRAVAAI_QML_SMOKE=1 timeout 20 \
      env HOME="$SANDBOX/home" "$RUN_PATH" --window --smoke-width="${size%:*}" --smoke-height="${size#*:}" "${SHOTS_ARGS[@]}" >"$LOG" 2>&1; then
    cat "$LOG" >&2; exit 1
  fi
  if grep -Eiq 'ReferenceError|TypeError|Binding loop|Cannot assign|not a type|default property|module .* not installed|QML smoke geometry' "$LOG"; then cat "$LOG" >&2; exit 1; fi
done
set +e
dbus-run-session -- env HOME="$SANDBOX/home" "$RUN_PATH" --window >"$SANDBOX/guard.log" 2>&1
RC=$?
set -e
[[ "$RC" == 73 ]] || { cat "$SANDBOX/guard.log"; exit 1; }
echo 'Portable smoke passed: extraction/cache, clean menu install, audio helpers, QML pages and daemon guard.'
