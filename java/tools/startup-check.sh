#!/usr/bin/env bash
#
# Start-up budget check (plan §5.1, plan §5.19.6, plan Appendix C).
#
#   java/tools/startup-check.sh [--runs N] [--require-preview] <app-image binary | AppImage>
#
# Each run gets a fresh profile under the scratch disk, an injected wall-clock launch timestamp
# (GRAVAAI_LAUNCHED_AT) and exits itself (GRAVAAI_EXIT_AFTER_MS). Parses the "startup:" log lines.
set -euo pipefail

RUNS=5
REQUIRE_PREVIEW=0
BIN=""
while [ $# -gt 0 ]; do
	case "$1" in
		--runs) RUNS="$2"; shift 2 ;;
		--require-preview) REQUIRE_PREVIEW=1; shift ;;
		-*) echo "startup-check.sh: unknown option: $1" >&2; exit 64 ;;
		*) BIN="$1"; shift ;;
	esac
done
if [ -z "$BIN" ]; then
	echo "usage: startup-check.sh [--runs N] [--require-preview] <binary>" >&2
	exit 64
fi
if [ ! -x "$BIN" ]; then
	echo "startup-check.sh: not executable: $BIN" >&2
	exit 64
fi

SCRATCH="${GRAVAAI_BUILD_ROOT:-/media/jmarceno/scratch/java-targets}/startup-check"
RUN_ROOT="$SCRATCH/run-$(date +%s)"
mkdir -p "$RUN_ROOT"
export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"

# The AppImage needs FUSE; fall back to the embedded extract-and-run mode when it is missing.
LAUNCH=("$BIN")
IS_APPIMAGE=0
case "$BIN" in
	*.AppImage) IS_APPIMAGE=1 ;;
esac
if [ "$IS_APPIMAGE" = 1 ] && [ ! -e /dev/fuse ]; then
	export APPIMAGE_EXTRACT_AND_RUN=1
	echo "startup-check.sh: /dev/fuse missing; AppImage runs in extract-and-run mode (numbers are a lower bound)" >&2
fi

visible_values=()
preview_values=()
failures=0
for i in $(seq 1 "$RUNS"); do
	profile="$RUN_ROOT/profile-$i"
	rm -rf "$profile"
	mkdir -p "$profile"
	export GRAVAAI_USER_DATA="$profile"
	export GRAVAAI_LAUNCHED_AT="$(date +%s%3N)"
	export GRAVAAI_EXIT_AFTER_MS=6000
	pkill -x gravaai 2>/dev/null || true
	timeout 40 "${LAUNCH[@]}" >"$RUN_ROOT/run-$i.out" 2>&1 || true
	pkill -x gravaai 2>/dev/null || true

	# JDK 27's FileHandler always uses generation suffixes when count > 1, so the live file is
	# logs/gravaai.log.0 (the base name is unused). Fall back to any generation file.
	log="$(ls "$profile"/logs/gravaai.log* 2>/dev/null | sort | head -1)"
	if [ -z "$log" ] || [ ! -f "$log" ]; then
		echo "startup-check.sh: run $i produced no log under $profile/logs" >&2
		tail -5 "$RUN_ROOT/run-$i.out" >&2 || true
		failures=$((failures + 1))
		continue
	fi
	visible="$(sed -n 's/.*startup: window shown at \([0-9-]*\) ms.*/\1/p' "$log" | head -1)"
	preview="$(sed -n 's/.*startup: first preview frame at \([0-9-]*\) ms.*/\1/p' "$log" | head -1)"
	visible_values+=("${visible:-?}")
	preview_values+=("${preview:-?}")
	printf 'run %d: window shown %s ms · first preview frame %s ms\n' "$i" "${visible:-?}" "${preview:-not measured}"
done

summarize() {
	local label="$1"; shift
	local values=("$@")
	local total=0 count=0
	for v in "${values[@]}"; do
		if [[ "$v" =~ ^[0-9]+$ ]]; then total=$((total + v)); count=$((count + 1)); fi
	done
	if [ "$count" -eq 0 ]; then
		printf '%s: no measurements\n' "$label"
		return 1
	fi
	printf '%s: mean %d ms over %d runs\n' "$label" "$((total / count))" "$count"
}

echo
echo "startup-check.sh: profile $RUN_ROOT"
summarize "window shown" "${visible_values[@]}" || true
summarize "first preview frame" "${preview_values[@]}" || true

visible_mean=0
for v in "${visible_values[@]}"; do [[ "$v" =~ ^[0-9]+$ ]] && visible_mean=$((visible_mean + v)); done
if [ "${#visible_values[@]}" -gt 0 ]; then visible_mean=$((visible_mean / ${#visible_values[@]})); fi
if [ "$visible_mean" -gt 800 ]; then
	echo "startup-check.sh: FAIL window visible mean ${visible_mean} ms > 800 ms" >&2
	failures=$((failures + 1))
else
	echo "startup-check.sh: PASS window visible mean ${visible_mean} ms <= 800 ms"
fi

if [ "$REQUIRE_PREVIEW" = 1 ]; then
	preview_mean=0
	valid=0
	for v in "${preview_values[@]}"; do
		if [[ "$v" =~ ^[0-9]+$ ]]; then preview_mean=$((preview_mean + v)); valid=$((valid + 1)); fi
	done
	if [ "$valid" -eq 0 ]; then
		echo "startup-check.sh: FAIL no first-preview-frame measurements" >&2
		failures=$((failures + 1))
	else
		preview_mean=$((preview_mean / valid))
		if [ "$preview_mean" -gt 900 ]; then
			echo "startup-check.sh: FAIL first preview frame mean ${preview_mean} ms > 900 ms" >&2
			failures=$((failures + 1))
		else
			echo "startup-check.sh: PASS first preview frame mean ${preview_mean} ms <= 900 ms"
		fi
	fi
fi

if [ "$failures" -ne 0 ]; then
	echo "startup-check.sh: $failures failure(s)" >&2
	exit 1
fi
echo "startup-check.sh: all checks passed"
