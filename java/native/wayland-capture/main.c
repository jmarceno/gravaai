// Screen and window recorder source for COSMIC (and other compositors with
// ext-image-copy-capture), bypassing the xdg-desktop-portal screencast.
//
// Usage: gravaai-wayland-capture <output-name|window:<identifier>> <fps>
//        gravaai-wayland-capture --list           (prints OUTPUT <name> <x> <y> <w> <h>)
//        gravaai-wayland-capture --list-windows   (prints WINDOW <identifier> <app_id> <title>)
//
// Captures the named output, or the toplevel named by an ext-foreign-toplevel-list
// identifier, into shm buffers and writes raw frames to stdout at a constant
// <fps>, repeating the latest frame when nothing changed. Before the first frame
// it prints one line to stderr:
//   FORMAT <width> <height> <rgba|bgra> <transform>
// and, for a window capture, one normalized pointer line per reported position:
//   CURSOR <x> <y>            (0..1 inside the recorded window frame)
// stdin commands: "pause", "resume", "stop" (EOF also stops).
//
// A window can be resized while it is recorded, which changes the capture
// session's buffer size. The encoder reads a fixed-size raw stream, so frames
// are composed into a canvas whose size is fixed by the first frame; content
// that no longer matches the canvas is scaled to fit and centred, with black
// bars where it is smaller (see capture-canvas.h). The compositor's cursor
// position is mapped through the same fit, so cursor telemetry keeps matching
// the frames across a resize.
//
// Window capture needs two protocols beyond the monitor path:
// ext-foreign-toplevel-list-v1 to enumerate toplevels and their stable
// identifiers, and ext-foreign-toplevel-image-capture-source-v1 to capture
// one. COSMIC has both, and implements the cursor session for toplevel sources
// too. A compositor without the toplevel capture protocol records monitors as
// before and reports a clear error for a window request.

#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/timerfd.h>
#include <unistd.h>
#include <wayland-client.h>

#include "capture-canvas.h"
#include "ext-foreign-toplevel-list-v1-client.h"
#include "ext-image-capture-source-v1-client.h"
#include "ext-image-copy-capture-v1-client.h"
#include "xdg-output-unstable-v1-client.h"

#define MAX_OUTPUTS 16
#define MAX_TOPLEVELS 64
#define WINDOW_PREFIX "window:"

static struct wl_shm *shm;
static struct wl_seat *seat;
static struct wl_pointer *pointer;
static struct zxdg_output_manager_v1 *xdg_output_manager;
static struct ext_output_image_capture_source_manager_v1 *source_manager;
static struct ext_foreign_toplevel_image_capture_source_manager_v1 *toplevel_source_manager;
static struct ext_image_copy_capture_manager_v1 *capture_manager;
static struct wl_output *outputs[MAX_OUTPUTS];
static char output_names[MAX_OUTPUTS][64];
static int32_t output_geometry[MAX_OUTPUTS][4];
static int output_count;

// --- toplevels -------------------------------------------------------------

struct toplevel {
	struct ext_foreign_toplevel_handle_v1 *handle;
	char identifier[64];
	char app_id[128];
	char title[256];
	bool closed;
};

static struct ext_foreign_toplevel_list_v1 *toplevel_list;
static struct toplevel toplevels[MAX_TOPLEVELS];
static int toplevel_count;

static void toplevel_closed(void *data, struct ext_foreign_toplevel_handle_v1 *handle) {
	(void)handle;
	((struct toplevel *)data)->closed = true;
}

static void toplevel_done(void *data, struct ext_foreign_toplevel_handle_v1 *handle) {
	(void)data; (void)handle;
}

static void toplevel_title(void *data, struct ext_foreign_toplevel_handle_v1 *handle,
		const char *title) {
	(void)handle;
	snprintf(((struct toplevel *)data)->title, sizeof toplevels[0].title, "%s", title);
}

static void toplevel_app_id(void *data, struct ext_foreign_toplevel_handle_v1 *handle,
		const char *app_id) {
	(void)handle;
	snprintf(((struct toplevel *)data)->app_id, sizeof toplevels[0].app_id, "%s", app_id);
}

static void toplevel_identifier(void *data, struct ext_foreign_toplevel_handle_v1 *handle,
		const char *identifier) {
	(void)handle;
	snprintf(((struct toplevel *)data)->identifier, sizeof toplevels[0].identifier, "%s",
		identifier);
}

static const struct ext_foreign_toplevel_handle_v1_listener toplevel_handle_listener = {
	.closed = toplevel_closed,
	.done = toplevel_done,
	.title = toplevel_title,
	.app_id = toplevel_app_id,
	.identifier = toplevel_identifier,
};

static void toplevel_list_toplevel(void *data, struct ext_foreign_toplevel_list_v1 *list,
		struct ext_foreign_toplevel_handle_v1 *handle) {
	(void)data; (void)list;
	if (toplevel_count >= MAX_TOPLEVELS)
		return;
	struct toplevel *entry = &toplevels[toplevel_count++];
	entry->handle = handle;
	// Attached from this event: the initial identifier, title and app_id arrive
	// immediately after it, before the next roundtrip.
	ext_foreign_toplevel_handle_v1_add_listener(handle, &toplevel_handle_listener, entry);
}

static void toplevel_list_finished(void *data, struct ext_foreign_toplevel_list_v1 *list) {
	(void)data; (void)list;
}

static const struct ext_foreign_toplevel_list_v1_listener toplevel_list_listener = {
	.toplevel = toplevel_list_toplevel,
	.finished = toplevel_list_finished,
};

// --- registry / outputs ----------------------------------------------------

static void xdg_name(void *data, struct zxdg_output_v1 *x, const char *name) {
	snprintf(output_names[(intptr_t)data], sizeof output_names[0], "%s", name);
}
static void xdg_pos(void *data, struct zxdg_output_v1 *x, int32_t a, int32_t b) {
	output_geometry[(intptr_t)data][0] = a;
	output_geometry[(intptr_t)data][1] = b;
}
static void xdg_size(void *data, struct zxdg_output_v1 *x, int32_t w, int32_t h) {
	output_geometry[(intptr_t)data][2] = w;
	output_geometry[(intptr_t)data][3] = h;
}
static void xdg_done(void *data, struct zxdg_output_v1 *x) {}
static void xdg_desc(void *data, struct zxdg_output_v1 *x, const char *d) {}
static const struct zxdg_output_v1_listener xdg_listener = {
	.logical_position = xdg_pos,
	.logical_size = xdg_size,
	.done = xdg_done,
	.name = xdg_name,
	.description = xdg_desc,
};

static void seat_capabilities(void *data, struct wl_seat *s, uint32_t caps) {
	(void)data;
	if ((caps & WL_SEAT_CAPABILITY_POINTER) && pointer == NULL)
		pointer = wl_seat_get_pointer(s);
}
static void seat_name(void *data, struct wl_seat *s, const char *name) {
	(void)data; (void)s; (void)name;
}
static const struct wl_seat_listener seat_listener = {
	.capabilities = seat_capabilities,
	.name = seat_name,
};

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
	const char *interface, uint32_t version) {
	(void)data;
	if (!strcmp(interface, wl_shm_interface.name)) {
		shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
	} else if (!strcmp(interface, wl_seat_interface.name) && seat == NULL) {
		seat = wl_registry_bind(registry, name, &wl_seat_interface, version < 5 ? version : 5);
		wl_seat_add_listener(seat, &seat_listener, NULL);
	} else if (!strcmp(interface, wl_output_interface.name) && output_count < MAX_OUTPUTS) {
		outputs[output_count++] = wl_registry_bind(registry, name, &wl_output_interface, 1);
	} else if (!strcmp(interface, zxdg_output_manager_v1_interface.name)) {
		xdg_output_manager =
			wl_registry_bind(registry, name, &zxdg_output_manager_v1_interface, version < 3 ? version : 3);
	} else if (!strcmp(interface, ext_output_image_capture_source_manager_v1_interface.name)) {
		source_manager = wl_registry_bind(registry, name,
			&ext_output_image_capture_source_manager_v1_interface, 1);
	} else if (!strcmp(interface, ext_foreign_toplevel_image_capture_source_manager_v1_interface.name)) {
		toplevel_source_manager = wl_registry_bind(registry, name,
			&ext_foreign_toplevel_image_capture_source_manager_v1_interface, 1);
	} else if (!strcmp(interface, ext_foreign_toplevel_list_v1_interface.name)) {
		toplevel_list = wl_registry_bind(registry, name,
			&ext_foreign_toplevel_list_v1_interface, 1);
		ext_foreign_toplevel_list_v1_add_listener(toplevel_list, &toplevel_list_listener, NULL);
	} else if (!strcmp(interface, ext_image_copy_capture_manager_v1_interface.name)) {
		capture_manager =
			wl_registry_bind(registry, name, &ext_image_copy_capture_manager_v1_interface, 1);
	}
}
static void registry_remove(void *data, struct wl_registry *registry, uint32_t name) {}
static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_remove,
};

// --- capture session -------------------------------------------------------

static uint32_t buffer_w, buffer_h, shm_format = UINT32_MAX;
static bool session_ready, session_stopped;

struct slot {
	struct wl_buffer *buffer;
	uint8_t *data;
};
static struct slot slots[2];
static uint8_t *slot_map = MAP_FAILED;
static size_t slot_map_size;
// The size the current slots were allocated at. A window resize re-announces
// buffer_w/buffer_h first, so the two differ until new slots are allocated;
// frames still in the old buffers must be described by the old size.
static uint32_t slot_w, slot_h;
static int capturing_slot = 0;
static struct ext_image_copy_capture_frame_v1 *frame;
static uint32_t frame_transform;
static bool frame_done, frame_failed;
// Set by the session when it re-announces its buffer size (a window resize).
static bool size_changed;

// The fixed-size frame the encoder reads (see capture-canvas.h). The composed
// frame is double buffered so the writer can stream the previous one while the
// capture callback composes the next.
#define CANVAS_BUFFERS 2
static struct capture_canvas canvas;
static uint8_t *canvas_frames[CANVAS_BUFFERS];
static int latest_canvas = -1;
static const uint8_t *latest_frame;
static size_t latest_bytes;

static void session_buffer_size(void *data, struct ext_image_copy_capture_session_v1 *s,
	uint32_t w, uint32_t h) {
	(void)data; (void)s;
	if (buffer_w != w || buffer_h != h)
		size_changed = true;
	buffer_w = w;
	buffer_h = h;
}
static void session_shm_format(void *data, struct ext_image_copy_capture_session_v1 *s,
	uint32_t format) {
	(void)data; (void)s;
	bool wanted = format == WL_SHM_FORMAT_XRGB8888 || format == WL_SHM_FORMAT_ARGB8888 ||
		format == WL_SHM_FORMAT_XBGR8888 || format == WL_SHM_FORMAT_ABGR8888;
	if (wanted && shm_format == UINT32_MAX) shm_format = format;
}
static void session_dmabuf_device(void *data, struct ext_image_copy_capture_session_v1 *s,
	struct wl_array *device) {}
static void session_dmabuf_format(void *data, struct ext_image_copy_capture_session_v1 *s,
	uint32_t format, struct wl_array *modifiers) {}
static void session_done(void *data, struct ext_image_copy_capture_session_v1 *s) {
	session_ready = true;
}
static void session_stopped_cb(void *data, struct ext_image_copy_capture_session_v1 *s) {
	session_stopped = true;
}
static const struct ext_image_copy_capture_session_v1_listener session_listener = {
	.buffer_size = session_buffer_size,
	.shm_format = session_shm_format,
	.dmabuf_device = session_dmabuf_device,
	.dmabuf_format = session_dmabuf_format,
	.done = session_done,
	.stopped = session_stopped_cb,
};

static void frame_transform_cb(void *data, struct ext_image_copy_capture_frame_v1 *f,
	uint32_t transform) {
	frame_transform = transform;
}
static void frame_damage(void *data, struct ext_image_copy_capture_frame_v1 *f, int32_t x,
	int32_t y, int32_t w, int32_t h) {}
static void frame_time(void *data, struct ext_image_copy_capture_frame_v1 *f, uint32_t hi,
	uint32_t lo, uint32_t ns) {}
static void frame_ready(void *data, struct ext_image_copy_capture_frame_v1 *f) {
	frame_done = true;
}
static void frame_failed_cb(void *data, struct ext_image_copy_capture_frame_v1 *f,
	uint32_t reason) {
	frame_failed = true;
}
static const struct ext_image_copy_capture_frame_v1_listener frame_listener = {
	.transform = frame_transform_cb,
	.damage = frame_damage,
	.presentation_time = frame_time,
	.ready = frame_ready,
	.failed = frame_failed_cb,
};

// --- cursor session --------------------------------------------------------
//
// This helper owns the cursor session because it owns the frame transform: the
// compositor reports the pointer inside the captured window, and only this
// process knows how the window is fitted into the fixed canvas. COSMIC
// implements cursor sessions for toplevel sources as well as outputs.

// The cursor session starts before the first frame, so a position can arrive
// before the canvas exists; it is replayed once the canvas is ready.
static bool pending_cursor;
static int32_t pending_cursor_x, pending_cursor_y;

/* Emits the newest pointer position in canvas space. The compositor reports it
 * in the source's buffer pixels, against the size it currently captures at. */
static void flush_cursor(void) {
	if (!pending_cursor || canvas.width == 0)
		return;
	pending_cursor = false;
	capture_canvas_write_cursor(&canvas, pending_cursor_x, pending_cursor_y, buffer_w, buffer_h);
}

static void cursor_enter(void *data, struct ext_image_copy_capture_cursor_session_v1 *s) {
	(void)data; (void)s;
}
static void cursor_leave(void *data, struct ext_image_copy_capture_cursor_session_v1 *s) {
	(void)data; (void)s;
}
static void cursor_hotspot(void *data, struct ext_image_copy_capture_cursor_session_v1 *s,
	int32_t x, int32_t y) {
	(void)data; (void)s; (void)x; (void)y;
}
static void cursor_position(void *data, struct ext_image_copy_capture_cursor_session_v1 *s,
	int32_t x, int32_t y) {
	(void)data; (void)s;
	// The session starts before the first frame, so the initial position (and
	// the enter that precedes it) can arrive before the canvas exists. Keep the
	// newest raw position and replay it once the canvas is ready; without that a
	// pointer that stays still for a whole take never reports again.
	pending_cursor = true;
	pending_cursor_x = x;
	pending_cursor_y = y;
	if (canvas.width == 0)
		return;
	flush_cursor();
}
static const struct ext_image_copy_capture_cursor_session_v1_listener cursor_listener = {
	.enter = cursor_enter,
	.leave = cursor_leave,
	.position = cursor_position,
	.hotspot = cursor_hotspot,
};

static void request_frame(struct ext_image_copy_capture_session_v1 *session) {
	frame_done = frame_failed = false;
	frame = ext_image_copy_capture_session_v1_create_frame(session);
	ext_image_copy_capture_frame_v1_add_listener(frame, &frame_listener, NULL);
	ext_image_copy_capture_frame_v1_attach_buffer(frame, slots[capturing_slot].buffer);
	// The damaged region describes the buffer, which is still at the size the
	// slots were created at even when a newer size has been announced.
	ext_image_copy_capture_frame_v1_damage_buffer(frame, 0, 0, slot_w ? slot_w : buffer_w,
		slot_h ? slot_h : buffer_h);
	ext_image_copy_capture_frame_v1_capture(frame);
}

static void destroy_slots(void) {
	for (int i = 0; i < 2; i++) {
		if (slots[i].buffer != NULL) {
			wl_buffer_destroy(slots[i].buffer);
			slots[i].buffer = NULL;
		}
		slots[i].data = NULL;
	}
	if (slot_map != MAP_FAILED) {
		munmap(slot_map, slot_map_size);
		slot_map = MAP_FAILED;
	}
	slot_map_size = 0;
}

static bool create_slots(void) {
	destroy_slots();
	size_t stride = (size_t)buffer_w * 4, size = stride * buffer_h;
	int fd = memfd_create("gravaai-capture", MFD_CLOEXEC);
	if (fd < 0 || ftruncate(fd, size * 2) < 0) return false;
	uint8_t *data = mmap(NULL, size * 2, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (data == MAP_FAILED) return false;
	slot_map = data;
	slot_map_size = size * 2;
	struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, size * 2);
	for (int i = 0; i < 2; i++) {
		slots[i].buffer =
			wl_shm_pool_create_buffer(pool, size * i, buffer_w, buffer_h, stride, shm_format);
		slots[i].data = data + size * i;
	}
	wl_shm_pool_destroy(pool);
	close(fd);
	slot_w = buffer_w;
	slot_h = buffer_h;
	return true;
}

/* Composes the newest captured buffer into the spare canvas frame and
 * publishes it. `frame_w`/`frame_h` are the size the buffer was captured at,
 * which differs from the session's current announcement while a resize is in
 * flight. The previous composed frame stays intact for the writer thread. */
static void publish_frame(int slot_index, uint32_t frame_w, uint32_t frame_h) {
	int spare = latest_canvas == 0 ? 1 : 0;
	uint8_t *frame_pixels = capture_canvas_compose(&canvas, canvas_frames[spare],
		slots[slot_index].data, (size_t)frame_w * 4u, frame_w, frame_h);
	if (frame_pixels == NULL)
		return;
	latest_canvas = spare;
	latest_frame = frame_pixels;
	latest_bytes = canvas.bytes;
}

/* Allocates the composed-frame buffers once the canvas size is known. */
static bool create_canvas_frames(void) {
	for (int i = 0; i < CANVAS_BUFFERS; i++) {
		canvas_frames[i] = malloc(canvas.bytes);
		if (canvas_frames[i] == NULL)
			return false;
	}
	return true;
}

static bool write_all(const uint8_t *data, size_t size) {
	while (size > 0) {
		ssize_t n = write(STDOUT_FILENO, data, size);
		if (n < 0) {
			if (errno == EINTR) continue;
			return false;
		}
		data += n;
		size -= n;
	}
	return true;
}

// --- toplevel selection ----------------------------------------------------

static struct toplevel *find_toplevel(const char *identifier) {
	for (int i = 0; i < toplevel_count; i++) {
		if (!toplevels[i].closed && !strcmp(toplevels[i].identifier, identifier))
			return &toplevels[i];
	}
	return NULL;
}

/* Emits WINDOW records. Tabs separate the fields, the title last: titles
 * contain spaces, identifiers and app ids do not contain tabs. */
static void print_toplevels(void) {
	for (int i = 0; i < toplevel_count; i++) {
		struct toplevel *entry = &toplevels[i];
		if (entry->closed || entry->identifier[0] == '\0')
			continue;
		printf("WINDOW\t%s\t%s\t%s\n", entry->identifier, entry->app_id, entry->title);
	}
	fflush(stdout);
}

int main(int argc, char **argv) {
	bool list_outputs = argc == 2 && !strcmp(argv[1], "--list");
	bool list_windows = argc == 2 && !strcmp(argv[1], "--list-windows");
	if (argc < 3 && !list_outputs && !list_windows) {
		fprintf(stderr,
			"usage: %s <output-name|window:<identifier>> <fps> | --list | --list-windows\n",
			argv[0]);
		return 2;
	}
	const char *wanted = (list_outputs || list_windows) ? NULL : argv[1];
	bool window = wanted != NULL && !strncmp(wanted, WINDOW_PREFIX, strlen(WINDOW_PREFIX));
	int fps = (list_outputs || list_windows) ? 0 : atoi(argv[2]);
	if (fps <= 0 || fps > 120) fps = 60;
	signal(SIGPIPE, SIG_IGN);

	struct wl_display *display = wl_display_connect(NULL);
	if (!display) {
		fprintf(stderr, "ERROR cannot connect to the Wayland display\n");
		return 1;
	}
	struct wl_registry *registry = wl_display_get_registry(display);
	wl_registry_add_listener(registry, &registry_listener, NULL);
	wl_display_roundtrip(display);
	if (!shm || !capture_manager) {
		fprintf(stderr, "ERROR compositor lacks ext-image-copy-capture\n");
		return 1;
	}
	wl_display_roundtrip(display);

	if (list_windows) {
		if (toplevel_list == NULL) {
			fprintf(stderr, "ERROR compositor lacks ext-foreign-toplevel-list\n");
			return 1;
		}
		// The initial toplevel events and their properties arrive in separate
		// batches; one more roundtrip after binding is not enough.
		wl_display_roundtrip(display);
		print_toplevels();
		return 0;
	}

	struct ext_image_capture_source_v1 *source = NULL;
	struct toplevel *target = NULL;
	if (window) {
		if (toplevel_list == NULL || toplevel_source_manager == NULL) {
			fprintf(stderr, "ERROR compositor lacks foreign toplevel capture\n");
			return 1;
		}
		wl_display_roundtrip(display);
		target = find_toplevel(wanted + strlen(WINDOW_PREFIX));
		if (target == NULL) {
			fprintf(stderr, "ERROR no window with identifier %s\n",
				wanted + strlen(WINDOW_PREFIX));
			return 1;
		}
		source = ext_foreign_toplevel_image_capture_source_manager_v1_create_source(
			toplevel_source_manager, target->handle);
	} else {
		if (!xdg_output_manager || !source_manager) {
			fprintf(stderr, "ERROR compositor lacks ext-output-image-capture-source\n");
			return 1;
		}
		for (intptr_t i = 0; i < output_count; i++) {
			struct zxdg_output_v1 *x =
				zxdg_output_manager_v1_get_xdg_output(xdg_output_manager, outputs[i]);
			zxdg_output_v1_add_listener(x, &xdg_listener, (void *)i);
		}
		wl_display_roundtrip(display);

		if (list_outputs) {
			for (int i = 0; i < output_count; i++)
				printf("OUTPUT %s %d %d %d %d\n", output_names[i], output_geometry[i][0],
					output_geometry[i][1], output_geometry[i][2], output_geometry[i][3]);
			return 0;
		}

		int index = -1;
		for (int i = 0; i < output_count; i++)
			if (!strcmp(output_names[i], wanted)) index = i;
		if (index < 0) {
			fprintf(stderr, "ERROR no output named %s\n", wanted);
			return 1;
		}
		source = ext_output_image_capture_source_manager_v1_create_source(source_manager,
			outputs[index]);
	}

	// No painted cursor: Gravaai draws its own from the cursor telemetry.
	struct ext_image_copy_capture_session_v1 *session =
		ext_image_copy_capture_manager_v1_create_session(capture_manager, source, 0);
	ext_image_copy_capture_session_v1_add_listener(session, &session_listener, NULL);
	while (!session_ready && !session_stopped)
		if (wl_display_dispatch(display) < 0) return 1;
	if (session_stopped || shm_format == UINT32_MAX || !create_slots()) {
		fprintf(stderr, "ERROR capture session unavailable\n");
		return 1;
	}

	// A window's size changes as it is resized; the canvas has to exist before
	// the first frame so the pointer can be mapped into it.
	if (window && seat != NULL && pointer != NULL) {
		struct ext_image_copy_capture_cursor_session_v1 *cursor_session =
			ext_image_copy_capture_manager_v1_create_pointer_cursor_session(
				capture_manager, source, pointer);
		ext_image_copy_capture_cursor_session_v1_add_listener(cursor_session, &cursor_listener,
			NULL);
	}

	request_frame(session);
	while (!frame_done && !frame_failed && !session_stopped)
		if (wl_display_dispatch(display) < 0) return 1;
	if (!frame_done) {
		fprintf(stderr, "ERROR first frame failed\n");
		return 1;
	}
	ext_image_copy_capture_frame_v1_destroy(frame);
	capture_canvas_init(&canvas, buffer_w, buffer_h);
	if (!create_canvas_frames()) {
		fprintf(stderr, "ERROR cannot allocate the capture canvas\n");
		return 1;
	}
	publish_frame(capturing_slot, slot_w, slot_h);
	flush_cursor();
	capturing_slot ^= 1;

	bool rgba = shm_format == WL_SHM_FORMAT_XBGR8888 || shm_format == WL_SHM_FORMAT_ABGR8888;
	fprintf(stderr, "FORMAT %u %u %s %u\n", canvas.width, canvas.height, rgba ? "rgba" : "bgra",
		frame_transform);
	fflush(stderr);

	int timer = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);
	long period_ns = 1000000000L / fps;
	struct itimerspec spec = {
		.it_interval = {.tv_sec = 0, .tv_nsec = period_ns},
		.it_value = {.tv_sec = 0, .tv_nsec = period_ns},
	};
	timerfd_settime(timer, 0, &spec, NULL);
	if (!write_all(latest_frame, latest_bytes)) return 0;

	request_frame(session);
	bool paused = false, running = true;
	char command[256];
	size_t command_len = 0;

	while (running && !session_stopped) {
		while (wl_display_prepare_read(display) != 0) wl_display_dispatch_pending(display);
		wl_display_flush(display);
		struct pollfd fds[3] = {
			{.fd = wl_display_get_fd(display), .events = POLLIN},
			{.fd = timer, .events = POLLIN},
			{.fd = STDIN_FILENO, .events = POLLIN},
		};
		if (poll(fds, 3, -1) < 0 && errno != EINTR) {
			wl_display_cancel_read(display);
			break;
		}
		if (fds[0].revents & POLLIN) {
			if (wl_display_read_events(display) < 0) break;
			wl_display_dispatch_pending(display);
		} else {
			wl_display_cancel_read(display);
		}

		if (frame_done || frame_failed) {
			ext_image_copy_capture_frame_v1_destroy(frame);
			if (frame_done) {
				publish_frame(capturing_slot, slot_w, slot_h);
				capturing_slot ^= 1;
			}
			// A window can resize mid-recording: the session re-announces the
			// buffer size then. The buffers are only replaced once no frame is in
			// flight (the protocol allows one at a time), and the canvas keeps
			// being written until a frame composed at the new size arrives.
			if (size_changed) {
				size_changed = false;
				if (buffer_w == 0 || buffer_h == 0 || !create_slots()) {
					fprintf(stderr, "ERROR cannot resize the capture buffers\n");
					running = false;
					break;
				}
			}
			request_frame(session);
		}

		if (fds[1].revents & POLLIN) {
			uint64_t ticks = 0;
			if (read(timer, &ticks, sizeof ticks) == sizeof ticks && !paused) {
				// Late ticks repeat the frame so the stream keeps wall-clock time.
				for (uint64_t i = 0; i < ticks && running; i++)
					running = write_all(latest_frame, latest_bytes);
			}
		}

		if (fds[2].revents & (POLLIN | POLLHUP | POLLERR)) {
			ssize_t n = read(STDIN_FILENO, command + command_len, sizeof command - command_len - 1);
			if (n <= 0) break;
			command_len += n;
			command[command_len] = 0;
			char *line;
			while ((line = strchr(command, '\n'))) {
				*line = 0;
				if (!strcmp(command, "pause")) paused = true;
				else if (!strcmp(command, "resume")) {
					paused = false;
					uint64_t drop;
					// Ticks that elapsed while paused are not recorded.
					if (read(timer, &drop, sizeof drop) < 0) {}
				} else if (!strcmp(command, "stop")) running = false;
				size_t rest = command_len - (line + 1 - command);
				memmove(command, line + 1, rest);
				command_len = rest;
				command[command_len] = 0;
			}
			if (command_len >= sizeof command - 1) command_len = 0;
		}
	}
	for (int i = 0; i < CANVAS_BUFFERS; i++) {
		free(canvas_frames[i]);
		canvas_frames[i] = NULL;
	}
	destroy_slots();
	return 0;
}
