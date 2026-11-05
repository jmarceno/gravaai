#!/usr/bin/env bash
# Stages a prebuilt FFmpeg into java/native/build/ffmpeg/{bin,lib} for the release AppImage.
#
# The build is BtbN's linux64 GPL shared build (FFmpeg 8.1: same sonames as the 8.0 headers the
# FFM bindings were generated from - libavcodec.so.62, libavutil.so.60, libavfilter.so.11). It
# carries the GPU paths (VA-API, Vulkan, NVENC/CUDA, QSV, AMF) next to libx264/libopus/libmp3lame,
# and needs nothing from the host but glibc 2.28, so it runs on every distribution the AppImage
# targets. Pinned by URL and sha256: a moved or changed file fails the release loudly.
#
# Usage: fetch-ffmpeg.sh <native-build-dir>     (e.g. java/native/build)
# Env:   GRAVAAI_NATIVE_CACHE   download cache (kept between runs when it is a volume)
set -euo pipefail

FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-11-04-12-51/ffmpeg-n8.1.3-34-ga35157abb9-linux64-gpl-shared-8.1.tar.xz"
FFMPEG_SHA256="31eb319e7bba354bd8dfb6dc8250a51aa2f3b305cf5cb949d00178386031f362"

out_root="${1:?usage: fetch-ffmpeg.sh <native-build-dir>}"
cache="${GRAVAAI_NATIVE_CACHE:-/var/cache/gravaai-native}/downloads"
tarball="$cache/$(basename "$FFMPEG_URL")"
out="$out_root/ffmpeg"

say() { printf '[fetch-ffmpeg] %s\n' "$*"; }
die() { printf '[fetch-ffmpeg] error: %s\n' "$*" >&2; exit 1; }

mkdir -p "$cache"
if [[ ! -f "$tarball" ]] || [[ "$(sha256sum "$tarball" | cut -d' ' -f1)" != "$FFMPEG_SHA256" ]]; then
	say "downloading $FFMPEG_URL"
	# --speed-limit/--speed-time abort a stalled transfer so --retry can start a fresh one.
	curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --speed-limit 262144 --speed-time 30 \
		--max-time 900 -o "$tarball.part" "$FFMPEG_URL"
	got="$(sha256sum "$tarball.part" | cut -d' ' -f1)"
	[[ "$got" == "$FFMPEG_SHA256" ]] || die "sha256 mismatch: expected $FFMPEG_SHA256, got $got"
	mv "$tarball.part" "$tarball"
fi

rm -rf "$out"
mkdir -p "$out/bin" "$out/lib"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tar xJf "$tarball" -C "$work" --strip-components=1
cp -a "$work/bin/ffmpeg" "$work/bin/ffprobe" "$out/bin/"
cp -a "$work"/lib/*.so* "$out/lib/"

# The CLIs already carry RPATH $ORIGIN/../lib; give the libraries $ORIGIN too, so each finds its
# siblings however it is loaded (the app dlopens them by path through java.lang.foreign).
for lib in "$out"/lib/*.so.*; do
	[[ -L "$lib" ]] || patchelf --set-rpath '$ORIGIN' "$lib"
done

"$out/bin/ffmpeg" -hide_banner -version | head -n1
for encoder in libx264 libopus libmp3lame h264_vaapi h264_vulkan h264_nvenc; do
	"$out/bin/ffmpeg" -hide_banner -encoders 2>/dev/null | grep -qE "^ *[A-Z.]+ +$encoder " \
		|| die "encoder $encoder missing from the staged FFmpeg"
done
say "staged $(du -sh "$out" | cut -f1) into $out"
