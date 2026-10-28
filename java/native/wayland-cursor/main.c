// Global cursor position and click monitor for Wayland sessions.
//
// Wayland gives ordinary clients no global pointer position or click events,
// so uiohook (X11) sees nothing there. This helper combines:
//   - ext-image-copy-capture pointer cursor sessions, one per output, which
//     report the cursor hotspot position in that output's buffer pixels, and
//   - evdev mouse buttons read straight from /dev/input (needs the `input` group).
//
// Output lines (stdout):
//   POS:<x>:<y>                     global logical coordinates
//   INTERACTION:mousedown:<1|2|3>   1 = left, 2 = right, 3 = middle
//   INTERACTION:mouseup
//   KEY:down:<code>           evdev keycode, only with --keys/--keys-only
//   KEY:up:<code>             (auto-repeat is suppressed)
//   READY
// The helper exits when stdin closes.

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <wayland-client.h>

#include "ext-image-capture-source-v1-client.h"
#include "ext-image-copy-capture-v1-client.h"
#include "xdg-output-unstable-v1-client.h"

#define MAX_OUTPUTS 16
#define MAX_INPUTS 64

struct output {
	struct wl_output *wl;
	struct zxdg_output_v1 *xdg;
	struct ext_image_capture_source_v1 *source;
	struct ext_image_copy_capture_cursor_session_v1 *cursor;
	int32_t transform;
	int32_t mode_w, mode_h;
	int32_t logical_x, logical_y, logical_w, logical_h;
};

static struct wl_seat *seat;
static struct wl_pointer *pointer;
static struct zxdg_output_manager_v1 *xdg_output_manager;
static struct ext_output_image_capture_source_manager_v1 *source_manager;
static struct ext_image_copy_capture_manager_v1 *capture_manager;
static struct output outputs[MAX_OUTPUTS];
static int output_count;

static int input_fds[MAX_INPUTS];
static char input_paths[MAX_INPUTS][300];
static int input_count;
static bool input_is_mouse[MAX_INPUTS];
static bool input_is_keyboard[MAX_INPUTS];

// Without flags this is exactly the mouse/cursor monitor it has always been.
// --keys additionally opens keyboards and reports KEY lines; --keys-only
// leaves out the cursor sessions and mouse buttons.
static bool want_keys;
static bool want_mouse = true;

static void emit(const char *line) {
	fputs(line, stdout);
	fputc('\n', stdout);
	fflush(stdout);
}

// --- wl_output -------------------------------------------------------------

static void output_geometry(void *data, struct wl_output *wl, int32_t x, int32_t y, int32_t pw,
	int32_t ph, int32_t subpixel, const char *make, const char *model, int32_t transform) {
	struct output *o = data;
	o->transform = transform;
	if (!o->logical_w) {
		o->logical_x = x;
		o->logical_y = y;
	}
}

static void output_mode(void *data, struct wl_output *wl, uint32_t flags, int32_t w, int32_t h,
	int32_t refresh) {
	struct output *o = data;
	if (flags & WL_OUTPUT_MODE_CURRENT) {
		o->mode_w = w;
		o->mode_h = h;
	}
}

static void output_done(void *data, struct wl_output *wl) {}
static void output_scale(void *data, struct wl_output *wl, int32_t factor) {}
static void output_name(void *data, struct wl_output *wl, const char *name) {}
static void output_description(void *data, struct wl_output *wl, const char *description) {}

static const struct wl_output_listener output_listener = {
	.geometry = output_geometry,
	.mode = output_mode,
	.done = output_done,
	.scale = output_scale,
	.name = output_name,
	.description = output_description,
};

// --- xdg_output ------------------------------------------------------------

static void xdg_logical_position(void *data, struct zxdg_output_v1 *xdg, int32_t x, int32_t y) {
	struct output *o = data;
	o->logical_x = x;
	o->logical_y = y;
}

static void xdg_logical_size(void *data, struct zxdg_output_v1 *xdg, int32_t w, int32_t h) {
	struct output *o = data;
	o->logical_w = w;
	o->logical_h = h;
}

static void xdg_done(void *data, struct zxdg_output_v1 *xdg) {}
static void xdg_name(void *data, struct zxdg_output_v1 *xdg, const char *name) {}
static void xdg_description(void *data, struct zxdg_output_v1 *xdg, const char *d) {}

static const struct zxdg_output_v1_listener xdg_output_listener = {
	.logical_position = xdg_logical_position,
	.logical_size = xdg_logical_size,
	.done = xdg_done,
	.name = xdg_name,
	.description = xdg_description,
};

// --- cursor session --------------------------------------------------------

static void cursor_enter(void *data, struct ext_image_copy_capture_cursor_session_v1 *s) {}
static void cursor_leave(void *data, struct ext_image_copy_capture_cursor_session_v1 *s) {}
static void cursor_hotspot(void *data, struct ext_image_copy_capture_cursor_session_v1 *s,
	int32_t x, int32_t y) {}

static void cursor_position(void *data, struct ext_image_copy_capture_cursor_session_v1 *s,
	int32_t x, int32_t y) {
	struct output *o = data;
	int32_t bw = o->mode_w, bh = o->mode_h;
	if (o->transform & 1) {
		int32_t t = bw;
		bw = bh;
		bh = t;
	}
	int32_t lw = o->logical_w ? o->logical_w : bw;
	int32_t lh = o->logical_h ? o->logical_h : bh;
	double sx = bw > 0 ? (double)lw / bw : 1.0;
	double sy = bh > 0 ? (double)lh / bh : 1.0;
	// Positions outside the buffer belong to a neighbouring output, which
	// reports them itself.
	if (x < 0 || y < 0 || (bw > 0 && x >= bw) || (bh > 0 && y >= bh)) return;
	char line[64];
	snprintf(line, sizeof line, "POS:%d:%d", (int)(o->logical_x + x * sx + 0.5),
		(int)(o->logical_y + y * sy + 0.5));
	emit(line);
}

static const struct ext_image_copy_capture_cursor_session_v1_listener cursor_listener = {
	.enter = cursor_enter,
	.leave = cursor_leave,
	.position = cursor_position,
	.hotspot = cursor_hotspot,
};

// --- seat / registry -------------------------------------------------------

static void seat_capabilities(void *data, struct wl_seat *s, uint32_t caps) {
	if ((caps & WL_SEAT_CAPABILITY_POINTER) && !pointer) pointer = wl_seat_get_pointer(s);
}

static void seat_name(void *data, struct wl_seat *s, const char *name) {}

static const struct wl_seat_listener seat_listener = {
	.capabilities = seat_capabilities,
	.name = seat_name,
};

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
	const char *interface, uint32_t version) {
	if (!strcmp(interface, wl_seat_interface.name) && !seat) {
		seat = wl_registry_bind(registry, name, &wl_seat_interface, version < 5 ? version : 5);
		wl_seat_add_listener(seat, &seat_listener, NULL);
	} else if (!strcmp(interface, wl_output_interface.name) && output_count < MAX_OUTPUTS) {
		struct output *o = &outputs[output_count++];
		o->wl = wl_registry_bind(registry, name, &wl_output_interface, version < 4 ? version : 4);
		wl_output_add_listener(o->wl, &output_listener, o);
	} else if (!strcmp(interface, zxdg_output_manager_v1_interface.name)) {
		xdg_output_manager = wl_registry_bind(registry, name, &zxdg_output_manager_v1_interface,
			version < 3 ? version : 3);
	} else if (!strcmp(interface, ext_output_image_capture_source_manager_v1_interface.name)) {
		source_manager = wl_registry_bind(registry, name,
			&ext_output_image_capture_source_manager_v1_interface, 1);
	} else if (!strcmp(interface, ext_image_copy_capture_manager_v1_interface.name)) {
		capture_manager =
			wl_registry_bind(registry, name, &ext_image_copy_capture_manager_v1_interface, 1);
	}
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {}

static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_global_remove,
};

// --- evdev -----------------------------------------------------------------

#define BITS_PER_LONG (sizeof(long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)

static bool has_bit(const unsigned long *bits, int bit) {
	return (bits[bit / BITS_PER_LONG] >> (bit % BITS_PER_LONG)) & 1;
}

static bool input_known(const char *path) {
	for (int i = 0; i < input_count; i++)
		if (!strcmp(input_paths[i], path)) return true;
	return false;
}

static void scan_inputs(void) {
	DIR *dir = opendir("/dev/input");
	if (!dir) return;
	struct dirent *entry;
	while ((entry = readdir(dir)) && input_count < MAX_INPUTS) {
		if (strncmp(entry->d_name, "event", 5)) continue;
		char path[300];
		snprintf(path, sizeof path, "/dev/input/%s", entry->d_name);
		if (input_known(path)) continue;
		int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0) continue;
		unsigned long keys[NLONGS(KEY_CNT)] = {0};
		if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keys), keys) < 0) {
			close(fd);
			continue;
		}
		bool mouse = has_bit(keys, BTN_LEFT);
		bool keyboard = has_bit(keys, KEY_A) && has_bit(keys, KEY_SPACE) && has_bit(keys, KEY_ENTER);
		if (!((want_mouse && mouse) || (want_keys && keyboard))) {
			close(fd);
			continue;
		}
		input_fds[input_count] = fd;
		input_is_mouse[input_count] = mouse;
		input_is_keyboard[input_count] = keyboard;
		snprintf(input_paths[input_count], sizeof input_paths[0], "%s", path);
		input_count++;
	}
	closedir(dir);
}

static void drop_input(int i) {
	close(input_fds[i]);
	input_count--;
	input_fds[i] = input_fds[input_count];
	memcpy(input_paths[i], input_paths[input_count], sizeof input_paths[0]);
	input_is_mouse[i] = input_is_mouse[input_count];
	input_is_keyboard[i] = input_is_keyboard[input_count];
}

static void read_input(int i) {
	struct input_event ev[64];
	for (;;) {
		ssize_t n = read(input_fds[i], ev, sizeof ev);
		if (n < 0) {
			if (errno != EAGAIN && errno != EINTR) drop_input(i);
			return;
		}
		if (n == 0) return;
		for (size_t k = 0; k < (size_t)n / sizeof ev[0]; k++) {
			if (ev[k].type != EV_KEY || ev[k].value == 2) continue;
			if (ev[k].code < BTN_MISC) {
				// Keycodes are only reported by keyboards and only with --keys.
				if (!want_keys || !input_is_keyboard[i]) continue;
				char keyLine[32];
				snprintf(keyLine, sizeof keyLine, "KEY:%s:%d", ev[k].value == 1 ? "down" : "up", ev[k].code);
				emit(keyLine);
				continue;
			}
			if (!want_mouse || !input_is_mouse[i]) continue;
			int button = ev[k].code == BTN_LEFT	? 1
				: ev[k].code == BTN_RIGHT	? 2
				: ev[k].code == BTN_MIDDLE	? 3
										: 0;
			if (!button) continue;
			if (ev[k].value == 1) {
				char line[40];
				snprintf(line, sizeof line, "INTERACTION:mousedown:%d", button);
				emit(line);
			} else {
				emit("INTERACTION:mouseup");
			}
		}
	}
}

// --- main ------------------------------------------------------------------

static double now_s(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--keys")) {
			want_keys = true;
		} else if (!strcmp(argv[i], "--keys-only")) {
			want_keys = true;
			want_mouse = false;
		}
	}
	struct wl_display *display = wl_display_connect(NULL);
	if (!display) {
		fprintf(stderr, "cannot connect to the Wayland display\n");
		return 1;
	}
	struct wl_registry *registry = wl_display_get_registry(display);
	wl_registry_add_listener(registry, &registry_listener, NULL);
	wl_display_roundtrip(display);
	wl_display_roundtrip(display);

	if (!want_mouse) {
		// The connection is kept (the poll loop needs its fd) but the cursor
		// sessions, the only source of POS lines, are not created.
	} else if (!pointer || !source_manager || !capture_manager) {
		fprintf(stderr, "compositor lacks ext-image-copy-capture cursor sessions\n");
	} else {
		for (int i = 0; i < output_count; i++) {
			struct output *o = &outputs[i];
			if (xdg_output_manager) {
				o->xdg = zxdg_output_manager_v1_get_xdg_output(xdg_output_manager, o->wl);
				zxdg_output_v1_add_listener(o->xdg, &xdg_output_listener, o);
			}
		}
		wl_display_roundtrip(display);
		for (int i = 0; i < output_count; i++) {
			struct output *o = &outputs[i];
			o->source = ext_output_image_capture_source_manager_v1_create_source(source_manager, o->wl);
			o->cursor = ext_image_copy_capture_manager_v1_create_pointer_cursor_session(
				capture_manager, o->source, pointer);
			ext_image_copy_capture_cursor_session_v1_add_listener(o->cursor, &cursor_listener, o);
		}
		wl_display_roundtrip(display);
	}

	scan_inputs();
	fprintf(stderr, "%s devices: %d\n", want_keys ? "input" : "mouse", input_count);
	emit("READY");

	double last_scan = now_s();
	for (;;) {
		struct pollfd fds[2 + MAX_INPUTS];
		int n = 0;
		fds[n++] = (struct pollfd){.fd = STDIN_FILENO, .events = POLLIN};
		fds[n++] = (struct pollfd){.fd = wl_display_get_fd(display), .events = POLLIN};
		for (int i = 0; i < input_count; i++)
			fds[n++] = (struct pollfd){.fd = input_fds[i], .events = POLLIN};

		while (wl_display_prepare_read(display) != 0) wl_display_dispatch_pending(display);
		wl_display_flush(display);

		if (poll(fds, n, 2000) < 0 && errno != EINTR) {
			wl_display_cancel_read(display);
			break;
		}

		if (fds[1].revents & POLLIN) {
			wl_display_read_events(display);
			wl_display_dispatch_pending(display);
		} else {
			wl_display_cancel_read(display);
		}
		if (fds[1].revents & (POLLERR | POLLHUP)) break;

		if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
			char buf[256];
			if (read(STDIN_FILENO, buf, sizeof buf) <= 0) break;
		}

		for (int i = n - 1; i >= 2; i--) {
			if (fds[i].revents & POLLIN) read_input(i - 2);
			else if (fds[i].revents & (POLLERR | POLLHUP)) drop_input(i - 2);
		}

		if (now_s() - last_scan > 2.0) {
			scan_inputs();
			last_scan = now_s();
		}
	}
	return 0;
}
