#!/usr/bin/env bash
#
# Package the Java app (plan §5.19): jlink runtime image (JDK + JavaFX jmods) -> jpackage
# app image -> AOT cache -> AppImage. Called by `./mvnw -T1 -Pdist package`.
#
# Machine rules that apply here (plan §1.1): one JVM build at a time, build output on the
# scratch disk, never any GPU video path.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JDK=""
JMODS=""
BUILD_DIR=""
MAIN_JAR=""
DEPS_DIR=""
NATIVE_DIR=""
ICON=""
APP_VERSION=""
IMAGE_NAME="gravaai"
OUT_DIR=""

while [ $# -gt 0 ]; do
	case "$1" in
		--jdk) JDK="$2"; shift 2 ;;
		--jmods) JMODS="$2"; shift 2 ;;
		--build-dir) BUILD_DIR="$2"; shift 2 ;;
		--main-jar) MAIN_JAR="$2"; shift 2 ;;
		--deps-dir) DEPS_DIR="$2"; shift 2 ;;
		--native-dir) NATIVE_DIR="$2"; shift 2 ;;
		--icon) ICON="$2"; shift 2 ;;
		--app-version) APP_VERSION="$2"; shift 2 ;;
		--image-name) IMAGE_NAME="$2"; shift 2 ;;
		--out-dir) OUT_DIR="$2"; shift 2 ;;
		*) echo "package-dist.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done

require() {
	local name="$1" value="$2"
	if [ -z "$value" ]; then
		echo "package-dist.sh: --$name is required" >&2
		exit 64
	fi
}

require jdk "$JDK"
require jmods "$JMODS"
require build-dir "$BUILD_DIR"
require main-jar "$MAIN_JAR"
require deps-dir "$DEPS_DIR"
require icon "$ICON"
require app-version "$APP_VERSION"
require out-dir "$OUT_DIR"

if [ ! -x "$JDK/bin/jlink" ] || [ ! -x "$JDK/bin/jpackage" ]; then
	echo "package-dist.sh: $JDK is not a JDK with jlink/jpackage" >&2
	exit 1
fi
if [ ! -f "$MAIN_JAR" ]; then
	echo "package-dist.sh: main jar not found: $MAIN_JAR" >&2
	exit 1
fi

# One build at a time (plan §1.1.2). The wrapper script serialises every Maven build; packaging
# runs inside that build, so nothing else needs the lock here.
WORK="$BUILD_DIR/dist/work"
INPUT="$WORK/input"
RUNTIME="$WORK/runtime"
IMAGE="$OUT_DIR/$IMAGE_NAME"

rm -rf "$WORK" "$IMAGE" "$OUT_DIR/Gravaai-linux-x64.AppImage"
mkdir -p "$INPUT" "$OUT_DIR"

cp -a "$DEPS_DIR"/. "$INPUT"/
cp "$MAIN_JAR" "$INPUT"/

# copy-dependencies only adds files, so after a version bump the previous release's module jars
# can still sit in the deps dir; the launcher would then resolve every class from whichever
# duplicate comes first on the classpath and ship a mixed-version image. Fail loudly instead.
duplicates="$(cd "$INPUT" && ls | sed -n 's/^\(.*\)-[0-9][^-]*\.jar$/\1/p' | sort | uniq -d)"
if [ -n "$duplicates" ]; then
	echo "package-dist.sh: multiple versions of the same artifact staged in $INPUT:" >&2
	echo "$duplicates" | sed 's/^/  /' >&2
	echo "package-dist.sh: remove the stale jars (mvn clean or delete $DEPS_DIR) and rebuild." >&2
	exit 1
fi

echo "package-dist.sh: jlink runtime"
"$JDK/bin/jlink" \
	--module-path "$JMODS" \
	--add-modules java.base,java.logging,java.net.http,jdk.unsupported,javafx.controls,jdk.crypto.ec,jdk.localedata,jdk.security.auth \
	--strip-debug --no-header-files --no-man-pages \
	--output "$RUNTIME"

echo "package-dist.sh: jpackage app image"
"$JDK/bin/jpackage" \
	--type app-image \
	--name "$IMAGE_NAME" \
	--app-version "$APP_VERSION" \
	--input "$INPUT" \
	--main-jar "$(basename "$MAIN_JAR")" \
	--main-class dev.gravaai.app.Main \
	--runtime-image "$RUNTIME" \
	--icon "$ICON" \
	--java-options "--enable-native-access=ALL-UNNAMED,javafx.graphics" \
	--java-options "-XX:+UseSerialGC" \
	--java-options '-XX:AOTCache=$APPDIR/gravaai.aot' \
	--dest "$OUT_DIR"

# Bundled native payload (helpers, FFmpeg, whisper, models): plan §5.19.3.
if [ -n "$NATIVE_DIR" ] && [ -d "$NATIVE_DIR" ]; then
	echo "package-dist.sh: bundling native payload from $NATIVE_DIR"
	mkdir -p "$IMAGE/lib/native/linux-x64"
	cp -a "$NATIVE_DIR"/. "$IMAGE/lib/native/linux-x64/"
else
	echo "package-dist.sh: no native payload yet ($NATIVE_DIR)"
fi

echo "package-dist.sh: AOT training"
"$SCRIPT_DIR/train-aot.sh" --image "$IMAGE" --build-dir "$BUILD_DIR"

echo "package-dist.sh: AppImage"
"$SCRIPT_DIR/build-appimage.sh" --image "$IMAGE" --name Gravaai --version "$APP_VERSION" \
	--icon "$ICON" --output "$OUT_DIR/Gravaai-linux-x64.AppImage"

echo "package-dist.sh: app image: $IMAGE"
echo "package-dist.sh: AppImage:  $OUT_DIR/Gravaai-linux-x64.AppImage"
