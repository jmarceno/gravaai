#!/usr/bin/env bash
#
# Builds the Linux native helpers used by the Java app (plan §5.7, §7.1 P1.5):
#
#   gravaai-wayland-capture  screen capture over ext-image-copy-capture
#   gravaai-wayland-cursor   global cursor position + click/key telemetry
#   gravaai-ctl              global-shortcut actions over the control socket
#
# Sources are copies of electron/native/{wayland-capture,wayland-cursor,gravaai-ctl}.
# The C code is unchanged; only the output layout differs from the Electron build:
#
#   java/native/build/linux-x64/<helper>
#
# which is what the app image bundles as <appdir>/lib/native/linux-x64/ (plan §5.19.3).
#
# Every Wayland protocol client binding is generated here with wayland-scanner, so a
# clean checkout needs only the system -dev packages below. Nothing hardware-accelerated
# is involved (plan §1.1.1).
#
# Usage: ./build-native-helpers.sh [--clean]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${GRAVAAI_NATIVE_OUT:-$here/build}"
gen="$here/wayland-cursor/build"

say() { printf '[build-native-helpers] %s\n' "$*"; }
die() { printf '[build-native-helpers] error: %s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == "--clean" ]]; then
	say "removing the helper binaries and staged models in $out"
	rm -f "$out"/gravaai-*
	rm -rf "$out/models"
fi

for tool in cc pkg-config wayland-scanner; do
	command -v "$tool" >/dev/null 2>&1 || die "$tool is missing (install a C toolchain + libwayland-dev)"
done
pkg-config --exists wayland-client || die "wayland-client.pc is missing (install libwayland-dev)"
proto="$(pkg-config --variable=pkgdatadir wayland-protocols)"
[[ -n "$proto" && -d "$proto" ]] || die "wayland-protocols is missing (install wayland-protocols)"

mkdir -p "$gen" "$out"

say "generating protocol client bindings from $proto"
for xml in \
	"$proto/staging/ext-image-capture-source/ext-image-capture-source-v1.xml" \
	"$proto/staging/ext-image-copy-capture/ext-image-copy-capture-v1.xml" \
	"$proto/staging/ext-foreign-toplevel-list/ext-foreign-toplevel-list-v1.xml" \
	"$proto/unstable/xdg-output/xdg-output-unstable-v1.xml"; do
	[[ -f "$xml" ]] || die "protocol xml not found: $xml"
	name="$(basename "$xml" .xml)"
	wayland-scanner client-header "$xml" "$gen/$name-client.h"
	wayland-scanner private-code "$xml" "$gen/$name.c"
done

cc_flags=(-O2 -Wall -Wextra -Wno-unused-parameter)
wl_flags=($(pkg-config --cflags --libs wayland-client))

say "building gravaai-wayland-cursor"
cc "${cc_flags[@]}" -I"$gen" -o "$out/gravaai-wayland-cursor" \
	"$here/wayland-cursor/main.c" "$gen"/*.c "${wl_flags[@]}"

# wayland-capture reuses the protocol bindings generated for the cursor helper, like
# electron/native/wayland-cursor/build.sh does.
say "building gravaai-wayland-capture"
cc "${cc_flags[@]}" -I"$gen" -o "$out/gravaai-wayland-capture" \
	"$here/wayland-capture/main.c" "$gen"/*.c "${wl_flags[@]}"

# gravaai-ctl is plain C: it only talks to the control socket over libc.
say "building gravaai-ctl"
cc "${cc_flags[@]}" -o "$out/gravaai-ctl" "$here/gravaai-ctl/main.c"

# rnnoise models for the mic noise reduction filter (plan §5.6.5): staged into the native
# payload so NativeHelpers.path("models/rnnoise/voice.rnnn") works in development and in the
# app image. The source copy stays in java/native/models/.
say "staging rnnoise models"
rm -rf "$out/models"
cp -a "$here/models" "$out/models"

say "done -> $out"
ls -l "$out"
