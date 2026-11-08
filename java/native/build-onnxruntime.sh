#!/usr/bin/env bash
#
# Stages the ONNX Runtime CPU library the Java app loads in-process through
# java.lang.foreign (OnnxLibs), plus the bundled detection/segmentation models it
# runs (webcam background blur and removal, auto-mask face detection). Unlike
# build-ffmpeg.sh nothing is compiled: the release tarball is fetched,
# checksum-verified and unpacked.
#
# Output layout (what -Pdist copies to <appimage>/lib/native/linux-x64/, plan §5.19.3):
#
#   java/native/build/onnxruntime/lib/libonnxruntime.so*
#   java/native/build/onnxruntime/include/...   (only to regenerate the FFM bindings)
#   java/native/build/models/segmentation/selfie-segmenter.onnx   (fast path)
#   java/native/build/models/segmentation/modnet.onnx             (precise path)
#   java/native/build/models/detection/face_detection_yunet_2023mar.onnx
#   java/native/build/models/detection/ch_PP-OCRv4_det_infer.onnx   (auto-mask screen text)
#   java/native/build/models/detection/ch_PP-OCRv4_rec_infer.onnx
#
# Properties
#   * CPU-only linux-x64 build: the models run on dedicated platform threads and
#     the CUDA/OpenVINO providers are never shipped (the FFmpeg payload policy of
#     software-only playback does not extend to inference, but GPU providers would
#     drag in CUDA runtimes we do not bundle).
#   * Every model is committed under java/native/models/: MediaPipe selfie
#     segmenter (Apache-2.0, ONNX conversion) for the fast path, MODNet (Apache-2.0,
#     README covers code, models and demos) for the precise one, and YuNet
#     (MIT, opencv_zoo) for face detection, and PP-OCRv4 mobile det+rec
#     (Apache-2.0) for screen text. The build script stages copies into
#     build/models/ like the rnnoise model so package-dist.sh has one layout.
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

# opencv_zoo face_detection_yunet_2023mar.onnx (232,589 bytes), MIT.
YUNET_SHA256="8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

# PP-OCRv4 mobile det+rec from RapidOCR's model zoo, Apache-2.0 (PaddleOCR derived).
PPOCR_DET_SHA256="d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"
PPOCR_REC_SHA256="48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${GRAVAAI_NATIVE_CACHE:-/media/jmarceno/scratch/gravaai-native}"
downloads="$cache/downloads"
out="$here/build/onnxruntime"
models_out="$here/build/models/segmentation"
detection_out="$here/build/models/detection"
model_src="$here/models/segmentation/selfie-segmenter.onnx"
modnet_src="$here/models/segmentation/modnet.onnx"
yunet_src="$here/models/detection/face_detection_yunet_2023mar.onnx"
ppocr_det_src="$here/models/detection/ch_PP-OCRv4_det_infer.onnx"
ppocr_rec_src="$here/models/detection/ch_PP-OCRv4_rec_infer.onnx"

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

# Stage the bundled models (committed in the repo; shas pinned above).
mkdir -p "$models_out" "$detection_out"
stage_model() {
	# stage_model <source> <destination directory> <file name> <sha256>
	local src="$1" dir="$2" name="$3" want="$4" got
	[[ -f "$src" ]] || die "missing model: $src"
	got="$(sha256sum "$src" | cut -d' ' -f1)"
	[[ "$got" == "$want" ]] || die "model sha mismatch for $name: expected $want, got $got"
	cp "$src" "$dir/$name"
	say "staged $dir/$name"
}
stage_model "$model_src" "$models_out" "selfie-segmenter.onnx" "$SELFIE_SEGMENTER_SHA256"
stage_model "$modnet_src" "$models_out" "modnet.onnx" "$MODNET_SHA256"
stage_model "$yunet_src" "$detection_out" "face_detection_yunet_2023mar.onnx" "$YUNET_SHA256"
stage_model "$ppocr_det_src" "$detection_out" "ch_PP-OCRv4_det_infer.onnx" "$PPOCR_DET_SHA256"
stage_model "$ppocr_rec_src" "$detection_out" "ch_PP-OCRv4_rec_infer.onnx" "$PPOCR_REC_SHA256"
say "done"
