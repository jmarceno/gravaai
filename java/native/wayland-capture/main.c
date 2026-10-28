// Screen recorder source for COSMIC (and other compositors with
// ext-image-copy-capture), bypassing the xdg-desktop-portal screencast.
//
// Usage: gravaai-wayland-capture <output-name> <fps>
//        gravaai-wayland-capture --list   (prints OUTPUT <name> <x> <y> <w> <h>)
//
// Captures the named output into shm buffers and writes raw frames to stdout
// at a constant <fps>, repeating the latest frame when nothing changed. Before
// the first frame it prints one line to stderr:
//   FORMAT <width> <height> <rgba|bgra> <wl_output transform>
// stdin commands: "pause", "resume", "stop" (EOF also stops).

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

#include "ext-image-capture-source-v1-client.h"
#include "ext-image-copy-capture-v1-client.h"
#include "xdg-output-unstable-v1-client.h"

#define MAX_OUTPUTS 16

static struct wl_shm *shm;
static struct zxdg_output_manager_v1 *xdg_output_manager;
static struct ext_output_image_capture_source_manager_v1 *source_manager;
static struct ext_image_copy_capture_manager_v1 *capture_manager;
static struct wl_output *outputs[MAX_OUTPUTS];
static char output_names[MAX_OUTPUTS][64];
static int32_t output_geometry[MAX_OUTPUTS][4];
static int output_count;

static uint32_t buffer_w, buffer_h, shm_format = UINT32_MAX;
static bool session_ready, session_stopped;

struct slot {
	struct wl_buffer *buffer;
	uint8_t *data;
};
static struct slot slots[2];
static int capturing_slot = 0, latest_slot = -1;
static struct ext_image_copy_capture_frame_v1 *frame;
static uint32_t frame_transform;
static bool frame_done, frame_failed;

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

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
	const char *interface, uint32_t version) {
	if (!strcmp(interface, wl_shm_interface.name)) {
		shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
	} else if (!strcmp(interface, wl_output_interface.name) && output_count < MAX_OUTPUTS) {
		outputs[output_count++] = wl_registry_bind(registry, name, &wl_output_interface, 1);
	} else if (!strcmp(interface, zxdg_output_manager_v1_interface.name)) {
		xdg_output_manager =
			wl_registry_bind(registry, name, &zxdg_output_manager_v1_interface, version < 3 ? version : 3);
	} else if (!strcmp(interface, ext_output_image_capture_source_manager_v1_interface.name)) {
		source_manager = wl_registry_bind(registry, name,
			&ext_output_image_capture_source_manager_v1_interface, 1);
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

static void session_buffer_size(void *data, struct ext_image_copy_capture_session_v1 *s,
	uint32_t w, uint32_t h) {
	buffer_w = w;
	buffer_h = h;
}
static void session_shm_format(void *data, struct ext_image_copy_capture_session_v1 *s,
	uint32_t format) {
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

static void request_frame(struct ext_image_copy_capture_session_v1 *session) {
	frame_done = frame_failed = false;
	frame = ext_image_copy_capture_session_v1_create_frame(session);
	ext_image_copy_capture_frame_v1_add_listener(frame, &frame_listener, NULL);
	ext_image_copy_capture_frame_v1_attach_buffer(frame, slots[capturing_slot].buffer);
	ext_image_copy_capture_frame_v1_damage_buffer(frame, 0, 0, buffer_w, buffer_h);
	ext_image_copy_capture_frame_v1_capture(frame);
}

static bool create_slots(void) {
	size_t stride = (size_t)buffer_w * 4, size = stride * buffer_h;
	int fd = memfd_create("gravaai-capture", MFD_CLOEXEC);
	if (fd < 0 || ftruncate(fd, size * 2) < 0) return false;
	uint8_t *data = mmap(NULL, size * 2, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (data == MAP_FAILED) return false;
	struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, size * 2);
	for (int i = 0; i < 2; i++) {
		slots[i].buffer =
			wl_shm_pool_create_buffer(pool, size * i, buffer_w, buffer_h, stride, shm_format);
		slots[i].data = data + size * i;
	}
	wl_shm_pool_destroy(pool);
	close(fd);
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

int main(int argc, char **argv) {
	bool list = argc == 2 && !strcmp(argv[1], "--list");
	if (argc < 3 && !list) {
		fprintf(stderr, "usage: %s <output-name> <fps> | --list\n", argv[0]);
		return 2;
	}
	const char *wanted = argv[1];
	int fps = list ? 0 : atoi(argv[2]);
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
	if (!shm || !xdg_output_manager || !source_manager || !capture_manager) {
		fprintf(stderr, "ERROR compositor lacks ext-image-copy-capture\n");
		return 1;
	}
	for (intptr_t i = 0; i < output_count; i++) {
		struct zxdg_output_v1 *x = zxdg_output_manager_v1_get_xdg_output(xdg_output_manager, outputs[i]);
		zxdg_output_v1_add_listener(x, &xdg_listener, (void *)i);
	}
	wl_display_roundtrip(display);

	if (list) {
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

	struct ext_image_capture_source_v1 *source =
		ext_output_image_capture_source_manager_v1_create_source(source_manager, outputs[index]);
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

	request_frame(session);
	while (!frame_done && !frame_failed && !session_stopped)
		if (wl_display_dispatch(display) < 0) return 1;
	if (!frame_done) {
		fprintf(stderr, "ERROR first frame failed\n");
		return 1;
	}
	ext_image_copy_capture_frame_v1_destroy(frame);
	latest_slot = capturing_slot;
	capturing_slot ^= 1;

	bool rgba = shm_format == WL_SHM_FORMAT_XBGR8888 || shm_format == WL_SHM_FORMAT_ABGR8888;
	fprintf(stderr, "FORMAT %u %u %s %u\n", buffer_w, buffer_h, rgba ? "rgba" : "bgra",
		frame_transform);
	fflush(stderr);

	size_t frame_size = (size_t)buffer_w * buffer_h * 4;
	int timer = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);
	long period_ns = 1000000000L / fps;
	struct itimerspec spec = {
		.it_interval = {.tv_sec = 0, .tv_nsec = period_ns},
		.it_value = {.tv_sec = 0, .tv_nsec = period_ns},
	};
	timerfd_settime(timer, 0, &spec, NULL);
	if (!write_all(slots[latest_slot].data, frame_size)) return 0;

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
				latest_slot = capturing_slot;
				capturing_slot ^= 1;
			}
			request_frame(session);
		}

		if (fds[1].revents & POLLIN) {
			uint64_t ticks = 0;
			if (read(timer, &ticks, sizeof ticks) == sizeof ticks && !paused) {
				// Late ticks repeat the frame so the stream keeps wall-clock time.
				for (uint64_t i = 0; i < ticks && running; i++)
					running = write_all(slots[latest_slot].data, frame_size);
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
	return 0;
}
