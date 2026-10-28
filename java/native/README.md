# java/native

Native payload for the Java app (plan §4.2, §4.4, §5.7, §5.19.3): the Wayland capture/cursor
helpers, `gravaai-ctl`, FFmpeg 8.0.x shared libraries + CLI, the whisper.cpp runtime, the rnnoise
models and the media-fixture generator.

`tools/package-dist.sh` copies **everything under `java/native/build/`** into
`<app image>/lib/native/linux-x64/`, and `dev.gravaai.platform.nativehelpers.NativeHelpers`
resolves development paths against `java/native/build/`. That is why the layout is flat:

```
java/native/
  build-native-helpers.sh      C helpers + rnnoise models
  build-ffmpeg.sh              FFmpeg 8.0.3 shared libs + ffmpeg/ffprobe CLIs
  build-whisper.sh             whisper.cpp v1.8.4 runtime (whisper-cli)
  make-fixtures.sh             writes the wrapper-test fixtures into java/parity/fixtures/media
  ffmpeg-jextract-includes.txt curated FFM binding symbol list (jextract input)
  wayland-capture/ wayland-cursor/ gravaai-ctl/ models/   copies of electron/native/*
  build/                       build output (git-ignored, never committed)
    gravaai-wayland-capture, gravaai-wayland-cursor, gravaai-ctl
    models/rnnoise/voice.rnnn
    ffmpeg/{bin,lib,include}
    whisper/{whisper-cli,*,whisper-runtime.json}
```

The C sources are **copies**; the Electron copy stays until P3.6 (plan §1.2 rule 10). Nothing
here touches a GPU: no VA-API, VDPAU, Vulkan or `-hwaccel` (plan §1.1.1).

## Quick start

```bash
cd java/native
./build-native-helpers.sh     # cc, pkg-config, libwayland-dev, wayland-protocols
./build-ffmpeg.sh             # long build, see below
./build-whisper.sh            # cmake
./make-fixtures.sh            # writes java/parity/fixtures/media (committed)
```

All scripts are idempotent: sources are cached under `$GRAVAAI_NATIVE_CACHE`
(default `/media/jmarceno/scratch/gravaai-native`), output is rebuilt only when the pin or the
configure list changes, and `build-ffmpeg.sh` verifies every component and runs functional
smokes before reporting success.

## Native helpers

`build-native-helpers.sh` generates the Wayland protocol client bindings with
`wayland-scanner` (ext-image-capture-source-v1, ext-image-copy-capture-v1,
ext-foreign-toplevel-list-v1, xdg-output-unstable-v1) and compiles
`gravaai-wayland-capture` (wayland-capture/main.c), `gravaai-wayland-cursor`
(wayland-cursor/main.c) and `gravaai-ctl` (gravaai-ctl/main.c). The old
`wayland-cursor/build.sh` is superseded: this script is the single build entry point and stages
the rnnoise models as `build/models/rnnoise/`.

## FFmpeg

**Pin: FFmpeg 8.0.3** (latest 8.0 point release), `https://ffmpeg.org/releases/ffmpeg-8.0.3.tar.xz`,
**sha256 `6136812ea6d4e68bdba27e33c2a94382711cdf4f8602ffef056ff792bd6f9818`**.

External codecs are built from source into the scratch dependency prefix because this host has
the runtime packages but no `-dev` packages, so the codecs are never silently dropped: x264
commit `31e19f92f00c7003fa115047ce50978bc98c3a0d` (stable), opus 1.5.2, lame 3.100, nasm from the
distribution `.deb` when missing.

Output: `build/ffmpeg/bin/{ffmpeg,ffprobe}`, `build/ffmpeg/lib/lib*.so*`,
`build/ffmpeg/include/` (only for regenerating the bindings). RPATH is set with patchelf and
verified with readelf: libraries `$ORIGIN`, CLIs `$ORIGIN/../lib`.

Build flags (full list in the script): `--enable-shared --disable-static --disable-everything
--disable-autodetect --disable-network --enable-gpl --enable-libx264 --enable-libopus
--enable-libmp3lame --enable-zlib`, all seven av* libraries, and:

* demuxers: the Electron set (mov, matroska, wav, ogg, flac, mp3, aac, raw PCM, rawvideo,
  concat, image2pipe, h264, webp_pipe, webm_dash_manifest) + image2. WebM/MKV demuxing uses the
  matroska demuxer (FFmpeg has no separate WebM demuxer).
* muxers: the Electron set + gif, webm, ipod (m4a), image2.
* decoders: h264, hevc, vp8, vp9, av1 (native), aac, opus, mp3, vorbis, flac, webp, gif, png,
  mjpeg, rawvideo, wrapped_avframe, the PCM family.
* encoders: libx264, aac, png, gif, mjpeg, libopus, libmp3lame, pcm_s16le. No rawvideo/wrapped
  encoder: raw frames are piped *into* ffmpeg, as in the Electron build.
* filters: the Electron set + palettegen, paletteuse, afade, volume, adelay.
* indevs: lavfi, v4l2, xcbgrab (x11grab kept for parity; the Java recorder uses the Wayland
  helper). Protocols: file, pipe.

No VA-API/VDPAU/Vulkan: the configure line is asserted to contain none, `ffmpeg -version` is
checked to list libx264/libopus/libmp3lame, and every smoke is software-only.

Measured on this machine (16 cores, `nice make -j4`, plan §1.1.2): first full FFmpeg run
**≈8 min** (x264/opus/lame ≈3 min, FFmpeg configure+build ≈4.5 min), relink-only rerun ≈40 s;
`build-whisper.sh` ≈46 s; `build-native-helpers.sh` <2 s. Payload: 17 MB FFmpeg, 12 MB whisper.

### Regenerating the FFM bindings (only when the FFmpeg pin changes)

The jextract output is committed under `gravaai-media/src/main/java/dev/gravaai/media/ffmpeg/gen/`;
**the Maven build never needs jextract** (plan §4.4). jextract: <https://jdk.java.net/jextract/>
(latest build, JDK 25 based, libclang 13); its output uses the final `java.lang.foreign` API and
compiles on the pinned JDK 27 toolchain.

```bash
./build-ffmpeg.sh
JEXTRACT=/media/jmarceno/scratch/java-toolchain/jextract-25/bin/jextract \
FFMPEG_PREFIX=$PWD/build/ffmpeg \
  ../tools/extract-ffmpeg-bindings.sh
```

The script runs, from `build/ffmpeg/include`:

```
jextract -I<prefix>/include --header-class-name ffmpeg_h \
  -t dev.gravaai.media.ffmpeg.gen @../../../native/ffmpeg-jextract-includes.txt \
  libavcodec/avcodec.h libavformat/avformat.h libavfilter/avfilter.h \
  libavfilter/buffersrc.h libavfilter/buffersink.h libavdevice/avdevice.h \
  libswscale/swscale.h libswresample/swresample.h libavutil/imgutils.h \
  libavutil/opt.h libavutil/display.h libavutil/samplefmt.h \
  libavutil/channel_layout.h libavutil/dict.h libavutil/log.h \
  --output <tmp>
```

`ffmpeg-jextract-includes.txt` is the curated symbol list the wrapper classes use. Macros the C
API cannot expose (`AVERROR`, `AVERROR_EOF`, `AV_NOPTS_VALUE` …) are computed in Java
(`FfmpegException`) or generated as accessors. The generated code resolves symbols through
`SymbolLookup.loaderLookup()`, so `FfmpegLibs` binds the libraries with
`SymbolLookup.libraryLookup` **and** `System.load` (the latter registers them with the class
loader the generated code searches), in dependency order: avutil, swresample, swscale, avcodec,
avformat, avfilter, avdevice.

## whisper runtime

`build-whisper.sh` ports `scripts/build-whisper-runtime.mjs` for Linux x64: whisper.cpp
**v1.8.4** (same pin as Electron), CMake Release, tests and HTTP server off, static libraries;
stages the `whisper*/ggml*` artifacts plus `whisper-runtime.json` into `build/whisper/`. The
CMake build is cached, so reruns are a no-op.

## Fixtures

`make-fixtures.sh` writes the four clips the wrapper tests decode into
`java/parity/fixtures/media/`: `h264-1080p60.mp4` (bundled CLI), `vp9-720p30.webm` (host
FFmpeg — plan §5.7 has no bundled VP9 encoder, but VP9 *decoding* is required because Electron
recordings are WebM/VP9), `aac-48k-stereo.m4a` (bundled CLI) and `rotated-phone.mp4` (bundled
CLI, `-display_rotation 90` + `-c copy` remux, display-matrix side data). They are
deterministic and small (~110 KB total), so they are committed.
