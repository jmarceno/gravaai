# Phase 3 — P3.6 Electron removal plan (preparation)

Status: **preparation only — nothing has been deleted, moved or committed.** This document is the
execution plan for `docs/java-rewrite-plan.md` §7.3 P3.6. Execute it only after the reviewer has
approved the P3.1–P3.5 gates.

Prepared by: `electron-prep` teammate (shared task `task-5`), 2026-10-28.
Repository branch at preparation time: `java-rewrite`. Record the exact commit before deleting
with `git rev-parse HEAD`; the snapshot at preparation time was `8c06405`.

Companion rules: plan §1.1 (machine safety), §1.2 rule 6 (deliverable is a native binary),
rule 10 (Java lives in `java/` until P3.6) and rule 13 (small commits on `java-rewrite`, never
push). This document does not discuss licensing or schedule.

Architecture note (Lead policy, 2026-10-28): the Java app is moving to **one main window**
(dashboard and editor share the primary stage). The only separate OS windows are the HUD
overlay/menu, countdown, capture pickers and the area-recording border — the same set Electron
had. Deletion/verification steps below must not assume an "editor window" of its own.

---

## 1. What is being removed

The Electron app is a separate, self-contained tree next to `java/`. The Java app builds and runs
without it today; the only coupling is the asset copy enumerated in §2 and the stale references
in §3. The assets in `public/` are Vite `publicDir` copies, served at the app root as
`/wallpapers/...`, `/app-icons/...`, `/announcements/...`.

| Path | Git | Size | Files | Action |
|---|---|---|---|---|
| `electron/` | tracked | 30 MB | 274 | delete |
| `src/` | tracked | 7.6 MB | 710 | delete |
| `public/` | tracked | 16 MB | 51 | delete **after** §2 (`git rm`) |
| `tests/` (Playwright UI specs) | tracked | 404 KB | 38 | delete after the P3.5 behaviour list is captured |
| `scripts/` (Node build scripts) | tracked | 212 KB | 22 | delete (bash ports live in `java/native/`, `java/tools/`) |
| `build/` (macOS entitlements only) | tracked | 12 KB | 2 | delete (Electron/macOS-only) |
| `dist/`, `dist-electron/`, `release/`, `node_modules/`, `test-results/`, `.playwright-mcp/` | ignored | 20 MB / 772 KB / 442 MB / 1.9 GB / 8 KB / 8 KB | — | `rm -rf` (regenerable) |
| `index.html`, `package.json`, `package-lock.json`, `vite.config.ts`, `vitest.config.ts`, `playwright.config.ts`, `postcss.config.cjs`, `tsconfig.json`, `tsconfig.node.json`, `biome.json`, `electron-builder.json5` | tracked | — | 11 | `git rm` |
| `.gitignore` | tracked | — | 1 | rewrite for the Java-only repo (§7) |
| `java/` | tracked | — | — | **keep** (see §8) |

Tracked Electron files total **1,108** (1,097 under the directories above + the 11 root files).

**Never delete** (not Electron-only; still used by the Java repo): `java/`, `docs/`,
`extension-examples/`, `icons/`, `branding/`, `LICENSE.md`, `README.md`, `CONTRIBUTING.md`,
`EXTENSIONS.md`, `THIRD_PARTY_NOTICES.md`, `TRANSLATION_GUIDE.md`, `spike/`, `.gitattributes`,
`.serena/`.

`icons/` is a Java packaging dependency, not an Electron leftover:
`java/gravaai-app/pom.xml:111` passes `icons/icons/png/256x256.png` to `jpackage`, and
`PlatformSmokeTest` loads `icons/icons/png/32x32.png` for the tray. `branding/gravaai.svg` is the
README logo. `spike/javafx-cosmic/` is the JavaFX proof-of-concept, not Electron.

---

## 2. Asset migration table

The rule: every file under `public/` (and every Electron-only asset directory the Java app
resolves) is either **already bundled byte-identically** in the owning module's resources,
**copied now**, or **explicitly N/A** with a reason. Java resource roots:

| Module | Resource root |
|---|---|
| UI (wallpapers, app icons, announcement banners, icons, fonts, i18n) | `java/gravaai-ui/src/main/resources/` |
| Media (cursor sprites, fonts) | `java/gravaai-media/src/main/resources/` |
| Services (agent tool schemas, meeting prompts) | `java/gravaai-services/src/main/resources/` |

### 2.1 Already bundled and byte-identical

`public/wallpapers/*` → `java/gravaai-ui/src/main/resources/ui/editor/wallpapers/*`.
All 39 files are present and **SHA-256 identical** (Java copies were made in the P2 review pass,
2026-10-28 00:40). Sizes and hashes:

| Electron path | Java resource | Size | SHA-256 (16) | Status |
|---|---|---|---|---|
| `public/wallpapers/bluerays.jpeg` | `ui/editor/wallpapers/bluerays.jpeg` | 236583 B | `64c714e845253b5d` | identical |
| `public/wallpapers/cherrypop.jpg` | `ui/editor/wallpapers/cherrypop.jpg` | 435987 B | `07ce8f77f722c13c` | identical |
| `public/wallpapers/cityscape.jpg` | `ui/editor/wallpapers/cityscape.jpg` | 197573 B | `3dff00056072d1a3` | identical |
| `public/wallpapers/energy-17.jpg` | `ui/editor/wallpapers/energy-17.jpg` | 66458 B | `c695545ab3c540a5` | identical |
| `public/wallpapers/energy-19.jpg` | `ui/editor/wallpapers/energy-19.jpg` | 81185 B | `2b9aae2e387c0cc8` | identical |
| `public/wallpapers/farmvalley.jpg` | `ui/editor/wallpapers/farmvalley.jpg` | 1805963 B | `e7e7b3ae4f889ad6` | identical |
| `public/wallpapers/glassmorphism-3.jpg` | `ui/editor/wallpapers/glassmorphism-3.jpg` | 83190 B | `58f1896e218494d1` | identical |
| `public/wallpapers/glassmorphism-4.jpg` | `ui/editor/wallpapers/glassmorphism-4.jpg` | 65823 B | `03f86004d667f1a1` | identical |
| `public/wallpapers/ipad-17-dark.jpg` | `ui/editor/wallpapers/ipad-17-dark.jpg` | 61982 B | `175f363f486da9d0` | identical |
| `public/wallpapers/ipad-17-light.jpg` | `ui/editor/wallpapers/ipad-17-light.jpg` | 70422 B | `f34afe61cccf7a3e` | identical |
| `public/wallpapers/iridescent-9.jpg` | `ui/editor/wallpapers/iridescent-9.jpg` | 67986 B | `76832bc25317880c` | identical |
| `public/wallpapers/lemonade.jpeg` | `ui/editor/wallpapers/lemonade.jpeg` | 529073 B | `d3d6efdac00f7647` | identical |
| `public/wallpapers/levels.jpg` | `ui/editor/wallpapers/levels.jpg` | 243649 B | `727f2d0d2e6c785f` | identical |
| `public/wallpapers/luisdelrio.jpg` | `ui/editor/wallpapers/luisdelrio.jpg` | 1800273 B | `04c7e948ebcef3ce` | identical |
| `public/wallpapers/midnight-8.jpg` | `ui/editor/wallpapers/midnight-8.jpg` | 204767 B | `d169fc00c7e3dbb1` | identical |
| `public/wallpapers/mountaintrees.jpg` | `ui/editor/wallpapers/mountaintrees.jpg` | 703517 B | `f7f3fb2aea76c57c` | identical |
| `public/wallpapers/sequoia-blue.jpg` | `ui/editor/wallpapers/sequoia-blue.jpg` | 55083 B | `370e68d7d77dc277` | identical |
| `public/wallpapers/sequoia-blue-orange.jpg` | `ui/editor/wallpapers/sequoia-blue-orange.jpg` | 59054 B | `0acd7802e05b88ee` | identical |
| `public/wallpapers/sonoma-clouds.jpg` | `ui/editor/wallpapers/sonoma-clouds.jpg` | 88605 B | `873ebee21d0f8e7f` | identical |
| `public/wallpapers/sonoma-dark.jpg` | `ui/editor/wallpapers/sonoma-dark.jpg` | 75911 B | `19686e13c6ea088c` | identical |
| `public/wallpapers/sonoma-evening.jpg` | `ui/editor/wallpapers/sonoma-evening.jpg` | 138807 B | `4e684d86ac40e3a0` | identical |
| `public/wallpapers/sonoma-horizon.jpg` | `ui/editor/wallpapers/sonoma-horizon.jpg` | 183309 B | `a021e13dd84283f9` | identical |
| `public/wallpapers/sonoma-light.jpg` | `ui/editor/wallpapers/sonoma-light.jpg` | 87925 B | `414d71c72a5aed98` | identical |
| `public/wallpapers/tahoe-dark.jpg` | `ui/editor/wallpapers/tahoe-dark.jpg` | 85996 B | `3dd4d97c88c9c34d` | identical |
| `public/wallpapers/tahoe-light.jpg` | `ui/editor/wallpapers/tahoe-light.jpg` | 95360 B | `7b99d6c0ea99f833` | identical |
| `public/wallpapers/ventura-dark.jpg` | `ui/editor/wallpapers/ventura-dark.jpg` | 80955 B | `918c565b4668b7d8` | identical |
| `public/wallpapers/ventura.jpg` | `ui/editor/wallpapers/ventura.jpg` | 83730 B | `9df56d0a2be2da7b` | identical |
| `public/wallpapers/wallpaper10.jpg` | `ui/editor/wallpapers/wallpaper10.jpg` | 164245 B | `44f0166bab1e3db8` | identical |
| `public/wallpapers/wallpaper11.jpg` | `ui/editor/wallpapers/wallpaper11.jpg` | 156998 B | `12bd0844fad40bc1` | identical |
| `public/wallpapers/wallpaper12.jpg` | `ui/editor/wallpapers/wallpaper12.jpg` | 155480 B | `7c2155175629f247` | identical |
| `public/wallpapers/wallpaper13.jpg` | `ui/editor/wallpapers/wallpaper13.jpg` | 218945 B | `62b9fe5cbb92fddc` | identical |
| `public/wallpapers/wallpaper15.jpg` | `ui/editor/wallpapers/wallpaper15.jpg` | 125196 B | `d9ced199cf2d7408` | identical |
| `public/wallpapers/wallpaper1.jpg` | `ui/editor/wallpapers/wallpaper1.jpg` | 81195 B | `1948d537c9a1b4ff` | identical |
| `public/wallpapers/wallpaper2.jpg` | `ui/editor/wallpapers/wallpaper2.jpg` | 144927 B | `10e17da7c44d80f6` | identical |
| `public/wallpapers/wallpaper3.jpg` | `ui/editor/wallpapers/wallpaper3.jpg` | 143990 B | `70542e17df45a13b` | identical |
| `public/wallpapers/wallpaper4.jpg` | `ui/editor/wallpapers/wallpaper4.jpg` | 307327 B | `471fc619d9990c82` | identical |
| `public/wallpapers/wallpaper7.jpg` | `ui/editor/wallpapers/wallpaper7.jpg` | 214677 B | `6b940062613d608f` | identical |
| `public/wallpapers/wallpaper9.jpg` | `ui/editor/wallpapers/wallpaper9.jpg` | 217872 B | `fbca5b1279b5f657` | identical |
| `public/wallpapers/wispysky.mp4` | `ui/editor/wallpapers/wispysky.mp4` | 2747876 B | `0ddd30bcaa7af2bc` | identical |

Derived previews (not Electron files, generated for the Java UI in P2): 25 files in
`ui/editor/wallpapers/thumbs/` (160×120) and 25 in `ui/editor/wallpapers/previews/`
(1280×720) = the 24 built-in menu images in `EditorWallpapers.IMAGE_FILES` plus the Wispy Sky
poster `wispysky.jpg`. The 14 full-size-only wallpapers (`bluerays`, `cherrypop`, `farmvalley`,
`lemonade`, `luisdelrio`, `mountaintrees`, `wallpaper1`, `wallpaper2`, `wallpaper7`, `wallpaper9`,
`wallpaper11`, `wallpaper12`, `wallpaper13`, `wallpaper15`) are not in the built-in menu but are
bundled because projects can reference them by `/wallpapers/<name>`; the preview path falls back
to the full-size image (`PreviewBackgrounds`). `wispysky.mp4` is the video wallpaper; the preview
and export path uses the poster/preview jpg plus the clip.

`public/app-icons/gravaai-{128,256}.png` are already bundled byte-identically too (not used by
the current Java tray code, but harmless). `public/app-icons/gravaai-32.png` is bundled and is
the tray idle icon (`/app-icons/gravaai-32.png`).

### 2.2 Copied in this preparation

| Electron path | Java resource | Size / SHA-256 (16) | Why |
|---|---|---|---|
| `public/rec-button.png` | `java/gravaai-ui/src/main/resources/app-icons/rec-button.png` | 726 B / `089f4b07536195d0` | **Missing.** `AppContext.startTray()` calls `icons.loadOrNull("rec-button.png")` (`/app-icons/rec-button.png`); missing meant the tray silently kept the idle icon while recording. |
| `public/announcements/placeholder-1.svg` | `java/gravaai-ui/src/main/resources/ui/dashboard/announcements/placeholder-1.svg` | 660 B / `9d68b0dfab3aa518` | **Missing.** `DashboardAnnouncementsView` resolves `/ui/dashboard/announcements/placeholder-1.svg`; the banner fell back to text-only. |
| `public/announcements/placeholder-2.svg` | `java/gravaai-ui/src/main/resources/ui/dashboard/announcements/placeholder-2.svg` | 702 B / `dd7549554d8fc748` | same as above |
| `public/rec-button.png` | `java/gravaai-platform/src/test/resources/icons/rec-button.png` | 726 B / `089f4b07536195d0` | `PlatformSmokeTest` needs a recording-icon file on the test classpath after `public/` is gone. |

### 2.3 Explicitly not migrated (N/A)

| Electron asset | Status | Reason |
|---|---|---|
| `public/app-icons/gravaai-{16,64,512,1024}.png` | N/A (one UI note) | Electron runtime requests only `gravaai-32` (tray, `electron/main.ts:293`) and `gravaai-512` (macOS dock, `:638`, N/A per plan §2.3). `electron-builder.json5:88` uses root `icons/icons/png/`, not these files. The React renderer also displayed `gravaai-128.png` (`src/App.tsx:32`) and `gravaai-64.png` (`DashboardSidebar.tsx:42`): Java bundles 128 (still unreferenced by main code today), and the Java dashboard logo uses `Icons.AppWindowIcon` instead of the 64 px PNG — a UI-fidelity item for P3.4/P3.5, not an asset-resolution gap. 16/1024 are unused. |
| `public/vite.svg` | N/A | `index.html` favicon only. |
| `public/wasm/web-demuxer.wasm` | N/A | Browser `mediainfo` only; the Java app probes with FFmpeg (`MediaProbe`). Phase-1 decision (phase-1 report, Appendix B). |
| `src/assets/fonts/dm-sans/{DMSans-Variable.ttf,DMSans-Italic-Variable.ttf,OFL.txt}` | superseded | Java bundles static DM Sans instances (`kit/fonts/`, `fonts/` in media: 9 TTF + `OFL.txt`, byte count 56–62 KB each) because JavaFX does not apply variable axes. No Electron file is referenced at runtime. The *generation* script `java/tools/fonts/generate-fonts.py` still reads the variable fonts — see §3.3. |
| `src/assets/cursors/**` (110 files) | subset bundled | `CursorAssets` resolves exactly 28 files: 9 keys × macOS/Tahoe/Windows 11 + 3 Golden Gate hand sprites + `custom/minimal-cursor.svg`. All 28 are bundled under `java/gravaai-media/src/main/resources/dev/gravaai/media/cursors/`; the other 82 Electron cursor SVGs are not referenced by `SUPPORTED_CURSOR_KEYS` and are not needed. |
| `src/assets/react.svg` | N/A | Dev asset for the React harness. |
| `build/entitlements*.plist` | delete | macOS code-signing entitlements, never used by the Java build. |

### 2.4 Loaders/tests that resolve the migrated assets

| Loader | Resource it resolves | Owning test |
|---|---|---|
| `AppIcons.loadOrNull/load` (`gravaai-app`) | `/app-icons/gravaai-32.png`, `/app-icons/rec-button.png` | **new** `AppIconsTest` (2 tests) |
| `DashboardAnnouncementsView.loadImage` (`gravaai-ui`) | `/ui/dashboard/announcements/<name>.svg` | **new** `BundledAssetsTest` |
| `EditorWallpapers.thumbnail/preview` (`gravaai-ui`) | `/ui/editor/wallpapers/thumbs|previews/<name>` | **new** `BundledAssetsTest` (all `IMAGE_FILES`, video poster) |
| `PreviewBackgrounds.of` (`gravaai-ui`) | `/ui/editor/wallpapers/previews/<name>` | existing `PreviewBackgroundsTest` |
| `ProjectExporter.stageWallpapers` (`gravaai-app`) | `/ui/editor/wallpapers/<name>` staged to a temp dir (archive-safe) | existing exporter tests |
| `ProjectEditorInputs.background/videoWallpaperPath` (`gravaai-media`) | directory containing `wallpapers/` passed in by the caller | `ProjectFrameInputFactoryTest` (now uses the Java resource root) |

New tests:

* `java/gravaai-ui/src/test/java/dev/gravaai/ui/asset/BundledAssetsTest.java` (5 tests) — classpath
  presence of the recording tray icon, default tray icon, both announcement SVGs, every
  `EditorWallpapers.IMAGE_FILES` full-size + thumb + preview, and the video wallpaper + poster.
* `java/gravaai-app/src/test/java/dev/gravaai/app/AppIconsTest.java` (2 tests) — `AppIcons`
  extracts `gravaai-32.png` and `rec-button.png` from `/app-icons/` and reports `null` for a
  missing resource.

---

## 3. Reference audit

Searched `java/**` (Java sources, main + test, C helpers, shell/Node/Python tools, parity
fixtures, POMs) for `electron/`, `src/`, `public/`, `node_modules`, `package.json`,
`electron-builder`, `dist/`, `release/`, and for the Electron CLI flag `--ozone-platform=headless`.

Result: **zero runtime-breaking references remain** in Java code, tests, build scripts and
packaging after the fixes in §3.1. The rest are historical source-path comments (deliberately
kept — they document which TS file each Java class ports) or Electron-only developer tooling that
is itself part of the removal (§3.3).

### 3.1 Fixed here (Java-side path references that would break after deletion)

| Site (before) | Reference | Breakage without the fix | Fix | Verified by |
|---|---|---|---|---|
| `java/gravaai-ui/src/main/java/dev/gravaai/ui/editor/EditorSnapshot.java:122` | `Path.of("../public/wallpapers/tahoe-light.jpg")` | dev/gallery editor snapshot lost the project-browser thumbnail once `public/` is gone (and it only worked when the CWD was the module dir) | `bundledThumbnail()` extracts `/ui/editor/wallpapers/previews/tahoe-light.jpg` from the classpath to a temp file | `EditorSnapshotTest` 3/3 |
| `java/gravaai-ui/src/test/java/dev/gravaai/ui/editor/EditorSnapshotTest.java:123-132` | `../../public/wallpapers/...` probe | same | delegates to `EditorSnapshot.bundledThumbnail()` | `EditorSnapshotTest` 3/3 |
| `java/gravaai-platform/src/test/java/dev/gravaai/platform/smoke/PlatformSmokeTest.java:49,141` | `repoRoot.resolve("public/rec-button.png")`; repo marker required `public/` | smoke test could not locate its recording icon/repo root; `IllegalStateException` | `/icons/rec-button.png` from the test classpath (`java/gravaai-platform/src/test/resources/icons/rec-button.png`); repo marker now `icons/icons/png/32x32.png` + `docs/java-rewrite-plan.md` | compiles; 3 tests are property-gated (`-Dgravaai.smoke=true`) and skipped, 0 failures |
| `java/gravaai-services/src/test/java/dev/gravaai/services/shortcuts/ControlSocketTest.java:35,41` | `electron/native/bin/linux-x64/gravaai-ctl` | the real-socket C-helper tests were silently skipped (`assumeTrue` on a missing path) | walks up for `java/native/build/linux-x64/gravaai-ctl` and `java/native/build/gravaai-ctl` (the copies the Java build produces) | `ControlSocketTest` 7/7 (C-helper tests now execute) |
| `java/gravaai-media/src/test/java/dev/gravaai/media/compose/ProjectFixture.java:49,148` | `REPO_ROOT.resolve("public")` background root; repo marker used `public/wallpapers` | every compositor/golden test that uses a wallpaper would paint black and fail after deletion | `wallpaperRoot()` = `java/gravaai-ui/src/main/resources/ui/editor`; marker switched to that path + `java/parity` | `ProjectFrameInputFactoryTest` 5/5 |
| `java/gravaai-media/src/test/java/dev/gravaai/media/compose/ProjectFrameInputFactoryTest.java:29,62,83,102` | `ProjectFixture.repoRoot().resolve("public")` | same | `ProjectFixture.wallpaperRoot()` | `ProjectFrameInputFactoryTest` 5/5 |
| `java/gravaai-app/src/test/java/dev/gravaai/app/editor/RealProjectCompareDumpTest.java:38` | `Path.of("..","..","public")` | dev "real projects side by side" diagnostic (env-gated) rendered black backgrounds | `java/gravaai-ui/src/main/resources/ui/editor` | compiles; 1 test env-gated and skipped, 0 failures |

### 3.2 Audited and safe (no action)

| Category | Evidence | Why it is safe |
|---|---|---|
| Historical source provenance in Javadoc/README | 241 lines mentioning `electron/` in 217 files; 170 lines mentioning `src/components|lib|i18n|assets|hooks|contexts` | Comments only, no filesystem access. They name the TS file each class ports (plan §8 method) and are useful history; keep them. |
| Phase-3 guards in TS-comparison tests | `java/gravaai-services/src/test/java/dev/gravaai/services/agent/AgentToolSchemasMatchTsTest.java:36`, `.../meeting/PromptsMatchTsTest.java:30` | `Assumptions.assumeTrue(source != null, "electron/ is gone (Phase 3)…")` — they skip, not fail, once `electron/` is deleted. |
| `docs/`, phase reports, `java/parity/golden/*/metadata.json` `"route": "electron MCP frames tool …"` (10 files) | provenance of the reference frames | historical records, no runtime use. |
| `java/native/README.md:18,93,128`, `java/native/build-native-helpers.sh:9,65` (`# Sources are copies of electron/native/...`), `java/native/models/rnnoise/notices/README.md:13` | comments | no filesystem access. |
| `java/gravaai-ui/src/main/java/dev/gravaai/ui/kit/README.md:168,190`, `.../launch/README.md:4,79`, `.../editor/preview/README.md:4-5` | port notes | comments/docs only. |
| `java/tools/parity/capture-java-ui.sh:75` (`node java/tools/parity/gallery.mjs`) | gallery tool kept | Node is still needed for the HTML gallery; that is a parity tool, not the Electron app. |

### 3.3 Electron-only tooling that becomes dead (report — not deleted by this task)

These are developer/parity scripts, not part of `java/mvnw -Pdist package`; the build and the
shipped app do **not** depend on them. P3.6 should either port them to the Java-only reality or
delete them (§4 has them as optional cleanup).

| Site | Reference | Impact | Recommended action |
|---|---|---|---|
| `java/native/make-fixtures.sh:28,36` | default host ffmpeg = `node_modules/ffmpeg-static/ffmpeg` | `--force` regeneration of the VP9 fixture fails after `node_modules/` is gone (fixtures are committed, normal builds unaffected) | default to `$java_root/native/build/ffmpeg/bin/ffmpeg` or the system ffmpeg and document `HOST_FFMPEG`; or delete the VP9-generation note. Owner: whoever next touches fixtures. |
| `java/tools/parity/capture-fixtures.sh:22,56` | `$repo/node_modules/electron/dist/electron`, `env … $electron . --mcp` | Electron golden capture cannot run | Electron captures are historical; delete the Electron half of the parity capture tooling (or move it under `docs/` as a historical recipe). Goldens/fixtures are committed. |
| `java/tools/parity/capture-golden.mjs:67` (default `npx electron . --mcp`), `capture-react-ui.spec.ts:1,44,47` (Playwright + `window.electronAPI` stubs), `playwright.config.ts:1,5,32` (`@playwright/test` from `node_modules`) | Electron capture / React reference screenshots | dev-only; cannot run after deletion | delete with the Electron tree; keep `gallery.mjs` (it tolerates a missing React capture dir via `existsSync`). |
| `java/tools/extract-icons.mjs:26,59,68` | reads `node_modules/@phosphor-icons/react`, `@solar-icons/react` | icon regeneration not reproducible; committed SVGs are complete and tested (`IconsTest`, 367 UI resources) | note in `kit/README.md`; if regeneration must stay possible, vendor the needed icons or keep a tiny npm project under `java/tools/` (reviewer decision). |
| `java/tools/fonts/generate-fonts.py:9-10` | reads `src/assets/fonts/dm-sans/DMSans*-Variable.ttf` | static-font regeneration not reproducible; committed TTFs are tested by snapshot tests | copy the two variable TTFs + `OFL.txt` (~531 KB) into `java/tools/fonts/source/` **before** deleting `src/`, or drop the script. Owner: Lead (write scope of this task excludes `java/tools/`). |
| `java/gravaai-app/src/main/java/dev/gravaai/app/agent/AgentWiring.java:96`; `java/gravaai-ui/src/main/java/dev/gravaai/ui/agent/PlaceholderAgentUiService.java:30,32,38`; tests `AgentSettingsViewTest.java:77`, `ServiceAgentUiServiceTest.java:52` | `--mcp --ozone-platform=headless` | **harmless**: `CliArgs.parse` ignores unknown args, so the flag is a no-op; but it is an Electron/Chromium flag and must not stay in user-facing MCP snippets | remove the flag from the MCP command string/args (and the two test expectations) — P3.2 agent-UI owner. |
| Javadoc that still names the old asset location: `java/gravaai-platform/src/main/java/dev/gravaai/platform/tray/TrayIcon.java:15` ("recording variant `public/rec-button.png`"), `java/gravaai-media/src/main/java/dev/gravaai/media/compose/ProjectEditorInputs.java:19` ("tests point at `public/`"), `ProjectFrameInputFactory.java:32` ("or the repo's `public/`") | comments | no runtime impact; now stale | update wording in the docs sweep (§7); not done here to keep main-code edits to load-bearing paths only. |
| `java/README.md:34,47` ("Never run the Java app and the Electron app together"; "Node only for asset extraction/parity") | README | argument no longer exists / partly stale | §7 update. |
| `java/parity/README.md:14`, `java/parity/golden/README.md:38,46-51,60,90,105` | Electron capture recipes | historical | annotate as historical (§7). |

### 3.4 Root paths the Java build legitimately keeps

| Root path | Referenced by | Keep? |
|---|---|---|
| `icons/icons/png/256x256.png` | `java/gravaai-app/pom.xml:111` (`jpackage --icon`) | yes |
| `icons/icons/png/32x32.png` | `PlatformSmokeTest:48,140` (tray smoke) | yes |
| `branding/gravaai.svg` | `README.md` logo | yes |
| `extension-examples/hello-overlay/**` | `ExtensionTestSupport` (services test), P3.3 Extension Manager examples | yes |
| `docs/java-rewrite-plan.md` | repo-root marker in `PlatformSmokeTest`, `I18nCheckTest` | yes |
| `java/native/build/ffmpeg/…`, `java/native/build/gravaai-*` | `NativeHelpers`, `ControlSocketTest` (dev runs, untracked) | yes (not Electron) |

### 3.5 CI / `.github` (rule 5)

There is **no `.github/` directory in this repository snapshot**, so there is no workflow to
convert to `workflow_dispatch`-only. P3.6 acceptance should state that explicitly; if CI is ever
added it must be manual-only (plan rule 5). `rg --files -g '.github/**'` returns nothing.

---

## 4. Deletion checklist (exact commands — NOT executed)

Run from the repository root. Do not `cd` into a path before computing a `rm -rf`; every target
below is relative and checked by `git status`/`ls` first. Machine rule: one build/test JVM at a
time (`flock /tmp/gravaai-mvn.lock`).

### Step 0 — preconditions

```bash
cd /home/jmarceno/Projects/gravaai
git rev-parse --abbrev-ref HEAD          # must print: java-rewrite
git status --porcelain                   # must be clean or only reviewed changes
git rev-parse HEAD                       # record this hash (rollback point)
# reviewer has approved P3.1–P3.5 (written note in the task/report)
```

### Step 1 — snapshot / rollback point

Rule 13: small commits, never push. Commit the preparation work first, then tag/branch the
rollback point locally:

```bash
git add -A
git commit -m "P3.6 prep: migrate Electron assets into Java resources"
git rev-parse HEAD > /tmp/gravaai-pre-p3.6.head   # /tmp survives only the session; also copy it
git tag pre-p3.6-electron-removal                 # local tag, never pushed
```

### Step 2 — prove the app builds and runs BEFORE deleting

```bash
cd java
flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 test
flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 -Pdist package
# then launch the AppImage from an unrelated directory and walk §5 items 3–8:
cd /tmp && /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage
```

The manual checks in §5 must pass; record screenshots/notes. Only then continue.

### Step 3 — remove ignored build output (safe now, regenerable)

```bash
cd /home/jmarceno/Projects/gravaai
rm -rf release dist dist-electron node_modules test-results .playwright-mcp
# Do NOT remove .tmp/ (agent scratch), .claude/ (user config) or .serena/ (tracked project config).
```

### Step 4 — remove the tracked Electron tree

```bash
cd /home/jmarceno/Projects/gravaai
git rm -r --ignore-unmatch electron
git rm -r --ignore-unmatch src
git rm -r --ignore-unmatch public
git rm -r --ignore-unmatch tests
git rm -r --ignore-unmatch scripts
git rm -r --ignore-unmatch build
git rm --ignore-unmatch index.html package.json package-lock.json
git rm --ignore-unmatch vite.config.ts vitest.config.ts playwright.config.ts postcss.config.cjs
git rm --ignore-unmatch tsconfig.json tsconfig.node.json biome.json electron-builder.json5
```

Keep: `java/ docs/ icons/ branding/ extension-examples/ spike/ LICENSE.md README.md
CONTRIBUTING.md EXTENSIONS.md THIRD_PARTY_NOTICES.md TRANSLATION_GUIDE.md .gitattributes
.serena/`.

### Step 5 — prove the repo builds with the Electron tree gone

```bash
cd /home/jmarceno/Projects/gravaai/java
flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 clean test
flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 -Pdist package
# run the AppImage from an unrelated directory (AppRun resolves jpackage.app-path):
cd /tmp && /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage &
sleep 3; pkill -x gravaai
java/tools/startup-check.sh --runs 5 /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage
```

If this fails, roll back with §6 and stop.

### Step 6 — docs and housekeeping commit

Apply §7 (README, CONTRIBUTING, THIRD_PARTY_NOTICES, TRANSLATION_GUIDE, EXTENSIONS.md,
`java/README.md`, `.gitignore`, parity READMEs, agent MCP snippets), then:

```bash
cd /home/jmarceno/Projects/gravaai
git add -A
git commit -m "P3.6: remove the Electron app"
git status --porcelain                   # expected: clean
```

Do not commit `java/native/build/` (ignored by `java/.gitignore`), `dist/`, `release/`,
`.tmp/`, `.claude/`; never push (rule 13).

### Optional cleanup (reviewer decision, not required by §7.3)

```bash
git rm -r --ignore-unmatch spike            # JavaFX spike: Java era evidence, not Electron
git rm --ignore-unmatch .gitattributes      # or trim the .dll/.exe/.icns rules
```

`tests/` deletion is gated on P3.5: the Playwright specs are the behaviour list for the parity
sweep (`docs/java-rewrite/phase-3-parity-sweep.md` in the working tree at preparation time).
Delete `tests/` only after that list is captured in the phase report.

---

## 5. Pre-deletion verification steps (manual + automated)

All automated steps: `cd java`, one JVM at a time, isolated display for JavaFX tests —
`DISPLAY=:99 ./mvnw -Dgravaai.test.display=:99 …` (the user is on `:0`; never open windows there).

1. **Full suite** — `flock /tmp/gravaai-mvn.lock env DISPLAY=:99 ./mvnw -o -T1 -Dgravaai.test.display=:99 test`.
   Expected today: core 322, platform 68 (3 skipped), media 484 (3 skipped), services 607
   (1 skipped), ui 438, app 22 (1 skipped) → 1,941 tests, 0 failures, 8 skipped (§9).
2. **Packaged AppImage** — `flock /tmp/gravaai-mvn.lock ./mvnw -o -T1 -Pdist package`, then launch
   `/media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage` from
   `/tmp` (not from `java/`, to prove `jpackage.app-path`/AppRun is correct).
3. **Single-main-window sanity** — dashboard and editor in the one primary window; HUD overlay +
   menu, countdown, capture picker and area border are the only extra windows.
4. **Tray recording icon** — start a recording from the HUD; on COSMIC the tray icon switches to
   the red record icon (`rec-button.png`) and back to `gravaai-32.png`. (This is the asset that
   was silently missing before this task.)
5. **Wallpapers** — open a project, Settings/Background: all 24 built-in thumbnails render, the
   Wispy Sky video tile previews; pick a full-size-only wallpaper via a project file (e.g.
   fixture 01 `/wallpapers/farmvalley.jpg`) and confirm the preview is not black.
6. **Export** — export fixture 05/07 (cursor + webcam + wallpaper): wallpaper, cursor sprite,
   click effect and keystroke chips present in the MP4 (this was the P2 review failure mode:
   "full-size wallpapers were not bundled, so exports painted black").
7. **Golden suite/gallery (optional record)** — `-Dtest=GoldenFrameSuiteTest` and
   `java/tools/parity/capture-java-ui.sh` + `node java/tools/parity/gallery.mjs` for the
   `docs/java-rewrite/phase-3-ui.html` evidence.
8. **Startup** — `java/tools/startup-check.sh --runs 5 <AppImage>`; compare against the P2 numbers
   (HUD 724 ms, editor-open 810 ms) — plan §7.3 acceptance requires the budget to be met.
9. **Post-deletion repeat** — after §4 step 5, repeat 1, 2, 4, 5, 6 and 8 with no `public/` present.

---

## 6. Rollback notes

* Everything deleted in §4 steps 4 and 6 is tracked in git. The preparation commit and the local
  tag `pre-p3.6-electron-removal` are the rollback points. Nothing is pushed (rule 13), so the
  tag/branch stays local until the reviewer is satisfied.
* Full rollback: `git reset --hard pre-p3.6-electron-removal` (or `git revert <deletion-commit>`
  to keep history). Then `npm ci` restores `node_modules/` from the restored `package-lock.json`
  (network required); Electron build output `dist/`, `dist-electron/`, `release/` is regenerated
  by `npm run build:linux`.
* Ignored directories removed in step 3 are not in git: `node_modules` via `npm ci`,
  `release/dist/dist-electron` via `npm run build:linux`, `test-results/.playwright-mcp` via
  `npm run test:ui` when needed.
* The Java side has its own rollback: the AppImage under
  `/media/jmarceno/scratch/java-targets/gravaai-app/dist/` from §5 step 2 remains buildable from
  the preparation commit even with the Electron tree deleted.
* If the post-deletion build fails, do not patch forward: roll back, add the missing asset to
  `java/gravaai-*/src/main/resources/`, re-run §5 and retry (§4 step 5).
* `java/parity/ui-java/*.png` are tracked and are rewritten by any test run that opens the UI;
  before the final P3.6 commit decide explicitly whether to commit the latest gallery or restore
  it (`git checkout -- java/parity/ui-java`), otherwise the commit mixes unrelated screenshot
  churn.

---

## 7. Post-removal documentation updates

| File | Update |
|---|---|
| `README.md` | Replace the Electron build/run sections: prerequisites (`libwayland-dev`, C toolchain, JDK via `java/mvnw`), `cd java && ./mvnw -T1 -Pdist package`, AppImage path (`java/tools/package-dist.sh` output), dev flags (`--ui-kit`, `--dashboard`, `--mcp`), "MCP client" command without `--ozone-platform=headless`, remove `npm install` / `npm run build:linux` / `npm run dev` / `npm run mcp` / `release/` mentions, note one main window. |
| `CONTRIBUTING.md` | Point contributors to `java/README.md` build/test commands and the Java layout (`gravaai-core … gravaai-app`); replace "Wallpaper submissions" with "Wallpaper/cursor asset additions (Java resources)"; remove any Node assumption. |
| `THIRD_PARTY_NOTICES.md` | DM Sans: OFL path `src/assets/fonts/dm-sans/OFL.txt` → `java/gravaai-ui/src/main/resources/kit/fonts/OFL.txt` (media copy: `java/gravaai-media/src/main/resources/fonts/OFL.txt`); wording "unmodified variable fonts from Google Fonts" → static instances generated by `java/tools/fonts/generate-fonts.py`. Solar Icons: replace "rendered using the MIT-licensed `@solar-icons/react` package" with "Solar SVGs bundled under `java/gravaai-ui/src/main/resources/kit/icons/solar/`". Voom section references `services/gravaai-share/worker`, which does not exist in this repo — verify and drop the section if nothing is bundled (P3.6 says list only what we bundle). |
| `TRANSLATION_GUIDE.md` | Locale path `src/i18n/locales/<locale>/<namespace>.json` → `java/gravaai-ui/src/main/resources/i18n/<locale>/<namespace>.json` (11 locales, same 7 namespaces); validation `npm run i18n:check` → `cd java && flock /tmp/gravaai-mvn.lock ./mvnw -o -q -pl gravaai-core -am -Dtest=I18nCheckTest -Dsurefire.failIfNoSpecifiedTests=false test`; contributor workflow: no `npm run dev` — use the packaged app image / `java/README.md`. |
| `EXTENSIONS.md` | "extensions run in the editor renderer" / "Chromium renderer" → Java extension host (GraalJS Polyglot, plan §5.18); `api.drawIcon` names are the bundled Phosphor/Solar sets under `java/gravaai-ui/src/main/resources/kit/icons/`; asset resolution examples unchanged (extension-bundled assets, not `public/`). |
| `java/README.md` | Remove the "never run the Java app and the Electron app together" line; `tools/ … (Node only for asset extraction/parity)` → note that `extract-icons.mjs`/`fonts/generate-fonts.py` need Node/source assets that P3.6 removed, outputs are committed; keep build/run flags and add the AppImage path. |
| `.gitignore` | Remove the Node/Electron blocks (`node_modules`, `dist`, `dist-electron`, `release`, vitest/playwright logs, Electron native build dirs, `.tmp`, tsbuildinfo, vite.*). Keep Java safety nets: `target/`, `*.class`, `.jqwik-database`, `java/.gitignore` can be merged into the root file after `java/*` stays put. |
| `.gitattributes` | Optional trim: `.dll/.exe/.icns` are Electron/macOS leftovers; keep `*.png *.jpg *.jpeg *.mp4 *.ttf *.wasm` binary rules. |
| `java/parity/README.md`, `java/parity/golden/README.md`, `java/parity/fixtures/README.md` | Mark the Electron capture recipes (`npx playwright …`, `./node_modules/.bin/electron …`) as historical; Java capture (`capture-java-ui.sh`, `capture-golden.mjs --ffmpeg` if ported) is the live path. |
| `java/gravaai-ui/src/main/java/dev/gravaai/ui/kit/README.md:168,190` | Icons: "extracted once from `node_modules`" → historical note; fonts: variable-font source removed, static instances committed. |
| `java/gravaai-platform/src/main/java/dev/gravaai/platform/tray/TrayIcon.java:15` | Javadoc: recording variant is `/app-icons/rec-button.png` (was `public/rec-button.png`). |
| `java/gravaai-media/src/main/java/dev/gravaai/media/compose/ProjectEditorInputs.java:19`, `ProjectFrameInputFactory.java:32` | Javadoc: "tests point at `public/`" → tests point at `java/gravaai-ui/src/main/resources/ui/editor`. |
| Agent MCP snippets | `AgentWiring.java:96`, `PlaceholderAgentUiService.java:30,32,38`, `AgentSettingsViewTest.java:77`, `ServiceAgentUiServiceTest.java:52` — drop `--ozone-platform=headless` (P3.2 owner). |
| `.github/` | Nothing to change (no directory today); any future workflow must be `workflow_dispatch` only (rule 5). |

---

## 8. Decision: keep `java/` or move `java/*` to the repository root?

### 8.1 Recommendation: **keep `java/`** (plan default)

Reasons, in order of weight:

1. **Moving is pure churn with no user-visible benefit.** Everything below already builds with
   the wrapper path `java/mvnw`; the plan's P3 acceptance gate is exactly
   `./mvnw -T1 -Pdist package`, which works today from `java/`.
2. **Moving touches 20+ path literals and 8 scripts, i.e. real regression risk during the same
   phase that deletes the app.** The repository is being edited concurrently by several agents;
   a repo-wide path move would collide with every in-flight change.
3. **`java/` is a useful marker** that the repo used to host two stacks and that plan/history
   documents (`docs/java-rewrite-plan.md`, phase reports, `java/README.md`) refer to `java/`
   paths. Moving now would invalidate every historical `file:line` in those reports ($7.3 leaves
   the decision to the reviewer, but old reports are evidence for the phase gates).
4. `java/.gitignore`, `java/.mvn/`, `java/mvnw` already encapsulate the Java build; nothing at the
   root needs to know about Maven except the READMEs.

**Concrete change list for keeping `java/`: none.** That is the recommendation: after §4 the repo
is a Java-only project whose build entry point is `java/mvnw` (or `java/tools/mvn-locked.sh`), and
P3.6's acceptance command becomes `cd java && ./mvnw -T1 -Pdist package`.

### 8.2 If the reviewer chooses to move: exact file-level change list

All paths below are relative to the repository root unless noted. Copy `java/*` (and
`java/.mvn`, `java/.gitignore`, `java/mvnw`, `java/mvnw.cmd`) to the root first, remove the
now-empty `java/`, then apply:

1. `java/gravaai-app/pom.xml:111` — jpackage icon:
   `${maven.multiModuleProjectDirectory}/../icons/icons/png/256x256.png` →
   `${maven.multiModuleProjectDirectory}/icons/icons/png/256x256.png`.
2. `java/gravaai-platform/src/main/java/dev/gravaai/platform/nativehelpers/NativeHelpers.java:29` —
   `BUILD_DIR = "java/native/build"` → `"native/build"`; update its Javadoc (lines 10-14).
   `java/gravaai-platform/src/test/java/dev/gravaai/platform/nativehelpers/NativeHelpersTest.java:23,27,34`
   — `workingDirectory.resolve("java/native/build…")` → `"native/build…"`.
3. `java/gravaai-core/src/test/java/dev/gravaai/core/i18n/I18nCheckTest.java:99` —
   `candidate.resolve("java/gravaai-ui/src/main/resources/i18n")` →
   `candidate.resolve("gravaai-ui/src/main/resources/i18n")`.
4. Media tests (all `java/`-prefixed repo paths):
   `java/gravaai-media/src/test/java/dev/gravaai/media/compose/ProjectFixture.java:36,39,157`
   (`java/gravaai-ui/src/main/resources/ui/editor` → `gravaai-ui/…`, `java/parity` → `parity`,
   marker check), `ProjectFrameInputFactoryTest.java:98`, `PortraitFrameReaderTest.java:26`,
   `GoldenFrameSuiteTest.java:67`, `GoldenParityReportTest.java:83`, `GoldenFixtures.java:42`.
5. `java/tools/parity/fixtures/GenerateFixtures.java:61,62,76,562,563,581` — `java/parity/…`,
   `java/gravaai-core/…`, `java/native/build/ffmpeg/…` → without the `java/` prefix.
6. Shell/Node tools that derive the repo root from `java/…` depth:
   * `java/tools/phase-gate.sh:17` `…/../..` → `…/..`; line 28 `cd "$REPO/java"` → `cd "$REPO"`.
   * `java/tools/parity/capture-fixtures.sh:17` `$here/../../..` → `$here/../..`.
   * `java/tools/parity/capture-java-ui.sh:12` `…/../../..` → `…/../..`.
   * `java/tools/parity/generate-fixtures.sh:14-15` — `java_dir=here/../..` becomes the repo root;
     keep two variables but set `repo="$java_dir"` (or rename).
   * `java/tools/parity/gallery.mjs:14`, `capture-golden.mjs:24`, `playwright.config.ts:5,32` —
     `path.resolve(here, '../../..')` → `'../..'` (and `cwd` at line 32 similarly).
   * `java/native/make-fixtures.sh:32-33` — after the move `repo_root` and `java_root` collapse;
     set `repo_root="$(cd "$here/../.." && pwd)"` → `"$(cd "$here/.." && pwd)"` and drop/merge
     `java_root`; fix the `node_modules` default at the same time (§3.3).
7. `java/README.md` (whole file): layout block, `cd java`, `java/tools/…`, `java/native/…`,
   "Java package root" paragraph; `README.md` build section; `CONTRIBUTING.md` build link;
   `docs/java-rewrite-plan.md` §0/§7.3 and `docs/java-rewrite/phase-*.md` links (historical
   reports can keep old paths, but the plan's live commands must change); `java/parity/*.md` and
   `java/native/README.md` command examples.
8. `.gitignore`: merge `java/.gitignore` rules (`target/`, `dist/`, `build/`, `.jqwik-database`,
   `*.class`) into the root `.gitignore`; delete `java/.gitignore`.
9. CI: no `.github/` today — nothing to update; any future workflow must call `./mvnw` from the
   root and be `workflow_dispatch`-only.

Effort: ~25 file edits + a repo-wide link/doc sweep, touching 8 shell/Node scripts and 8 test
files that other agents are actively editing. **Not recommended.**

---

## 9. Tests run for this preparation (counts)

Environment: `flock /tmp/gravaai-mvn.lock`, `cd java`, Maven offline (`-o`), `-T1`, JavaFX tests on
the isolated display (`DISPLAY=:99 -Dgravaai.test.display=:99`). No app image was launched and no
window was opened on the user's display `:0` (one `EditorSnapshotTest` run happened before the
Lead's `:99` policy message and opened JavaFX stages for ~8 s; everything after that used `:99`).

Targeted runs of the touched tests:

| Command scope | Result |
|---|---|
| `-pl gravaai-ui -am -Dtest=BundledAssetsTest` | 5 tests, 0 failures |
| `-pl gravaai-ui -am -Dtest=EditorSnapshotTest` | 3 tests, 0 failures (90 s timeout honoured) |
| `-pl gravaai-platform,gravaai-services,gravaai-media,gravaai-app -am -Dtest=PlatformSmokeTest,ControlSocketTest,ProjectFrameInputFactoryTest,AppIconsTest,RealProjectCompareDumpTest` | platform 3 skipped (property-gated smoke), services 7/7, media 5/5, app 2/2 + 1 skipped (env-gated) — 0 failures |

Full suite (final gate before writing this document):

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| gravaai-core | 322 | 0 | 0 | 0 |
| gravaai-platform | 68 | 0 | 0 | 3 (smoke, `-Dgravaai.smoke`) |
| gravaai-media | 484 | 0 | 0 | 3 (GL/ffmpeg assumptions) |
| gravaai-services | 607 | 0 | 0 | 1 (Electron TS-comparison guard) |
| gravaai-ui | 438 | 0 | 0 | 0 |
| gravaai-app | 22 | 0 | 0 | 1 (`GRAVAAI_PROJECT_COMPARE`) |
| **Total** | **1,941** | **0** | **0** | **8** |

Build: `BUILD SUCCESS` for all seven reactor modules (`./mvnw -o -T1 test`, 1:44 min).

---

## 10. Open questions / blockers for the reviewer

1. **Gate order.** The checklist in §4 is executable only after written approval of P3.1–P3.5.
   This preparation intentionally deleted nothing.
2. **Concurrent work.** The working tree contains other agents' in-flight changes when this was
   written (editor/dashboard/preview/export/extension work). The Lead must pick the commit that
   becomes the rollback point; do not `git add -A` blindly before checking `git status`.
3. **Gallery churn.** `java/parity/ui-java/*.png` are tracked and rewritten by every UI test run
   (including the full-suite run above). Decide whether the P3.6 commit includes the fresh
   gallery or restores it (`git checkout -- java/parity/ui-java`), and keep it out of the
   deletion commit if it is unrelated.
4. **Font/icon regeneration assets.** Regenerating the static fonts needs the two variable TTFs
   from `src/assets/fonts/dm-sans/`, and regenerating the icon SVGs needs the npm icon packages.
   Copy the 3 font files into `java/tools/fonts/source/` before deleting `src/` if reproduction
   matters (this task's write scope excludes `java/tools/`); otherwise record that committed
   outputs are the source of truth.
5. **`THIRD_PARTY_NOTICES.md` Voom entry** points at `services/gravaai-share/worker`, absent from
   the repo — confirm whether it is history or a stale entry before "only list what we bundle".
6. **`spike/`** is the JavaFX spike, not Electron; keep or delete at the reviewer's discretion
   (not required by §7.3).
7. **Move vs keep:** recommendation is keep `java/` (§8). If the reviewer moves it, schedule the
   move as its own commit **before** the deletion commit, never mixed with it.
8. **`--ozone-platform=headless`:** harmless (CLI ignores unknown args) but stale in user-facing
   MCP snippets; removing it belongs to the P3.2 owner.
