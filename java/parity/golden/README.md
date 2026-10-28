# Golden reference frames (P2.1, plan §6.2)

Electron reference frames for the compositor parity tests. The Java compositor (P2.4) renders the
same fixture project at the listed times and is compared against these PNGs.

## Contents

- `manifest.json` — the machine-readable reference set:
  `{ "cases": [ { "projectPath", "outputWidth", "outputHeight", "times", "referenceImages" } ] }`.
  `projectPath` and every entry in `referenceImages` are **relative to this directory**
  (`../fixtures/projects/<name>.gravaai`, `<name>/<ms>.png`). `times` are timeline seconds;
  `referenceImages` has one PNG per time, in the same order.
- `<project>/<ms>.png` — one frame per capture time; `<project>/metadata.json` records the fixture,
  times, width and capture route for that case.
- PNGs are ordinary top-first (row 0 = top) ffmpeg PNGs converted from the Electron capture; RGBA is
  not used (the capture is opaque RGB). Reference decoding is therefore top-first, left-to-right.

For the first P2.4 pass, cases `01-wallpaper-blur`, `02-padding-crop-aspect` and
`03-zoom-motion-blur` cover the base stage set (background/blur, layout/crop/aspect, zoom + motion
blur); the rest cover cursor styles and click effects, webcam layouts, annotations, caption
animations, keystrokes, the fade transition and connected/classic zooms on a VP9 source.

Ten cases / 36 frames: `01-wallpaper-blur` (3), `02-padding-crop-aspect` (3),
`03-zoom-motion-blur` (5), `04-cursor-windows11` (3), `05-cursor-dot-keystrokes` (4),
`06-cursor-figma` (2), `07-webcam-bottom-right` (3), `08-webcam-layouts-fade` (5),
`09-annotations` (4), `10-connected-zoom-classic-vp9` (4). Fixture coverage and times are listed in
`../fixtures/README.md` and `../fixtures/capture-plan.json`.

## Comparison contract (agreed with the Lead)

- Dimension mismatch is an error.
- Mean absolute RGB difference <= 2.0.
- At most 0.5 % of pixels may have any RGB channel different by more than 2.

## Capture route

`java/tools/parity/capture-fixtures.sh` runs the fixtures from `capture-plan.json` through
Electron's MCP `frames` tool (electron/agent/*, one frame per call — the tool tiles several
timestamps into a contact sheet) and converts each returned JPEG to PNG with the bundled software
ffmpeg CLI. It writes `manifest.json` at the end via `java/tools/parity/write-golden-manifest.mjs`.

```bash
# 1. Start the Electron reference app with a scratch profile, AI control enabled and a local
#    DevTools port (the capture script uses it to present the editor window on Wayland).
#    Never touch ~/.config/Gravaai and never run the Java app at the same time (plan §7).
mkdir -p /tmp/gravaai-electron-parity/Gravaai
printf '{\n  "aiControlEnabled": true\n}\n' > /tmp/gravaai-electron-parity/Gravaai/app-settings.json
XDG_CONFIG_HOME=/tmp/gravaai-electron-parity \
DISPLAY=:0 WAYLAND_DISPLAY=wayland-1 XDG_RUNTIME_DIR=/run/user/1000 \
XDG_SESSION_TYPE=wayland ELECTRON_OZONE_PLATFORM_HINT=wayland \
  ./node_modules/.bin/electron . \
  --remote-debugging-port=9333 \
  --disable-renderer-backgrounding --disable-backgrounding-occluded-windows \
  --disable-background-timer-throttling &

# 2. Capture every fixture (or --only 03-zoom-motion-blur).
java/tools/parity/capture-fixtures.sh

# 3. Stop Electron exactly by process name.
pkill -x electron
```

The unpackaged MCP bridge resolves its own profile to `<XDG_CONFIG_HOME>/Gravaai-dev` while the dev
app uses `<XDG_CONFIG_HOME>/Gravaai`; the capture script gives the bridge a throwaway config home
(`/tmp/gravaai-parity-bridge`, overridable with `GRAVAAI_BRIDGE_HOME`) containing the app's
`agent.sock` and `agent-token`, and spawns it with `--ozone-platform=headless`.

Three properties of the capture shaped the script:

- Several timestamps come back as one contact sheet, so the script asks the `frames` tool for one
  time per call.
- The editor mounts the compositor preview asynchronously after a project opens, so one throwaway
  warm-up capture runs first; otherwise the first frames only contain the bare video layer without
  wallpaper/padding/cursor.
- Under COSMIC/XWayland the agent-created editor window can be reported "visible" by Chromium while
  the compositor never presents it, leaving the renderer without animation frames; the `frames` tool
  then hangs until its timeout. The script therefore uses the local DevTools port to send
  `Page.bringToFront` and wait for one animation frame after opening each project
  (`--remote-debugging-port=9333`, overridable with `GRAVAAI_ELECTRON_CDP_PORT`). Without the port
  the capture still runs but relies on the app having presented the window itself.
- After the run the script restores `fixtures/projects` from a temporary backup and deletes the
  `*.bak` / `*.preview.png` files and the `<video>.gravaai-session.json` manifest that the app writes
  while it opens the projects, so the committed fixtures stay canonical.

## Software-only evidence (plan §1.1.1)

The capture route performs **no hardware video decode or encode**. Evidence recorded on 2026-10-27
during the capture run:

1. **VA-API video features are disabled in every Electron process.** `electron/gpuSwitches.ts`
   passes `--disable-features=VaapiVideoDecoder,VaapiVideoEncoder`; the live GPU and renderer command
   lines confirm it:

   ```text
   ... --type=gpu-process ... --disable-features=DropInputEventsWhilePaintHolding,LocalNetworkAccessChecks,
       ScreenAIOCREnabled,SpareRendererForSitePerProcess,TraceSiteInstanceGetProcessCreation,
       VaapiVideoDecoder,VaapiVideoEncoder ...
   ... --type=renderer ... --disable-features=...VaapiVideoDecoder,VaapiVideoEncoder ...
   ```

2. **H.264 is decoded in software.** While capturing the H.264 fixtures the renderer logs Chromium's
   software H.264 decoder:

   ```text
   journalctl --user -u gravaai-electron-parity | grep OpenH264
   [OpenH264] this = 0x..., Warning:bEnableFrameSkip = 0,bitrate can't be controlled for ...
   ```

   VP9 fixtures (case 10) use Chromium's bundled libvpx software decoder under the same flags.

3. **Our ffmpeg CLI has no hardware acceleration compiled in** and is only used for the JPEG -> PNG
   conversion (`-c:v png`, software):

   ```text
   java/native/build/ffmpeg/bin/ffmpeg -hide_banner -buildconf | grep -E 'vaapi|vdpau|vulkan|cuda|nvenc'
   # (no output)
   ```

   No `ffmpeg` child is alive during capture except those short conversions; there is no
   `-hwaccel`, no VA-API/VDPAU/Vulkan encoder or decoder anywhere in the route.

4. The frames tool itself only makes a page screenshot (`webContents.capturePage`) and re-encodes it
   as JPEG in Chromium, then ffmpeg re-encodes that JPEG to PNG. No video is encoded at all.

The Chromium **GPU process does map** `libva`/`libvulkan` for graphics/device probing
(ANGLE/Dawn/VA-API capability probing); plan §1.1.1 allows OpenGL rendering. What matters is that the
VA-API *video* decoder/encoder features are disabled and the actual H.264 decoding is OpenH264
software. No Vulkan video path is used by the capture (no video encode happens; decoding is
OpenH264/libvpx). The earlier note in `../README.md` claiming no libva/vulkan is mapped was
corrected there.

## Notes / known limitations

- Capture width is 1280 px (agent `frames` width). The 9:16 fixture therefore produces
  `1280x2276` PNGs; that is intentional (it is the aspect-ratio fixture).
- The images are exactly what the Electron editor preview shows at those times, including captions,
  cursor, click effects, annotations, webcam and clip transitions. The fixture media are synthetic
  high-contrast test patterns, which makes pixel comparisons sensitive.
- Fixtures reference the shared media by absolute path (P2.1 decision); re-run
  `java/tools/parity/generate-fixtures.sh` after moving the checkout.
- Capture times are in seconds; the file names use `Math.round(seconds * 1000)`.
