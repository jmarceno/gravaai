# Golden-frame fixture projects (P2.1, plan §6.2)

Ten `.gravaai` projects that exercise the compositor feature set used by the golden-frame parity
tests. They are generated, not hand-edited: `java/tools/parity/generate-fixtures.sh` patches the
committed real project `java/gravaai-core/src/test/resources/project/Untitled Project 1.gravaai`,
normalises it through the production `ProjectPersistence` and writes it through `ProjectDocument`.
That means each fixture loads through the same normaliser as a user project in both apps.

- `projects/*.gravaai` — the fixtures.
- `media/*` — shared short clips (generated once with our bundled software ffmpeg) plus the
  cursor/keystroke telemetry sidecars and the image-annotation PNG.
- `capture-plan.json` — the machines-readable plan used by `capture-fixtures.sh`: project, the
  capture times and the features each fixture covers.

## Media paths are absolute (P2.1 decision)

The fixtures reference the shared media under `java/parity/fixtures/media/` by **absolute path**,
per the Lead's P2.1 decision ("absolute paths are fine for local capture; a portable path scheme can
come later"). Regenerating the fixtures on another checkout rewrites those paths, so run
`java/tools/parity/generate-fixtures.sh` after moving the repository. The cursor and keystroke
sidecars live next to the video (`<videoPath>.cursor.json` / `<videoPath>.keys.json`), which is the
recording layout, so the app picks them up without extra project fields.

## Fixtures, capture times and coverage

| Project | Captures (s) | Covers |
|---|---|---|
| `01-wallpaper-blur` | 0.35, 0.9, 1.65 | wallpaper, background blur, linked padding, shadow, border radius, cursor style `macos`, click effect `spotlight`, captions `fade` |
| `02-padding-crop-aspect` | 0.5, 1.0, 1.6 | per-side padding, crop region, aspect ratio 9:16, border radius, cursor `tahoe`, click `ripple`, captions `rise` |
| `03-zoom-motion-blur` | 0.25, 0.6, 1.05, 1.35, 1.8 | two zoom regions (depth 5/3, different focus), zoom motion blur, cursor `tahoe-inverted`, click `echo`, captions `pop` |
| `04-cursor-windows11` | 0.5, 0.72, 1.25 | cursor `windows11`, click `spotlight` (second colour/scale), zoom, captions `none` |
| `05-cursor-dot-keystrokes` | 0.35, 0.75, 1.15, 1.55 | cursor `dot`, click `ripple` (large), keystroke overlay from `h264-1080p60.mp4.keys.json`, captions `fade` |
| `06-cursor-figma` | 0.5, 1.25 | cursor `figma`, click `none`, dark wallpaper, captions `rise` |
| `07-webcam-bottom-right` | 0.35, 0.9, 1.5 | webcam overlay (bottom-right preset, react-to-zoom), cursor `tahoe`, click `ripple`, captions `pop` |
| `08-webcam-layouts-fade` | 0.35, 0.82, 1.05, 1.55, 1.8 | webcam top-left preset, clip camera layouts `default` / `hide-camera` / `camera-only`, two `fade-black` clip transitions |
| `09-annotations` | 0.25, 0.6, 1.0, 1.5 | annotation types `text`, `image`, `figure` (arrow), `blur`, cursor `macos`, click `spotlight`, captions `fade` |
| `10-connected-zoom-classic-vp9` | 0.35, 0.6, 1.0, 1.25 | connected zooms, classic zoom mode, zoom motion blur, VP9 source decode |

Cursor styles: all six built-ins (`macos`, `tahoe`, `tahoe-inverted`, `windows11`, `dot`, `figma`).
Click effects: all four (`none`, `spotlight`, `ripple`, `echo`).
Caption animations: all four (`none`, `fade`, `rise`, `pop`).
Annotations, camera layouts, the fade transition and keystrokes: see the table.

The media are deliberately high-contrast test patterns (dark frame with a moving white rectangle), so
pixel diffs are sensitive to geometry, zoom and motion blur.

## Regenerate

```bash
java/tools/parity/generate-fixtures.sh
```

The script needs a JDK 27 (taken from `java/pom.xml`, override with `GRAVAAI_JAVA_HOME`) and the
built `gravaai-core` classes; it compiles `fixtures/GenerateFixtures.java` into
`/media/jmarceno/scratch/java-targets/parity-fixtures`. It is safe to re-run: it rewrites the
fixtures, the telemetry sidecars, the annotation PNG and `capture-plan.json`.

Capture the reference frames with `java/tools/parity/capture-fixtures.sh` (see
`java/parity/golden/README.md`). The capture run opens every fixture in the Electron app, which
rewrites the project (adds a generated `projectId`, its own number formatting) and drops `.bak` /
`.preview.png` artefacts next to it; the capture script restores `projects/` from a backup when it
ends, so re-run the generator or the capture script rather than editing the fixtures by hand.