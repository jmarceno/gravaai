#!/usr/bin/env bash
#
# Stages the ONNX Runtime CPU library the Java app loads in-process through
# java.lang.foreign (OnnxLibs), plus the two bundled segmentation models it runs
# (webcam background blur and removal). Unlike build-ffmpeg.sh nothing is
# compiled: the release tarball is fetched, checksum-verified and unpacked.
#
# Output layout (what -Pdist copies to <appimage>/lib/native/linux-x64/, plan §5.19.3):
#
#   java/native/build/onnxruntime/lib/libonnxruntime.so*
#   java/native/build/onnxruntime/include/...   (only to regenerate the FFM bindings)
#   java/native/build/models/segmentation/selfie-segmenter.onnx   (fast path)
#   java/native/build/models/segmentation/modnet.onnx             (precise path)
#
# Properties
#   * CPU-only linux-x64 build: the segmenter runs on a dedicated platform thread and
#     the CUDA/OpenVINO providers are never shipped (the FFmpeg payload policy of
#     software-only playback does not extend to inference, but GPU providers would
#     drag in CUDA runtimes we do not bundle).
#   * Both models are committed at java/native/models/segmentation/: MediaPipe selfie
#     segmenter (Apache-2.0, ONNX conversion) for the fast path and MODNet (Apache-2.0,
#     README covers code, models and demos) for the precise one. The build script stages
#     copies into build/models/ like the rnnoise model so package-dist.sh has one layout.
#
# Usage: ./build-onnxruntime.sh [--clean]
#   GRAVAAI_NATIVE_CACHE   scratch download directory
#                          (default /media/jmarceno/scratch/gravaai-native)
set -euo pipefail

ONNXRUNTIME_VERSION="1.31.1"
# sha256 of https://github.com/microsoft/onnxruntime/releases/download/v1.31.1/onnxruntime-linux-x64-1.31.1.tgz
ONNXRUNTIME_SHA256="c849e36a1ef3e22d4ff217b9d1ce4a3cf5b9467b975610913ae959ad5b16bdc9"

SELFIE_SEGMENTER_SHA256="3241ac4ad8aa35bdaf33946776db29f7c283a413aa0b0dacb9483594b4531aad"
# Xenova/modnet onnx/model.onnx, the file the two-path design was measured on
# (25,888,640 bytes).
MODNET_SHA256="07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${GRAVAAI_NATIVE_CACHE:-/media/jmarceno/scratch/gravaai-native}"
downloads="$cache/downloads"
out="$here/build/onnxruntime"
models_out="$here/build/models/segmentation"
model_src="$here/models/segmentation/selfie-segmenter.onnx"
modnet_src="$here/models/segmentation/modnet.onnx"

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

# Stage the bundled segmentation models (committed in the repo; shas pinned above).
mkdir -p "$models_out"
stage_model() {
	# stage_model <source> <file name> <sha256>
	local src="$1" name="$2" want="$3" got
	[[ -f "$src" ]] || die "missing model: $src"
	got="$(sha256sum "$src" | cut -d' ' -f1)"
	[[ "$got" == "$want" ]] || die "model sha mismatch for $name: expected $want, got $got"
	cp "$src" "$models_out/$name"
	say "staged $models_out/$name"
}
stage_model "$model_src" "selfie-segmenter.onnx" "$SELFIE_SEGMENTER_SHA256"
stage_model "$modnet_src" "modnet.onnx" "$MODNET_SHA256"
say "done"
