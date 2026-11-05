---
name: gravaai-animations
description: Build animated graphics in Gravaai videos through its MCP server — code-drawn 2D Canvas elements (title cards, lower thirds, counters, callouts, badges, particles), no-code motion presets and keyframe tracks, motion blur, Lottie, SkSL GPU shaders, embedded fonts and images, and 3D glTF models with lights and a camera. Use whenever the user asks for any animated graphic or motion in a Gravaai project — "add an animated title", "make an intro/outro", "make the logo spin", "3D model", "Lottie", "shader background", "animated counter", "callout that follows the zoom", "kinetic text", "confetti", "make something move" — or mentions elements/components in the gravaai MCP. Companion to gravaai-video-editing, which covers cuts, zooms, transcripts, captions and exports.
---

# Animated elements in Gravaai

Gravaai's animation system is Remotion-style **elements**: a COMPONENT is a JavaScript ES module
whose `render(ctx, state)` draws one frame on the editor's Skia canvas; an ELEMENT places that
component on the timeline with props, a box, and optional host-applied motion. You write the code
and place the instances through the gravaai MCP; everything renders live in the user's preview.

Two animation layers, and knowing which to use is most of the craft:

1. **In-component (code)** — the component draws something different every frame, using
   `interpolate`, `spring`, `Easing`, `random`. Use for anything that changes *inside* the graphic:
   counters, wipes, particles, kinetic text, 3D turns.
2. **Host motion (data)** — `motion` on the element: presets and keyframe tracks that animate the
   *whole box* (opacity, translateX/Y, scale, rotate) with zero code. Use for entrances, exits and
   movement of the graphic as a unit.

They compose: host motion transforms the box, the component draws inside it. Rule of thumb:
**motion moves the box, code animates the content** — don't stack both on the same property
(e.g. `spring-pop` motion on a component that already springs its own scale = double scale).

Elements persist in the project (undoable, listed in `inspect overview`), ride along when clips are
cut, and render in the preview, agent frames, and both the agent's and the app's exports.

## Setup

Same MCP server as editing: tools `inspect`, `edit`, `frames`, `project`, `job`. If they are
missing, follow the Setup section of the **gravaai-video-editing** skill (user enables
Settings → AI control; you register the server; connection errors mean it is off or the app is
closed — tell the user, don't hunt for sockets).

**The app is the source of truth.** `inspect {view:"help", topic:"elements"}` documents the exact
version you are driving. If it ever disagrees with this skill, trust the app.

Read these before the matching work (they are long; don't load them all up front):

| File | Read it before… |
|---|---|
| `references/component-authoring.md` | writing any custom component (module contract, runtime API, Canvas 2D surface, performance, errors) |
| `references/motion-and-space.md` | using `motion`, `motionBlur` or `space` on an element |
| `references/assets-lottie-shaders.md` | embedding files, Lottie, fonts, or writing SkSL shaders |
| `references/3d.md` | any `scene3d` call or 3D model work |

## The loop

1. **Orient.** `inspect` (overview) shows element counts, `element errors` and `element render
   time` lines when present. `inspect {view:"timeline"}` lists placed elements with their `e` ids.
   If no project is open, `inspect {view:"library"}` lists the user's projects and recordings —
   open one with `project {action:"open", path}`. When several could be "the video", ask which;
   otherwise the most recently modified is the sane default (say which you picked).
2. **See what exists.** `inspect {view:"components"}` lists built-ins and project components with
   fps, duration and props schema. `inspect {view:"components", topic:"gravaai/title-card"}`
   returns a component's full source — read a built-in, then fork it.
3. **Choose an approach** (see Decision guide).
4. **Get a component** — built-in as-is, forked under a new name, or written from scratch with
   `component {name, source}`. It is compiled and test-rendered (0%, 50%, last frame + a
   determinism check) before the batch commits; failures come back with `line:column`.
5. **Place it** with `element {…}` in the same batch, and do several related edits in one batch.
6. **Review** with a contact sheet over the element span: `frames {from, to, count:6}`. The sheet
   shows the animation curve; the returned images arrive in the order of the listed timestamps.
   For a pinpoint check of one instant use `frames {at:[2.5]}` — `from`/`to` need `to > from`.
   Fix the component with `component` (same name) and look again.
7. **Export only when asked.** Exports fail while any element has an error — check
   `inspect overview` first.

## Ops (inside `edit` batches, atomic, with `rev`)

```
component {name, source, description?}   create/replace a project component (validated before commit)
delete_component {name}                  refused while elements use it

element {component, from, to?, x?, y?, w?, h?, props?, layer?, space?, motion?, motionBlur?}
update_element {id, …same fields…}
delete_element {id} | {all:true}

asset {name, path, kind?}                embed a local file (image | lottie | font | model)
delete_asset {name}
```

Semantics that are easy to get wrong:

- **Geometry:** `x`,`y` = centre; `w`,`h` = size; all 0–1 fractions of the frame; default is the
  whole frame. `w`/`h` up to 4, and `x`/`y` may be negative or >1 — the box can hang off-frame.
- **Duration:** `to` defaults to `from + durationInFrames/fps` seconds. `from` must be before the
  end of the timeline; `to` clamps to it.
- **`render`'s `durationInFrames` follows the placed span** (`span_seconds * fps`), not the module
  export — so exit curves written against `durationInFrames` adapt when the element is resized.
  The exported `durationInFrames` only sets the default placement length.
- **`props`** are validated against the component's `propsSchema`: unknown names, wrong types or
  out-of-range numbers fail the batch (a component without a schema accepts anything). Props merge
  **shallowly** over `defaultProps`; in `update_element` a `null` prop removes a key.
- **`layer`** is z-order among elements (whole number, default: on top of everything placed).
- **`motion` replaces** on update (`motion: []` clears it); it does not merge.
- **`update_element` with only `from`** shifts the element, keeping its duration.
- Component names: letters, digits, `-`, `_`, `/` (max 64). The `gravaai/` prefix is reserved.
- Components and assets live **in the project file** — a new project starts with only the
  built-ins.

## Decision guide

| The user wants… | Do this |
|---|---|
| A standard title / lower third / counter / callout | Built-in component + `props` |
| A built-in with different behaviour | Fork: read its source via `inspect components topic:…`, `component {name:"my-…", source}` with edits |
| Movement of the whole graphic only (enter/exit/slide/spin) | Any component + `motion` presets or tracks — no code |
| A new 2D graphic (badge, chart, particles, kinetic text) | Custom component (Canvas 2D) — read `references/component-authoring.md` |
| An existing animation file (After Effects-style JSON) | `asset {kind:"lottie"}` + `gravaai/lottie` |
| An animated background / full-frame gradient art | `gravaai/shader-backdrop`, or custom SkSL via `shader()` |
| A logo/image/font/3D model from disk | `asset {name, path}` first, then reference it by name |
| Anything 3D (product, logo, shape) | `gravaai/model-3d` or a custom `scene3d()` call — read `references/3d.md` |
| Speed ramps / real motion graphics tied to screen content | Combine `space:"scene"` (follows zoom/pan) with custom code |

## Built-ins

`gravaai/title-card`, `gravaai/lower-third`, `gravaai/counter`, `gravaai/callout`,
`gravaai/lottie {asset}`, `gravaai/shader-backdrop {style: aurora|waves|plasma}`,
`gravaai/model-3d {model}`.

All of them self-animate (springs, wipes, fades are in their code). Fork instead of stacking
`motion` on top of what they already do.

## Recipes

Times are edited-timeline seconds. Every snippet is one `edit` batch with your current `rev`.

**Title card for the first 4 s** (built-in, its own spring entrance — no motion needed):
```json
[{"op":"element","component":"gravaai/title-card","from":0,"to":4,
  "props":{"title":"Quarterly Review","subtitle":"Q3 2026","accent":"#7c5cff"}}]
```

**Lower third that follows a zoom** (scene space: it stays glued to the screen content):
```json
[{"op":"element","component":"gravaai/lower-third","from":2,"to":8,
  "x":0.28,"y":0.85,"w":0.5,"h":0.18,"space":"scene",
  "props":{"name":"Ana Ribeiro","role":"Product Lead"}}]
```

**Callout pointing at a UI region** (`gravaai/callout` defaults to `space:"scene"`, so place the
box over the target and it tracks zoom/pan):
```json
[{"op":"element","component":"gravaai/callout","from":5,"to":9,
  "x":0.72,"y":0.3,"w":0.22,"h":0.16,"props":{"label":"New export","shape":"ellipse"}}]
```

**Static logo from disk, animated with no-code motion** (the two layers in their pure form — the
component never changes, `motion` does all the animation):
```json
[{"op":"asset","name":"logo","path":"/home/me/brand/logo.png"},
 {"op":"component","name":"logo-badge","source":"export const fps=30;\nexport const durationInFrames=90;\nexport const defaultProps={asset:'logo'};\nexport function render(ctx,{width,height,props}){const img=image(props.asset);const s=Math.min(width,height)*0.7;ctx.drawImage(img,(width-s)/2,(height-s)/2,s,s);}"},
 {"op":"element","component":"logo-badge","from":0,"to":3,
  "x":0.85,"y":0.12,"w":0.18,"h":0.2,
  "motion":["spring-pop"], "motionBlur":{"samples":8,"shutter":1.5}}]
```

**Animated counter** (built-in; the number, bar, colours and count duration are all props):
```json
[{"op":"element","component":"gravaai/counter","from":1,"to":5,
  "props":{"from":0,"to":98,"suffix":"%","label":"adoption","decimals":0,"countFrames":90}}]
```

**Shader backdrop behind a title** (two elements; `layer` puts the backdrop underneath):
```json
[{"op":"element","component":"gravaai/shader-backdrop","from":0,"to":5,"layer":0,
  "props":{"style":"aurora","colorA":"#3ddc97","colorB":"#6d3fd9"}},
 {"op":"element","component":"gravaai/title-card","from":0.4,"to":4.6,"layer":1,
  "props":{"title":"Gravaai 2.0","background":"rgba(0,0,0,0)"}}]
```

**3D turntable logo** (procedural shape, no assets needed; a `.glb` asset name works the same):
```json
[{"op":"element","component":"gravaai/model-3d","from":0,"to":5,
  "props":{"model":"gravaai:torus-knot","spin":50,"tilt":15,"tint":"#c9a6ff"}}]
```

**Review what you made:**
```json
frames {"from":0, "to":4, "count":6}
```
then read `inspect overview` for element errors and the slowest elements before any export.

## Quality gates

- **Contact sheet over the span** (`frames {from,to,count:6}`, count ≤ 9) — you should see the
  entrance, the hold and the exit. One frame tells you almost nothing about an animation.
- **`inspect overview`** lists `element errors` (drawn as a red box in preview; **exports fail**
  until fixed) and `element render time (avg/max ms per frame, budget 4)`. Judge slowness by the
  **avg** — a max far above it is usually one-time first-frame work (font/asset warm-up), not the
  steady-state cost. Fix slow components before exporting — see the performance section of
  `references/component-authoring.md`.
- Components are validated when written (compile + test renders + determinism). A component that
  only fails at certain props still passes validation — check `frames` with the real props.

## Pitfalls

- **Pure render or nothing.** `render` may be called in any order, on several threads, more than
  once. No module-level mutable state, no timers, promises, fetch, imports, DOM. `Date.now`,
  `performance.now` and `Math.random` are frozen/seeded per frame; prefer `random("seed")`.
- **Don't double-animate.** Built-ins animate themselves; host `motion` is for components that
  don't. Check which layer owns a property before adding the other.
- **Clamp your extrapolation.** `interpolate` extends past the range by default; entrances/exits
  need `extrapolateLeft:"clamp", extrapolateRight:"clamp"` or values blow past 0–1.
- **`measureText` and state reads flush the draw batch.** Cheap in setup, expensive in a hot loop —
  measure once per frame, not per particle.
- **Re-read after cuts *and* inserts.** Cutting clips ripples elements (they follow their
  footage), and placing an element earlier than existing ones shifts their `e` ids (ids are
  ordered by start time). Times and ids from an older read are stale after either. Same `STALE`
  discipline as any edit.
- **Shallow props merge.** Passing `props:{style:{...}}` replaces the whole nested object, it does
  not deep-merge.
- **Negative keyframe times** (`at:-0.5`) count back from the element's end — that's how you build
  exits that survive resizing.
- **Scene draws under screen.** Scene-space elements composite beneath screen-space elements and
  the webcam. A full-frame opaque backdrop in screen space makes every zoom-tracking callout
  invisibly hidden (no errors — it just never shows up). Put full-frame backdrops in
  `space:"scene"`, and remember a zoom only moves scene content — screen-space elements must be
  converted to ride a punch-in. Details in `references/motion-and-space.md`.
- **One component per project.** Components aren't shared across projects; copy the source into a
  new project if needed.
