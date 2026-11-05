# Assets, Lottie, fonts and shaders

## The asset op

```
asset {name, path, kind?}      // kind: image | lottie | font | model (inferred from the extension)
delete_asset {name}
```

- `path` must be **absolute** and readable; the file is **embedded into the project** (hashed), so
  the project no longer depends on the original file. Max 24 MB per asset.
- Kinds by extension: image `.png .jpg .webp`, lottie `.json`, font `.ttf .otf`, model
  `.glb .gltf` (**with embedded buffers** — external .bin/texture references won't resolve).
- Name: 1–64 letters, digits, `-`, `_`, `.`. Re-adding an existing name replaces it.
- Assets belong to the project, like components. Components resolve assets **by name**, never by
  path — embed first, then reference.

Inside a component: `assets(kind)` lists the project's asset names of a kind (e.g.
`assets("image")`), which lets a component adapt to what is embedded (e.g. default to the first
logo).

## Images

```js
const img = image("logo");               // by asset name
ctx.drawImage(img, x, y);                // or (img, x, y, w, h) to scale
const pat = ctx.createPattern(img, "repeat");  // usable as fillStyle/strokeStyle
```

`image()` returns a drawable handle; it is not pixel-readable (no getImageData). Draw order vs 2D
commands is preserved (the batch is flushed before the image is placed).

## Lottie

`gravaai/lottie` plays a Lottie asset seeked by frame (Skottie). The global, for custom components:

```js
const anim = lottie("sticker");           // asset name
anim.duration; anim.fps; anim.width; anim.height;
anim.draw(ctx, seconds, x?, y?, w?, h?);  // seeks the timeline and draws into the box
```

Seek by computed time so the render stays a pure function of the frame — `seconds = (frame / fps) *
speed`, modulo `anim.duration` to loop. Contain-fit like the built-in does:

```js
const scale = Math.min(width / anim.width, height / anim.height);
anim.draw(ctx, seconds, (width - anim.width * scale) / 2, (height - anim.height * scale) / 2,
          anim.width * scale, anim.height * scale);
```

## Fonts

Embed `.ttf`/`.otf` as a font asset, then use it in `ctx.font` by asset name or family:

```js
ctx.font = "bold 64px 'Inter SemiBold'";   // family, or the asset name: "bold 64px my-font"
```

Falling back silently to sans-serif means the name didn't match — check spelling against
`assets("font")`.

## SkSL shaders

`shader(source, uniforms)` returns a **paint**: assign it to `fillStyle`/`strokeStyle` and fill a
rect (or clip a shape first). The GPU runs it per pixel — animated backgrounds and texture effects
cost almost nothing.

```js
const SOURCE = `
uniform float2 uSize;      // set to [width, height] by convention
uniform float  uTime;      // seconds * speed — the animation clock
half4 main(float2 coord) {
  float2 uv = coord / uSize;
  float v = 0.5 + 0.5 * sin(uv.x * 10.0 + uTime * 2.0);
  return half4(half3(mix(float3(0.1), float3(0.5, 0.2, 0.9), v)), 1.0);
}`;

ctx.fillStyle = shader(SOURCE, { uSize: [width, height], uTime: frame / fps });
ctx.fillRect(0, 0, width, height);
```

Contract:

- Entry point `half4 main(float2 coord)`; **`coord` is in design pixels** of the element box (0,0
  top-left).
- Uniform values: JS `number` → `float`; JS array → the uniform's declared size (`float2/3/4`).
  Every declared uniform must be supplied with the right size — a missing or wrong-size uniform
  fails the call, as does SKSL that doesn't compile.
- Time must come in as a uniform (`uTime`) — never from a clock (see purity rules in
  `references/component-authoring.md`).
- `gravaai/shader-backdrop` (aurora / waves / plasma) is a complete, forkable example including
  noise and palette mixing — read it with `inspect {view:"components", topic:"gravaai/shader-backdrop"}`.

Shaders are also how you make **animated textures for shapes**: set a clip (roundRect, circle,
text via composition), then fill with the shader paint.
