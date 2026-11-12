// Behaviour checks for the capture canvas both helpers share.
//
// The canvas is what keeps a window recording encodable across a resize and what maps
// the compositor's pointer reading into the recorded frame, so these checks are the
// contract the Java side relies on. It is plain C with no compositor or PipeWire
// dependency: build it with build-native-helpers.sh --test, or by hand with cc and -lm.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "capture-canvas.h"

static int failures;

static void check(int condition, const char *what) {
	printf("%s %s\n", condition ? "ok  " : "FAIL", what);
	if (!condition)
		failures++;
}

static uint32_t pixel(const uint8_t *frame, uint32_t stride, uint32_t x, uint32_t y) {
	uint32_t value;
	memcpy(&value, frame + (size_t)y * stride + (size_t)x * 4u, sizeof value);
	return value;
}

int main(void) {
	struct capture_canvas canvas;
	uint8_t *source;
	uint8_t *destination;

	capture_canvas_init(&canvas, 800, 600);
	check(canvas.width == 800 && canvas.height == 600, "takes the first frame's size");
	check(canvas.bytes == 800u * 600u * 4u, "byte count matches the size");

	/* yuv420p rejects odd dimensions, so an odd window is rounded down once, here. */
	struct capture_canvas odd;
	capture_canvas_init(&odd, 801, 601);
	check(odd.width == 800 && odd.height == 600, "rounds odd dimensions down to even");
	capture_canvas_init(&odd, 1, 1);
	check(odd.width == 2 && odd.height == 2, "never goes below a 2x2 frame");

	source = malloc(1600u * 600u * 4u);
	destination = malloc(canvas.bytes);
	check(source != NULL && destination != NULL, "allocates test buffers");
	if (source == NULL || destination == NULL)
		return 1;

	/* A same-size frame is a straight copy: no resampling, no bars. */
	memset(source, 0xAB, 800u * 600u * 4u);
	uint8_t *composed = capture_canvas_compose(&canvas, destination, source, 800u * 4u, 800, 600);
	check(composed == destination, "returns the destination frame");
	check(pixel(destination, canvas.width * 4u, 0, 0) == 0xABABABABu
			&& pixel(destination, canvas.width * 4u, 799, 599) == 0xABABABABu,
		"same-size frame is copied verbatim");

	/* Same aspect ratio: the content fills the whole canvas, still no bars. */
	memset(source, 0xCD, 400u * 300u * 4u);
	capture_canvas_compose(&canvas, destination, source, 400u * 4u, 400, 300);
	check(pixel(destination, canvas.width * 4u, 0, 0) == 0xCDCDCDCDu
			&& pixel(destination, canvas.width * 4u, 799, 599) == 0xCDCDCDCDu,
		"same-aspect resize fills the canvas");

	/* A narrower window (400x600) fits to 400x600 and is centred horizontally. */
	memset(source, 0x11, 400u * 600u * 4u);
	capture_canvas_compose(&canvas, destination, source, 400u * 4u, 400, 600);
	check(pixel(destination, canvas.width * 4u, 10, 300) == 0,
		"the letterbox bar is black");
	check(pixel(destination, canvas.width * 4u, 400, 300) == 0x11111111u,
		"the content is horizontally centred");

	/* A wider window (1600x600) fits to 800x300 and is centred vertically. */
	memset(source, 0x22, 1600u * 600u * 4u);
	capture_canvas_compose(&canvas, destination, source, 1600u * 4u, 1600, 600);
	check(pixel(destination, canvas.width * 4u, 400, 10) == 0,
		"the pillarbox bar is black");
	check(pixel(destination, canvas.width * 4u, 400, 300) == 0x22222222u,
		"the content is vertically centred");

	/* A degenerate frame leaves the canvas untouched instead of reading past it. */
	memset(destination, 0x7F, canvas.bytes);
	check(capture_canvas_compose(&canvas, destination, source, 800u * 4u, 0, 600) == NULL,
		"refuses a zero-width source");

	free(source);
	free(destination);

	if (failures != 0)
		printf("%d check(s) failed\n", failures);
	return failures == 0 ? 0 : 1;
}
