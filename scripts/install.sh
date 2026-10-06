#!/usr/bin/env bash
# Install a portable .run as ~/.local/bin/gravaai plus its menu entry.
#
# Usage:
#   ./scripts/install.sh [path/to/*-portable.run] [--prefix <dir>] [--autostart]
#
# Without a path, uses the newest build/portable/*-portable.run.
set -euo pipefail

PROJECT_ROOT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
APP=gravaai
PREFIX="$HOME/.local"
RUN_PATH=""
INSTALL_ARGS=()

while (($#)); do
  case "$1" in
    --prefix) PREFIX="${2:?--prefix needs a dir}"; shift 2 ;;
    --prefix=*) PREFIX="${1#*=}"; shift ;;
    --autostart) INSTALL_ARGS+=(--autostart); shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    -*) echo "error: unknown option: $1" >&2; exit 2 ;;
    *) RUN_PATH="$1"; shift ;;
  esac
done

if [[ -z "$RUN_PATH" ]]; then
  RUN_PATH="$(ls -t "$PROJECT_ROOT"/build/portable/*-portable.run 2>/dev/null | head -n1 || true)"
  [[ -n "$RUN_PATH" ]] || { echo "error: no build/portable/*-portable.run; run ./scripts/build-portable.sh first" >&2; exit 1; }
fi
[[ -f "$RUN_PATH" ]] || { echo "error: not found: $RUN_PATH" >&2; exit 1; }

TARGET="$PREFIX/bin/$APP"
mkdir -p "$PREFIX/bin"
# Copy to a temp name and rename: replacing a running executable in place
# fails with "Text file busy".
install -m 0755 "$RUN_PATH" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
echo "installed: $TARGET"

# The app writes its own desktop entries pointing at the stable .run path.
"$TARGET" install "${INSTALL_ARGS[@]}"
command -v update-desktop-database >/dev/null 2>&1 \
  && update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true

[[ ":$PATH:" == *":$PREFIX/bin:"* ]] || echo "note: $PREFIX/bin is not on PATH"
