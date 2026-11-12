// Fixed-size frame canvas shared by the two screen-capture helpers; see the header.

#define _GNU_SOURCE
#include "capture-canvas.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

void capture_canvas_init(struct capture_canvas *canvas, uint32_t source_w, uint32_t source_h) {
	uint32_t width = source_w & ~1u;
	uint32_t height = source_h & ~1u;
	if (width < 2)
		width = 2;
	if (height < 2)
		height = 2;
	canvas->width = width;
	canvas->height = height;
	canvas->bytes = (size_t)width * (size_t)height * 4u;
}

/* True when the source is the canvas plus at most the one column/row the even rounding in
 * capture_canvas_init dropped. Such a source is cropped, not scaled: every odd-sized window would
 * otherwise be resampled by nearest neighbour on every frame, with a one-pixel black bar, for a
 * difference of a single pixel. */
static bool is_crop(const struct capture_canvas *canvas, uint32_t source_w, uint32_t source_h) {
	return source_w >= canvas->width && source_w - canvas->width <= 1
		&& source_h >= canvas->height && source_h - canvas->height <= 1;
}

/* The rect a source occupies inside the canvas, preserving aspect ratio. */
static void fit_rect(const struct capture_canvas *canvas, uint32_t source_w, uint32_t source_h,
		double *ox, double *oy, double *dw, double *dh) {
	if (is_crop(canvas, source_w, source_h)) {
		/* Drawn 1:1 from the top-left; the dropped odd column/row falls off the edge. */
		*ox = *oy = 0;
		*dw = source_w;
		*dh = source_h;
		return;
	}
	if (source_w == canvas->width && source_h == canvas->height) {
		*ox = *oy = 0;
		*dw = canvas->width;
		*dh = canvas->height;
		return;
	}
	double scale = fmin((double)canvas->width / (double)source_w,
		(double)canvas->height / (double)source_h);
	double content_w = (double)source_w * scale;
	double content_h = (double)source_h * scale;
	// Clamp after rounding so the content never leaves the canvas.
	if (content_w > canvas->width)
		content_w = canvas->width;
	if (content_h > canvas->height)
		content_h = canvas->height;
	*dw = content_w;
	*dh = content_h;
	*ox = ((double)canvas->width - content_w) / 2.0;
	*oy = ((double)canvas->height - content_h) / 2.0;
}

uint8_t *capture_canvas_compose(struct capture_canvas *canvas, uint8_t *dest,
		const uint8_t *source, size_t stride, uint32_t source_w, uint32_t source_h) {
	if (dest == NULL || source == NULL || source_w == 0 || source_h == 0)
		return NULL;

	double ox, oy, dw, dh;
	fit_rect(canvas, source_w, source_h, &ox, &oy, &dw, &dh);
	// Only a size match (or the odd-pixel crop, whose source is at least as large as the canvas)
	// may copy rows verbatim: a source with the canvas's aspect ratio but a smaller size also fits
	// the whole canvas, and copying its rows at the canvas width would read past the source buffer.
	if (is_crop(canvas, source_w, source_h)) {
		for (uint32_t y = 0; y < canvas->height; y++)
			memcpy(dest + (size_t)y * canvas->width * 4u, source + (size_t)y * stride,
				(size_t)canvas->width * 4u);
		return dest;
	}

	memset(dest, 0, canvas->bytes);
	uint32_t target_w = (uint32_t)(dw + 0.5);
	uint32_t target_h = (uint32_t)(dh + 0.5);
	uint32_t target_x = (uint32_t)(ox + 0.5);
	uint32_t target_y = (uint32_t)(oy + 0.5);
	if (target_w == 0 || target_h == 0)
		return dest;
	// Nearest-neighbour: this only runs when the window no longer matches the
	// first frame's size, and the recording is not an upscaler.
	for (uint32_t y = 0; y < target_h; y++) {
		uint32_t sy = (uint32_t)((uint64_t)y * source_h / target_h);
		if (sy >= source_h)
			sy = source_h - 1;
		uint32_t *row_out = (uint32_t *)(dest
			+ (size_t)(target_y + y) * canvas->width * 4u + (size_t)target_x * 4u);
		const uint8_t *row = source + (size_t)sy * stride;
		for (uint32_t x = 0; x < target_w; x++) {
			uint32_t sx = (uint32_t)((uint64_t)x * source_w / target_w);
			if (sx >= source_w)
				sx = source_w - 1;
			memcpy(row_out + x, row + (size_t)sx * 4u, 4u);
		}
	}
	return dest;
}

void capture_canvas_write_cursor(const struct capture_canvas *canvas, int32_t x, int32_t y,
		uint32_t source_w, uint32_t source_h) {
	if (canvas->width == 0 || canvas->height == 0 || source_w == 0 || source_h == 0)
		return;
	double nx = (double)x / (double)source_w;
	double ny = (double)y / (double)source_h;
	if (nx < 0 || ny < 0 || nx > 1 || ny > 1)
		return;
	double ox, oy, dw, dh;
	fit_rect(canvas, source_w, source_h, &ox, &oy, &dw, &dh);
	double cx = (ox + nx * dw) / (double)canvas->width;
	double cy = (oy + ny * dh) / (double)canvas->height;
	if (cx < 0)
		cx = 0;
	if (cy < 0)
		cy = 0;
	if (cx > 1)
		cx = 1;
	if (cy > 1)
		cy = 1;
	fprintf(stderr, "CURSOR %.6f %.6f\n", cx, cy);
	fflush(stderr);
}
