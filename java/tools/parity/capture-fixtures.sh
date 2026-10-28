#!/usr/bin/env bash
#
# Captures the P2.1 golden reference frames from the running Electron app (plan §6.2, §7.2 P2.1).
#
#   # 1. start the Electron reference app with a scratch profile and AI control enabled
#   #    (see java/parity/README.md; never run the Java app at the same time)
#   # 2. capture every fixture
#   java/tools/parity/capture-fixtures.sh
#   java/tools/parity/capture-fixtures.sh --only 03-zoom-motion-blur
#
# The frames come from Electron's MCP "frames" tool, one call per timestamp (the tool tiles several
# timestamps into a contact sheet). Each frame is converted from the tool's JPEG to PNG with our own
# software ffmpeg build. The script refuses to run while the Java app is running.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
fixtures="$repo/java/parity/fixtures"
plan="$fixtures/capture-plan.json"
out="$repo/java/parity/golden"
ffmpeg=${GRAVAAI_FFMPEG:-"$repo/java/native/build/ffmpeg/bin/ffmpeg"}
electron=${GRAVAAI_ELECTRON_BIN:-"$repo/node_modules/electron/dist/electron"}
app_profile=${GRAVAAI_ELECTRON_PROFILE:-/tmp/gravaai-electron-parity/Gravaai}
bridge_home=${GRAVAAI_BRIDGE_HOME:-/tmp/gravaai-parity-bridge}
cdp_port=${GRAVAAI_ELECTRON_CDP_PORT:-9333}
only=""

while [ $# -gt 0 ]; do
	case "$1" in
		--only) only="${2:-}"; shift 2 ;;
		--out) out="$2"; shift 2 ;;
		*) echo "capture-fixtures: unknown argument $1" >&2; exit 64 ;;
	esac
done

if pgrep -x gravaai > /dev/null 2>&1; then
	echo "capture-fixtures: the Java app is running; never run Electron and Gravaai at the same time (plan §7)" >&2
	exit 1
fi
if [ ! -S "$app_profile/agent.sock" ]; then
	echo "capture-fixtures: no running Electron app at $app_profile/agent.sock" >&2
	echo "  start it with AI control enabled and --remote-debugging-port=$cdp_port (see java/parity/golden/README.md), then retry" >&2
	exit 1
fi
if [ ! -x "$ffmpeg" ]; then
	echo "capture-fixtures: bundled ffmpeg missing at $ffmpeg (run java/native/build-ffmpeg.sh)" >&2
	exit 1
fi

# The unpackaged --mcp bridge resolves its own userData to <XDG_CONFIG_HOME>/Gravaai-dev while the
# dev app uses <XDG_CONFIG_HOME>/Gravaai, so give the bridge a throwaway config home that contains
# the app's socket and token.
mkdir -p "$bridge_home/Gravaai-dev"
ln -sfn "$app_profile/agent.sock" "$bridge_home/Gravaai-dev/agent.sock"
cp "$app_profile/agent-token" "$bridge_home/Gravaai-dev/agent-token"
mcp_cmd="env XDG_CONFIG_HOME=$bridge_home $electron . --mcp --ozone-platform=headless"
width=$(jq -r '.width' "$plan")

# The app assigns a projectId, rewrites the project JSON in its own formatting and writes
# .bak/.preview.png thumbnails next to every project it opens; keep the committed fixtures clean by
# restoring them and deleting those artefacts when the capture run ends.
projects_dir="$fixtures/projects"
fixtures_backup=$(mktemp -d)
cp -a "$projects_dir/." "$fixtures_backup/"
restore_fixtures() {
	rm -f "$projects_dir"/*.bak "$projects_dir"/*.preview.png
	cp -a "$fixtures_backup/." "$projects_dir/"
	find "$fixtures/media" -maxdepth 1 -name '*.gravaai-session.json' -delete
	rm -rf "$fixtures_backup"
}
trap restore_fixtures EXIT

jq -r '.projects[] | [.name, (.timesSeconds | map(tostring) | join(","))] | @tsv' "$plan" |
while IFS=$'\t' read -r name times; do
	if [ -n "$only" ] && [ "$only" != "$name" ]; then
		continue
	fi
	echo "capture-fixtures: $name @ $times"
	node "$here/capture-golden.mjs" \
		--project "$fixtures/projects/$name.gravaai" \
		--times "$times" \
		--name "$name" \
		--width "$width" \
		--out "$out" \
		--mcp-cmd "$mcp_cmd" \
		--ffmpeg "$ffmpeg" \
		--cdp-port "$cdp_port"
done

node "$here/write-golden-manifest.mjs"

echo "capture-fixtures: done -> $out"