# JavaFX on COSMIC — window/GPU spike

Throwaway probe for the Java rewrite. Builds a native launcher with a bundled runtime and checks
the three things the rewrite depends on: a transparent always-on-top HUD, an editor window, and
an OpenGL preview delivered into JavaFX.

Stack: JDK 27.0.1 (Temurin), JavaFX 27, LWJGL 3.4.3 (OpenGL + EGL), FFM for X11/EGL calls,
Maven 3.9.16, `jlink` + `jpackage`.

## Build and run

```bash
JAVA_HOME=/media/jmarceno/scratch/java-toolchain/jdk-27.0.1.0.2+1 /media/jmarceno/scratch/java-toolchain/apache-maven-3.9.16/bin/mvn -q package
```

```bash
/media/jmarceno/scratch/java-targets/gravaai-spike/dist/GravaaiSpike/bin/GravaaiSpike
```

The app image is 109 MB (runtime included). The log goes to `~/.cache/gravaai-spike/spike.log`.
Four seconds after start the editor raises itself over the HUD to test stacking.

## Findings (RX 550, COSMIC, two monitors, one rotated)

| Check | Result |
|---|---|
| Packaging | ✅ `mvn package` → jlink runtime → jpackage app image with an ELF launcher. No scripts at runtime. |
| Window system | JavaFX 27 runs on **XWayland** (GTK3 X11 backend, Prism ES2 on GLX). It has no native Wayland support. |
| Editor window | ✅ Decorated window, JavaFX CSS with the app palette, slider/checkbox/timeline all fine. |
| Monitors | ✅ Both screens reported correctly, including the rotated 1080×1920 one; scale 1.0. |
| GL preview | ✅ Offscreen EGL surfaceless context (no window), GL 4.6 core, 1920×1080 FBO → PBO readback → double `PixelBuffer`. **60 fps presented**, draw < 0.1 ms CPU, readback + copy 1–9 ms. |
| HUD transparency | ✅ Rounded pill with transparent corners and shadow. COSMIC draws its cyan active-window outline around it. |
| HUD placement | ❌ COSMIC ignores and reverts client positioning of XWayland windows. The HUD lands where COSMIC puts it, not bottom-centre. |
| HUD always-on-top | ❌ Neither `Stage.setAlwaysOnTop` nor a direct EWMH `_NET_WM_STATE_ABOVE` client message (FFM → Xlib on GTK's connection) has any effect. Raising the editor covers the HUD. |
| HUD drag | Not verified (needs a human drag): it is handed to the WM through `_NET_WM_MOVERESIZE`, because client moves are reverted. |

Gotchas fixed along the way:
- LWJGL's argument check rejects the null native display that surfaceless EGL needs. That one call goes through FFM.
- JDK 27 needs `--enable-native-access=ALL-UNNAMED,javafx.graphics`.
- LWJGL 3.4.3 still prints `sun.misc.Unsafe` deprecation warnings.

## What it means for the rewrite

- The editor, the settings windows and the compositor-into-JavaFX path are proven.
- The HUD (and any other overlay: countdown, region picker, webcam bubble) cannot be a JavaFX
  window on COSMIC. It has to be a **`zwlr_layer_shell_v1` overlay surface**. COSMIC exposes v5,
  and that is what its own panel uses. Layer shell also solves placement (anchor bottom + margin),
  drag (update the margin) and click-through (`wl_surface.set_input_region`). It can be done in Java
  through FFM on `libwayland-client`, with the HUD drawn by the same GL/EGL stack on a Wayland EGL
  surface. JavaFX does not take part.
