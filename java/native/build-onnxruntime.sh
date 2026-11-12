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
#   java/native/build/onnxruntime/lib/libonnxruntime_providers_webgpu.so  (GPU plugin EP)
#   java/native/build/onnxruntime/include/...   (only to regenerate the FFM bindings)
#   java/native/build/models/segmentation/selfie-segmenter.onnx   (fast path)
#   java/native/build/models/segmentation/modnet.onnx             (precise path)
#   java/native/build/models/detection/face_detection_yunet_2023mar.onnx
#   java/native/build/models/detection/ch_PP-OCRv4_det_infer.onnx   (auto-mask screen text)
#   java/native/build/models/detection/ch_PP-OCRv4_rec_infer.onnx
#
# Properties
#   * The runtime is the stock CPU-only linux-x64 build: every model except the
#     super-resolution upscaler runs on the CPU EP, on dedicated platform
#     threads. Vendor GPU *bridge* providers (CUDA, ROCm, OpenVINO) are never
#     shipped: each one is single-vendor and drags in a vendor runtime.
#   * The one GPU provider is the vendor-neutral **WebGPU plugin EP**
#     (`onnxruntime_providers_webgpu.so`, MIT, Microsoft): on Linux it runs
#     through Dawn on Vulkan, so AMD (RADV/AMDVLK), Intel (ANV) and NVIDIA
#     all use the same library. It is a *plugin* registered into the stock
#     runtime, not a second libonnxruntime.so, so OnnxLibs' one-runtime
#     invariant holds. The system Vulkan loader (`libvulkan.so.1`) is a runtime
#     requirement and is deliberately NOT bundled — it must match the host
#     driver. Registration and device enumeration are validated at startup;
#     any failure (missing loader, old driver) means "CPU only", never a crash.
#   * Every model is committed under java/native/models/: MediaPipe selfie
#     segmenter (Apache-2.0, ONNX conversion) for the fast path, MODNet (Apache-2.0,
#     README covers code, models and demos) for the precise one, and YuNet
#     (MIT, opencv_zoo) for face detection, and PP-OCRv4 mobile det+rec
#     (Apache-2.0) for screen text. The build script stages copies into
#     build/models/ like the rnnoise model so package-dist.sh has one layout.
#   * The super-resolution model (Real-ESRGAN general x4v3, BSD-3-Clause) is
#     NOT staged and NOT bundled: it is an optional ~4.9 MB download installed
#     on first use (SuperResAssets/SuperResInstaller), like the GLiNER
#     auto-mask asset. The committed copy under
#     java/native/models/superres/ is the pinned source tests run against; it
#     is deliberately absent from build/models/ so package-dist never ships
#     megabytes every user would then download again into their profile.
#
# Usage: ./build-onnxruntime.sh [--clean]
#   GRAVAAI_NATIVE_CACHE   scratch download directory
#                          (default /media/jmarceno/scratch/gravaai-native)
set -euo pipefail

ONNXRUNTIME_VERSION="1.31.1"
# sha256 of https://github.com/microsoft/onnxruntime/releases/download/v1.31.1/onnxruntime-linux-x64-1.31.1.tgz
ONNXRUNTIME_SHA256="c849e36a1ef3e22d4ff217b9d1ce4a3cf5b9467b975610913ae959ad5b16bdc9"

# WebGPU plugin EP (MIT, Microsoft), the vendor-neutral GPU provider: Dawn over Vulkan on Linux.
# The wheel is a zip; only libonnxruntime_providers_webgpu.so is staged, never the wheel.
WEBGPU_EP_VERSION="0.5.0"
WEBGPU_EP_WHEEL_SHA256="b6baa3e223d9a679023388b1179e5597b6bc0d282ba72f2afe38d46ef37deeb4"
# sha256 of the extracted libonnxruntime_providers_webgpu.so (16,613,128 bytes).
WEBGPU_EP_SO_SHA256="fc98b10c7d03537694ff4ea6ebca79ed3b41ca71e316dac3292257ee5f85c95d"

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

# fetch_optional is fetch(), but a missing network is a skip instead of a build failure. Used for
# the WebGPU plugin EP, whose absence degrades the app to the CPU provider but breaks nothing.
fetch_optional() {
	# fetch_optional <url> <dst> <sha256>
	local url="$1" dst="$2" want="$3"
	if [[ -f "$dst" && "$(sha256sum "$dst" | cut -d' ' -f1)" == "$want" ]]; then
		return 0
	fi
	rm -f "$dst" "$dst.part"
	mkdir -p "$(dirname "$dst")"
	say "downloading $url"
	if ! curl -fsSL --retry 3 --retry-all-errors --retry-delay 5 --max-time 600 -o "$dst.part" "$url"; then
		rm -f "$dst.part"
		return 1
	fi
	local got
	got="$(sha256sum "$dst.part" | cut -d' ' -f1)"
	if [[ "$got" != "$want" ]]; then
		rm -f "$dst.part"
		die "sha256 mismatch for $dst: expected $want, got $got"
	fi
	mv "$dst.part" "$dst"
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

# Stage the WebGPU plugin EP beside the runtime. It is skipped (with a warning) when the
# manylinux wheel is unreachable, because every other feature still works on the CPU EP; but a
# downloaded wheel whose .so does not match the pin is fatal.
if [[ -f "$out/lib/libonnxruntime_providers_webgpu.so" ]]; then
	say "webgpu plugin EP $WEBGPU_EP_VERSION already staged"
else
	wheel="$downloads/onnxruntime_ep_webgpu-$WEBGPU_EP_VERSION-py3-none-manylinux_2_28_x86_64.whl"
	if fetch_optional "https://files.pythonhosted.org/packages/e2/1d/ac0ccbe8bd6435b6e1946fea964790ffa3d73ee2513775e90958fd6b860e/onnxruntime_ep_webgpu-$WEBGPU_EP_VERSION-py3-none-manylinux_2_28_x86_64.whl" \
			"$wheel" "$WEBGPU_EP_WHEEL_SHA256"; then
		say "extracting libonnxruntime_providers_webgpu.so from $wheel"
		# The wheel is a zip. Extract the one member into a scratch dir: running an interpreter
		# here is unnecessary, and the member path is fixed by the package layout.
		ep_tmp="$(mktemp -d)"
		unzip -q -o "$wheel" 'onnxruntime_ep_webgpu/libonnxruntime_providers_webgpu.so' -d "$ep_tmp"
		ep_so="$ep_tmp/onnxruntime_ep_webgpu/libonnxruntime_providers_webgpu.so"
		[[ -f "$ep_so" ]] || die "libonnxruntime_providers_webgpu.so missing from $wheel"
		got="$(sha256sum "$ep_so" | cut -d' ' -f1)"
		[[ "$got" == "$WEBGPU_EP_SO_SHA256" ]] \
			|| die "sha256 mismatch for libonnxruntime_providers_webgpu.so: expected $WEBGPU_EP_SO_SHA256, got $got"
		# Baseline check: the plugin may need nothing newer than the manylinux_2_28 glibc.
		if command -v objdump >/dev/null 2>&1; then
			newest_glibc="$(objdump -T "$ep_so" | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1)"
			case "$newest_glibc" in
				""|GLIBC_2.25|GLIBC_2.27|GLIBC_2.28) ;;
				*) die "$ep_so needs $newest_glibc, newer than the manylinux_2_28 baseline" ;;
			esac
		fi
		cp "$ep_so" "$out/lib/libonnxruntime_providers_webgpu.so"
		rm -rf "$ep_tmp"
		say "staged $out/lib/libonnxruntime_providers_webgpu.so (Vulkan loader is a system requirement)"
	else
		say "warning: could not fetch the webgpu plugin EP; GPU super-resolution will be unavailable"
	fi
fi

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
