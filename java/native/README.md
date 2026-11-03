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
  wayland-capture/ wayland-cursor/ gravaai-ctl/ portal-capture/ kwin-cursor/ models/
                               helper sources
  build/                       build output (git-ignored, never committed)
    gravaai-wayland-capture, gravaai-wayland-cursor, gravaai-ctl
    gravaai-portal-capture, gravaai-kwin-cursor   (optional, see "Compositor backends" below)
    models/rnnoise/voice.rnnn
    ffmpeg/{bin,lib,include}
    whisper/{whisper-cli,*,whisper-runtime.json}
```

The C sources are **copies**; the Electron copy stays until P3.6 (plan §1.2 rule 10). Nothing
here touches a GPU: no VA-API, VDPAU, Vulkan or `-hwaccel` (plan §1.1.1).

## Compositor backends

Screen capture has two interchangeable helpers that share one command line
(`<output-name> <fps>`) and one stream contract: a single
`FORMAT <w> <h> <rgba|bgra> <transform>` line on stderr, then raw frames on stdout, with
`pause`/`resume`/`stop` on stdin. The Java recording pipeline is therefore identical for both.

- `gravaai-wayland-capture` — direct capture over `ext-image-copy-capture`, used on COSMIC and
  any compositor that advertises the protocol.
- `gravaai-portal-capture` — `xdg-desktop-portal` ScreenCast into PipeWire, used on desktops
  whose compositor only exposes the portal: KDE Plasma/KWin, GNOME and wlroots sessions. Here
  the portal's own dialog picks the monitor, so the `<output-name>` argument is accepted and
  ignored, and `--list` enumerates monitors over `wl_output`.

`dev.gravaai.platform.compositor.CaptureBackend` chooses between them from `XDG_SESSION_TYPE`
and `XDG_CURRENT_DESKTOP`; anything unrecognised keeps the historical COSMIC behaviour, and
`CompositorRecordingEnvironment` applies the choice to the recorder.

What the portal helper does beyond the COSMIC contract:

- **Restore token.** It asks for `persist_mode=2` and keeps the token the portal returns in
  `$XDG_STATE_HOME/gravaai/portal-restore-token` (mode 0600). The desktop's dialog appears on the
  first recording only; later ones start silently. A rejected token is deleted and the dialog
  comes back.
- **Hidden cursor.** It requests `cursor_mode=1`, so the frames carry no pointer and the editor
  draws its own from the cursor helper's telemetry. With the cursor embedded the video showed
  two pointers.
- **Ended from the desktop.** When the user stops sharing from the desktop's own indicator
  ("End" on KDE, the top-bar button on GNOME), the portal emits `Session.Closed` and the
  PipeWire stream disconnects. The helper prints `ENDED ...` and exits; `CosmicRecorder` sees a
  capture helper exit while the recording is still active and raises
  `recording-stop-requested`, so the recording is saved and opened like a normal stop.
- **Frame pacing.** Frames are written at most `<fps>` times a second and late ticks are
  dropped. The encoder stamps each frame with its arrival time (`-use_wallclock_as_timestamps 1`,
  `-fps_mode vfr`), so recordings keep their wall-clock length even when the machine encodes
  slower than `<fps>`.

### Pointer position

The pointer is a separate choice, `dev.gravaai.platform.compositor.CursorBackend`, because no
portal reports where the pointer is. Every cursor helper prints the same lines (`POS:x:y`,
`READY`, `INTERACTION:mousedown:N`, `INTERACTION:mouseup`, and `KEY:down|up:code` with
`--keys`), so `CursorCaptureSession` does not care which one runs.

- `gravaai-wayland-cursor` — COSMIC's ext-image-copy-capture cursor sessions.
- `gravaai-kwin-cursor` — KDE Plasma. It loads a KWin script (through `org.kde.kwin.Scripting`)
  that polls `workspace.cursorPos` every 16 ms and calls back into the helper over D-Bus. KWin's
  `cursorPosChanged` does not fire for pointer motion and the script engine has no
  `setTimeout`, hence the `QTimer`. Buttons and keys come from evdev (`/dev/input`, the user must
  be in the `input` group, as on COSMIC). A press is held until the next position report (at most
  60 ms), so a quick move-then-click is attributed to where the pointer landed.

### Global shortcuts

On COSMIC the shortcuts are written into COSMIC's own keyboard settings and run `gravaai-ctl`.
Everywhere else `GlobalShortcutsService` uses the `org.freedesktop.portal.GlobalShortcuts`
portal (`dev.gravaai.platform.portal.GlobalShortcutsPortal`) when the desktop offers it, and
falls back to the copyable `--action` commands when it does not. The portal needs an app id
for unsandboxed apps: the client registers as `gravaai` through
`org.freedesktop.host.portal.Registry` on a D-Bus connection of its own, matching the
`gravaai.desktop` entry the AppImage installs. The desktop confirms the shortcuts with the user
once and owns the keys afterwards, so the settings page lists the keys it reports instead of
editing them.

### Adding another desktop (GNOME)

KDE Plasma 6 is verified end to end. For GNOME:

1. **Capture** already selects the portal helper (`CaptureBackend` maps `GNOME` to `PORTAL`);
   check the restore token and the `Session.Closed` path against GNOME's own dialog.
2. **Pointer** comes from `gravaai-gnome-cursor` (`CursorBackend.GNOME_BRIDGE`) and the
   `gravaai-cursor@gravaai.dev` shell extension (`gnome-cursor/extension`, shipped as
   `gnome-extension/` next to the helper). The helper copies the extension into
   `~/.local/share/gnome-shell/extensions/` when missing or changed and enables it over
   `org.gnome.Shell.Extensions`; on Wayland a new or changed extension only loads after the next
   login. `metadata.json` must declare `session-modes`, or GNOME 51 lists it ACTIVE but never
   calls `enable()`. The ScreenCast `SPA_META_Cursor` route was dropped: on mutter 51 the
   metadata never reaches a consumer process.
3. **Shortcuts** work through the same portal on GNOME 48+; older GNOME falls back to the
   `--action` commands automatically.

Building the portal and KWin helpers needs `libpipewire-0.3-dev` and `libdbus-1-dev` in addition
to the usual toolchain. Without them `build-native-helpers.sh` prints a skip line and builds only
the COSMIC helpers, which is enough for a COSMIC-only build:

```bash
sudo apt install libpipewire-0.3-dev libdbus-1-dev
```

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
