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

# The GraalJS dependency pulls in ICU4J with locale data for every locale on
# earth (~18 MB stored). Trim it to the locales the app ships (root/en/pt) so
# it only carries what Intl can actually use; verify-icu-trim.sh re-checks the
# behavior against the packaged runtime after jpackage.
"$SCRIPT_DIR/trim-icu.sh" --jar "$INPUT"/icu4j-*.jar --work-dir "$WORK/icu-trim"

echo "package-dist.sh: jlink runtime"
# jdk.localedata ships CLDR data for the whole world (~26 MB). The UI text is
# English and the java.text formatters follow the user's system locale, so keep
# only en and pt (root data is always included); any other system locale falls
# back to English formats instead of crashing.
"$JDK/bin/jlink" \
	--module-path "$JMODS" \
	--add-modules java.base,java.logging,java.net.http,jdk.unsupported,javafx.controls,jdk.crypto.ec,jdk.localedata,jdk.security.auth \
	--include-locales=en,pt \
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
# Staged from an allowlist, never a blanket copy: the native build trees carry
# developer artifacts that must not ship — FFmpeg's install prefix keeps
# include/ headers and share/ examples, and the whisper.cpp cmake build emits
# every tool (bench, quantize, server, vad-speech-segments) although only
# whisper-cli is ever executed (captions + meeting transcription).
if [ -n "$NATIVE_DIR" ] && [ -d "$NATIVE_DIR" ]; then
	echo "package-dist.sh: bundling native payload from $NATIVE_DIR"
	LINUX="$IMAGE/lib/native/linux-x64"
	mkdir -p "$LINUX"
	# Capture/layer-shell helpers and the noise-suppression model.
	find "$NATIVE_DIR" -maxdepth 1 -type f -name 'gravaai-*' -exec cp -a {} "$LINUX/" \;
	if [ -d "$NATIVE_DIR/models" ]; then cp -a "$NATIVE_DIR/models" "$LINUX/models"; fi
	# whisper-cli plus the manifest; libwhisper*/libggml* only exist when a
	# shared backend is enabled, and then they must ship too.
	mkdir -p "$LINUX/whisper"
	for entry in whisper-cli whisper-runtime.json libwhisper* libggml*; do
		if [ -e "$NATIVE_DIR/whisper/$entry" ]; then cp -a "$NATIVE_DIR/whisper/$entry" "$LINUX/whisper/"; fi
	done
	# FFmpeg: the CLI tools the export pipeline runs and the shared libs the
	# JVM loads through FFM.
	if [ -d "$NATIVE_DIR/ffmpeg/bin" ] || [ -d "$NATIVE_DIR/ffmpeg/lib" ]; then
		mkdir -p "$LINUX/ffmpeg"
		[ -d "$NATIVE_DIR/ffmpeg/bin" ] && cp -a "$NATIVE_DIR/ffmpeg/bin" "$LINUX/ffmpeg/bin"
		[ -d "$NATIVE_DIR/ffmpeg/lib" ] && cp -a "$NATIVE_DIR/ffmpeg/lib" "$LINUX/ffmpeg/lib"
		# lib/pkgconfig is build tooling, not runtime.
		rm -rf "$LINUX/ffmpeg/lib/pkgconfig"
	fi
else
	echo "package-dist.sh: no native payload yet ($NATIVE_DIR)"
fi

# Acceptance gate for the ICU trim: run Intl through the real engine (GraalJS
# via the polyglot API) on the packaged runtime, so a GraalVM bump that changes
# the ICU data layout fails the build instead of shipping broken Intl.
echo "package-dist.sh: verifying trimmed ICU locale data"
"$SCRIPT_DIR/verify-icu-trim.sh" \
	--javac "$JDK/bin/javac" \
	--java "$IMAGE/lib/runtime/bin/java" \
	--lib-dir "$IMAGE/lib/app" \
	--work-dir "$WORK/icu-verify"

echo "package-dist.sh: AOT training"
"$SCRIPT_DIR/train-aot.sh" --image "$IMAGE" --build-dir "$BUILD_DIR"

echo "package-dist.sh: AppImage"
"$SCRIPT_DIR/build-appimage.sh" --image "$IMAGE" --name Gravaai --version "$APP_VERSION" \
	--icon "$ICON" --output "$OUT_DIR/Gravaai-linux-x64.AppImage"

echo "package-dist.sh: app image: $IMAGE"
echo "package-dist.sh: AppImage:  $OUT_DIR/Gravaai-linux-x64.AppImage"
