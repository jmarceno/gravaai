# Motion, motion blur and space

Host motion animates the **whole element box** — opacity, position, scale, rotation — evaluated by
the app from data on the element, on top of whatever the component draws. It exists so entrances,
exits and movement need no code. The transform happens around the box centre; translate is in
**percent of the output frame**, rotate in **degrees**.

The `motion` field is a list; each item is a preset name, a preset object, or a track. A bare
string means "in and out, 0.4 s each". Presets **expand into explicit keyframes** when stored, so
the curves are visible and tweakable in the editor's element inspector after the fact.

## Presets

`fade rise slide-left slide-right slide-up slide-down spring-pop zoom spin`

| Preset | In (from → rest) | Out (rest → to) |
|---|---|---|
| `fade` | opacity 0→1, ease-out-cubic | mirrored, ease-in-cubic |
| `rise` | fade + translateY +4%→0 | mirrored |
| `slide-left` / `slide-right` | fade + translateX ∓12%→0, ease-out-expo | mirrored |
| `slide-up` / `slide-down` | fade + translateY ±12%→0, ease-out-expo | mirrored |
| `spring-pop` | opacity 0→1 (half duration) + scale 0.5→1, **spring** out | scale →0.5, ease-in-back |
| `zoom` | fade + scale 1.2→1 | mirrored |
| `spin` | fade + rotate −90°→0 + scale 0.6→1, ease-out-back | mirrored, ease-in-back |

Options per preset: `{"preset":"rise", "in":true, "out":true, "duration":0.4}` — `duration` is
seconds (0–30]. If the preset can't fit in the element (in+out > span), the app compresses it to
half/whole the span rather than failing, so short elements still animate sanely.

## Tracks (full control)

```json
{"property":"rotate", "keyframes":[{"at":0,"value":-8},{"at":1,"value":0,"easing":"spring"}]}
```

- `property`: `opacity` (0–1, clamped) · `translateX`/`translateY` (percent of the frame; negative
  = left/up) · `scale` (factor, ≥ 0) · `rotate` (degrees, clockwise).
- `at`: seconds **from the element start**; **negative counts back from the element end** — exits
  written this way keep working when the element is resized. (`at:0` with `"fromEnd":true` is
  equivalent.)
- `easing` shapes the segment that **ends** at that key. Names: `linear`, `ease`, `ease-in`,
  `ease-out`, `ease-in-out`, `ease-{in|out|in-out}-{quad|cubic|quart|quint|sine|circ|expo|back|elastic|bounce}`,
  `bounce`, `spring`, `cubic-bezier(x1,y1,x2,y2)`. Unknown names fail the batch with the accepted
  list. `spring` in a track is a precomputed spring curve (no overshoot toggles).
- Values **hold** before the first and after the last key (clamp). Two keys at the same resolved
  time = a cut.
- Multiple tracks (or presets) are combined **per property**: keyframes for the same property are
  concatenated into one curve. So `"fade"` (in) + `{"preset":"slide-down","in":false,"out":true}`
  yields one opacity curve with both ramps.

A typical no-code build:

```json
"motion": ["fade",
           {"preset":"slide-up","out":false,"duration":0.6},
           {"property":"rotate","keyframes":[{"at":0,"value":-8},{"at":1,"value":0,"easing":"spring"}]}]
```

**Motion replaces on update** — `update_element {id, motion:[…]}` rewrites the whole list;
`motion: []` clears it. It does not merge with the previous motion.

## Motion blur

```json
"motionBlur": {"samples":8, "shutter":1.5}   // samples 2–16, shutter 0–4 frames
"motionBlur": true                            // = 6 samples, 1 frame
"motionBlur": false                           // off (default)
```

The element is drawn `samples` times across `shutter` frame intervals at its nominal fps, oldest
faintest (≈55%·k strength), newest at full strength. Costs `samples`× draws — keep samples ≤ 8
unless the shot demands it. With host motion, trails are drawn **only while the motion pose is
actually changing** within the shutter window (a resting element costs one draw). Without host
motion, the component's own animation is what gets trailed — that is how you blur a fast
code-animated wipe too. Blur needs real speed: on a 0.4 s entrance it is barely visible; on a
12%-frame slide it reads beautifully.

## Space: screen vs scene

| | `screen` (default) | `scene` |
|---|---|---|
| Transform | Fixed overlay on the output frame | Inside the camera: **follows zoom and pan** |
| Use for | Titles, badges, lower thirds, backdrops, anything "on the video" | Callouts, highlights, labels attached to screen content |
| Draw phase | After annotations | After the cursor, inside the scene transform |

`gravaai/callout` defaults to `space:"scene"`; everything else defaults to `screen`. A scene-space
element placed over a button stays glued to that button while an `auto_zoom` zooms in — that is the
single most common reason to reach for it. Screen-space is right when the graphic is a layer of the
video, not a part of the recorded screen.

## Layer, geometry and span

- `layer` (z-order among elements): whole number −1000…1000; higher draws on top; default places
  the new element above all existing ones. Use `layer:0` for backdrops under a full-frame element.
- Box: `x`/`y` centre, `w`/`h` size, 0–1 frame fractions. Sizes up to 4 and centres from −2 to 3
  are allowed — the box may hang off-frame (a slide-in from outside the frame is
  `motion:[{"property":"translateX","keyframes":[{"at":0,"value":-30},{"at":0.5,"value":0}]}]`
  on a box already at the left edge).
- `element` clamps `to` to the timeline end and needs `from` before it; minimum span is tiny
  (10 ms), but an element shorter than its entrance looks broken — keep spans ≥ preset duration.
- Elements **ripple with clip cuts**: they follow the footage they were placed over. Re-read
  (`inspect {view:"timeline"}`) after any cut before further element edits.
- Elements draw **above** the recording, annotations and captions in their phase; among themselves
  by `layer`, then start time.
