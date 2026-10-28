#!/usr/bin/env bash
#
# Wrap the jpackage app image into an AppImage (plan §5.19.5): AppDir with an AppRun entry point
# (which also stamps GRAVAAI_LAUNCHED_AT before the JVM boots), .desktop file and icons.
# appimagetool is downloaded once into the scratch toolchain and pinned by SHA-256.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IMAGE=""
NAME="Gravaai"
VERSION=""
ICON=""
OUTPUT=""
while [ $# -gt 0 ]; do
	case "$1" in
		--image) IMAGE="$2"; shift 2 ;;
		--name) NAME="$2"; shift 2 ;;
		--version) VERSION="$2"; shift 2 ;;
		--icon) ICON="$2"; shift 2 ;;
		--output) OUTPUT="$2"; shift 2 ;;
		*) echo "build-appimage.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done
for required in IMAGE NAME VERSION ICON OUTPUT; do
	eval "value=\${$required}"
	if [ -z "$value" ]; then
		echo "build-appimage.sh: --$(echo "$required" | tr 'A-Z_' 'a-z-') is required" >&2
		exit 64
	fi
done

TOOL="${GRAVAAI_APPIMAGE_TOOL:-/media/jmarceno/scratch/java-toolchain/bin/appimagetool-x86_64.AppImage}"
URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
PIN_FILE="$SCRIPT_DIR/appimagetool.sha256"

if [ ! -x "$TOOL" ]; then
	echo "build-appimage.sh: downloading appimagetool to $TOOL"
	mkdir -p "$(dirname "$TOOL")"
	curl -fL --retry 3 --connect-timeout 20 -o "$TOOL.part" "$URL"
	mv "$TOOL.part" "$TOOL"
	chmod +x "$TOOL"
fi
if [ -f "$PIN_FILE" ]; then
	expected="$(tr -d '[:space:]' < "$PIN_FILE")"
	actual="$(sha256sum "$TOOL" | cut -d' ' -f1)"
	if [ "$expected" != "$actual" ]; then
		echo "build-appimage.sh: appimagetool checksum mismatch" >&2
		echo "  expected $expected" >&2
		echo "  actual   $actual" >&2
		exit 1
	fi
fi

WORK="$(dirname "$OUTPUT")/appimage-work"
APPDIR="$WORK/$NAME.AppDir"
rm -rf "$APPDIR" "$OUTPUT"
mkdir -p "$APPDIR"
cp -a "$IMAGE"/. "$APPDIR/usr/"

cp "$ICON" "$APPDIR/$NAME.png"
cp "$ICON" "$APPDIR/.DirIcon"
cat > "$APPDIR/$NAME.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$NAME
GenericName=Screen Recorder
Comment=Screen recording, video editing and meeting notes
Exec=$NAME %U
Icon=$NAME
Terminal=false
Categories=AudioVideo;Recorder;
StartupWMClass=GravaaiApp
DESKTOP

# Build the runtime class path from the jpackage launcher config. Keeping jpackage's order
# matters for the AOT cache, and the paths are re-based on the AppImage mount.
CFG="$APPDIR/usr/lib/app/gravaai.cfg"
if [ ! -f "$CFG" ]; then
	echo "build-appimage.sh: missing $CFG" >&2
	exit 1
fi
EMBEDDED_CP=""
while IFS= read -r entry; do
	[ -n "$entry" ] || continue
	EMBEDDED_CP="$EMBEDDED_CP:\$APPDIR/usr/lib/app/$(basename "$entry")"
done < <(sed -n 's/^app.classpath=//p' "$CFG")
EMBEDDED_CP="${EMBEDDED_CP#:}"
MAINCLASS="$(sed -n 's/^app.mainclass=//p' "$CFG" | head -1)"
APP_VERSION="$(sed -n 's/^java-options=-Djpackage.app-version=//p' "$CFG" | head -1)"
if [ -z "$MAINCLASS" ] || [ -z "$EMBEDDED_CP" ]; then
	echo "build-appimage.sh: cannot read the launcher class path from $CFG" >&2
	exit 1
fi

# Run the bundled runtime directly. The jpackage launcher probes rpm/dpkg on every start (~200 ms
# before the JVM boots, measured on this machine); the options and class path below are exactly
# the ones jpackage generated in lib/app/gravaai.cfg (plan §5.19.4). The jpackage launcher itself
# stays in the app image for development runs.
cat > "$APPDIR/AppRun" <<APPRUN
#!/bin/sh
# The AppImage runtime runs this entry point. Stamp the launch time before the JVM boots:
# it is the only honest start-up measurement (plan §5.1, plan §3 gotcha 3).
if [ -z "\${GRAVAAI_LAUNCHED_AT:-}" ]; then
	GRAVAAI_LAUNCHED_AT="\$(date +%s%3N 2>/dev/null || echo 0)"
	export GRAVAAI_LAUNCHED_AT
fi
exec "\$APPDIR/usr/lib/runtime/bin/java" \\
	-Djpackage.app-version=$APP_VERSION \\
	-Djpackage.app-path="\$APPDIR/usr/bin/gravaai" \\
	--enable-native-access=ALL-UNNAMED,javafx.graphics \\
	-XX:+UseSerialGC \\
	-XX:AOTCache="\$APPDIR/usr/lib/app/gravaai.aot" \\
	-cp "$EMBEDDED_CP" \\
	$MAINCLASS "\$@"
APPRUN
chmod +x "$APPDIR/AppRun"

echo "build-appimage.sh: appimagetool $APPDIR"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUTPUT"
chmod +x "$OUTPUT"
echo "build-appimage.sh: $OUTPUT ($(stat -c %s "$OUTPUT") bytes)"
