#!/usr/bin/env bash
# Build and stage the toolkit-free daemon and Qt companion.
set -euo pipefail
PROJECT_ROOT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$PROJECT_ROOT/scripts/lib/sanitize-host-env.sh"
sanitize_host_env
STAGE="$PROJECT_ROOT/build/stage"
PROFILE=release
while (($#)); do
  case "$1" in
    --release) PROFILE=release; shift ;;
    --debug) PROFILE=debug; shift ;;
    --stage) STAGE="${2:?missing stage}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$STAGE" == /* && "$STAGE" != / && "$STAGE" != "$PROJECT_ROOT" ]] || exit 2
ARGS=(--locked --manifest-path "$PROJECT_ROOT/linux/Cargo.toml")
[[ "$PROFILE" == release ]] && ARGS+=(--release)
CORE_TARGET="$(uname -m)-unknown-linux-musl"
command -v musl-gcc >/dev/null || { echo 'musl-gcc is required; use --container' >&2; exit 1; }
cargo build "${ARGS[@]}" --target "$CORE_TARGET" --no-default-features --bin gravaai
cargo build "${ARGS[@]}" --features ui --bin gravaai-ui
TARGET_DIR="${CARGO_TARGET_DIR:-$PROJECT_ROOT/linux/target}"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/share/gravaai" "$STAGE/share/icons"
install -m 0755 "$TARGET_DIR/$CORE_TARGET/$PROFILE/gravaai" "$STAGE/bin/gravaai"
install -m 0755 "$TARGET_DIR/$PROFILE/gravaai-ui" "$STAGE/bin/gravaai-ui"
if readelf -l "$STAGE/bin/gravaai" | grep -q INTERP; then
  echo 'Daemon must be statically linked' >&2; exit 1
fi
cp -a "$PROJECT_ROOT/linux/assets/tray" "$STAGE/share/gravaai/"
cp -a "$PROJECT_ROOT/linux/assets/icons/." "$STAGE/share/icons/"
printf '%s\n' "${APP_VERSION:-$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)"/\1/p' "$PROJECT_ROOT/linux/Cargo.toml" | head -1)}" > "$STAGE/share/gravaai/VERSION"
[[ ! -f "$PROJECT_ROOT/LICENSE" ]] || cp "$PROJECT_ROOT/LICENSE" "$STAGE/share/gravaai/"
