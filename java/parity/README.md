# java/parity

Golden frames, UI reference screenshots and fixture projects (plan §6.2, §6.3).

## UI screenshot parity

Reference screenshots come from the React UI in the browser harness; JavaFX captures come from
the packaged app. Pixel equality is not expected (font rasterisation differs) - the reviewer
compares them side by side in the generated gallery.

Repository root, in this order:

```bash
# 1. React reference screenshots (Playwright + vite dev:ui harness, tests/ui/bridge.ts stubs electronAPI)
npx playwright test -c java/tools/parity/playwright.config.ts

# 2. JavaFX screenshots (packaged app, scratch profile, cosmic-screenshot)
java/tools/parity/capture-java-ui.sh \
  --binary /media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai/bin/gravaai

# 3. Side-by-side gallery
node java/tools/parity/gallery.mjs 1        # -> docs/java-rewrite/phase-1-ui.html
```

Screens and states are listed in `java/tools/parity/capture-react-ui.spec.ts` (one entry per
screen) and `java/tools/parity/capture-java-ui.sh` (one case per screen). Add the same name on
both sides and it shows up in the gallery.

## Golden frames (P2.1)

`java/parity/fixtures/projects/` holds ten generated `.gravaai` fixtures covering the compositor
feature set; `java/parity/golden/` holds the Electron reference frames and `manifest.json`. See
`java/parity/fixtures/README.md` for the fixture/feature matrix and `java/parity/golden/README.md`
for the manifest format, capture commands and the software-only evidence.

```bash
# regenerate fixtures (uses the production project normaliser)
java/tools/parity/generate-fixtures.sh

# capture reference frames from a scratch-profile Electron app (AI control on)
java/tools/parity/capture-fixtures.sh
```

Comparison contract (P2.4): dimension mismatch is an error, mean absolute RGB difference <= 2.0, and
at most 0.5 % of pixels with any channel different by more than 2.

### Capture route is software-only (verified 2026-10-27)

Plan §1.1.1 forbids hardware video decode/encode. The Electron app disables VA-API on Linux in
`electron/gpuSwitches.ts` (`disableFeatures: ["VaapiVideoDecoder", "VaapiVideoEncoder"]`), the live
GPU/renderer command lines carry those `--disable-features` flags, H.264 decoding happens in
Chromium's software OpenH264 decoder (visible in the journal while capturing), and our bundled
ffmpeg CLI is built without VA-API/VDPAU/Vulkan/CUDA and is only used to re-encode the captured
JPEG to PNG. The frames tool performs no video encode; it captures page pixels only.

The Chromium GPU process still maps `libva`/`libvulkan` for GPU capability probing and OpenGL
rendering (plan §1.1.1 allows OpenGL rendering); no VA-API/Vulkan *video* path is enabled or used.
The previous claim in this file that no libva/vulkan is mapped was wrong and is corrected here.

The same scratch-profile rule applies: never touch the real `~/.config/Gravaai`, and never run
Electron and the Java app at the same time (plan §7).
