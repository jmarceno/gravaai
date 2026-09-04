#!/usr/bin/env bash
# Build the self-contained GravaAI Type-2 AppImage.
#
# The payload deliberately contains two executables: usr/bin/gravaai is the
# toolkit-free daemon and usr/libexec/gravaai/gravaai-ui is the Qt/QML window.
# Qt, QML, the platform plugins and the audio helpers are copied into the
# image; whisper.cpp, Ollama and model files remain opt-in user installs.
#
# Usage: build-appimage.sh [version] [output-dir]
# Environment:
#   SKIP_BUILD=1       reuse release binaries
#   APPIMAGETOOL=path  use a caller-provided appimagetool
#   QMAKE=path         use a caller-provided qmake6
#   NO_STRIP=true       preserve RELR metadata during linuxdeploy-like stages

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LINUX="$ROOT/linux"
MANIFEST="$LINUX/Cargo.toml"
VERSION="${1:-}"
OUT_DIR="${2:-$LINUX/packaging/appimage/out}"
APP_NAME="gravaai"
DESKTOP_ID="io.github.jmarceno.GravaAi"

if [[ -z "$VERSION" ]]; then
  VERSION="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)"/\1/p' "$MANIFEST" | head -n1)"
fi
[[ -n "$VERSION" ]] || { echo "Could not determine package version" >&2; exit 1; }

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ARCH=x86_64 ;;
  aarch64|arm64) ARCH=aarch64 ;;
  *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

# Cursor/OpenCode export these for their own AppImage.  Never let those
# values influence this build or leak into a child process in the payload.
unset APPIMAGE APPDIR OWD ARGV0 || true
clean_inherited_library_path() {
  local raw="${LD_LIBRARY_PATH:-}" cleaned=() entry
  local -a entries=()
  IFS=: read -r -a entries <<< "$raw"
  for entry in "${entries[@]:-}"; do
    [[ -z "$entry" || "$entry" == /tmp/.mount_* ]] && continue
    cleaned+=("$entry")
  done
  if ((${#cleaned[@]})); then
    (IFS=:; printf '%s' "${cleaned[*]}")
  fi
}
export LD_LIBRARY_PATH="$(clean_inherited_library_path)"

echo "==> Building GravaAI AppImage v${VERSION} (${ARCH})"
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  echo "==> Building toolkit-free daemon"
  cargo build --release --locked --manifest-path "$MANIFEST" \
    --no-default-features --bin gravaai
  echo "==> Building Qt/QML companion"
  cargo build --release --locked --manifest-path "$MANIFEST" \
    --features ui --bin gravaai-ui
fi

DAEMON_BIN="$LINUX/target/release/gravaai"
UI_BIN="$LINUX/target/release/gravaai-ui"
for required in "$DAEMON_BIN" "$UI_BIN"; do
  [[ -x "$required" ]] || {
    echo "Missing release executable: $required (unset SKIP_BUILD to build it)" >&2
    exit 1
  }
done

QMAKE="${QMAKE:-qmake6}"
command -v "$QMAKE" >/dev/null 2>&1 || {
  echo "qmake6 is required to stage Qt (set QMAKE=/path/to/qmake6)" >&2
  exit 1
}
QMAKE_BIN="$(command -v "$QMAKE")"
QT_LIBS="$("$QMAKE_BIN" -query QT_INSTALL_LIBS)"
QT_PLUGINS="$("$QMAKE_BIN" -query QT_INSTALL_PLUGINS)"
QT_QML="$("$QMAKE_BIN" -query QT_INSTALL_QML)"
[[ -d "$QT_LIBS" && -d "$QT_PLUGINS" && -d "$QT_QML" ]] || {
  echo "qmake6 reported incomplete Qt paths" >&2
  exit 1
}
command -v qmlimportscanner >/dev/null 2>&1 || {
  echo "qmlimportscanner is required to validate QML staging" >&2
  exit 1
}

STAGE_ROOT="$(mktemp -d)"
STAGE_APPDIR="$STAGE_ROOT/GravaAi.AppDir"
cleanup() { rm -rf "$STAGE_ROOT"; }
trap cleanup EXIT

mkdir -p \
  "$STAGE_APPDIR/usr/bin" \
  "$STAGE_APPDIR/usr/libexec/gravaai" \
  "$STAGE_APPDIR/usr/lib" \
  "$STAGE_APPDIR/usr/plugins/platforms" \
  "$STAGE_APPDIR/usr/plugins/imageformats" \
  "$STAGE_APPDIR/usr/plugins/iconengines" \
  "$STAGE_APPDIR/usr/plugins/xcbglintegrations" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration" \
  "$STAGE_APPDIR/usr/plugins/wayland-graphics-integration-client" \
  "$STAGE_APPDIR/usr/qml" \
  "$STAGE_APPDIR/usr/share/$APP_NAME/tray" \
  "$STAGE_APPDIR/usr/share/$APP_NAME/icons" \
  "$STAGE_APPDIR/usr/share/$APP_NAME" \
  "$STAGE_APPDIR/usr/share/applications" \
  "$STAGE_APPDIR/usr/share/icons/hicolor"

install -Dm755 "$DAEMON_BIN" "$STAGE_APPDIR/usr/bin/$APP_NAME"
install -Dm755 "$UI_BIN" "$STAGE_APPDIR/usr/libexec/$APP_NAME/$APP_NAME-ui"
printf '%s\n' "$VERSION" > "$STAGE_APPDIR/usr/share/$APP_NAME/VERSION"
cp -a "$LINUX/assets/tray/." "$STAGE_APPDIR/usr/share/$APP_NAME/tray/"
cp -a "$LINUX/assets/icons/." "$STAGE_APPDIR/usr/share/$APP_NAME/icons/"

icons_src="$LINUX/assets/icons/hicolor"
for size in 16 24 32 48 64 128 256; do
  install -Dm644 "$icons_src/${size}x${size}/apps/$APP_NAME.png" \
    "$STAGE_APPDIR/usr/share/icons/hicolor/${size}x${size}/apps/$APP_NAME.png"
done
install -Dm644 "$icons_src/scalable/apps/$APP_NAME.svg" \
  "$STAGE_APPDIR/usr/share/icons/hicolor/scalable/apps/$APP_NAME.svg"
sed "s/@VERSION@/$VERSION/g" "$LINUX/packaging/appimage/$DESKTOP_ID.desktop" \
  > "$STAGE_APPDIR/$DESKTOP_ID.desktop"
install -Dm644 "$STAGE_APPDIR/$DESKTOP_ID.desktop" \
  "$STAGE_APPDIR/usr/share/applications/$DESKTOP_ID.desktop"
install -Dm644 "$icons_src/256x256/apps/$APP_NAME.png" "$STAGE_APPDIR/$APP_NAME.png"

# qmlimportscanner must see the generated CXX-Qt module, not a hand-written
# placeholder.  This catches a missing qmldir/plugin before appimagetool.
QML_MODULE_ROOT="$(find "$LINUX/target/release/build" -type d \
  -path '*/out/qt-build-utils/qml_modules' -print -quit)"
[[ -n "$QML_MODULE_ROOT" ]] || {
  echo "CXX-Qt QML module staging directory was not generated" >&2
  exit 1
}
[[ -f "$QML_MODULE_ROOT/io/github/jmarceno/gravaai/qmldir" ]] || {
  echo "Generated GravaAI qmldir is missing" >&2
  exit 1
}
SCAN_JSON="$STAGE_ROOT/qmlimportscanner.json"
qmlimportscanner -rootPath "$LINUX/qml" -importPath "$QML_MODULE_ROOT" > "$SCAN_JSON"
for import_name in QtQuick QtQuick.Controls QtQuick.Layouts QtQuick.Dialogs QtQuick.Window io.github.jmarceno.gravaai; do
  grep -Fq '"name": "'$import_name'"' "$SCAN_JSON" || {
    echo "qmlimportscanner did not report required import $import_name" >&2
    exit 1
  }
done

# Copy only the QML modules used by the source (plus their Basic style and
# implementation plugins). Keeping the module directories intact makes the
# same import paths work from an extracted AppImage and from a mounted one.
for module in QtQml QtCore QtQuick QtQuick/Controls QtQuick/Dialogs QtQuick/Layouts QtQuick/Window; do
  [[ -d "$QT_QML/$module" ]] || { echo "Qt QML module missing: $module" >&2; exit 1; }
  mkdir -p "$STAGE_APPDIR/usr/qml/$(dirname "$module")"
  cp -a "$QT_QML/$module" "$STAGE_APPDIR/usr/qml/$(dirname "$module")/"
done

copy_plugin() {
  local relative="$1" source="$QT_PLUGINS/$1"
  [[ -f "$source" ]] || { echo "Required Qt plugin missing: $relative" >&2; exit 1; }
  install -Dm755 "$source" "$STAGE_APPDIR/usr/plugins/$relative"
}
copy_plugin platforms/libqxcb.so
copy_plugin platforms/libqwayland.so
copy_plugin platforms/libqoffscreen.so
copy_plugin imageformats/libqsvg.so
copy_plugin iconengines/libqsvgicon.so
copy_plugin xcbglintegrations/libqxcb-glx-integration.so
copy_plugin xcbglintegrations/libqxcb-egl-integration.so
copy_plugin wayland-shell-integration/libxdg-shell.so
copy_plugin wayland-shell-integration/libwl-shell-plugin.so
copy_plugin wayland-shell-integration/libfullscreen-shell-v1.so
copy_plugin wayland-shell-integration/libivi-shell.so
copy_plugin wayland-shell-integration/libqt-shell.so
copy_plugin wayland-graphics-integration-client/libqt-plugin-wayland-egl.so
copy_plugin wayland-graphics-integration-client/libshm-emulation-server.so

# Qt's QML resources are compiled into gravaai-ui, while qt.conf and these
# environment paths make dynamic imports deterministic in both AppImage modes.
cat > "$STAGE_APPDIR/usr/bin/qt.conf" <<'EOF'
[Paths]
Prefix=..
Plugins=plugins
Qml2Imports=qml
EOF

cat > "$STAGE_APPDIR/AppRun" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
[[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]] && SCRIPT_DIR="."
HERE="$(cd "$SCRIPT_DIR" && pwd -P)"

# The AppImage runtime normally sets these to this mount. If a host IDE left
# stale values behind, reject them and continue from the mount containing this
# AppRun. Presence of both executables is the ownership check.
if [[ -n "${APPDIR:-}" && "${APPDIR}" != "$HERE" ]]; then
  echo "GravaAI: ignoring foreign APPDIR=${APPDIR}" >&2
  unset APPDIR
fi
if [[ -n "${APPIMAGE:-}" ]]; then
  APPIMAGE_NAME="${APPIMAGE##*/}"
  if [[ ! -f "${APPIMAGE}" || "${APPIMAGE_NAME}" != gravaai-*.AppImage ]]; then
    echo "GravaAI: ignoring foreign APPIMAGE=${APPIMAGE}" >&2
    unset APPIMAGE
  fi
fi
[[ -x "$HERE/usr/bin/gravaai" && -x "$HERE/usr/libexec/gravaai/gravaai-ui" ]] || {
  echo "GravaAI: incomplete AppImage mount at $HERE" >&2
  exit 70
}
export APPDIR="$HERE"
export PATH="$HERE/usr/bin:${PATH:-}"
export LD_LIBRARY_PATH="$HERE/usr/lib"
export QT_PLUGIN_PATH="$HERE/usr/plugins"
export QT_QPA_PLATFORM_PLUGIN_PATH="$HERE/usr/plugins/platforms"
export QML2_IMPORT_PATH="$HERE/usr/qml"
export QT_QUICK_CONTROLS_STYLE=Basic
export QT_FORCE_STDERR_LOGGING=1
export XDG_DATA_DIRS="$HERE/usr/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
exec "$HERE/usr/bin/gravaai" "$@"
EOF
chmod 755 "$STAGE_APPDIR/AppRun"

# Recursive dependency collection. The dynamic loader and glibc remain
# legitimate host platform components; everything else required by the app,
# Qt, platform plugins or bundled helpers is copied under usr/lib.
declare -A SEEN_LIBS=()
is_platform_lib() {
  local base="$(basename "$1")"
  case "$base" in
    ld-linux*.so*|libc.so*|libm.so*|libpthread.so*|libdl.so*|librt.so*|libresolv.so*|libnss_*.so*|libutil.so*|libanl.so*) return 0 ;;
  esac
  return 1
}
bundle_deps() {
  local object="$1" real dep dest
  real="$(readlink -f "$object")"
  [[ -f "$real" ]] || return 0
  [[ -n "${SEEN_LIBS[$real]+seen}" ]] && return 0
  SEEN_LIBS[$real]=1
  while IFS= read -r dep; do
    [[ -f "$dep" ]] || continue
    is_platform_lib "$dep" && continue
    dest="$STAGE_APPDIR/usr/lib/$(basename "$dep")"
    if [[ ! -e "$dest" ]]; then
      install -Dm755 "$dep" "$dest"
    fi
    bundle_deps "$dep"
  done < <(ldd "$real" 2>/dev/null | sed -nE 's/.*=> (\/[^ ]+).*/\1/p; s/^[[:space:]]*(\/[^ ]+).*/\1/p' | sort -u)
}

bundle_deps "$STAGE_APPDIR/usr/bin/$APP_NAME"
bundle_deps "$STAGE_APPDIR/usr/libexec/$APP_NAME/$APP_NAME-ui"
for helper in ffmpeg ffprobe pactl; do
  source="$(command -v "$helper" || true)"
  [[ -n "$source" && -f "$source" ]] || {
    echo "Required runtime helper '$helper' is not installed on this build host" >&2
    exit 1
  }
  install -Dm755 "$(readlink -f "$source")" "$STAGE_APPDIR/usr/bin/$helper"
  bundle_deps "$STAGE_APPDIR/usr/bin/$helper"
done
for plugin in \
  "$STAGE_APPDIR/usr/plugins/platforms/libqxcb.so" \
  "$STAGE_APPDIR/usr/plugins/platforms/libqwayland.so" \
  "$STAGE_APPDIR/usr/plugins/platforms/libqoffscreen.so" \
  "$STAGE_APPDIR/usr/plugins/imageformats/libqsvg.so" \
  "$STAGE_APPDIR/usr/plugins/iconengines/libqsvgicon.so" \
  "$STAGE_APPDIR/usr/plugins/xcbglintegrations/libqxcb-glx-integration.so" \
  "$STAGE_APPDIR/usr/plugins/xcbglintegrations/libqxcb-egl-integration.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration/libxdg-shell.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration/libwl-shell-plugin.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration/libfullscreen-shell-v1.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration/libivi-shell.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-shell-integration/libqt-shell.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-graphics-integration-client/libqt-plugin-wayland-egl.so" \
  "$STAGE_APPDIR/usr/plugins/wayland-graphics-integration-client/libshm-emulation-server.so"; do
  bundle_deps "$plugin"
done
while IFS= read -r plugin; do
  bundle_deps "$plugin"
done < <(find "$STAGE_APPDIR/usr/qml" -type f -name '*.so' -print)

# Preserve Rust release optimisation while avoiding linuxdeploy stripping
# RELR metadata on toolchains that emit it.
if [[ "${NO_STRIP:-true}" == true ]] && command -v patchelf >/dev/null 2>&1; then
  patchelf --set-rpath '$ORIGIN/../lib' "$STAGE_APPDIR/usr/bin/$APP_NAME"
  patchelf --set-rpath '$ORIGIN/../../lib' "$STAGE_APPDIR/usr/libexec/$APP_NAME/$APP_NAME-ui"
  for helper in ffmpeg ffprobe pactl; do
    patchelf --set-rpath '$ORIGIN/../lib' "$STAGE_APPDIR/usr/bin/$helper"
  done
fi

cat > "$STAGE_APPDIR/usr/share/$APP_NAME/THIRD_PARTY" <<EOF
GravaAI AppImage third-party inventory
=======================================
Qt 6 (QtCore, QtGui, QtQml, QtQuick, QtQuick Controls, QtQuick Dialogs,
QtQuick Layouts, QtQuick Window, QtNetwork, QtSvg and platform plugins).
See the Qt license notices shipped by the build distribution and
https://www.qt.io/licensing/.

FFmpeg/ffprobe: $(ffmpeg -version 2>/dev/null | head -n1)
PulseAudio pactl: $(pactl --version 2>/dev/null | head -n1)
Rust dependencies are statically linked where possible; their licenses are
recorded by Cargo.lock and the project license metadata.

The host remains responsible for a compatible kernel/glibc, compositor
(X11/Wayland), session D-Bus, PipeWire/PulseAudio server, portals,
notification service and graphics drivers. Whisper.cpp, Ollama and model
artifacts are downloaded and verified on demand outside this image.
EOF

TOOLS="$LINUX/packaging/appimage/.tools"
mkdir -p "$TOOLS"
if [[ -n "${APPIMAGETOOL:-}" && -x "$APPIMAGETOOL" ]]; then
  TOOL="$APPIMAGETOOL"
else
  TOOL="$TOOLS/appimagetool-${ARCH}.AppImage"
  if [[ ! -x "$TOOL" ]]; then
    echo "==> Downloading appimagetool (${ARCH})"
    command -v curl >/dev/null 2>&1 || { echo "curl is required only to bootstrap appimagetool" >&2; exit 1; }
    curl --fail --location --retry 3 --output "$TOOL" \
      "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage"
    chmod 755 "$TOOL"
  fi
fi

mkdir -p "$OUT_DIR"
OUT_NAME="$APP_NAME-$VERSION-$ARCH.AppImage"
OUT_PATH="$OUT_DIR/$OUT_NAME"
echo "==> Checking staged ELF dependencies"
for object in "$STAGE_APPDIR/usr/bin/$APP_NAME" "$STAGE_APPDIR/usr/libexec/$APP_NAME/$APP_NAME-ui" \
  "$STAGE_APPDIR/usr/bin/ffmpeg" "$STAGE_APPDIR/usr/bin/ffprobe" "$STAGE_APPDIR/usr/bin/pactl"; do
  if ldd "$object" 2>&1 | grep -q 'not found'; then
    echo "Unresolved dependency in $object" >&2
    ldd "$object" >&2
    exit 1
  fi
done
if readelf -d "$STAGE_APPDIR/usr/bin/$APP_NAME" | grep -Eqi 'Qt|gtk|adwaita|glib|gio'; then
  echo "Daemon unexpectedly links a UI toolkit" >&2
  exit 1
fi
if readelf -d "$STAGE_APPDIR/usr/libexec/$APP_NAME/$APP_NAME-ui" | grep -Eqi 'libgtk|libadwaita'; then
  echo "Qt UI unexpectedly links GTK/libadwaita" >&2
  exit 1
fi

echo "==> Packing $OUT_PATH"
export APPIMAGE_EXTRACT_AND_RUN=1
export ARCH
(cd "$OUT_DIR" && "$TOOL" -n "$STAGE_APPDIR" "$OUT_NAME")
chmod 755 "$OUT_PATH"
echo "==> Done: $OUT_PATH"
ls -lh "$OUT_PATH"
