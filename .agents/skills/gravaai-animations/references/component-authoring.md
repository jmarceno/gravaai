# Authoring element components

A component is a plain JS ES module evaluated in a sandboxed GraalJS context, once per element.
It exports metadata and one `render` function. No JSX, no imports, no DOM. The module is compiled
and test-rendered when you write it (frames at 0%, 50% and last, plus a determinism check), so
syntax errors and impure renders come back immediately, with `line:column`.

## Module contract

```js
export const fps = 30;               // nominal frame rate; only defines frame numbering + default length
export const durationInFrames = 90;  // default element length when placed without an explicit `to`
export const defaultProps = { title: "Hi" };
export const propsSchema = {         // optional; without it any JSON props are accepted
  title: "string",
  size:  { type: "number", min: 10, max: 200 },
  mode:  { type: "enum", values: ["a", "b"] },
};
export const description = "one line shown in inspect components";

export function render(ctx, { frame, fps, durationInFrames, timeMs, width, height, props, random }) {
  // draw one frame
}
```

Schema types: `string`, `number` (with `min`/`max`), `boolean`, `color` (CSS colour string),
`enum` (with `values`), `object`, `array`, `any`. The MCP validates every `element`/`update_element`
against this schema and **fails the batch** on unknown props, wrong types or out-of-range numbers —
that is deliberate: bad props should be caught at edit time, not seen as a wrong-looking frame.

`props` in render is `defaultProps` shallow-merged with the element's props. Nested objects are
replaced whole, never deep-merged — pass complete nested values.

## render() arguments

| Arg | Meaning |
|---|---|
| `frame` | **Fractional** frame number: `ms_since_element_start * fps / 1000`. Don't floor unless you want discrete steps (`Easing.steps` also exists). |
| `durationInFrames` | The **placed span** in frames (`element_seconds * fps`), *not* the module export. Write entrances/exits against it and they keep working when the element is resized on the timeline. |
| `timeMs` | Milliseconds since the element start (same clock as `frame`). |
| `width`, `height` | The element box in **design pixels**, as if the frame were 1920 wide. Sizes look identical at every export resolution. |
| `props` | Merged props (see above). |
| `random` | `random(seedString) → 0..1`, deterministic per element and seed (see below). |

`ctx` is translated to the box origin and **clipped to the box** — drawing outside it does nothing.
`ctx.setTransform` is relative to that base transform, not the canvas.

## Purity — the one rule everything else follows

`render` is called for frames **in any order, on several threads, possibly twice** for the same
frame (motion blur draws extra samples; the determinism check re-renders). Therefore:

- No mutable module-level state, no `setTimeout`/`setInterval`/`queueMicrotask` (they throw), no
  promises, no fetch, no imports, no DOM.
- `Date.now()`, `new Date()` and `performance.now()` are **frozen to the frame's time** — they work
  but always report the frame clock.
- `Math.random` is seeded per frame (stable within a frame, different every frame — good for
  shimmer/jitter that should change each frame).
- `random("seed")` returns the **same value for the same seed on every frame** — use it for things
  that must persist: particle positions, per-instance layout jitter. The `random` render argument
  and the global `random()` are both deterministic per element and seed, but they hash the seed
  differently, so `random("x")` ≠ `random("x")` across the two — pick one and use it consistently.

A component that throws draws a **red error box** in the preview and in `frames`, is listed under
`element errors` in `inspect overview`, and **fails exports**. Fix it by sending `component` again
with the same name, then look again with `frames`.

## Animation globals (Remotion semantics)

```js
interpolate(input, [in0, in1, …], [out0, out1, …], {
  easing?,                 // an Easing function
  extrapolateLeft?,  extrapolateRight?,   // "extend" (default) | "clamp" | "identity" | "wrap"
})
// Multi-stop ranges are supported; clamp at the edges for entrances/exits.

spring({ frame, fps, config?, from?, to?, delay?, durationInFrames?, reverse? })
// config: { mass:1, damping:10, stiffness:100, overshootClamping:false }
// Returns ~0→1 but can overshoot (>1 or <0) unless overshootClamping — clamp before using as opacity.
measureSpring({ fps, config? })  // frames until the spring settles — pair with durationInFrames

Easing.linear|ease|quad|cubic|sin|circle|exp|bounce
Easing.poly(n) Easing.back(s) Easing.elastic(b) Easing.bezier(x1,y1,x2,y2) Easing.steps(n)
Easing.in(f) Easing.out(f) Easing.inOut(f)

random(seed)                                  // deterministic 0..1
sequence(frame, from, durInFrames, fn)        // fn(localFrame) only inside the window
series(frame, [{durationInFrames, render(localFrame)}, …])  // back-to-back chapters
```

`sequence`/`series` are how you build multi-beat components (intro: wipe → text → underline) without
nested conditionals. Example:

```js
export function render(ctx, s) {
  series(s.frame, [
    { durationInFrames: 20, render: (f) => drawWipe(ctx, f, 20) },
    { durationInFrames: 60, render: (f) => drawText(ctx, f) },
  ]);
}
```

## Canvas 2D surface

`ctx` follows the HTML Canvas 2D API, backed by Skia. Supported:

- **State:** `fillStyle` `strokeStyle` (CSS colours, gradients, patterns, shaders) `lineWidth`
  `lineCap` `lineJoin` `miterLimit` `globalAlpha` `globalCompositeOperation` `shadowColor`
  `shadowBlur` `shadowOffsetX/Y` `font` `textAlign` `textBaseline` `filter` `lineDashOffset`
- **Transforms:** `save` `restore` `translate` `rotate` `scale` `transform` `setTransform`
  (relative to the element's base matrix) `resetTransform`
- **Paths:** `beginPath` `closePath` `moveTo` `lineTo` `quadraticCurveTo` `bezierCurveTo` `arc`
  `ellipse` `rect` `roundRect(x,y,w,h,r|[r…])` `fill` `stroke` `clip` `setLineDash` `getLineDash`
- **Rects/text:** `fillRect` `strokeRect` `clearRect` `fillText` `strokeText`
  `measureText` → `{width, actualBoundingBox*}`
- **Paint:** `createLinearGradient` `createRadialGradient` (`addColorStop`) `createPattern(img,"repeat")`
  `drawImage(img, x, y, w?, h?)`
- **Filters:** CSS filter syntax — `"blur(4px)"`, `"drop-shadow(4px 6px 8px rgba(0,0,0,.5))"`.

**Not available:** DOM, images from URLs, `putImageData`, `getImageData`.

Fonts: `ctx.font = "bold 48px sans-serif"` as usual; a **font asset** may be referenced by asset
name or family name (see `references/assets-lottie-shaders.md`).

## Performance

The 4 ms/frame budget (per element) is real: `inspect overview` prints avg/max render time per
element, and slow elements stall previews and exports.

- Draw calls are **batched** into a command buffer and replayed in one host call, so loops of
  `fillRect`/`arc`/`lineTo` are cheap — thousands of particles are fine.
- Anything that **reads state** (`measureText`, `ctx.fillStyle`, gradients' reads) or passes
  objects **flushes the batch first**. Measure once per frame before the loop; never inside it.
- Prefer pure numeric draw loops over per-particle string building (e.g. building a gradient per
  particle is a flush per particle).
- `blur(...)` filters and shadows are per-draw costs — a blurred particle field is much heavier
  than plain shapes.

## Worked example — progress ring with a counting centre

```js
export const fps = 30;
export const durationInFrames = 90;
export const defaultProps = { label: "Rendering", color: "#7c5cff", track: "rgba(255,255,255,0.15)" };
export const propsSchema = { label: "string", color: "color", track: "color" };
export const description = "Ring that fills 0→100% with a springy centre label";

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  const in_  = interpolate(frame, [0, 12], [0, 1], { extrapolateRight: "clamp" });
  const out  = interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0],
                             { extrapolateLeft: "clamp" });
  const p    = interpolate(frame, [6, durationInFrames - 6], [0, 1],
                             { extrapolateLeft: "clamp", extrapolateRight: "clamp",
                               easing: Easing.out(Easing.cubic) });
  const cx = width / 2, cy = height / 2, r = Math.min(width, height) * 0.38;

  ctx.globalAlpha = Math.min(in_, out);
  ctx.lineWidth = r * 0.16; ctx.lineCap = "round";
  ctx.strokeStyle = props.track;
  ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
  ctx.strokeStyle = props.color;
  ctx.beginPath(); ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + p * Math.PI * 2); ctx.stroke();

  ctx.fillStyle = props.color;
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.font = "bold " + Math.round(r * 0.5) + "px sans-serif";
  ctx.fillText(Math.round(p * 100) + "%", cx, cy - r * 0.1);
  ctx.font = Math.round(r * 0.18) + "px sans-serif";
  ctx.globalAlpha = Math.min(in_, out) * 0.7;
  ctx.fillText(props.label, cx, cy + r * 0.35);
}
```

## Worked example — confetti burst (batching + seeded randomness)

```js
export const fps = 30;
export const durationInFrames = 75;
export const defaultProps = { count: 220, colors: ["#ff3b6b", "#ffd23f", "#3ddc97", "#7c5cff"] };
export const propsSchema = { count: { type: "number", min: 10, max: 2000 }, colors: { type: "array" } };

export function render(ctx, { frame, fps, durationInFrames, width, height, props, random }) {
  const t = frame / fps;                       // seconds since start
  for (let i = 0; i < props.count; i++) {
    // Layout is seeded: same confetti every frame, only physics move.
    const x0  = random("x" + i),  burst = random("v" + i) * 0.5 + 0.5;
    const ang = random("a" + i) * Math.PI * 2, spin = random("s" + i) * 10 - 5;
    const life = 1.6 + random("l" + i) * 0.8;
    const k = t / life;
    if (k >= 1) continue;
    const x = width / 2 + Math.cos(ang) * burst * width * 0.45 * Math.min(t * 2, 1);
    const y = height * 0.45 + Math.sin(ang) * burst * height * 0.4 * Math.min(t * 2, 1)
            + 900 * k * k;                                     // gravity, design px
    const fade = 1 - k;
    const size = 6 + random("z" + i) * 8;
    ctx.save();
    ctx.globalAlpha = fade;
    ctx.translate(x, y);
    ctx.rotate(spin * t + ang);
    ctx.fillStyle = props.colors[i % props.colors.length];
    ctx.fillRect(-size / 2, -size / 4, size, size / 2);        // batched: cheap in a loop
    ctx.restore();
  }
}
```

Note the pattern: every per-piece constant comes from `random("prefix" + i)` so the render stays a
pure function of the frame; `Math.random()` would change between the blur samples and the
determinism check.
