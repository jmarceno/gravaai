#!/usr/bin/env bash
#
# Regenerates the ONNX Runtime FFM bindings in
# gravaai-media/src/main/java/dev/gravaai/media/onnx/gen/ (webcam background blur).
#
# The generated bindings are committed: the Maven build must never need jextract. Run this
# script only when the pinned ONNX Runtime version (java/native/build-onnxruntime.sh)
# changes, then commit the regenerated sources.
#
# jextract comes from jdk.java.net/jextract (the latest build; JDK 25 based), the same
# toolchain the FFmpeg bindings use (see tools/extract-ffmpeg-bindings.sh). Unlike FFmpeg
# the ONNX Runtime C API is a single struct of function pointers (OrtApi): the generated
# OrtApi class exposes every entry point as a struct field accessor, and the wrapper
# (OnnxRuntimeApi) binds the handful of functions the segmenter needs.
#
# Usage:
#   java/native/build-onnxruntime.sh        # once, to stage the headers
#   java/tools/extract-onnxruntime-bindings.sh
#
# Environment:
#   JEXTRACT              path to the jextract launcher
#                         (default /media/jmarceno/scratch/java-toolchain/jextract-25/bin/jextract)
#   ONNXRUNTIME_PREFIX    the ONNX Runtime install prefix
#                         (default java/native/build/onnxruntime)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_root="$(cd "$here/.." && pwd)"
jextract_bin="${JEXTRACT:-/media/jmarceno/scratch/java-toolchain/jextract-25/bin/jextract}"
prefix="${ONNXRUNTIME_PREFIX:-$java_root/native/build/onnxruntime}"
includes="$java_root/native/onnxruntime-jextract-includes.txt"
target_package="dev.gravaai.media.onnx.gen"
gen_dir="$java_root/gravaai-media/src/main/java/dev/gravaai/media/onnx/gen"

[[ -x "$jextract_bin" ]] || {
	echo "extract-onnxruntime-bindings.sh: jextract not found at $jextract_bin" >&2
	echo "download it from https://jdk.java.net/jextract/ and set JEXTRACT=<path>/bin/jextract" >&2
	exit 1
}
[[ -f "$prefix/include/onnxruntime_c_api.h" ]] || {
	echo "extract-onnxruntime-bindings.sh: $prefix/include/onnxruntime_c_api.h is missing; run java/native/build-onnxruntime.sh first" >&2
	exit 1
}
[[ -f "$includes" ]] || {
	echo "extract-onnxruntime-bindings.sh: include list $includes is missing" >&2
	exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

(
	cd "$prefix/include"
	"$jextract_bin" -I"$prefix/include" \
		--header-class-name onnxruntime_h \
		-t "$target_package" \
		@"$includes" \
		onnxruntime_c_api.h \
		--output "$tmp"
)

generated="$tmp/$(echo "$target_package" | tr '.' '/')"
[[ -d "$generated" ]] || {
	echo "extract-onnxruntime-bindings.sh: jextract produced no sources" >&2
	exit 1
}
rm -rf "$gen_dir"
mkdir -p "$gen_dir"
cp "$generated"/*.java "$gen_dir"/
# Same NullAway hardening as the FFmpeg bindings (see that script's header): the shared
# canonical-layout constants must be non-null and the package gets @NullMarked.
python3 "$here/postprocess-ffmpeg-bindings.py" \
	--package "$target_package" \
	--shared-name 'onnxruntime_h$shared.java' \
	"$gen_dir"

echo "extract-onnxruntime-bindings.sh: regenerated $(ls "$gen_dir" | wc -l) files in $gen_dir"
