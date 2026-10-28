#!/usr/bin/env bash
#
# Builds the FFmpeg 8.0.x shared libraries and the ffmpeg/ffprobe CLIs that the Java app
# bundles (plan §5.7, §4.4, §7.1 P1.5). This is the bash port of
# scripts/build-minimal-ffmpeg.mjs, extended with the in-process editing components.
#
# Output layout (what -Pdist copies to <appimage>/lib/native/linux-x64/, plan §5.19.3):
#
#   java/native/build/linux-x64/ffmpeg/bin/ffmpeg
#   java/native/build/linux-x64/ffmpeg/bin/ffprobe
#   java/native/build/linux-x64/ffmpeg/lib/libav*.so*
#   java/native/build/linux-x64/ffmpeg/lib/libsw*.so*
#   java/native/build/linux-x64/ffmpeg/include/...   (only to regenerate the FFM bindings)
#
# Properties
#   * --enable-shared --disable-static: the JVM loads these libraries in-process through
#     java.lang.foreign (FfmpegLibs); the CLIs come from the same build tree.
#   * --disable-autodetect plus the explicit component lists below: nothing is discovered
#     from the host.
#   * NO hardware video anywhere (plan §1.1.1): no --enable-vaapi, no --enable-vdpau, no
#     --enable-vulkan, no -hwaccel. All encode/decode is software libavcodec/libx264.
#   * External codecs (libx264, libopus, libmp3lame) are built from source into the scratch
#     cache because the host has runtime packages but no -dev packages; the codecs are never
#     silently dropped.
#   * RPATH: libraries get $ORIGIN, the two CLIs get $ORIGIN/../lib, applied with patchelf
#     after install and verified with readelf.
#   * Parallelism is capped at JOBS (default 4) with nice(1): plan §1.1.2.
#
# Usage: ./build-ffmpeg.sh [--clean]
#   JOBS=4                 parallel make jobs (default 4)
#   GRAVAAI_NATIVE_CACHE   scratch work/download directory
#                          (default /media/jmarceno/scratch/gravaai-native)
#   HOST_FFMPEG            host ffmpeg CLI used only to create two decode fixtures (flac,
#                          raw rgba); those smokes are skipped when it is missing
#
# Sources are cached; rerunning is a no-op unless the version or the configure list changes
# (that is what the .configure-stamp fingerprint is for).
set -euo pipefail

FFMPEG_VERSION="8.0.3"
# sha256 of https://ffmpeg.org/releases/ffmpeg-8.0.3.tar.xz
FFMPEG_SHA256="6136812ea6d4e68bdba27e33c2a94382711cdf4f8602ffef056ff792bd6f9818"

X264_COMMIT="31e19f92f00c7003fa115047ce50978bc98c3a0d"
OPUS_VERSION="1.5.2"
OPUS_SHA256="65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
LAME_VERSION="3.100"
LAME_SHA256="ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"
NASM_DEB_SHA256="22eede0f2dd62343b0298182f62f7485704fe02f166395b02c92a8883377e0b3"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${GRAVAAI_NATIVE_CACHE:-/media/jmarceno/scratch/gravaai-native}"
downloads="$cache/downloads"
src="$cache/src"
deps="$cache/deps"
build="$cache/build/ffmpeg-$FFMPEG_VERSION"
out="$here/build/ffmpeg"
logs="$cache/logs"
jobs="${JOBS:-4}"
host_ffmpeg="${HOST_FFMPEG:-$(command -v ffmpeg || true)}"

say() { printf '[build-ffmpeg] %s\n' "$*"; }
die() { printf '[build-ffmpeg] error: %s\n' "$*" >&2; exit 1; }
elapsed() { printf '%dm%02ds' "$((SECONDS / 60))" "$((SECONDS % 60))"; }

# run <log-name> <cmd...>: runs quietly, keeps the log, prints the tail on failure.
run() {
	local name="$1"; shift
	if ! "$@" >>"$logs/$name.log" 2>&1; then
		printf '[build-ffmpeg] FAILED: %s\n' "$*" >&2
		tail -n 40 "$logs/$name.log" >&2 || true
		exit 1
	fi
}

fetch() {
	# fetch <url> <dst> <sha256|"">
	local url="$1" dst="$2" want="$3"
	if [[ -f "$dst" ]]; then
		if [[ -z "$want" ]] || [[ "$(sha256sum "$dst" | cut -d' ' -f1)" == "$want" ]]; then
			return
		fi
		say "checksum mismatch, re-downloading $dst"
		rm -f "$dst"
	fi
	mkdir -p "$(dirname "$dst")"
	say "downloading $url"
	curl -fsSL --retry 3 --max-time 1800 -o "$dst.part" "$url"
	mv "$dst.part" "$dst"
	if [[ -n "$want" ]]; then
		local got
		got="$(sha256sum "$dst" | cut -d' ' -f1)"
		[[ "$got" == "$want" ]] || die "sha256 mismatch for $dst: expected $want, got $got"
	fi
}

check_tools() {
	local missing=()
	for tool in cc gcc make pkg-config curl tar xz sha256sum patchelf readelf strip nice; do
		command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
	done
	(("${#missing[@]}" == 0)) || die "missing tools: ${missing[*]}"
	mkdir -p "$logs" "$downloads" "$src" "$deps/bin" "$deps/lib/pkgconfig"
	# The scratch dependency prefix must be visible to configure (FFmpeg resolves the external
	# codecs through pkg-config) and to the x264 assembler.
	export PATH="$deps/bin:$PATH"
	export PKG_CONFIG_PATH="$deps/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
}

# ---- external codecs ---------------------------------------------------------
# The host has the runtime packages (libx264-164, libopus0, libmp3lame0) but no -dev
# packages, so all three codecs are built from source into the scratch dep prefix. They are
# static archives linked into libavcodec.so; only FFmpeg's own libraries ship in the app.
ensure_nasm() {
	if command -v nasm >/dev/null 2>&1; then
		return
	fi
	if [[ -x "$deps/bin/nasm" ]]; then
		export PATH="$deps/bin:$PATH"
		return
	fi
	say "nasm is missing; extracting the distribution package into the scratch prefix"
	local debdir="$src/nasm-deb"
	mkdir -p "$debdir"
	if (cd "$debdir" && apt-get download nasm) >>"$logs/nasm.log" 2>&1; then
		local deb
		deb="$(find "$debdir" -maxdepth 1 -name 'nasm_*.deb' -print -quit)"
		if [[ -n "$deb" ]]; then
			local got
			got="$(sha256sum "$deb" | cut -d' ' -f1)"
			if [[ "$got" != "$NASM_DEB_SHA256" ]]; then
				say "warning: nasm package sha256 differs from the pinned one ($got)"
			fi
			rm -rf "$debdir/root"
			dpkg -x "$deb" "$debdir/root"
			install -m 0755 "$debdir/root/usr/bin/nasm" "$deps/bin/nasm"
			export PATH="$deps/bin:$PATH"
			say "nasm $(nasm -v)"
			return
		fi
	fi
	say "warning: nasm unavailable; x264 will be built without assembly (slower encode)"
}

ensure_pkg_config_stubs() {
	# Stable .pc files pointing at the scratch prefix. The Electron build wrote these for the
	# same reason: autotools installs carry whichever absolute prefix they were built with.
	local pc="$deps/lib/pkgconfig"
	cat >"$pc/x264.pc" <<EOF
prefix=$deps
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: x264
Description: H.264 video encoder
Version: 1

Libs: -L\${libdir} -lx264
Libs.private: -lm -lpthread -ldl
Cflags: -I\${includedir}
EOF
	cat >"$pc/opus.pc" <<EOF
prefix=$deps
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: opus
Description: Opus audio codec
Version: 1
Requires:
Conflicts:
Libs: -L\${libdir} -lopus
Libs.private: -lm
Cflags: -I\${includedir}/opus
EOF
	cat >"$pc/libmp3lame.pc" <<EOF
prefix=$deps
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: libmp3lame
Description: mp3 encoder
Version: 1

Libs: -L\${libdir} -lmp3lame
Libs.private: -lm
Cflags: -I\${includedir}
EOF
}

build_x264() {
	[[ -f "$deps/lib/libx264.a" ]] && return
	say "building x264 $X264_COMMIT"
	local dir="$src/x264"
	if [[ ! -f "$dir/x264.c" ]]; then
		rm -rf "$dir"
		mkdir -p "$dir"
		(
			cd "$dir"
			git init -q
			git remote add origin https://github.com/mirror/x264.git
			git fetch -q --depth 1 origin "$X264_COMMIT"
			git checkout -q FETCH_HEAD
		) >>"$logs/x264-fetch.log" 2>&1 || {
			tail -n 20 "$logs/x264-fetch.log" >&2
			die "could not fetch x264 at $X264_COMMIT"
		}
	fi
	local args=(--prefix="$deps" --enable-static --disable-shared --disable-cli --enable-pic)
	command -v nasm >/dev/null 2>&1 || args+=(--disable-asm)
	(
		cd "$dir"
		./configure "${args[@]}"
		make -j"$jobs"
		make install
	) >>"$logs/x264.log" 2>&1 || {
		tail -n 40 "$logs/x264.log" >&2
		die "x264 build failed"
	}
	[[ -f "$deps/lib/libx264.a" ]] || die "libx264.a missing after install"
}

build_opus() {
	[[ -f "$deps/lib/libopus.a" ]] && return
	say "building opus $OPUS_VERSION"
	local tarball="$downloads/opus-$OPUS_VERSION.tar.gz"
	fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz" "$tarball" "$OPUS_SHA256"
	local dir="$src/opus-$OPUS_VERSION"
	if [[ ! -f "$dir/configure" ]]; then
		tar xf "$tarball" -C "$src"
	fi
	(
		cd "$dir"
		./configure --prefix="$deps" --enable-static --disable-shared --disable-docs \
			--disable-extra-programs --with-pic
		make -j"$jobs"
		make install
	) >>"$logs/opus.log" 2>&1 || {
		tail -n 40 "$logs/opus.log" >&2
		die "opus build failed"
	}
	[[ -f "$deps/lib/libopus.a" ]] || die "libopus.a missing after install"
}

build_lame() {
	[[ -f "$deps/lib/libmp3lame.a" ]] && return
	say "building lame $LAME_VERSION"
	local tarball="$downloads/lame_$LAME_VERSION.orig.tar.gz"
	fetch "https://ftp.debian.org/debian/pool/main/l/lame/lame_$LAME_VERSION.orig.tar.gz" "$tarball" "$LAME_SHA256"
	local dir="$src/lame-$LAME_VERSION"
	if [[ ! -f "$dir/configure" ]]; then
		mkdir -p "$dir"
		tar xf "$tarball" -C "$dir" --strip-components=1
	fi
	(
		cd "$dir"
		./configure --prefix="$deps" --enable-static --disable-shared --disable-frontend \
			--with-pic --disable-dependency-tracking
		make -j"$jobs"
		make install
	) >>"$logs/lame.log" 2>&1 || {
		tail -n 40 "$logs/lame.log" >&2
		die "lame build failed"
	}
	[[ -f "$deps/lib/libmp3lame.a" ]] || die "libmp3lame.a missing after install"
}

build_deps() {
	ensure_nasm
	ensure_pkg_config_stubs
	build_x264
	build_opus
	build_lame
}

# ---- FFmpeg configure --------------------------------------------------------
# Component matrix. "Today" entries are the ones scripts/build-minimal-ffmpeg.mjs verified
# against the Electron call sites (kept 1:1). The ADD entries are the in-process
# editing/decoding needs from plan §5.7. --disable-everything compiles out the rest and all
# component enables MUST come after it.
#
# Notes:
#   * WebM has no demuxer of its own: the matroska demuxer handles WebM, so the §5.7 "webm"
#     demuxer entry is satisfied by matroska. The webm MUXER is a separate component.
#   * PCM: the plain PCM family needed by user audio imports (§5.7 "pcm_*").
#   * x11grab (xcbgrab) stays as today; the Java recorder captures through the Wayland helper
#     but the bundled CLI keeps the old capability. It is software only, no GPU path.
DEMUXERS_TODAY="mov,matroska,wav,ogg,flac,mp3,aac,pcm_s16le,pcm_u8,pcm_s24le,pcm_f32le,rawvideo,concat,image2pipe,h264,image_webp_pipe,webm_dash_manifest"
DEMUXERS_ADD="image2"
MUXERS_TODAY="mp4,matroska,wav,ogg,flac,mp3,segment,image2pipe,pcm_s16le,rawvideo,null,webm_dash_manifest"
MUXERS_ADD="gif,webm,ipod,image2"
PARSERS_TODAY="h264,aac,mp3,opus,vorbis,flac,mjpeg"
PARSERS_ADD="hevc,av1"
DECODERS_TODAY="vp8,vp9,h264,opus,flac,mp3,aac,vorbis,pcm_s16le,pcm_u8,pcm_s24le,pcm_f32le,png,mjpeg,gif,webp,wrapped_avframe,rawvideo"
DECODERS_ADD="hevc,av1,pcm_s16be,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32be,pcm_f64le,pcm_f64be,pcm_alaw,pcm_mulaw"
ENCODERS_TODAY="libx264,libmp3lame,libopus,aac,pcm_s16le,png,mjpeg"
ENCODERS_ADD="gif"
FILTERS_TODAY="concat,crop,format,fps,gblur,lut,overlay,pad,scale,setpts,setsar,transpose,tpad,trim,adeclick,adeclip,afftdn,aformat,alimiter,amerge,amix,anullsrc,apad,aresample,arnndn,asetpts,atempo,atrim,highpass,lowpass,speechnorm,silencedetect,anull,color,sine"
FILTERS_ADD="palettegen,paletteuse,afade,volume,adelay"
INDEVS_TODAY="lavfi,xcbgrab"
INDEVS_ADD="v4l2"
PROTOCOLS="file,pipe"

FFMPEG_CONFIGURE=(
	"--prefix=$out"
	"--pkg-config-flags=--static"
	"--extra-cflags=-I$deps/include"
	"--extra-ldflags=-L$deps/lib"
	"--enable-ffmpeg"
	"--enable-ffprobe"
	"--enable-avcodec"
	"--enable-avformat"
	"--enable-avfilter"
	"--enable-avdevice"
	"--enable-swscale"
	"--enable-swresample"
	"--enable-pthreads"
	"--enable-zlib"
	"--enable-gpl"
	"--enable-libx264"
	"--enable-libopus"
	"--enable-libmp3lame"
	# xcbgrab = the x11grab input device; libxcb + xcb-shm only (the app captures with
	# -draw_mouse 0), exactly like the Electron build's pkg-config stubs.
	"--enable-libxcb"
	"--enable-libxcb-shm"
	"--enable-shared"
	"--disable-static"
	# NOTE ordering: --disable-everything below clobbers component enables placed before it,
	# so every component enable comes after it.
	"--disable-everything"
	"--disable-doc"
	"--disable-debug"
	"--disable-network"
	"--disable-autodetect"
	"--enable-indev=$INDEVS_TODAY,$INDEVS_ADD"
	"--enable-demuxer=$DEMUXERS_TODAY,$DEMUXERS_ADD"
	"--enable-muxer=$MUXERS_TODAY,$MUXERS_ADD"
	"--enable-parser=$PARSERS_TODAY,$PARSERS_ADD"
	"--enable-decoder=$DECODERS_TODAY,$DECODERS_ADD"
	"--enable-encoder=$ENCODERS_TODAY,$ENCODERS_ADD"
	"--enable-filter=$FILTERS_TODAY,$FILTERS_ADD"
	"--enable-protocol=$PROTOCOLS"
)

configure_fingerprint() {
	printf '%s\n' "$FFMPEG_VERSION" "${FFMPEG_CONFIGURE[@]}"
}

# ---- build -------------------------------------------------------------------
build_ffmpeg() {
	mkdir -p "$build"
	local tarball="$downloads/ffmpeg-$FFMPEG_VERSION.tar.xz"
	fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "$tarball" "$FFMPEG_SHA256"
	[[ -f "$src/ffmpeg-$FFMPEG_VERSION/configure" ]] || tar xf "$tarball" -C "$src"
	local dir="$src/ffmpeg-$FFMPEG_VERSION"

	say "configuring FFmpeg $FFMPEG_VERSION (-j$jobs)"
	(
		cd "$dir"
		./configure "${FFMPEG_CONFIGURE[@]}"
	) >"$logs/configure.log" 2>&1 || {
		tail -n 40 "$logs/configure.log" >&2
		die "FFmpeg configure failed (see $logs/configure.log)"
	}
	# The build must never grow a hardware video path (plan §1.1.1).
	if grep -qx 'CONFIG_VAAPI=1\|CONFIG_VDPAU=1\|CONFIG_VULKAN=1' "$dir"/ffbuild/config.mak 2>/dev/null; then
		die "hardware video option found in ffbuild/config.mak"
	fi
	run build nice -n 10 make -C "$dir" -j"$jobs"
	run install nice -n 10 make -C "$dir" install
	[[ -x "$out/bin/ffmpeg" && -x "$out/bin/ffprobe" ]] || die "ffmpeg/ffprobe missing after install"
}

# ---- install layout: RPATH + strip ------------------------------------------
patch_rpath() {
	say "setting RPATH (\$ORIGIN for libraries, \$ORIGIN/../lib for the CLIs)"
	local lib bin
	for lib in "$out"/lib/lib*.so.*; do
		patchelf --set-rpath '$ORIGIN' "$lib"
	done
	for bin in "$out/bin/ffmpeg" "$out/bin/ffprobe"; do
		patchelf --set-rpath '$ORIGIN/../lib' "$bin"
	done
	# Verify: a wrong RPATH only shows up at packaging time otherwise.
	if ! readelf -d "$out/bin/ffmpeg" | grep -q 'RUNPATH.*\$ORIGIN/../lib'; then
		die "ffmpeg has no \$ORIGIN/../lib RUNPATH"
	fi
	if ! readelf -d "$out"/lib/libavcodec.so.* | grep -q 'RUNPATH.*\$ORIGIN'; then
		die "libavcodec has no \$ORIGIN RUNPATH"
	fi
	strip --strip-unneeded "$out"/lib/lib*.so.* "$out/bin/ffmpeg" "$out/bin/ffprobe"
}

# ---- verification ------------------------------------------------------------
# 1) feature lists expose the whole matrix; 2) functional smokes for the invocation shapes
# the app uses (each comment names the Electron call site the shape comes from).
REQUIRED_FEATURES=(
	"-encoders:libx264"
	"-encoders:libmp3lame"
	"-encoders:libopus"
	"-encoders:aac"
	"-encoders:pcm_s16le"
	"-encoders:png"
	"-encoders:mjpeg"
	"-encoders:gif"
	"-decoders:vp9"
	"-decoders:vp8"
	"-decoders:h264"
	"-decoders:hevc"
	"-decoders:av1"
	"-decoders:opus"
	"-decoders:flac"
	"-decoders:mp3"
	"-decoders:vorbis"
	"-decoders:aac"
	"-decoders:webp"
	"-decoders:wrapped_avframe"
	"-decoders:rawvideo"
	"-decoders:gif"
	"-decoders:png"
	"-decoders:mjpeg"
	"-decoders:pcm_s16le"
	"-demuxers:matroska"
	"-demuxers:mp4"
	"-demuxers:wav"
	"-demuxers:ogg"
	"-demuxers:image2"
	"-demuxers:concat"
	"-devices:lavfi"
	"-devices:v4l2"
	"-devices:x11grab"
	"-demuxers:s16le"
	"-demuxers:rawvideo"
	"-demuxers:webp_pipe"
	"-muxers:mp4"
	"-muxers:matroska"
	"-muxers:webm"
	"-muxers:ipod"
	"-muxers:gif"
	"-muxers:image2"
	"-muxers:wav"
	"-muxers:ogg"
	"-muxers:segment"
	"-muxers:null"
	"-muxers:image2pipe"
	"-muxers:flac"
	"-muxers:mp3"
	"-muxers:s16le"
	"-muxers:rawvideo"
	"-filters:palettegen"
	"-filters:paletteuse"
	"-filters:atempo"
	"-filters:aresample"
	"-filters:afade"
	"-filters:volume"
	"-filters:adelay"
	"-filters:amix"
	"-filters:arnndn"
	"-filters:scale"
	"-filters:fps"
	"-filters:silencedetect"
	"-filters:anullsrc"
	"-filters:afftdn"
	"-filters:alimiter"
	"-filters:adeclick"
	"-filters:adeclip"
	"-filters:speechnorm"
	"-filters:highpass"
	"-filters:lowpass"
	"-filters:tpad"
	"-filters:transpose"
	"-filters:trim"
	"-filters:gblur"
	"-filters:overlay"
	"-protocols:file"
	"-protocols:pipe"
)

feature_list_has() {
	# feature_list_has <-flag> <token>
	local flag="$1" token="$2"
	"$out/bin/ffmpeg" -hide_banner "$flag" 2>/dev/null | grep -qw -- "$token"
}

verify() {
	local missing=() entry flag token
	for entry in "${REQUIRED_FEATURES[@]}"; do
		flag="${entry%%:*}"
		token="${entry#*:}"
		feature_list_has "$flag" "$token" || missing+=("$flag $token")
	done
	(("${#missing[@]}" == 0)) || die "feature list misses: ${missing[*]}"
	say "feature lists complete"

	local version_line config_line
	version_line="$("$out/bin/ffmpeg" -version | head -n 1)"
	[[ "$version_line" == *"ffmpeg version $FFMPEG_VERSION"* ]] || die "unexpected ffmpeg version: $version_line"
	config_line="$("$out/bin/ffmpeg" -version | grep '^configuration:')"
	for codec in libx264 libopus libmp3lame; do
		[[ "$config_line" == *"--enable-$codec"* ]] || die "$codec missing from the configure line"
	done
	for hw in vaapi vdpau vulkan; do
		[[ "$config_line" != *"$hw"* ]] || die "hardware video option $hw in the configure line"
	done
	say "ffmpeg -version: $version_line"
	say "no hardware video options in the configure line"

	local work="$cache/verify/$FFMPEG_VERSION"
	rm -rf "$work"
	mkdir -p "$work"

	# --- smokes that only need our own CLI -----------------------------------
	# nativeVideoExport.ts: lavfi/frames → format=yuv420p → libx264 → mp4 +faststart
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=black:s=256x256:r=30 -t 0.4 \
		-vf scale=256:256,setpts=PTS-STARTPTS,format=yuv420p \
		-c:v libx264 -pix_fmt yuv420p -movflags +faststart "$work/mp4-test.mp4" \
		|| die "smoke: libx264 mp4 failed"
	[[ -s "$work/mp4-test.mp4" ]] || die "smoke: mp4 not written"

	# captions/generate.ts + meeting/transcript.ts: any input → pcm_s16le wav
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i anullsrc=r=48000:cl=stereo -t 0.5 -c:a pcm_s16le "$work/wav-conv.wav" \
		|| die "smoke: pcm_s16le wav failed"

	# captions/silence.ts + generate.ts: silencedetect probe to the null muxer
	"$out/bin/ffmpeg" -hide_banner -loglevel error \
		-f lavfi -i anullsrc=r=48000:cl=stereo -t 1 \
		-af silencedetect=noise=-50dB:d=0.3 -f null - \
		|| die "smoke: silencedetect failed"

	# micNoiseReduction.ts: the exact production chain with the bundled rnnoise model
	local rnnoise="$here/models/rnnoise/voice.rnnn"
	if [[ -f "$rnnoise" ]]; then
		"$out/bin/ffmpeg" -hide_banner -loglevel error \
			-f lavfi -i anullsrc=r=48000:cl=mono -t 0.3 \
			-af "highpass=f=80,arnndn=m=$rnnoise:mix=0.85" -f null - \
			|| die "smoke: arnndn chain failed"
	else
		say "smoke: SKIP arnndn (model $rnnoise not found)"
	fi

	# recording/cosmic.ts: libopus speaker track
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i anullsrc=r=48000:cl=mono -t 0.4 -ac 1 -c:a libopus -b:a 48k \
		"$work/opus-track.opus" || die "smoke: libopus failed"

	# meeting/apiTranscription.ts: lame mp3 + segment muxer
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i anullsrc=r=48000:cl=mono -t 1 -af aresample=16000 \
		-c:a libmp3lame -b:a 64k -f segment -segment_time 30 "$work/seg-%03d.mp3" \
		|| die "smoke: libmp3lame segment failed"
	[[ -s "$work/seg-000.mp3" ]] || die "smoke: segment output missing"

	# recording/thumbnail.ts: mjpeg into image2pipe
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=black:s=64x64:r=1 -frames:v 1 -vf format=rgb24 \
		-c:v mjpeg -f image2pipe "$work/thumb.mjpeg" || die "smoke: mjpeg failed"

	# project/firstFrameThumbnail.ts: png into image2pipe
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=black:s=64x64:r=1 -frames:v 1 \
		-vf scale=32:32,setpts=PTS-STARTPTS -c:v png -f image2pipe "$work/frame.pngstream" \
		|| die "smoke: png failed"

	# native-video.ts: ffprobe stream stats contract (json + -count_frames)
	"$out/bin/ffprobe" -v error -select_streams v \
		-show_entries stream=codec_name,width,height,nb_read_frames -count_frames \
		-of json "$work/mp4-test.mp4" | grep -q '"codec_name"' \
		|| die "smoke: ffprobe json failed"

	# Native aac encoder → ipod (m4a) muxer, the meeting recording format.
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i anullsrc=r=48000:cl=stereo -t 0.5 -c:a aac -b:a 128k \
		"$work/aac-test.m4a" || die "smoke: aac/ipod failed"

	# gifExporter.ts: two-pass palettegen/paletteuse. The duration goes on the lavfi source
	# (color=...:d=), not on the output: an output -t with palettegen never reaches EOF and
	# the host ffmpeg builds hang the same way, so this is the invocation shape the export
	# pipeline must use too.
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=red:s=64x64:r=10:d=0.3 -vf "fps=10,palettegen" "$work/palette.png" \
		|| die "smoke: palettegen failed"
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=red:s=64x64:r=10:d=0.3 -i "$work/palette.png" \
		-filter_complex "fps=10[x];[x][1:v]paletteuse" -f gif "$work/gif-test.gif" \
		|| die "smoke: paletteuse failed"

	# recording/cosmic.ts mux: raw s16le tracks + adelay/amix → aac mp4
	"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i color=c=black:s=64x64:r=10 -t 0.4 \
		-f lavfi -i anullsrc=r=48000:cl=stereo -t 0.4 \
		-filter_complex "[1:a]adelay=200|200,aresample=async=1:first_pts=0[a]" \
		-map 0:v -map "[a]" -c:v libx264 -preset ultrafast -c:a aac -movflags +faststart \
		"$work/mux-test.mp4" || die "smoke: cosmic mux filter failed"

	# --- smokes that need a host ffmpeg to create a decode-only fixture -------
	# flac is decode-only in our matrix (user imports), and the rawvideo *encoder* is not
	# enabled (the app pipes raw frames in; it never writes raw files), so those two
	# fixtures come from the host CLI, exactly like the Electron script did.
	if [[ -n "$host_ffmpeg" && -x "$host_ffmpeg" ]]; then
		"$host_ffmpeg" -y -hide_banner -loglevel error \
			-f lavfi -i sine=frequency=440:duration=0.4 -c:a flac "$work/tone.flac" \
			|| die "fixture: host ffmpeg could not create tone.flac"
		"$out/bin/ffmpeg" -hide_banner -loglevel error -i "$work/tone.flac" -f null - \
			|| die "smoke: flac decode failed"

		"$host_ffmpeg" -y -hide_banner -loglevel error \
			-f lavfi -i color=c=red:s=128x128:r=30:d=0.5 -pix_fmt rgba -f rawvideo "$work/frames.raw" \
			|| die "fixture: host ffmpeg could not create frames.raw"
		"$out/bin/ffmpeg" -y -hide_banner -loglevel error \
			-f rawvideo -pix_fmt rgba -s 128x128 -r 30 -i "$work/frames.raw" \
			-vf format=yuv420p -an -c:v libx264 -preset ultrafast "$work/raw.mp4" \
			|| die "smoke: rawvideo decode → libx264 failed"
		[[ -s "$work/raw.mp4" ]] || die "smoke: raw.mp4 not written"
	else
		say "smoke: SKIP flac/rawvideo fixtures (host ffmpeg not found; set HOST_FFMPEG)"
	fi
	say "all smokes passed"
}

# ---- main --------------------------------------------------------------------
if [[ "${1:-}" == "--clean" ]]; then
	say "removing $build and $out"
	rm -rf "$build" "$out"
	shift
fi

check_tools
build_deps

mkdir -p "$out"
fingerprint="$(configure_fingerprint)"
stamp="$out/.configure-stamp"
if [[ -f "$out/bin/ffmpeg" && -f "$out/bin/ffprobe" && -f "$stamp" \
	&& "$(cat "$stamp")" == "$fingerprint" ]]; then
	say "cached build matches the configure list — verifying"
else
	build_ffmpeg
	patch_rpath
	printf '%s\n' "$fingerprint" >"$stamp"
	{
		printf 'ffmpeg_version=%s\n' "$FFMPEG_VERSION"
		printf 'ffmpeg_tarball_sha256=%s\n' "$FFMPEG_SHA256"
		printf 'x264_commit=%s\n' "$X264_COMMIT"
		printf 'opus_version=%s\n' "$OPUS_VERSION"
		printf 'lame_version=%s\n' "$LAME_VERSION"
		printf 'jobs=%s\n' "$jobs"
		printf 'build_time=%s\n' "$(elapsed)"
	} >"$out/.build-info"
fi
verify

say "done in $(elapsed)"
say "tarball sha256: $FFMPEG_SHA256"
cat "$out/.build-info"
du -sh "$out"
