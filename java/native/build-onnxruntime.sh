#!/usr/bin/env bash
#
# Stages the ONNX Runtime CPU library the Java app loads in-process through
# java.lang.foreign (OnnxLibs), plus the bundled selfie-segmenter model it runs
# (webcam background blur). Unlike build-ffmpeg.sh nothing is compiled: the
# release tarball is fetched, checksum-verified and unpacked.
#
# Output layout (what -Pdist copies to <appimage>/lib/native/linux-x64/, plan §5.19.3):
#
#   java/native/build/onnxruntime/lib/libonnxruntime.so*
#   java/native/build/onnxruntime/include/...   (only to regenerate the FFM bindings)
#   java/native/build/models/segmentation/selfie-segmenter.onnx
#
# Properties
#   * CPU-only linux-x64 build: the segmenter runs on a dedicated platform thread and
#     the CUDA/OpenVINO providers are never shipped (the FFmpeg payload policy of
#     software-only playback does not extend to inference, but GPU providers would
#     drag in CUDA runtimes we do not bundle).
#   * The model is committed at java/native/models/segmentation/ (Apache-2.0,
#     MediaPipe selfie segmenter ONNX conversion); the build script stages a copy
#     into build/models/ like the rnnoise model so package-dist.sh has one layout.
#
# Usage: ./build-onnxruntime.sh [--clean]
#   GRAVAAI_NATIVE_CACHE   scratch download directory
#                          (default /media/jmarceno/scratch/gravaai-native)
set -euo pipefail

ONNXRUNTIME_VERSION="1.31.1"
# sha256 of https://github.com/microsoft/onnxruntime/releases/download/v1.31.1/onnxruntime-linux-x64-1.31.1.tgz
ONNXRUNTIME_SHA256="c849e36a1ef3e22d4ff217b9d1ce4a3cf5b9467b975610913ae959ad5b16bdc9"

SELFIE_SEGMENTER_SHA256="3241ac4ad8aa35bdaf33946776db29f7c283a413aa0b0dacb9483594b4531aad"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${GRAVAAI_NATIVE_CACHE:-/media/jmarceno/scratch/gravaai-native}"
downloads="$cache/downloads"
out="$here/build/onnxruntime"
models_out="$here/build/models/segmentation"
model_src="$here/models/segmentation/selfie-segmenter.onnx"

say() { printf '[build-onnxruntime] %s\n' "$*"; }
die() { printf '[build-onnxruntime] error: %s\n' "$*" >&2; exit 1; }

fetch() {
	# fetch <url> <dst> <sha256>
	local url="$1" dst="$2" want="$3"
	if [[ -f "$dst" ]]; then
		if [[ "$(sha256sum "$dst" | cut -d' ' -f1)" == "$want" ]]; then
			return
		fi
		say "checksum mismatch, re-downloading $dst"
		rm -f "$dst"
	fi
	mkdir -p "$(dirname "$dst")"
	say "downloading $url"
	curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --max-time 1800 -o "$dst.part" "$url"
	mv "$dst.part" "$dst"
	local got
	got="$(sha256sum "$dst" | cut -d' ' -f1)"
	[[ "$got" == "$want" ]] || die "sha256 mismatch for $dst: expected $want, got $got"
}

if [[ "${1:-}" == "--clean" ]]; then
	say "cleaning $out"
	rm -rf "$out"
fi

if [[ -f "$out/lib/libonnxruntime.so.$ONNXRUNTIME_VERSION" ]]; then
	say "onnxruntime $ONNXRUNTIME_VERSION already staged"
else
	tarball="$downloads/onnxruntime-linux-x64-$ONNXRUNTIME_VERSION.tgz"
	fetch "https://github.com/microsoft/onnxruntime/releases/download/v$ONNXRUNTIME_VERSION/onnxruntime-linux-x64-$ONNXRUNTIME_VERSION.tgz" \
		"$tarball" "$ONNXRUNTIME_SHA256"
	say "unpacking $tarball"
	work="$(mktemp -d)"
	trap 'rm -rf "$work"' EXIT
	tar -xzf "$tarball" -C "$work"
	rm -rf "$out"
	mkdir -p "$out"
	cp -r "$work/onnxruntime-linux-x64-$ONNXRUNTIME_VERSION/lib" "$out/lib"
	cp -r "$work/onnxruntime-linux-x64-$ONNXRUNTIME_VERSION/include" "$out/include"
	say "staged $out"
fi

[[ -f "$out/lib/libonnxruntime.so" ]] || die "libonnxruntime.so missing after staging"

# Stage the bundled segmentation model (committed in the repo; sha pinned above).
got="$(sha256sum "$model_src" | cut -d' ' -f1)"
[[ "$got" == "$SELFIE_SEGMENTER_SHA256" ]] || die "model sha mismatch: expected $SELFIE_SEGMENTER_SHA256, got $got"
mkdir -p "$models_out"
cp "$model_src" "$models_out/selfie-segmenter.onnx"
say "staged $models_out/selfie-segmenter.onnx"
say "done"
