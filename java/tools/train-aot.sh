#!/usr/bin/env bash
#
# AOT training run (plan §5.19.4). Launches the image's bundled runtime with exactly the
# launcher's classpath and options (read from lib/app/gravaai.cfg), plus -XX:AOTCacheOutput,
# and exits after a few seconds. The app must show its window during the run: AOT training needs
# a display, which this machine has.
set -euo pipefail

IMAGE=""
BUILD_DIR=""
while [ $# -gt 0 ]; do
	case "$1" in
		--image) IMAGE="$2"; shift 2 ;;
		--build-dir) BUILD_DIR="$2"; shift 2 ;;
		*) echo "train-aot.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done
if [ -z "$IMAGE" ] || [ -z "$BUILD_DIR" ]; then
	echo "train-aot.sh: --image and --build-dir are required" >&2
	exit 64
fi

CFG="$IMAGE/lib/app/gravaai.cfg"
JAVA="$IMAGE/lib/runtime/bin/java"
CACHE="$IMAGE/lib/app/gravaai.aot"
if [ ! -f "$CFG" ] || [ ! -x "$JAVA" ]; then
	echo "train-aot.sh: $IMAGE is not a jpackage app image" >&2
	exit 1
fi

classpath="$(sed -n 's/^app.classpath=//p' "$CFG" | paste -sd: -)"
mainclass="$(sed -n 's/^app.mainclass=//p' "$CFG" | head -1)"
if [ -z "$classpath" ] || [ -z "$mainclass" ]; then
	echo "train-aot.sh: cannot read classpath/main class from $CFG" >&2
	exit 1
fi
# The launcher expands $APPDIR to the app directory ($IMAGE/lib/app in a jpackage app image);
# jpackage writes every app.classpath entry relative to it. Skip the AOTCache read option: this
# run writes the cache.
app_dir="$IMAGE/lib/app"
classpath="${classpath//\$APPDIR/$app_dir}"
options=()
while IFS= read -r line; do
	case "$line" in
		-XX:AOTCache=*) ;;
		'') ;;
		*) options+=("$line") ;;
	esac
done < <(sed -n 's/^java-options=//p' "$CFG")

PROFILE="$BUILD_DIR/dist/aot-training-profile"
rm -rf "$PROFILE"
mkdir -p "$PROFILE"
rm -f "$CACHE"

echo "train-aot.sh: training $CACHE"
set +e
DISPLAY="${DISPLAY:-:0}" \
GRAVAAI_USER_DATA="$PROFILE" \
GRAVAAI_LAUNCHED_AT="$(date +%s%3N)" \
GRAVAAI_EXIT_AFTER_MS="${GRAVAAI_AOT_TRAIN_MS:-4000}" \
"$JAVA" "${options[@]}" \
	-XX:AOTCacheOutput="$CACHE" \
	-Dgravaai.exitAfterMs="${GRAVAAI_AOT_TRAIN_MS:-4000}" \
	-cp "$classpath" "$mainclass"
status=$?
set -e

if [ ! -f "$CACHE" ]; then
	echo "train-aot.sh: the training run did not produce $CACHE (exit $status)" >&2
	exit 1
fi
echo "train-aot.sh: wrote $CACHE ($(stat -c %s "$CACHE") bytes)"
