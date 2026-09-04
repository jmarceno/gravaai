#!/usr/bin/env bash
# Build a GravaAi AppImage (Type-2).
#
# Bundles the release binary + tray/icon assets. System libraries (GTK4,
# libadwaita, ffmpeg, pactl, …) stay on the host — same contract as today's
# standalone binary / pacman package.
#
# Usage:
#   ./linux/packaging/appimage/build-appimage.sh [version] [output-dir]
#
# Environment:
#   SKIP_BUILD=1     reuse an existing release binary
#   APPIMAGETOOL=…   path to appimagetool (otherwise downloaded once)
#
# Host AppImages (e.g. Cursor) export APPIMAGE/APPDIR into this shell — clear
# them for the packaging tools so they are not confused with our payload.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LINUX="$ROOT/linux"
MANIFEST="$LINUX/Cargo.toml"
VERSION="${1:-}"
OUT_DIR="${2:-$LINUX/packaging/appimage/out}"
APP_NAME=gravaai
DESKTOP_ID=io.github.jmarceno.GravaAi

if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -m1 '^version' "$MANIFEST" | sed -E 's/.*"([^"]+)".*/\1/')"
fi

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ARCH=x86_64 ;;
  aarch64|arm64) ARCH=aarch64 ;;
  *)
    echo "Unsupported architecture: $ARCH" >&2
    exit 1
    ;;
esac

# Do not inherit a host IDE's AppImage environment.
unset APPIMAGE APPDIR OWD ARGV0 || true

echo "==> Building GravaAi AppImage v${VERSION} (${ARCH})"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> cargo build --release"
  cargo build --release --locked --manifest-path "$MANIFEST"
fi

# linux/target may be a symlink (e.g. to a scratch disk); -x follows it.
BIN="$LINUX/target/release/${APP_NAME}"
if [[ ! -x "$BIN" ]]; then
  echo "Release binary not found at $BIN (build first, or unset SKIP_BUILD)" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

APPDIR="$STAGE/GravaAi.AppDir"
mkdir -p \
  "$APPDIR/usr/bin" \
  "$APPDIR/usr/share/${APP_NAME}/tray" \
  "$APPDIR/usr/share/${APP_NAME}/icons" \
  "$APPDIR/usr/share/applications" \
  "$APPDIR/usr/share/icons/hicolor"

install -Dm755 "$BIN" "$APPDIR/usr/bin/${APP_NAME}"
printf '%s\n' "$VERSION" > "$APPDIR/usr/share/${APP_NAME}/VERSION"

cp -a "$LINUX/assets/tray/." "$APPDIR/usr/share/${APP_NAME}/tray/"
cp -a "$LINUX/assets/icons/." "$APPDIR/usr/share/${APP_NAME}/icons/"

# Hicolor theme icons (launcher / window).
icons_src="$LINUX/assets/icons/hicolor"
for size in 16 24 32 48 64 128 256; do
  install -Dm644 \
    "${icons_src}/${size}x${size}/apps/${APP_NAME}.png" \
    "${APPDIR}/usr/share/icons/hicolor/${size}x${size}/apps/${APP_NAME}.png"
done
install -Dm644 \
  "${icons_src}/scalable/apps/${APP_NAME}.svg" \
  "${APPDIR}/usr/share/icons/hicolor/scalable/apps/${APP_NAME}.svg"

# Desktop entry + AppDir icon (appimagetool requires both at AppDir root).
sed "s/@VERSION@/${VERSION}/g" \
  "$LINUX/packaging/appimage/${DESKTOP_ID}.desktop" \
  > "$APPDIR/${DESKTOP_ID}.desktop"
cp "$APPDIR/${DESKTOP_ID}.desktop" \
  "$APPDIR/usr/share/applications/${DESKTOP_ID}.desktop"
install -Dm644 \
  "${icons_src}/256x256/apps/${APP_NAME}.png" \
  "$APPDIR/${APP_NAME}.png"

# AppRun → the binary (thin AppImage; no library bundling).
ln -sf "usr/bin/${APP_NAME}" "$APPDIR/AppRun"

# --- appimagetool -----------------------------------------------------------
TOOLS="$LINUX/packaging/appimage/.tools"
mkdir -p "$TOOLS"
if [[ -n "${APPIMAGETOOL:-}" && -x "$APPIMAGETOOL" ]]; then
  TOOL="$APPIMAGETOOL"
else
  TOOL="$TOOLS/appimagetool-${ARCH}.AppImage"
  if [[ ! -x "$TOOL" ]]; then
    echo "==> Downloading appimagetool (${ARCH})"
    url="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage"
    curl -fsSL -o "$TOOL" "$url"
    chmod +x "$TOOL"
  fi
fi

mkdir -p "$OUT_DIR"
OUT_NAME="${APP_NAME}-${VERSION}-${ARCH}.AppImage"
OUT_PATH="$OUT_DIR/$OUT_NAME"

echo "==> Packing $OUT_PATH"
# Containers / nested AppImage hosts often lack FUSE for nested AppImages.
export APPIMAGE_EXTRACT_AND_RUN=1
# ARCH is read by appimagetool for the runtime selection.
export ARCH
(cd "$OUT_DIR" && "$TOOL" -n "$APPDIR" "$OUT_NAME")

chmod +x "$OUT_PATH"
echo "==> Done: $OUT_PATH"
ls -lh "$OUT_PATH"
