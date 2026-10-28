#!/usr/bin/env bash
#
# Regenerates the FFmpeg FFM bindings in
# gravaai-media/src/main/java/dev/gravaai/media/ffmpeg/gen/ (plan §4.4, §5.7).
#
# The generated bindings are committed: the Maven build must never need jextract. Run this
# script only when the pinned FFmpeg version (java/native/build-ffmpeg.sh) changes, then
# commit the regenerated sources.
#
# jextract comes from jdk.java.net/jextract (the latest build; JDK 25 based). Its output uses
# the final java.lang.foreign API, which compiles on the pinned JDK 27 toolchain.
#
# Usage:
#   java/native/build-ffmpeg.sh            # once, to install the headers
#   java/tools/extract-ffmpeg-bindings.sh  # then regenerate
#
# Environment:
#   JEXTRACT       path to the jextract launcher
#                  (default /media/jmarceno/scratch/java-toolchain/jextract-25/bin/jextract)
#   FFMPEG_PREFIX  the FFmpeg install prefix
#                  (default java/native/build/ffmpeg)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_root="$(cd "$here/.." && pwd)"
repo_root="$(cd "$java_root/.." && pwd)"
jextract_bin="${JEXTRACT:-/media/jmarceno/scratch/java-toolchain/jextract-25/bin/jextract}"
ffmpeg_prefix="${FFMPEG_PREFIX:-$java_root/native/build/ffmpeg}"
includes="$java_root/native/ffmpeg-jextract-includes.txt"
target_package="dev.gravaai.media.ffmpeg.gen"
gen_dir="$java_root/gravaai-media/src/main/java/dev/gravaai/media/ffmpeg/gen"

[[ -x "$jextract_bin" ]] || {
	echo "extract-ffmpeg-bindings.sh: jextract not found at $jextract_bin" >&2
	echo "download it from https://jdk.java.net/jextract/ and set JEXTRACT=<path>/bin/jextract" >&2
	exit 1
}
[[ -d "$ffmpeg_prefix/include" ]] || {
	echo "extract-ffmpeg-bindings.sh: $ffmpeg_prefix/include is missing; run java/native/build-ffmpeg.sh first" >&2
	exit 1
}
[[ -f "$includes" ]] || {
	echo "extract-ffmpeg-bindings.sh: include list $includes is missing" >&2
	exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Exact command (also documented in java/native/README.md). Header names are resolved below
# $ffmpeg_prefix/include; @includes.txt restricts generation to the symbols the wrappers use.
(
	cd "$ffmpeg_prefix/include"
	"$jextract_bin" -I"$ffmpeg_prefix/include" \
		--header-class-name ffmpeg_h \
		-t "$target_package" \
		@"$includes" \
		libavcodec/avcodec.h \
		libavformat/avformat.h \
		libavfilter/avfilter.h \
		libavfilter/buffersrc.h \
		libavfilter/buffersink.h \
		libavdevice/avdevice.h \
		libswscale/swscale.h \
		libswresample/swresample.h \
		libavutil/imgutils.h \
		libavutil/opt.h \
		libavutil/display.h \
		libavutil/samplefmt.h \
		libavutil/channel_layout.h \
		libavutil/dict.h \
		libavutil/log.h \
		--output "$tmp"
)

generated="$tmp/$(echo "$target_package" | tr '.' '/')"
[[ -d "$generated" ]] || {
	echo "extract-ffmpeg-bindings.sh: jextract produced no sources" >&2
	exit 1
}
rm -rf "$gen_dir"
mkdir -p "$gen_dir"
cp "$generated"/*.java "$gen_dir"/

echo "extract-ffmpeg-bindings.sh: regenerated $(ls "$gen_dir" | wc -l) files in $gen_dir"
