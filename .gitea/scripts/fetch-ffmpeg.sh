#!/usr/bin/env bash
# Stages a prebuilt FFmpeg into java/native/build/ffmpeg/{bin,lib} for the release AppImage.
#
# The build is BtbN's linux64 GPL shared build (FFmpeg 8.1: same sonames as the 8.0 headers the
# FFM bindings were generated from - libavcodec.so.62, libavutil.so.60, libavfilter.so.11). It
# carries the GPU paths (VA-API, Vulkan, NVENC/CUDA, QSV, AMF) next to libx264/libopus/libmp3lame,
# and needs nothing from the host but glibc 2.28, so it runs on every distribution the AppImage
# targets. Pinned by URL and sha256: a moved or changed file fails the release loudly.
#
# This build resolves libva lazily through an import shim that requires libva >= 2.21
# (`vaMapBuffer2`). Ubuntu 24.04 and other targets ship 2.20, where the shim aborts the process on
# the first VA-API call ("failed to resolve symbol 'vaMapBuffer2'"). A newer libva is therefore
# staged next to the FFmpeg libraries and found through the CLI's $ORIGIN/../lib RPATH, so the
# bundled GPU encoder works on hosts whose libva is older than the build expects.
#
# Usage: fetch-ffmpeg.sh <native-build-dir>     (e.g. java/native/build)
# Env:   GRAVAAI_NATIVE_CACHE   download cache (kept between runs when it is a volume)
set -euo pipefail

FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-11-04-12-51/ffmpeg-n8.1.3-34-ga35157abb9-linux64-gpl-shared-8.1.tar.xz"
FFMPEG_SHA256="31eb319e7bba354bd8dfb6dc8250a51aa2f3b305cf5cb949d00178386031f362"
# libva 2.22.0-3 from Debian trixie: the first libva that exports vaMapBuffer2 on this ABI.
LIBVA_URL="http://ftp.debian.org/debian/pool/main/libv/libva/libva2_2.22.0-3_amd64.deb"
LIBVA_SHA256="b76bdd330de47a826698aaed10f53435b703e9a7d4415dd68269c97709f46a9b"

out_root="${1:?usage: fetch-ffmpeg.sh <native-build-dir>}"
cache="${GRAVAAI_NATIVE_CACHE:-/var/cache/gravaai-native}/downloads"
tarball="$cache/$(basename "$FFMPEG_URL")"
libva_deb="$cache/$(basename "$LIBVA_URL")"
out="$out_root/ffmpeg"

say() { printf '[fetch-ffmpeg] %s\n' "$*"; }
die() { printf '[fetch-ffmpeg] error: %s\n' "$*" >&2; exit 1; }

fetch_pinned() {
	# fetch_pinned <url> <destination> <sha256>
	local url="$1" dst="$2" want="$3" got
	if [[ -f "$dst" ]] && [[ "$(sha256sum "$dst" | cut -d' ' -f1)" == "$want" ]]; then
		return
	fi
	say "downloading $url"
	# Abort a dead transfer so --retry can start a fresh one, but do not require a minimum
	# speed: that would kill a healthy slow download (see the Containerfile's download note).
	curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --speed-limit 10240 --speed-time 60 \
		--max-time 1800 -o "$dst.part" "$url"
	got="$(sha256sum "$dst.part" | cut -d' ' -f1)"
	[[ "$got" == "$want" ]] || die "sha256 mismatch for $url: expected $want, got $got"
	mv "$dst.part" "$dst"
}

mkdir -p "$cache"
fetch_pinned "$FFMPEG_URL" "$tarball" "$FFMPEG_SHA256"
fetch_pinned "$LIBVA_URL" "$libva_deb" "$LIBVA_SHA256"

rm -rf "$out"
mkdir -p "$out/bin" "$out/lib"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tar xJf "$tarball" -C "$work" --strip-components=1
cp -a "$work/bin/ffmpeg" "$work/bin/ffprobe" "$out/bin/"
cp -a "$work"/lib/*.so* "$out/lib/"

# The newer libva (and its DRM sibling, when the package carries one) ships next to the FFmpeg
# libraries. Its soname libva.so.2 is what the FFmpeg import shim loads by name.
mkdir -p "$work/libva"
dpkg-deb -x "$libva_deb" "$work/libva"
shopt -s nullglob
libva_libs=("$work/libva"/usr/lib/*/libva.so.2* "$work/libva"/usr/lib/*/libva-drm.so.2*)
shopt -u nullglob
((${#libva_libs[@]} > 0)) || die "the libva package has no libva.so.2"
cp -a "${libva_libs[@]}" "$out/lib/"
say "staged $(basename "$LIBVA_URL") next to the FFmpeg libraries"

# The CLIs already carry RPATH $ORIGIN/../lib; give the libraries $ORIGIN too, so each finds its
# siblings however it is loaded (the app dlopens them by path through java.lang.foreign). The
# binaries must also find the staged libva through ../lib on hosts that lack a new enough one.
for lib in "$out"/lib/*.so.*; do
	[[ -L "$lib" ]] || patchelf --set-rpath '$ORIGIN' "$lib"
done
for bin in "$out/bin/ffmpeg" "$out/bin/ffprobe"; do
	patchelf --set-rpath '$ORIGIN/../lib' "$bin"
done

"$out/bin/ffmpeg" -hide_banner -version | head -n1
encoders="$("$out/bin/ffmpeg" -hide_banner -encoders 2>/dev/null)"
for encoder in libx264 libopus libmp3lame h264_vaapi h264_vulkan h264_nvenc; do
	grep -qE "^ *[A-Z.]+ +$encoder " <<<"$encoders" \
		|| die "encoder $encoder missing from the staged FFmpeg"
done
say "staged $(du -sh "$out" | cut -f1) into $out"
