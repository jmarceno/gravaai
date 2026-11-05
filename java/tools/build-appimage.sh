#!/usr/bin/env bash
#
# Wrap the jpackage app image into an AppImage (plan §5.19.5): AppDir with an AppRun entry point
# (which also stamps GRAVAAI_LAUNCHED_AT before the JVM boots), .desktop file and icons.
# appimagetool is downloaded once into java/build/tools (gitignored) and pinned by SHA-256.
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

TOOL="${GRAVAAI_APPIMAGE_TOOL:-$SCRIPT_DIR/../build/tools/appimagetool-x86_64.AppImage}"
# A versioned release, never "continuous": that tag is rebuilt upstream, which breaks the pin.
URL="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
PIN_FILE="$SCRIPT_DIR/appimagetool.sha256"
expected="$(tr -d '[:space:]' < "$PIN_FILE")"

# A cached tool that does not match the pin (an older pin, a partial file) is replaced.
if [ ! -x "$TOOL" ] || [ "$(sha256sum "$TOOL" | cut -d' ' -f1)" != "$expected" ]; then
	echo "build-appimage.sh: downloading appimagetool to $TOOL"
	mkdir -p "$(dirname "$TOOL")"
	curl -fL --retry 5 --retry-all-errors --retry-delay 5 --connect-timeout 20 \
		--speed-limit 262144 --speed-time 30 --max-time 600 -o "$TOOL.part" "$URL"
	actual="$(sha256sum "$TOOL.part" | cut -d' ' -f1)"
	if [ "$expected" != "$actual" ]; then
		echo "build-appimage.sh: appimagetool checksum mismatch" >&2
		echo "  expected $expected" >&2
		echo "  actual   $actual" >&2
		rm -f "$TOOL.part"
		exit 1
	fi
	mv "$TOOL.part" "$TOOL"
	chmod +x "$TOOL"
fi

WORK="$(dirname "$OUTPUT")/.appimage-work"
APPDIR="$WORK/$NAME.AppDir"
rm -rf "$APPDIR" "$OUTPUT"
mkdir -p "$APPDIR"
cp -a "$IMAGE"/. "$APPDIR/usr/"

cp "$ICON" "$APPDIR/$NAME.png"
cp "$ICON" "$APPDIR/.DirIcon"
# Every icon size ships so the launcher never falls back to a stale size from an older install.
mkdir -p "$APPDIR/icons"
cp "$SCRIPT_DIR"/../../icons/icons/png/{16x16,24x24,32x32,48x48,64x64,128x128,256x256,512x512}.png "$APPDIR/icons/"
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
MimeType=application/x-gravaai;
StartupWMClass=dev.gravaai.app.GravaaiApp
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
APPRUN
# Freedesktop integration: on first launch (and whenever the AppImage moves) register a launcher
# and icon in the user's XDG data dir so any desktop menu finds the app. Quoted heredoc: this
# block is copied verbatim. Opt out with GRAVAAI_NO_DESKTOP_INTEGRATION=1.
cat >> "$APPDIR/AppRun" <<'INTEGRATE'
if [ -n "${APPIMAGE:-}" ] && [ -z "${GRAVAAI_NO_DESKTOP_INTEGRATION:-}" ]; then
	case "$APPIMAGE" in
		*[\"\`\$\\]*) ;;
		*)
			_data="${XDG_DATA_HOME:-$HOME/.local/share}"
			_desktop="$_data/applications/gravaai.desktop"
			mkdir -p "$_data/applications" "$_data/mime/packages" 2>/dev/null
			for _icon in "$APPDIR"/icons/*.png; do
				_size="$(basename "$_icon" .png)"
				mkdir -p "$_data/icons/hicolor/$_size/apps" 2>/dev/null
				cp "$_icon" "$_data/icons/hicolor/$_size/apps/gravaai.png" 2>/dev/null
			done
			# A scalable icon from an older install outranks every PNG above.
			rm -f "$_data/icons/hicolor/scalable/apps/gravaai.svg" 2>/dev/null
			# Rewritten whenever the AppImage moves or the entry format changes (an older build
			# skipped the write when the Exec line matched, leaving entries without MimeType).
			cat > "$_desktop.tmp" 2>/dev/null <<DESKTOP
[Desktop Entry]
Type=Application
Name=Gravaai
GenericName=Screen Recorder
Comment=Screen recording, video editing and meeting notes
Exec="$APPIMAGE" %U
TryExec=$APPIMAGE
Icon=gravaai
Terminal=false
Categories=AudioVideo;Recorder;
MimeType=application/x-gravaai;
StartupWMClass=dev.gravaai.app.GravaaiApp
DESKTOP
			if ! cmp -s "$_desktop.tmp" "$_desktop" 2>/dev/null; then
				mv "$_desktop.tmp" "$_desktop" 2>/dev/null
				command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$_data/applications" >/dev/null 2>&1
			else
				rm -f "$_desktop.tmp" 2>/dev/null
			fi
			# The .gravaai project type (legacy .openscreen included) so the file manager offers
			# Gravaai and opens projects by default when a file is clicked.
			cat > "$_data/mime/packages/gravaai.xml" 2>/dev/null <<MIME
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
	<mime-type type="application/x-gravaai">
		<comment>Gravaai project</comment>
		<sub-class-of type="application/json"/>
		<glob pattern="*.gravaai"/>
		<glob pattern="*.openscreen"/>
	</mime-type>
</mime-info>
MIME
			command -v update-mime-database >/dev/null 2>&1 && update-mime-database "$_data/mime" >/dev/null 2>&1
			command -v xdg-mime >/dev/null 2>&1 && xdg-mime default gravaai.desktop application/x-gravaai >/dev/null 2>&1
			command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$_data/icons/hicolor" >/dev/null 2>&1
			;;
	esac
fi
INTEGRATE
cat >> "$APPDIR/AppRun" <<APPRUN
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
