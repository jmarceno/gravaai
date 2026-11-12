// Fixed-size frame canvas shared by the two screen-capture helpers.
//
// The encoder reads rawvideo at one fixed size (`-s WxH`), but a recorded
// window can be resized while the capture runs: the compositor then
// re-announces the source's buffer size, and a frame stream whose size changed
// mid-file cannot be encoded. Both helpers therefore compose every frame into
// a canvas whose size is fixed by the first frame; content that does not match
// the canvas is scaled to fit and centred, and the rest is black.
//
// The same fit is what the pointer position is mapped through: a compositor
// reports the cursor inside the captured source, in source buffer pixels, so
// `capture_canvas_write_cursor` turns that into the 0..1 space of the canvas
// that the recorded video actually shows. The sidecar then keeps lining up
// with the frame across resizes.
//
// The source size is always passed in rather than cached: a resize announcement
// and the frames still in flight at the old size overlap, and only the caller
// knows which size a given buffer belongs to.
//
// Wire format (stderr, one line per reported position):
//   CURSOR <x> <y>        normalized 0..1 inside the recorded frame
//
// Shared by gravaai-wayland-capture (ext-image-copy-capture) and
// gravaai-portal-capture (PipeWire), so the Java side has one contract.

#ifndef GRAVAAI_CAPTURE_CANVAS_H
#define GRAVAAI_CAPTURE_CANVAS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct capture_canvas {
	uint32_t width, height;
	size_t bytes;
};

/* Fixes the canvas to the first frame's source size; it keeps that size for
 * the rest of the recording. Dimensions are rounded down to even values
 * because the yuv420p encoder rejects odd ones. */
void capture_canvas_init(struct capture_canvas *canvas, uint32_t source_w, uint32_t source_h);

/* Composes one tightly packed source frame into `dest` (which must hold
 * `canvas->bytes`) and returns it. `source_w`/`source_h` describe the source
 * buffer, which may differ from the canvas after a window resize. */
uint8_t *capture_canvas_compose(struct capture_canvas *canvas, uint8_t *dest,
	const uint8_t *source, size_t stride, uint32_t source_w, uint32_t source_h);

/* Reports a cursor position given in source buffer pixels as a CURSOR line in
 * canvas space; `source_w`/`source_h` describe the source the compositor
 * reported it against. Out-of-source positions (negative or beyond the buffer)
 * mean the pointer is outside the recorded source and are not reported. */
void capture_canvas_write_cursor(const struct capture_canvas *canvas, int32_t x, int32_t y,
	uint32_t source_w, uint32_t source_h);

#endif
