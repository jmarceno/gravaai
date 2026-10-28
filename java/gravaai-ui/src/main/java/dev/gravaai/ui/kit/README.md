# Gravaai UI kit (P1.3)

JavaFX port of the React design system (plan §5.12, §6.3, §7.1 P1.3). The classes mirror the
HeroUI/shadcn components in `src/components/ui/*` and the launch CSS modules, so a screen port can
be diffed against its React original line by line.

## Using it

```java
// 1. Fonts once, before any Scene.
dev.gravaai.ui.AppFonts.load();

// 2. Every window's root is a KitRoot: popovers/dialogs/toasts are in-window nodes, not OS windows.
KitRoot root = new KitRoot(content);
Scene scene = new Scene(root, 1280, 800);
Theme.install(scene);

// 3. Components
Button save = new Button("Save");
Button bold = new Button("", Button.Variant.SECONDARY, Button.Size.ICON).icon(Icons.Play);
ToggleGroup align = new ToggleGroup(ToggleGroup.Mode.SINGLE);
align.item("left", "Left", Icons.AlignLeft);
Switch monitor = new Switch("Show webcam").onCheckedChange(checked -> {});
Slider volume = new Slider("Volume").range(0, 100).sliderControl();
Select quality = new Select();
quality.placeholder("Quality").item("1080", "1080p").item("720", "720p");
Toast.show(scene.getRoot(), "Recording saved").variant(Toast.Variant.SUCCESS);
```

Showcase (visual gate): `dev.gravaai.ui.kit.UiKitShowcase.show(Stage)` — wired to
`gravaai --ui-kit` by the app module. `createScene()` returns the scene for TestFX/snapshot use.
Development harness: `dev.gravaai.ui.kit.UiKitShowcase.Main` (inner class).

```bash
# Run the showcase from a built module (development):
java -cp "gravaai-ui/target/classes:...javafx jars" 'dev.gravaai.ui.kit.UiKitShowcase$Main'
# Screenshots (plan gotcha 8):
cosmic-screenshot --interactive=false --notify=false -s <dir>
# Optional harness properties:
#   -Dgravaai.uiKit.scroll=bottom|<0..1>   scroll the gallery before the screenshot
#   -Dgravaai.uiKit.demo=overlays          auto-open two toasts
```

## Components (package `dev.gravaai.ui.kit`)

| Class | React source | HeroUI CSS |
|---|---|---|
| `Button` (`Variant`, `Size`, `IconSize`) | `ui/button.tsx` | `components/button.css` |
| `Label` (`required()`, `invalid()`) | `ui/label.tsx` | `label.css` |
| `Input`, `TextArea` | `ui/input.tsx` | `input.css`, `textfield.css`, `textarea.css` |
| `Card` (`Header`, `Title`, `Description`, `Content`, `Footer`) | `ui/card.tsx` | `card.css` |
| `Separator` (`Tone`, `Orientation`, `vertical()`) | `ui/separator.tsx` | `separator.css` |
| `Toggle` (`Variant`, `Size`) | `ui/toggle.tsx` | `toggle-button.css` |
| `ToggleGroup` (`Mode.SINGLE/MULTIPLE`, `detached()`) | `ui/toggle-group.tsx` | `toggle-button-group.css` |
| `Switch` (`Size`) | `ui/switch.tsx` | `switch.css` |
| `Slider` (SliderControl layout via `sliderControl()`) | `ui/slider.tsx`, `video-editor/SliderControl.tsx` | `slider.css` |
| `Select` (`item`, `group`, `separator`, `value`) | `ui/select.tsx` | `select.css`, `list-box-item.css` |
| `DropdownMenu` (`item`, `dangerItem`, `label`, `separator`) | `ui/dropdown-menu.tsx` | `dropdown.css`, `menu.css`, `menu-item.css` |
| `Popover` | `ui/popover.tsx` | `popover.css` |
| `Tooltip`, `HudTooltip` | `ui/tooltip.tsx`, `launch/HudTooltip.tsx` | `tooltip.css` |
| `Dialog` (in-window modal + backdrop) | `ui/dialog.tsx` | `modal.css` |
| `Tabs` | `ui/tabs.tsx` | `tabs.css` |
| `Accordion` | `ui/accordion.tsx` | `accordion.css` |
| `Skeleton` (`pulse()`) | `ui/skeleton.tsx` | `skeleton.css` |
| `Toast` (`Variant`) | `ui/toast.tsx` | `toast.css` |
| `ChoiceGroup` (`item`) | `ui/choice-group.tsx` | `tag.css` |
| `ColorPicker` (+ `ColorMath`) | `ui/color-picker.tsx` | `color-picker.css`, `color-area.css`, `color-slider.css` |
| `AudioLevelMeter` | `ui/audio-level-meter.tsx` | `meter.css` |
| `ContentClamp` | `ui/content-clamp.tsx` | tooltip |
| `MarqueeText` | `launch/MarqueeText.tsx` + `MarqueeText.css` | — |
| `Icon` + `Icons` | `ui/icons.tsx` (extracted SVGs) | — |
| `KitRoot`, `Kit`, `Theme`, `Motion`, `Anchors`, `Popup`, `ShowcaseStage` | infrastructure | — |
| `UiKitShowcase` | visual gate | — |

## Tailwind class → px mapping (used consistently)

Tailwind v4 default scale (`--spacing: 0.25rem`), 1 rem = 16 px. The app runs on desktop, so the
`md` (`min-width: 768px`) values from the HeroUI CSS are the ones used.

| Tailwind | px | | Tailwind | px |
|---|---|---|---|---|
| `p-0.5` / `m-0.5` | 2 | | `p-6` | 24 |
| `p-1` / `gap-1` | 4 | | `p-7` | 28 |
| `p-1.5` | 6 | | `p-8` | 32 |
| `p-2` / `gap-2` | 8 | | `p-10` | 40 |
| `p-2.5` | 10 | | `p-12` | 48 |
| `p-3` / `gap-3` | 12 | | `h-1`/`w-1` | 4 |
| `p-3.5` | 14 | | `h-4`/`size-4` | 16 |
| `p-4` / `gap-4` | 16 | | `h-5`/`size-5` | 20 |
| `p-5` | 20 | | `h-8`/`size-8` | 32 |

Radii come from `@heroui/styles` (`--radius: 0.5rem`): `rounded-xs` 2, `rounded-sm` 4,
`rounded-md` 6, `rounded-lg` 8, `rounded-xl` 12, `rounded-2xl` 16, `rounded-3xl` 24,
`rounded-4xl` 32, `rounded-field` 12 px.

Typography (Tailwind defaults): `text-xs` 12/16, `text-sm` 14/20, `text-base` 16/24,
`text-lg` 18/28, `text-xl` 20/28, `text-2xl` 24/32; weights 400/500/600/700.

### Component measurements

| Component | Tailwind source | px values |
|---|---|---|
| Button (md) | `h-9 px-4 gap-2 text-sm rounded-3xl` | h 36, px 16, gap 8, text 14/500, r 24 |
| Button sm / lg | `h-8 px-3` / `h-10 text-base` | 32/12 vs 40/16 |
| Button icon | `size-4` desktop (`size-3.5`/`size-5`/`size-6` variants) | 16 (14/20/24) |
| ToggleButton | same box as Button | h 36, px 16, r 24 |
| Input / Select trigger | `px-3 py-2 text-sm rounded-field` | px 12, py 8, r 12, min-h 36 |
| Label | `text-sm font-medium` | 14/500 |
| Card | `p-4 gap-3 rounded-3xl` | p 16, gap 12, r 24 |
| Popover | `p-0 rounded-3xl` | r 24, shadow overlay |
| Popover dialog | `p-4` | p 16 |
| Tooltip | `p-2 text-xs rounded-xl max-w-xs` | p 8, text 12, r 12, max-w 320 |
| Menu | `p-1 gap-0.5` | p 4, gap 2 |
| Menu item | `min-h-9 px-2 py-1.5 gap-3 rounded-2xl` | 36, 8/6, 12, r 16 |
| Select list item | `min-h-9 px-2 py-1.5 pe-7 rounded-2xl` | 36, r 16 |
| Dialog | `p-6 rounded-3xl` | p 24, r 24, max-w 512 (lg) |
| Tabs list container | `p-1 rounded-[calc(radius*2.5)]` | p 4, r 20 |
| Tab | `h-8 px-4 rounded-3xl text-sm font-medium` | 32, 16, r 24 |
| Accordion trigger | `px-4 py-4 text-sm font-medium` | 16/16 |
| Accordion body | `px-4 pb-4 text-sm` | 16 sides, 16 bottom |
| Switch (md) | track `h-5 w-10 rounded-xl`, thumb `h-4 w-[1.375rem] rounded-lg` | track 40x20 r12, thumb 22x16 r8, margins 2 → 16 |
| Switch sm / lg | `h-4 w-8` / `h-6 w-12` | 32x16 / 48x24 |
| Slider | track `h-5 rounded-xl`, thumb 28x20 (pill 24x16 r8) | inset 12, fill accent |
| Tag (ChoiceGroup) | `px-2 py-1 text-xs rounded-xl min-h-8` | 8/4, 12, r 12, 32 |
| Toast | `px-4 py-3 gap-1.5 rounded-3xl text-sm` | 16/12, 6, r 24, min-w 384 |
| Meter (sm) | track `h-1 rounded-xs` | h 4, r 2 |
| Separator | `h-px` / vertical `w-px min-h-2` | 1 px |
| Skeleton | `rounded-sm` | r 4, shimmer 2 s linear |
| Color area dialog | `w-64 p-4 gap-4` | 288 wide, 256x160 area |
| Scrollbar | `scrollbar-thin` | 8 px, thumb #2a2f38 |

## Tokens (`resources/kit/gravaai.css`, prefix `-g-`)

Source: `src/index.css` `.dark` block (the app's only theme) + `@heroui/styles`
`themes/default/variables.css` dark block. `oklch()` and `color-mix()` are resolved to sRGB
hex/rgba because JavaFX CSS has neither.

| Token | Value | Source |
|---|---|---|
| `-g-bg` | #121418 | `--background` |
| `-g-fg` | #ffffff | `--foreground` |
| `-g-surface` | #1b1e25 | `--surface` |
| `-g-surface-secondary` | #232833 | `--surface-secondary` |
| `-g-surface-tertiary` | #2a303b | `--surface-tertiary` |
| `-g-surface-hover` | #2a2d34 | mix(surface 92 %, fg 8 %, oklab) |
| `-g-overlay` | #1b1e25 | `--overlay` |
| `-g-default` / `-g-default-hover` | #232833 / #2a2f3a | `--default` / mix 96/4 |
| `-g-segment` | #343b48 | `--segment` |
| `-g-field` / `-g-field-hover` | #12151b / #16191f | `--field-background` |
| `-g-field-placeholder` | #6b7280 | `--field-placeholder` |
| `-g-border` / `-g-separator` | #2a303b | `--border` / `--separator` |
| `-g-accent` / `-g-accent-hover` | #2fb3a3 / #3fd4c1 | `--accent` / `--accent-hover` |
| `-g-accent-soft` | rgba(47,179,163,.13) | `--accent-soft` (13 %) |
| `-g-accent-soft-foreground` | #3fd4c1 | `--accent-soft-foreground` |
| `-g-success` / `-g-warning` / `-g-danger` | #3ecf8e / #e8b339 / #e5656e | status colours |
| `-g-success-soft` / `-g-warning-soft` / `-g-danger-soft` | rgba(...,.13/.15/.15) | soft variants |
| `-g-muted` | #8b929a | `--muted` |
| `-g-focus` | #2fb3a3 | `--focus: var(--accent)` |
| `-g-backdrop` | rgba(0,0,0,.6) | `--backdrop` (dark) |
| `-g-scrollbar-thumb` | #2a2f38 | `--scrollbar-thumb` |
| `-g-icon-color` | #ffffff | **JavaFX-only**: maps SVG `currentColor` |

Launch/source-selector aliases are ported as `-g-launch-*` and the `.source-selector-*` classes
(`launchTheme.css`, `SourceSelector.css`, `MarqueeText.css`).

## Icons

Extracted once from the existing `node_modules` by `java/tools/extract-icons.mjs` (Node +
`react-dom/server`), never hand-drawn:

```bash
node java/tools/extract-icons.mjs   # writes resources/kit/icons/** and manifest.txt
```

- Solar: 76 icons × {linear, bold} — the app's `icons.tsx` maps weight `regular` to linear and
  `fill`/`bold` to bold.
- Phosphor: `Export`, `GoogleLogo`, `XLogo` × every weight (regular, thin, light, bold, fill,
  duotone).
- Composite: `MicrophoneSlash` = the solar microphone glyph plus the slash path from
  `icons.tsx` (composed by the script, not drawn by hand).

`Icon.of("phosphor/Microphone", 16)` / `Icon.of("solar/add", 16, "bold")` /
`Icon.of("solar/bold/add", 16)`. Names for the `icons.tsx` exports live in `Icons`
(100 constants), e.g. `Icons.MicrophoneIcon` → `"solar/microphone"`. Colour comes from the
looked-up colour `-g-icon-color`, which parent controls set (a primary Button sets it to
`-g-accent-foreground`).

## Fonts

DM Sans from `src/assets/fonts/dm-sans` is a variable font; JavaFX does not apply variable axes,
so static instances are generated once and committed under `resources/kit/fonts/`:

```bash
/media/jmarceno/scratch/gravaai-fonttools/venv/bin/python java/tools/fonts/generate-fonts.py
```

Files: `DMSans-{Regular,Medium,SemiBold,Bold}.ttf` + `...Italic.ttf` (8 faces, OFL licence
copied alongside). The `opsz` axis is pinned to its default 9 pt (the UI runs at 12-14 px).
`AppFonts.load()` must run before the first Scene; CSS uses `-fx-font-family: "DM Sans"` with
numeric weights.

## Animations (`Motion`)

Read from the CSS/motion source; cubic-bezier curves map to `Interpolator.ofSpline`:

| Where | Duration | Easing | Source |
|---|---|---|---|
| Button/Toggle press scale | 250 ms | `ease` (0.25,0.1,0.25,1) | button.css `transform 250ms` |
| Button colour | 100 ms | `ease-out` (0,0,0.2,1) | button.css |
| Switch track colour | 250/200 ms | `ease` / `ease-out` | switch.css |
| Switch thumb margin | 300 ms | `--ease-out-fluid` (0.32,0.72,0,1) | switch.css |
| Slider thumb | 250 ms | `ease-out` | slider.css |
| Meter fill width | 300 ms | `ease-out` | meter.css |
| Popover/tooltip enter / exit | 150 / 100 ms | `ease` | popover.css |
| Tooltip delay | 1500 ms (`HudTooltip` 300 ms) | — | `--tooltip-delay` |
| Dialog enter / exit | 250 / 100 ms | `ease-out-quad` (0.25,0.46,0.45,0.94) | modal.css |
| Backdrop | 150 / 100 ms | `ease-out` | modal.css |
| Tabs indicator | 250 ms | `ease-out-fluid` | tabs.css |
| Accordion panel | 200 ms | height `ease-out-quad`, opacity `ease-out` | accordion.css |
| Skeleton shimmer | 2 s linear infinite | linear | `@keyframes skeleton` |
| Toast enter / exit / opacity | 350 / 250 / 150 ms | `ease-out-fluid` | toast.css |
| Marquee | 10 s linear infinite | linear | MarqueeText.css |
| Menu card in (HUD) | 240 ms | motion [0.22,1,0.36,1] | LaunchWindow.tsx |

## Overlays

All overlays are nodes inside the window (`KitRoot`), never OS windows (plan §5.12):

- `KitRoot.popoverLayer()` — Popover, DropdownMenu, Select, Tooltip, ColorPicker;
  `Popup` handles placement/flip (`Anchors`), outside-click and Escape, enter/exit animation.
- `KitRoot.dialogLayer()` — `Dialog` (backdrop + centred panel, Escape/backdrop dismiss).
- `KitRoot.toastLayer()` — `Toast` stack (bottom end, default 4 s; danger 8 s).

## Deviations / limits (report these to the reviewer)

- JavaFX CSS has no transitions, so discrete hover/pressed *colour* changes are instant; the
  durations that matter visually are implemented as `Transition`/`Timeline` in the components
  (listed above). Button press scale, switch thumb, tabs indicator, accordion, skeleton, toast,
  popover/dialog and marquee are animated.
- Focus is a JavaFX `:focused` state (no `:focus-visible` distinction), drawn as the HeroUI
  `ring-2 ring-focus` via layered backgrounds so it does not shift layout.
- Light theme is not ported: the app only ever used the dark palette ("the app's only theme",
  `src/index.css`).
- Toast stacking is a simple bottom-end stack; HeroUI's Sonner-style collapsed/expanded stack
  heights are not reproduced (toasts overlap-free, latest at the bottom).
- Popovers do not implement HeroUI's arrow, nested submenus or RTL placement.
- The showcase window is placed explicitly with `ShowcaseStage` on the widest screen because this
  machine's primary display is portrait; COSMIC may still re-position the window (status quo,
  plan §5.4).
