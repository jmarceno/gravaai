#!/usr/bin/env bash
# Portable single-file build.
#
# Produces one self-extracting executable:
#   build/portable/GravaAi-<version>-x86_64-portable.run
#
# The .run embeds the release binary plus its full shared-library closure
# (Qt, its plugins and QML modules, GL, X11/Wayland client libs, ...). On
# first launch it extracts to $XDG_CACHE_HOME/gravaai-portable/<version>-<hash>/
# and execs the app; later launches reuse that tree. Needs nothing on the
# host beyond a kernel, glibc and a GPU/display stack.
#
# The bundle inherits the glibc floor of the machine that built it, so
# release builds MUST use --container (Ubuntu 22.04, glibc 2.35). A native
# build is fine for local testing.
#
# Usage:
#   ./scripts/build-portable.sh [--container] [--rebuild-image]
#                               [--stage <abs-path>] [--output-dir <abs-path>]
set -euo pipefail

PROJECT_ROOT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
# shellcheck source=scripts/lib/sanitize-host-env.sh
source "$PROJECT_ROOT/scripts/lib/sanitize-host-env.sh"
sanitize_host_env

APP=gravaai
APP_NAME="GravaAi"
GLIBC_BASELINE=GLIBC_2.35
CONTAINER=0
REBUILD_IMAGE=0
STAGE=""
OUTPUT_DIR=""
IMAGE="${PORTABLE_IMAGE:-$APP-portable-builder:22.04}"

usage() {
  cat <<EOF
Usage: $0 [--container] [--rebuild-image] [--stage <abs-path>] [--output-dir <abs-path>]

  --container          Build inside the Ubuntu 22.04 (glibc 2.35) container.
                       Required for release bundles.
  --rebuild-image      Rebuild the container image even if present.
  --stage <path>       Native stage dir (default: \$PROJECT_ROOT/build/stage).
  --output-dir <path>  Output dir for the .run (default: \$PROJECT_ROOT/build/portable).
EOF
}

while (($#)); do
  case "$1" in
    --container) CONTAINER=1; shift ;;
    --rebuild-image) REBUILD_IMAGE=1; shift ;;
    --stage) STAGE="${2:?--stage needs a path}"; shift 2 ;;
    --stage=*) STAGE="${1#*=}"; shift ;;
    --output-dir) OUTPUT_DIR="${2:?--output-dir needs a path}"; shift 2 ;;
    --output-dir=*) OUTPUT_DIR="${1#*=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "build-portable: $*" >&2; exit 1; }

# ---------------------------------------------------------------- container
if [[ "$CONTAINER" -eq 1 ]]; then
  command -v docker >/dev/null 2>&1 || die "docker not found (needed for --container)"
  if [[ "$REBUILD_IMAGE" -eq 1 ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "--- Building container image $IMAGE ---"
    docker build -f "$PROJECT_ROOT/linux/packaging/portable/Containerfile" \
      -t "$IMAGE" "$PROJECT_ROOT/linux/packaging/portable/"
  fi
  INNER_ARGS=()
  if [[ -n "$STAGE" ]]; then
    [[ "$STAGE" == "$PROJECT_ROOT"/* ]] || die "--stage with --container must be under $PROJECT_ROOT"
    INNER_ARGS+=(--stage "/workspace/${STAGE#"$PROJECT_ROOT"/}")
  fi
  if [[ -n "$OUTPUT_DIR" ]]; then
    [[ "$OUTPUT_DIR" == "$PROJECT_ROOT"/* ]] || die "--output-dir with --container must be under $PROJECT_ROOT"
    INNER_ARGS+=(--output-dir "/workspace/${OUTPUT_DIR#"$PROJECT_ROOT"/}")
  fi
  echo "--- Building portable bundle inside $IMAGE ---"
  # Named volumes keep cargo's registry and target dir across runs, so
  # rebuilds are incremental. The target dir is NOT the host's target/
  # (different glibc / Qt: mixing them causes confusing link errors).
  docker run --rm \
    -v "$PROJECT_ROOT:/workspace" \
    -v "$APP-portable-cargo:/opt/cargo/registry" \
    -v "$APP-portable-target:/workspace-target" \
    -e CARGO_TARGET_DIR=/workspace-target \
    ${APP_VERSION:+-e APP_VERSION="$APP_VERSION"} \
    -e PORTABLE_CHOWN_TO="$(id -u):$(id -g)" \
    -w /workspace "$IMAGE" ./scripts/build-portable.sh "${INNER_ARGS[@]}"
  exit $?
fi

# ------------------------------------------------------------- native build
VERSION="${APP_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -m1 -E '^version\s*=' "$PROJECT_ROOT/linux/Cargo.toml" | sed -E 's/.*"([^"]+)".*/\1/')"
fi
[[ -n "$VERSION" ]] || die "cannot read version from Cargo.toml"

[[ -z "$STAGE" ]] && STAGE="$PROJECT_ROOT/build/stage"
[[ -z "$OUTPUT_DIR" ]] && OUTPUT_DIR="$PROJECT_ROOT/build/portable"
[[ "$STAGE" == /* ]] || die "--stage must be absolute: $STAGE"
[[ "$OUTPUT_DIR" == /* ]] || die "--output-dir must be absolute: $OUTPUT_DIR"

PAYLOAD="$OUTPUT_DIR/payload"
ARCH="$(uname -m)"
case "$ARCH" in x86_64|aarch64) ;; *) die "unsupported architecture: $ARCH" ;; esac
OUTPUT="$OUTPUT_DIR/${APP_NAME// /}-${VERSION}-${ARCH}-portable.run"

echo "=== portable build $VERSION -> $OUTPUT ==="

for tool in patchelf ldd readelf tar gzip file strings sha256sum awk; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool missing: $tool"
done
QMAKE="${QMAKE:-$(command -v qmake6 || command -v qmake || true)}"
[[ -n "$QMAKE" ]] || die "qmake6/qmake not found"

# Always re-stage: cargo makes a no-op rebuild cheap, and a stale stage is
# how an old binary ends up in a new bundle.
"$PROJECT_ROOT/scripts/build-native.sh" --release --stage "$STAGE"
[[ -x "$STAGE/bin/$APP" ]] || die "staged binary missing at $STAGE/bin/$APP"

rm -rf "$PAYLOAD"
mkdir -p "$OUTPUT_DIR" "$PAYLOAD/bin" "$PAYLOAD/lib" "$PAYLOAD/plugins" "$PAYLOAD/qml" "$PAYLOAD/share"
cp -a "$STAGE/bin/." "$PAYLOAD/bin/"
cp -a "$STAGE/share/." "$PAYLOAD/share/"

is_elf() { [[ -f "$1" ]] && file -b "$1" 2>/dev/null | grep -q ELF; }

# --- app-specific extras ------------------------------------------------
# Recording needs a PulseAudio-capable FFmpeg, its matching probe and pactl.
FFMPEG=""
while IFS= read -r candidate; do
  if "$candidate" -hide_banner -demuxers 2>/dev/null | grep -E ' D[[:space:]]+pulse[[:space:]]' >/dev/null; then
    FFMPEG="$candidate"; break
  fi
done < <(type -a -p ffmpeg | awk '!seen[$0]++')
[[ -n "$FFMPEG" ]] || die "FFmpeg with pulse demuxer is required"
for helper in ffmpeg ffprobe pactl; do
  if [[ "$helper" == pactl ]]; then source="$(command -v pactl)"; else source="$(dirname "$FFMPEG")/$helper"; fi
  [[ -x "$source" ]] || die "missing helper: $helper"
  install -m 0755 "$(readlink -f "$source")" "$PAYLOAD/bin/$helper"
done
cat > "$PAYLOAD/share/gravaai/THIRD_PARTY" <<'LICENSES'
GravaAi portable third-party inventory
Qt 6 and QML modules: LGPL-3.0 / GPL-3.0 / commercial
FFmpeg and FFprobe: LGPL-2.1-or-later / GPL-2.0-or-later (build configuration)
PulseAudio pactl and client libraries: LGPL-2.1-or-later
Other bundled libraries retain their respective upstream licenses.
Rust crate licenses are recorded in linux/Cargo.lock and the crate metadata.
LICENSES

# Keep text readable on minimal desktops with no font package/configuration.
mkdir -p "$PAYLOAD/share/fonts" "$PAYLOAD/share/fontconfig"
FONT_DIR=/usr/share/fonts/truetype/dejavu
[[ -f "$FONT_DIR/DejaVuSans.ttf" ]] || die "DejaVu fonts are required in the builder"
cp "$FONT_DIR"/DejaVuSans*.ttf "$PAYLOAD/share/fonts/"
[[ ! -f /usr/share/doc/fonts-dejavu-core/copyright ]] || cp /usr/share/doc/fonts-dejavu-core/copyright "$PAYLOAD/share/fonts/LICENSE"
cat > "$PAYLOAD/share/fontconfig/fonts.conf" <<'FONTS'
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<fontconfig>
  <dir prefix="relative">../fonts</dir>
  <cachedir prefix="xdg">fontconfig</cachedir>
  <alias><family>sans-serif</family><prefer><family>DejaVu Sans</family></prefer></alias>
</fontconfig>
FONTS

# Helper executables go in $PAYLOAD/bin (the stub appends it to PATH), data
# in $PAYLOAD/share, dlopen()ed libraries (invisible to ldd) in
# $PAYLOAD/lib. Add them HERE, before the closure scan, so their own
# dependencies get bundled too.

# --- Qt platform plugins + QML modules ---------------------------------
echo "--- Qt plugins / QML ---"
QT_PLUGINS_DIR="$("$QMAKE" -query QT_INSTALL_PLUGINS 2>/dev/null || true)"
QT_QML_DIR="$("$QMAKE" -query QT_INSTALL_QML 2>/dev/null || true)"
[[ -d "$QT_PLUGINS_DIR" ]] || die "QT_INSTALL_PLUGINS not found via $QMAKE"
[[ -d "$QT_QML_DIR" ]] || die "QT_INSTALL_QML not found via $QMAKE"
for cat in platforms platforminputcontexts platformthemes imageformats iconengines \
           tls networkinformation wayland-decoration-client \
           wayland-graphics-integration-client wayland-shell-integration \
           xcbglintegrations egldeviceintegrations generic; do
  if [[ -d "$QT_PLUGINS_DIR/$cat" ]]; then
    mkdir -p "$PAYLOAD/plugins/$cat"
    cp -a "$QT_PLUGINS_DIR/$cat/." "$PAYLOAD/plugins/$cat/"
  fi
done
# Optional KDE image codecs often link against libs absent from the build
# host; Qt's built-in image plugins cover normal use.
rm -f "$PAYLOAD/plugins/imageformats/kimg_"*
# QML modules imported by qml/*.qml. Add more here when you import them
# (e.g. QtQuick.Dialogs lives under QtQuick/, Qt5Compat needs its own dir).
for mod in QtQuick QtQml Qt/labs/platform; do
  if [[ -d "$QT_QML_DIR/$mod" ]]; then
    mkdir -p "$PAYLOAD/qml/$mod"
    cp -a "$QT_QML_DIR/$mod/." "$PAYLOAD/qml/$mod/"
  fi
done
cat > "$PAYLOAD/bin/qt.conf" <<'EOF'
[Paths]
Prefix = ./../
Plugins = plugins
Imports = qml
Qml2Imports = qml
EOF

# --- shared-library closure -------------------------------------------
# Bundle everything ldd resolves EXCEPT the host ABI: the dynamic loader,
# the libc family and NSS (they must match the running kernel/libc).
# libstdc++/libgcc ARE bundled: a 22.04 libstdc++ runs on newer hosts,
# while an older host libstdc++ fails with missing GLIBCXX symbols.
# GL/EGL userspace (glvnd + Mesa) is bundled too; glvnd still loads the
# host's vendor (e.g. NVIDIA) driver from system paths.
echo "--- Shared-library closure ---"
EXCLUDE_RE='^(linux-vdso|ld-linux|libc\.so|libm\.so|libpthread\.so|libdl\.so|librt\.so|libresolv\.so|libutil\.so|libnss_)'
declare -A SCANNED=()
MISSING_DEPS=()
QUEUE=()
while IFS= read -r -d '' elf; do
  QUEUE+=("$elf")
done < <(find "$PAYLOAD/bin" "$PAYLOAD/lib" "$PAYLOAD/plugins" "$PAYLOAD/qml" -type f -print0)
while ((${#QUEUE[@]} > 0)); do
  f="${QUEUE[0]}"
  QUEUE=("${QUEUE[@]:1}")
  is_elf "$f" || continue
  [[ -n "${SCANNED[$f]:-}" ]] && continue
  SCANNED["$f"]=1
  while read -r name path; do
    [[ -z "$name" ]] && continue
    if [[ "$name" == "MISSING:" ]]; then
      MISSING_DEPS+=("$path (needed by $f)")
      continue
    fi
    [[ "$name" =~ $EXCLUDE_RE ]] && continue
    dest="$PAYLOAD/lib/$name"
    if [[ ! -e "$dest" ]]; then
      cp -L "$path" "$dest" || die "cannot copy $path -> $dest"
      QUEUE+=("$dest")
    fi
  done < <(env -u LD_LIBRARY_PATH ldd "$f" 2>/dev/null | awk '/=> not found/ {print "MISSING:", $1} /=> \// {print $1, $3}' || true)
done
if ((${#MISSING_DEPS[@]} > 0)); then
  printf 'error: unresolved shared libraries:\n' >&2
  printf '  %s\n' "${MISSING_DEPS[@]}" >&2
  exit 1
fi
echo "  bundled libs: $(find "$PAYLOAD/lib" -type f | wc -l)"

# --- RPATH -------------------------------------------------------------
# Relative RPATHs make the tree relocatable without LD_LIBRARY_PATH magic.
echo "--- RPATH ---"
set_rpath_under() {
  local rpath="$1"; shift
  while IFS= read -r -d '' elf; do
    is_elf "$elf" || continue
    readelf -d "$elf" 2>/dev/null | grep -qE 'NEEDED|SONAME' || continue
    patchelf --force-rpath --set-rpath "$rpath" "$elf"
  done < <(find "$@" -type f -print0)
}
set_rpath_under '$ORIGIN/../lib' "$PAYLOAD/bin"
set_rpath_under '$ORIGIN' "$PAYLOAD/lib"
set_rpath_under '$ORIGIN:$ORIGIN/../lib:$ORIGIN/../../lib:$ORIGIN/../../../lib:$ORIGIN/../../../../lib:$ORIGIN/../../../../../lib' \
  "$PAYLOAD/plugins" "$PAYLOAD/qml"

# --- glibc floor report --------------------------------------------------
max_sym() {
  find "$PAYLOAD" -type f -print0 | while IFS= read -r -d '' elf; do
    is_elf "$elf" && strings -a "$elf" | grep -o "${1}_[0-9][0-9.]*"
  done | sort -Vu | tail -n1
}
GLIBC_FLOOR="$(max_sym GLIBC || true)"
echo "--- ABI floor ---"
echo "  max GLIBC required:   ${GLIBC_FLOOR:-none}"
echo "  max GLIBCXX required: $(max_sym GLIBCXX || true)"
if [[ -n "$GLIBC_FLOOR" && "$(printf '%s\n%s\n' "$GLIBC_BASELINE" "$GLIBC_FLOOR" | sort -Vu | tail -n1)" != "$GLIBC_BASELINE" ]]; then
  echo "  WARNING: bundle needs $GLIBC_FLOOR (> $GLIBC_BASELINE); it will not run on older distros."
  echo "  WARNING: use --container for release builds."
fi

# --- verify ---------------------------------------------------------------
echo "--- Verifying payload ---"
VERIFY_FAILED=0
while IFS= read -r -d '' elf; do
  is_elf "$elf" || continue
  out="$(env -u LD_LIBRARY_PATH ldd "$elf" 2>&1 || true)"
  if grep -q "not found" <<<"$out"; then
    echo "error: missing libs for $elf:" >&2
    grep "not found" <<<"$out" | sed 's/^/  /' >&2
    VERIFY_FAILED=1
  fi
done < <(find "$PAYLOAD" -type f -print0)
[[ "$VERIFY_FAILED" -eq 0 ]] || exit 1
env -u LD_LIBRARY_PATH "$PAYLOAD/bin/$APP" --version || die "payload binary failed to run"
find "$PAYLOAD" -type f | sort > "$OUTPUT_DIR/payload-files.txt"

# --- pack single file ---------------------------------------------------------
echo "--- Packing single file ---"
TARBALL="$OUTPUT_DIR/payload.tar.gz"
rm -f "$TARBALL" "$OUTPUT"
tar -czf "$TARBALL" -C "$PAYLOAD" .
PAYLOAD_SHA="$(sha256sum "$TARBALL" | awk '{print $1}')"
cat > "$OUTPUT" <<'STUB_EOF'
#!/bin/sh
# Portable single-file bundle: self-extracting launcher.
# Generated by scripts/build-portable.sh; do not edit.
set -eu

APP="__APP__"
VERSION="__VERSION__"
PAYLOAD_SHA256="__SHA256__"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [PORTABLE-OPTIONS] [APP-ARGS...]

Portable options (must come first):
  --portable-extract-to DIR   Extract to DIR instead of the cache and run there
  --portable-re-extract       Re-extract even if the cache looks current
  --portable-root             Print the extracted tree path and exit
  --portable-help             Show this help
  --                          Stop option parsing; the rest goes to the app
USAGE
}

EXTRACT_TO=""
RE_EXTRACT=0
PRINT_ROOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --portable-extract-to) EXTRACT_TO="${2:?--portable-extract-to needs DIR}"; shift 2 ;;
    --portable-extract-to=*) EXTRACT_TO="${1#*=}"; shift ;;
    --portable-re-extract) RE_EXTRACT=1; shift ;;
    --portable-root) PRINT_ROOT=1; shift ;;
    --portable-help) usage; exit 0 ;;
    --) shift; break ;;
    *) break ;;
  esac
done

SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF="$(command -v "$SELF")" ;;
esac
case "$SELF" in
  /*) ;;
  *) SELF="$(pwd)/$SELF" ;;
esac

# Keyed by version AND payload hash: two builds of the same version never
# share (and half-overwrite) one extraction.
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/$APP-portable"
DEST="${EXTRACT_TO:-$CACHE_BASE/$VERSION-$(printf '%.12s' "$PAYLOAD_SHA256")}"
if [ "$PRINT_ROOT" -eq 1 ]; then
  printf '%s\n' "$DEST"
  exit 0
fi

MARKER="$DEST/.portable-marker"
CACHED=""
[ -f "$MARKER" ] && CACHED="$(cat "$MARKER" 2>/dev/null || true)"
if [ "$RE_EXTRACT" -eq 1 ] || [ "$CACHED" != "$PAYLOAD_SHA256" ] || [ ! -x "$DEST/bin/$APP" ]; then
  OFFSET="$(awk '/^__PAYLOAD_FOLLOWS__$/{print NR+1; exit 0;}' "$SELF")"
  [ -n "$OFFSET" ] || { echo "portable: payload marker not found in $SELF" >&2; exit 1; }
  ACTUAL_SHA="$(tail -n +"$OFFSET" "$SELF" | sha256sum | cut -d ' ' -f1)"
  [ "$ACTUAL_SHA" = "$PAYLOAD_SHA256" ] || { echo "portable: payload checksum mismatch" >&2; exit 1; }
  if [ -n "$EXTRACT_TO" ]; then
    # User-chosen dir: extract in place, never delete anything there.
    mkdir -p "$DEST"
    tail -n +"$OFFSET" "$SELF" | gzip -dc | tar -x -C "$DEST" \
      || { echo "portable: extraction failed" >&2; exit 1; }
    printf '%s\n' "$PAYLOAD_SHA256" > "$MARKER"
  else
    # Cache dir: extract beside DEST and swap in, so a crash never leaves
    # a half-written tree.
    mkdir -p "$CACHE_BASE"
    TMP="$DEST.tmp.$$"
    rm -rf "$TMP"
    mkdir -p "$TMP"
    if ! tail -n +"$OFFSET" "$SELF" | gzip -dc | tar -x -C "$TMP"; then
      rm -rf "$TMP"
      echo "portable: extraction failed" >&2
      exit 1
    fi
    printf '%s\n' "$PAYLOAD_SHA256" > "$TMP/.portable-marker"
    if [ "$RE_EXTRACT" -eq 1 ]; then rm -rf "$DEST"; fi
    if ! mv -T "$TMP" "$DEST" 2>/dev/null; then
      if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$PAYLOAD_SHA256" ]; then
        rm -rf "$TMP"
      else
        rm -rf "$TMP"
        echo "portable: could not publish extraction" >&2
        exit 1
      fi
    fi
  fi
fi

# Bundled libraries first; ignore transient IDE library paths.
_CLEAN_LD="$(printf '%s' "${LD_LIBRARY_PATH:-}" | tr ':' '\n' | grep -v '^/tmp/\.mount_' | grep -v '^$' | paste -sd: - 2>/dev/null || true)"
export LD_LIBRARY_PATH="$DEST/lib${_CLEAN_LD:+:$_CLEAN_LD}"
export PATH="$DEST/bin:$PATH"
export QT_PLUGIN_PATH="$DEST/plugins"
export QT_QPA_PLATFORM_PLUGIN_PATH="$DEST/plugins/platforms"
export QML2_IMPORT_PATH="$DEST/qml"
export QML_IMPORT_PATH="$DEST/qml"
# Stable launcher path for desktop entries (the extraction is versioned).
export GRAVAAI_PORTABLE_EXE="$SELF"
export GRAVAAI_PORTABLE_ROOT="$DEST"
export XDG_DATA_DIRS="$DEST/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
if [ ! -f /etc/fonts/fonts.conf ] && [ -z "${FONTCONFIG_FILE:-}" ]; then
  export FONTCONFIG_FILE="$DEST/share/fontconfig/fonts.conf"
fi

exec "$DEST/bin/$APP" "$@"
__PAYLOAD_FOLLOWS__
STUB_EOF
sed -i "s/__APP__/$APP/; s/__VERSION__/$VERSION/; s/__SHA256__/$PAYLOAD_SHA/;" "$OUTPUT"
cat "$TARBALL" >> "$OUTPUT"
chmod 0755 "$OUTPUT"
rm -f "$TARBALL"

if [[ -n "${PORTABLE_CHOWN_TO:-}" ]]; then
  chown -R "$PORTABLE_CHOWN_TO" "$PROJECT_ROOT/build" 2>/dev/null || true
fi

echo
echo "=== build-portable complete ==="
echo "single file: $OUTPUT"
du -h "$OUTPUT" | cut -f1
echo "glibc floor: ${GLIBC_FLOOR:-none}"
