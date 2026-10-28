#!/usr/bin/env bash
#
# Capture JavaFX screens for the UI parity gallery (plan §6.3).
#
#   java/tools/parity/capture-java-ui.sh [--binary <app image bin | AppImage>] [--out <dir>] [--runs 1]
#
# Each case launches the packaged app with a scratch GRAVAAI_USER_DATA profile, waits for the
# window, screenshots the whole screen with cosmic-screenshot and kills the app. Cases grow with
# the UI: add a case here when a screen lands in Java, then re-run gallery.mjs.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BINARY="${GRAVAAI_APP_BINARY:-/media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai/bin/gravaai}"
OUT="$REPO/java/parity/ui-java"
RUNS=1

while [ $# -gt 0 ]; do
	case "$1" in
		--binary) BINARY="$2"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--runs) RUNS="$2"; shift 2 ;;
		*) echo "capture-java-ui.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done

if [ ! -x "$BINARY" ]; then
	echo "capture-java-ui.sh: not executable: $BINARY" >&2
	echo "  build it first: cd java && ./tools/mvn-locked.sh -q -B -DskipTests -Pdist package" >&2
	exit 1
fi

export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
mkdir -p "$OUT" /tmp/gravaai-parity-shots

# name|extra arguments passed to the app. Names match capture-react-ui.spec.ts where a React
# reference exists; the rest are Java-only development screens.
CASES=(
	"hud-overlay|"
	"dashboard|--dashboard"
	"ui-kit|--ui-kit"
)

LAUNCH=("$BINARY")
case "$BINARY" in
	*.AppImage) [ -e /dev/fuse ] || export APPIMAGE_EXTRACT_AND_RUN=1 ;;
esac

for run in $(seq 1 "$RUNS"); do
	for entry in "${CASES[@]}"; do
		name="${entry%%|*}"
		extra="${entry#*|}"
		profile="/tmp/gravaai-parity-profile-$name-$run"
		rm -rf "$profile"
		mkdir -p "$profile"
		pkill -x gravaai 2>/dev/null || true
		# shellcheck disable=SC2086
		GRAVAAI_USER_DATA="$profile" "${LAUNCH[@]}" $extra >/tmp/gravaai-parity-shots/$name-$run.out 2>&1 &
		pid=$!
		sleep 3
		shot="$(cosmic-screenshot --interactive=false --notify=false -s /tmp/gravaai-parity-shots 2>/dev/null | tail -1)"
		latest="$(ls -t /tmp/gravaai-parity-shots/*.png 2>/dev/null | head -1)"
		if [ -n "$latest" ]; then
			cp "$latest" "$OUT/$name.png"
			echo "capture-java-ui.sh: $name -> $OUT/$name.png"
		else
			echo "capture-java-ui.sh: $name: no screenshot produced ($shot)" >&2
		fi
		kill "$pid" 2>/dev/null || true
		pkill -x gravaai 2>/dev/null || true
	done
done

echo "capture-java-ui.sh: done. Run 'node java/tools/parity/gallery.mjs' to rebuild the gallery."
