// Screen and window capture for compositors that only expose the xdg-desktop-portal
// ScreenCast path (KDE Plasma/KWin, GNOME, wlroots). COSMIC and other compositors
// with ext-image-copy-capture keep using gravaai-wayland-capture.
//
// Usage: gravaai-portal-capture <output-name> <fps>
//        gravaai-portal-capture --list   (prints OUTPUT <name> <x> <y> <w> <h>)
//
// The contract is intentionally identical to gravaai-wayland-capture so the Java
// recording pipeline does not need a second code path:
//   - raw frames go to stdout,
//   - one "FORMAT <width> <height> <rgba|bgra> <transform>" line goes to stderr
//     before the first frame,
//   - one "SOURCE <monitor|window>" line reports what the portal's dialog picked,
//   - one "CURSOR <x> <y>" line reports the pointer inside a shared window (0..1),
//   - stdin commands are "pause", "resume" and "stop" (EOF also stops).
//
// Unlike the COSMIC helper this one cannot be told which surface to capture: the
// portal's own dialog is the picker, so <output-name> is accepted and ignored. Both
// monitors and windows are offered to that dialog, and its answer decides what is
// captured. The frames are repeated at a constant <fps> so the muxer sees a steady
// clock even when nothing on screen changes.
//
// A shared window can be resized while it is recorded. Its frames then come at
// different sizes, which a fixed-size raw stream cannot carry, so every frame is
// composed into a canvas fixed by the first frame (see capture-canvas.h). The
// pointer position the compositor stamps into the buffers is mapped through the
// same fit, so cursor telemetry follows the recorded frame across a resize.

#define _GNU_SOURCE
#include <dbus/dbus.h>
#include <pipewire/pipewire.h>
#include <pipewire/loop.h>
#include <spa/buffer/buffer.h>
#include <spa/buffer/meta.h>
#include <spa/param/buffers.h>
#include <spa/param/props.h>
#include <spa/param/video/format-utils.h>
#include <spa/param/video/raw-utils.h>
#include <spa/pod/builder.h>
#include <wayland-client.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#include "capture-canvas.h"
#include "xdg-output-unstable-v1-client.h"

#define DEFAULT_FPS 60
#define MAX_OUTPUTS 16

static volatile sig_atomic_t running = 1;
static volatile sig_atomic_t paused = 0;

/* ------------------------------------------------------------------ --list */

struct wl_output_info {
	char name[64];
	int32_t x, y, w, h;
};

static struct {
	struct wl_display *display;
	struct wl_registry *registry;
	struct zxdg_output_manager_v1 *xdg_manager;
	struct wl_output *outputs[MAX_OUTPUTS];
	struct zxdg_output_v1 *xdg_outputs[MAX_OUTPUTS];
	struct wl_output_info info[MAX_OUTPUTS];
	int count;
	bool done;
} listing;

static void list_geometry(void *data, struct wl_output *wl, int32_t x, int32_t y,
		int32_t pw, int32_t ph, int32_t subpixel, const char *make,
		const char *model, int32_t transform) {
	(void)wl; (void)pw; (void)ph; (void)subpixel; (void)make; (void)model;
	(void)transform;
	struct wl_output_info *i = data;
	i->x = x;
	i->y = y;
}

static void list_mode(void *data, struct wl_output *wl, uint32_t flags,
		int32_t width, int32_t height, int32_t refresh) {
	(void)wl; (void)refresh;
	struct wl_output_info *i = data;
	if (flags & WL_OUTPUT_MODE_CURRENT) {
		i->w = width;
		i->h = height;
	}
}

static void list_done(void *data, struct wl_output *wl) {
	(void)data; (void)wl;
}

static void list_scale(void *data, struct wl_output *wl, int32_t factor) {
	(void)data; (void)wl; (void)factor;
}

static void list_name(void *data, struct wl_output *wl, const char *name) {
	(void)wl;
	struct wl_output_info *i = data;
	snprintf(i->name, sizeof(i->name), "%s", name);
}

static void list_description(void *data, struct wl_output *wl, const char *d) {
	(void)data; (void)wl; (void)d;
}

static const struct wl_output_listener output_listener = {
	.geometry = list_geometry,
	.mode = list_mode,
	.done = list_done,
	.scale = list_scale,
	.name = list_name,
	.description = list_description,
};

static void xdg_logical_position(void *data, struct zxdg_output_v1 *o, int32_t x, int32_t y) {
	(void)o;
	struct wl_output_info *i = data;
	i->x = x;
	i->y = y;
}

static void xdg_logical_size(void *data, struct zxdg_output_v1 *o, int32_t w, int32_t h) {
	(void)o;
	struct wl_output_info *i = data;
	i->w = w;
	i->h = h;
}

static void xdg_done(void *data, struct zxdg_output_v1 *o) {
	(void)data; (void)o;
	listing.done = true;
}

static void xdg_name(void *data, struct zxdg_output_v1 *o, const char *name) {
	(void)o;
	struct wl_output_info *i = data;
	snprintf(i->name, sizeof(i->name), "%s", name);
}

static void xdg_description(void *data, struct zxdg_output_v1 *o, const char *d) {
	(void)data; (void)o; (void)d;
}

static const struct zxdg_output_v1_listener xdg_listener = {
	.logical_position = xdg_logical_position,
	.logical_size = xdg_logical_size,
	.done = xdg_done,
	.name = xdg_name,
	.description = xdg_description,
};

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
		const char *interface, uint32_t version) {
	(void)data;
	if (!strcmp(interface, zxdg_output_manager_v1_interface.name)) {
		listing.xdg_manager = wl_registry_bind(registry, name,
				&zxdg_output_manager_v1_interface, version < 3 ? version : 3);
	} else if (!strcmp(interface, wl_output_interface.name)) {
		if (listing.count >= MAX_OUTPUTS)
			return;
		int index = listing.count++;
		struct wl_output *output = wl_registry_bind(registry, name,
				&wl_output_interface, version < 4 ? version : 4);
		listing.outputs[index] = output;
		snprintf(listing.info[index].name, sizeof(listing.info[index].name),
				"monitor-%d", index);
		wl_output_add_listener(output, &output_listener, &listing.info[index]);
	}
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
	(void)data; (void)registry; (void)name;
}

static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_global_remove,
};

static int list_outputs(void) {
	listing.display = wl_display_connect(NULL);
	if (listing.display == NULL) {
		fprintf(stderr, "ERROR cannot connect to Wayland display\n");
		return 1;
	}
	listing.registry = wl_display_get_registry(listing.display);
	wl_registry_add_listener(listing.registry, &registry_listener, NULL);
	wl_display_roundtrip(listing.display);
	wl_display_roundtrip(listing.display);

	for (int i = 0; i < listing.count; i++) {
		if (listing.xdg_manager != NULL) {
			listing.xdg_outputs[i] = zxdg_output_manager_v1_get_xdg_output(
					listing.xdg_manager, listing.outputs[i]);
			zxdg_output_v1_add_listener(listing.xdg_outputs[i], &xdg_listener,
					&listing.info[i]);
		}
	}
	if (listing.xdg_manager != NULL)
		wl_display_roundtrip(listing.display);

	for (int i = 0; i < listing.count; i++) {
		struct wl_output_info *info = &listing.info[i];
		if (info->w <= 0 || info->h <= 0)
			continue;
		printf("OUTPUT %s %d %d %d %d\n", info->name, info->x, info->y,
				info->w, info->h);
	}
	fflush(stdout);
	wl_display_disconnect(listing.display);
	return 0;
}

/* --------------------------------------------------------------- portal */

static DBusConnection *bus;
static uint32_t node_id = SPA_ID_INVALID;
/* The portal session object; the desktop emits Session.Closed on it when the user ends the share
 * from its own indicator ("End" on KDE, the top-bar button on GNOME). */
static char *session_path;

static char *request_path_for(const char *token) {
	const char *unique = dbus_bus_get_unique_name(bus);
	char *segment = strdup(unique + 1);
	for (char *p = segment; *p; p++)
		if (*p == '.')
			*p = '_';
	size_t size = strlen(segment) + strlen(token) + 64;
	char *path = calloc(1, size);
	snprintf(path, size, "/org/freedesktop/portal/desktop/request/%s/%s", segment, token);
	free(segment);
	return path;
}

static void add_entry(DBusMessageIter *dict, const char *key, char type, const void *value) {
	DBusMessageIter entry, variant;
	char signature[2] = { type, 0 };
	dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry);
	dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
	dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, signature, &variant);
	dbus_message_iter_append_basic(&variant, type, value);
	dbus_message_iter_close_container(&entry, &variant);
	dbus_message_iter_close_container(dict, &entry);
}

static void add_string(DBusMessageIter *dict, const char *key, const char *value) {
	add_entry(dict, key, DBUS_TYPE_STRING, &value);
}

static void add_uint32(DBusMessageIter *dict, const char *key, uint32_t value) {
	add_entry(dict, key, DBUS_TYPE_UINT32, &value);
}

static void add_bool(DBusMessageIter *dict, const char *key, dbus_bool_t value) {
	add_entry(dict, key, DBUS_TYPE_BOOLEAN, &value);
}

/* Restore tokens let the desktop remember which monitor the user picked, so the system dialog
 * appears once and later recordings start silently. KDE honours "Allow restoring on future
 * sessions" only when the caller both asks to persist (persist_mode=2) and stores the token the
 * portal hands back, then sends it again on the next SelectSources. Without this the dialog
 * reappears in front of every single recording. */
#define RESTORE_TOKEN_MAX 4096

static char *restore_token_path(void) {
	const char *state_home = getenv("XDG_STATE_HOME");
	char *path = NULL;
	if (state_home != NULL && state_home[0] != '\0') {
		if (asprintf(&path, "%s/gravaai/portal-restore-token", state_home) < 0)
			return NULL;
		return path;
	}
	const char *home = getenv("HOME");
	if (home == NULL || home[0] == '\0')
		return NULL;
	if (asprintf(&path, "%s/.local/state/gravaai/portal-restore-token", home) < 0)
		return NULL;
	return path;
}

/* Creates every missing parent of path, so the token can be written on a fresh profile. */
static void make_parent_directories(char *path) {
	for (char *cursor = path + 1; *cursor != '\0'; cursor++) {
		if (*cursor != '/')
			continue;
		*cursor = '\0';
		mkdir(path, 0700);
		*cursor = '/';
	}
}

/* Reads the saved token, or NULL when there is none yet (first run, or the portal forgot it). */
static char *load_restore_token(void) {
	char *path = restore_token_path();
	if (path == NULL)
		return NULL;
	FILE *file = fopen(path, "r");
	free(path);
	if (file == NULL)
		return NULL;
	char buffer[RESTORE_TOKEN_MAX];
	size_t count = fread(buffer, 1, sizeof(buffer) - 1, file);
	fclose(file);
	buffer[count] = '\0';
	while (count > 0 && (buffer[count - 1] == '\n' || buffer[count - 1] == '\r'))
		buffer[--count] = '\0';
	return count > 0 ? strdup(buffer) : NULL;
}

static void save_restore_token(const char *token) {
	char *path = restore_token_path();
	if (path == NULL)
		return;
	if (token == NULL || token[0] == '\0') {
		/* The portal rejected the remembered screen (unplugged, rearranged): start over. */
		unlink(path);
		free(path);
		return;
	}
	make_parent_directories(path);
	/* The token identifies a screen the user already approved: keep it private, like a cookie. */
	int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
	if (fd >= 0) {
		size_t length = strlen(token);
		ssize_t token_written = write(fd, token, length);
		ssize_t newline_written = write(fd, "\n", 1);
		/* A partial token is worse than none: the next run would send garbage and the portal
		 * would prompt again anyway, so drop the file rather than keep half a token. */
		if (token_written != (ssize_t)length || newline_written != 1)
			unlink(path);
		close(fd);
	}
	free(path);
}

/* Waits for the portal's Response signal; results is set to the results dict. */
static int wait_response(const char *path, DBusMessage **results) {
	for (;;) {
		DBusMessage *message;
		if (!dbus_connection_read_write(bus, -1))
			return -1;
		while ((message = dbus_connection_pop_message(bus)) != NULL) {
			if (dbus_message_is_signal(message, "org.freedesktop.portal.Request",
						"Response") &&
					!strcmp(dbus_message_get_path(message), path)) {
				DBusMessageIter iter;
				uint32_t response = 1;
				if (dbus_message_iter_init(message, &iter) &&
						dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_UINT32)
					dbus_message_iter_get_basic(&iter, &response);
				if (response != 0) {
					fprintf(stderr, "ERROR the screen share request was not accepted\n");
					dbus_message_unref(message);
					return -1;
				}
				if (dbus_message_iter_next(&iter))
					*results = dbus_message_ref(message);
				dbus_message_unref(message);
				return 0;
			}
			dbus_message_unref(message);
		}
	}
}

static DBusMessage *call_and_wait(DBusMessage *message, const char *token) {
	DBusError error;
	dbus_error_init(&error);
	DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, message, -1, &error);
	dbus_message_unref(message);
	if (reply == NULL) {
		fprintf(stderr, "ERROR portal call failed: %s\n", error.message);
		dbus_error_free(&error);
		return NULL;
	}
	const char *handle = NULL;
	if (!dbus_message_get_args(reply, NULL, DBUS_TYPE_OBJECT_PATH, &handle,
				DBUS_TYPE_INVALID))
		handle = NULL;
	char *expected = request_path_for(token);
	char *path = strdup(handle != NULL ? handle : expected);
	dbus_message_unref(reply);
	free(expected);

	DBusMessage *results = NULL;
	if (wait_response(path, &results) != 0) {
		free(path);
		return NULL;
	}
	free(path);
	return results;
}

static char *results_string(DBusMessageIter *dict, const char *key) {
	DBusMessageIter iter;
	dbus_message_iter_recurse(dict, &iter);
	while (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_DICT_ENTRY) {
		DBusMessageIter entry, variant;
		const char *name = NULL;
		dbus_message_iter_recurse(&iter, &entry);
		dbus_message_iter_get_basic(&entry, &name);
		if (name != NULL && !strcmp(name, key)) {
			dbus_message_iter_next(&entry);
			dbus_message_iter_recurse(&entry, &variant);
			if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_STRING) {
				const char *value = NULL;
				dbus_message_iter_get_basic(&variant, &value);
				return strdup(value);
			}
			return NULL;
		}
		dbus_message_iter_next(&iter);
	}
	return NULL;
}

/* ScreenCast source types, as the portal and the compositors report them. */
#define SOURCE_TYPE_MONITOR 1u
#define SOURCE_TYPE_WINDOW 2u

/* Cursor modes from org.freedesktop.portal.ScreenCast. */
#define CURSOR_MODE_HIDDEN 1u
#define CURSOR_MODE_METADATA 4u

/* Which kind of surface the portal ended up sharing, known once Start returned. */
static uint32_t stream_source_type = SOURCE_TYPE_MONITOR;
/* True when the session asked for cursor metadata rather than just a hidden cursor. */
static bool cursor_metadata_requested;

/* Reads the ScreenCast.AvailableCursorModes property.
 *
 * Cursor metadata is the only way to learn where the pointer is inside a shared *window*: the
 * portal reports neither a window's position nor its global bounds, so a global pointer reading
 * cannot be normalized against it. Asking for an unsupported mode closes the session, so the
 * request has to follow what this backend advertises. */
static bool portal_offers_cursor_metadata(void) {
	DBusMessage *message = dbus_message_new_method_call("org.freedesktop.portal.Desktop",
			"/org/freedesktop/portal/desktop", "org.freedesktop.DBus.Properties", "Get");
	if (message == NULL)
		return false;
	const char *interface = "org.freedesktop.portal.ScreenCast";
	const char *property = "AvailableCursorModes";
	dbus_message_append_args(message, DBUS_TYPE_STRING, &interface, DBUS_TYPE_STRING, &property,
			DBUS_TYPE_INVALID);
	DBusError error;
	dbus_error_init(&error);
	DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, message, 2000, &error);
	dbus_message_unref(message);
	if (reply == NULL) {
		dbus_error_free(&error);
		return false;
	}
	bool offered = false;
	DBusMessageIter iter, variant;
	if (dbus_message_iter_init(reply, &iter)) {
		dbus_message_iter_recurse(&iter, &variant);
		if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_UINT32) {
			uint32_t modes = 0;
			dbus_message_iter_get_basic(&variant, &modes);
			offered = (modes & CURSOR_MODE_METADATA) != 0;
		}
	}
	dbus_message_unref(reply);
	return offered;
}

/* Reads the stream property dict the portal returned. Only `source_type` matters here: it tells
 * the recorder whether the user picked a monitor or a window, which decides how the pointer
 * telemetry is interpreted. Every property is optional in older portal versions, so a missing
 * source_type keeps the monitor default. */
static void read_stream_properties(DBusMessageIter *stream) {
	if (!dbus_message_iter_next(stream))
		return;
	if (dbus_message_iter_get_arg_type(stream) != DBUS_TYPE_ARRAY)
		return;
	DBusMessageIter dict, entry;
	dbus_message_iter_recurse(stream, &dict);
	while (dbus_message_iter_get_arg_type(&dict) == DBUS_TYPE_DICT_ENTRY) {
		DBusMessageIter value;
		const char *name = NULL;
		dbus_message_iter_recurse(&dict, &entry);
		dbus_message_iter_get_basic(&entry, &name);
		if (name != NULL && !strcmp(name, "source_type")) {
			dbus_message_iter_next(&entry);
			dbus_message_iter_recurse(&entry, &value);
			if (dbus_message_iter_get_arg_type(&value) == DBUS_TYPE_UINT32)
				dbus_message_iter_get_basic(&value, &stream_source_type);
		}
		dbus_message_iter_next(&dict);
	}
}

/* results["streams"] is a(ua{sv}); take the first node id and, when the portal reports them,
 * the stream's source type and size. */
static uint32_t results_stream_node(DBusMessageIter *dict) {
	DBusMessageIter iter;
	dbus_message_iter_recurse(dict, &iter);
	while (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_DICT_ENTRY) {
		DBusMessageIter entry, variant;
		const char *name = NULL;
		dbus_message_iter_recurse(&iter, &entry);
		dbus_message_iter_get_basic(&entry, &name);
		if (name != NULL && !strcmp(name, "streams")) {
			DBusMessageIter array;
			dbus_message_iter_next(&entry);
			dbus_message_iter_recurse(&entry, &variant);
			dbus_message_iter_recurse(&variant, &array);
			if (dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_STRUCT) {
				DBusMessageIter stream;
				uint32_t node = SPA_ID_INVALID;
				dbus_message_iter_recurse(&array, &stream);
				if (dbus_message_iter_get_arg_type(&stream) == DBUS_TYPE_UINT32)
					dbus_message_iter_get_basic(&stream, &node);
				read_stream_properties(&stream);
				return node;
			}
			return SPA_ID_INVALID;
		}
		dbus_message_iter_next(&iter);
	}
	return SPA_ID_INVALID;
}

/* Runs CreateSession + SelectSources + Start and leaves node_id set. */
static int open_portal_stream(void) {
	DBusError error;
	dbus_error_init(&error);
	bus = dbus_bus_get(DBUS_BUS_SESSION, &error);
	if (bus == NULL) {
		fprintf(stderr, "ERROR no session bus: %s\n",
				error.message != NULL ? error.message : "?");
		return -1;
	}
	dbus_connection_set_exit_on_disconnect(bus, FALSE);
	dbus_bus_add_match(bus,
			"type='signal',interface='org.freedesktop.portal.Request',member='Response'",
			&error);
	dbus_connection_flush(bus);

	char token[64], session_token[64];
	snprintf(token, sizeof(token), "gravaai_cs_%d", (int)getpid());
	snprintf(session_token, sizeof(session_token), "gravaai_s_%d", (int)getpid());

	DBusMessage *message = dbus_message_new_method_call(
			"org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
			"org.freedesktop.portal.ScreenCast", "CreateSession");
	DBusMessageIter iter, options;
	dbus_message_iter_init_append(message, &iter);
	dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &options);
	add_string(&options, "handle_token", token);
	add_string(&options, "session_handle_token", session_token);
	dbus_message_iter_close_container(&iter, &options);

	DBusMessage *results = call_and_wait(message, token);
	if (results == NULL)
		return -1;
	DBusMessageIter reply;
	dbus_message_iter_init(results, &reply);
	dbus_message_iter_next(&reply);
	char *session = results_string(&reply, "session_handle");
	if (session == NULL) {
		fprintf(stderr, "ERROR the portal did not return a session handle\n");
		dbus_message_unref(results);
		return -1;
	}

	/* Monitors and windows: the desktop's own dialog is the picker, so it offers both and the
	 * user's choice decides what is recorded. Requesting a single type would hide the other. */
	snprintf(token, sizeof(token), "gravaai_ss_%d", (int)getpid());
	message = dbus_message_new_method_call("org.freedesktop.portal.Desktop",
			"/org/freedesktop/portal/desktop", "org.freedesktop.portal.ScreenCast",
			"SelectSources");
	dbus_message_iter_init_append(message, &iter);
	dbus_message_iter_append_basic(&iter, DBUS_TYPE_OBJECT_PATH, &session);
	dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &options);
	add_string(&options, "handle_token", token);
	add_uint32(&options, "types", SOURCE_TYPE_MONITOR | SOURCE_TYPE_WINDOW);
	add_bool(&options, "multiple", FALSE);
	/* 1 = hidden: the frames carry no cursor, and the editor draws its own from the telemetry
	 * the cursor helper records. With 2 (embedded) the compositor burnt a second pointer into
	 * the video at a position the app does not know about, which showed up as two mice.
	 * 4 = metadata would be the ideal source for window-relative positions, but not every
	 * backend advertises it; the helper reads it when it is present and falls back to the
	 * cursor helper otherwise. */
	add_uint32(&options, "cursor_mode",
			(cursor_metadata_requested = portal_offers_cursor_metadata())
					? CURSOR_MODE_METADATA : CURSOR_MODE_HIDDEN);
	/* 2 = persist until revoked: ask the desktop to remember this selection, and hand back a
	 * token in Start's results so the next recording can reuse it instead of re-prompting. */
	add_uint32(&options, "persist_mode", 2);
	char *restore_token = load_restore_token();
	if (restore_token != NULL) {
		add_string(&options, "restore_token", restore_token);
		free(restore_token);
	}
	dbus_message_iter_close_container(&iter, &options);
	results = call_and_wait(message, token);
	if (results == NULL) {
		free(session);
		return -1;
	}
	dbus_message_unref(results);

	snprintf(token, sizeof(token), "gravaai_st_%d", (int)getpid());
	message = dbus_message_new_method_call("org.freedesktop.portal.Desktop",
			"/org/freedesktop/portal/desktop", "org.freedesktop.portal.ScreenCast",
			"Start");
	dbus_message_iter_init_append(message, &iter);
	dbus_message_iter_append_basic(&iter, DBUS_TYPE_OBJECT_PATH, &session);
	const char *parent = "";
	dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &parent);
	dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &options);
	add_string(&options, "handle_token", token);
	dbus_message_iter_close_container(&iter, &options);
	results = call_and_wait(message, token);
	if (results == NULL) {
		free(session);
		return -1;
	}
	dbus_message_iter_init(results, &reply);
	dbus_message_iter_next(&reply);
	/* Only present when the portal agreed to persist; absent otherwise, which is harmless. */
	char *returned_token = results_string(&reply, "restore_token");
	if (returned_token != NULL) {
		save_restore_token(returned_token);
		free(returned_token);
	}
	node_id = results_stream_node(&reply);
	dbus_message_unref(results);
	char *closed_rule = NULL;
	if (asprintf(&closed_rule, "type='signal',interface='org.freedesktop.portal.Session',"
				"member='Closed',path='%s'", session) >= 0) {
		dbus_bus_add_match(bus, closed_rule, NULL);
		free(closed_rule);
	}
	session_path = session;
	if (node_id == SPA_ID_INVALID) {
		fprintf(stderr, "ERROR the portal returned no stream\n");
		return -1;
	}
	return 0;
}

/* ------------------------------------------------------------- pipewire */

static struct pw_main_loop *pw_loop;
static struct pw_stream *pw_stream;
static struct spa_hook stream_listener;

static pthread_mutex_t frame_lock = PTHREAD_MUTEX_INITIALIZER;
/* Three packed frames: the newest complete one, the one the writer is streaming out, and a spare
 * the PipeWire callback composes into. With only two, a second frame arriving during one 8 MB
 * write was packed over the very buffer being written, which tore the recorded frame.
 *
 * Each one is canvas-sized, because the encoder reads a fixed-size stream (see capture-canvas.h)
 * while a shared window can be resized mid-recording. */
#define FRAME_BUFFERS 3
static struct capture_canvas canvas;
static unsigned char *buffers[FRAME_BUFFERS];
static int latest_buffer = -1;
static int writing_buffer = -1;
static size_t buffer_bytes;
static int frame_width, frame_height;
static bool frame_announced;
static bool format_reported;

/* Room for the cursor metadata plus a bitmap of up to w x h pixels (the producer may attach the
 * cursor image; only the position is read). */
#define CURSOR_META_SIZE(w, h) \
	(sizeof(struct spa_meta_cursor) + sizeof(struct spa_meta_bitmap) + (size_t)(w) * (h) * 4u)

/* Asks the producer to attach SPA_META_Cursor to every buffer. cursor_mode=metadata only tells
 * the portal not to burn the pointer into the frames; the stream still has to request the meta
 * itself, or the buffers never carry it and every window take falls back to the frame centre. */
static void request_cursor_metadata(struct pw_stream *stream) {
	uint8_t storage[256];
	struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
	const struct spa_pod *params[1];
	params[0] = spa_pod_builder_add_object(&builder,
			SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
			SPA_PARAM_META_type, SPA_POD_Id(SPA_META_Cursor),
			SPA_PARAM_META_size, SPA_POD_CHOICE_RANGE_Int(
				(int)CURSOR_META_SIZE(64, 64), (int)CURSOR_META_SIZE(1, 1),
				(int)CURSOR_META_SIZE(256, 256)));
	pw_stream_update_params(stream, params, 1);
}

static void on_param_changed(void *data, uint32_t id, const struct spa_pod *param) {
	if (param == NULL || id != SPA_PARAM_Format)
		return;
	struct spa_video_info info;
	memset(&info, 0, sizeof(info));
	if (spa_format_parse(param, &info.media_type, &info.media_subtype) < 0)
		return;
	if (info.media_type != SPA_MEDIA_TYPE_video ||
			info.media_subtype != SPA_MEDIA_SUBTYPE_raw)
		return;
	if (spa_format_video_raw_parse(param, &info.info.raw) < 0)
		return;

	uint64_t format = info.info.raw.format;
	/* BGRx/RGBx are what KWin and Mutter hand out; BGRA/RGBA show up on some
	 * wlroots builds. Everything is written as opaque 32-bit pixels. */
	bool rgba = format == SPA_VIDEO_FORMAT_RGBx || format == SPA_VIDEO_FORMAT_RGBA;
	int width = (int)info.info.raw.size.width;
	int height = (int)info.info.raw.size.height;
	if (width <= 0 || height <= 0)
		return;

	pthread_mutex_lock(&frame_lock);
	if (!frame_announced) {
		/* The canvas is fixed by the first negotiated format; a later resize
		 * only changes how the source is fitted into it. */
		capture_canvas_init(&canvas, (uint32_t)width, (uint32_t)height);
		buffer_bytes = canvas.bytes;
		for (int i = 0; i < FRAME_BUFFERS; i++) {
			buffers[i] = calloc(1, buffer_bytes);
			if (buffers[i] == NULL) {
				pthread_mutex_unlock(&frame_lock);
				return;
			}
		}
		frame_announced = true;
	}
	/* The size the compositor captures at now; a shared window changes it. */
	frame_width = width;
	frame_height = height;
	/* The canvas never changes size, so it is announced exactly once: a renegotiation before
	 * the first frame (common while a stream starts) must not print a second FORMAT. */
	bool first = !format_reported;
	format_reported = true;
	pthread_mutex_unlock(&frame_lock);

	if (cursor_metadata_requested)
		request_cursor_metadata((struct pw_stream *)data);

	if (first) {
		/* No transform: the compositor already applies rotation. */
		fprintf(stderr, "FORMAT %u %u %s 0\n", canvas.width, canvas.height,
			rgba ? "rgba" : "bgra");
		fflush(stderr);
	}
}

/* Cursor metadata: the compositor stamps the pointer position into the buffer, in the source's
 * pixels. It is the only window-relative reading available through the portal, because a window
 * stream reports neither its position nor its bounds. */
static void read_cursor_metadata(struct spa_buffer *spa_buffer) {
	const struct spa_meta_cursor *cursor = spa_buffer_find_meta_data(spa_buffer,
			SPA_META_Cursor, sizeof(struct spa_meta_cursor));
	if (cursor == NULL || !spa_meta_cursor_is_valid(cursor))
		return;
	if (!frame_announced)
		return;
	capture_canvas_write_cursor(&canvas, cursor->position.x, cursor->position.y,
			(uint32_t)frame_width, (uint32_t)frame_height);
}

static void on_process(void *data) {
	struct pw_stream *stream = data;
	struct pw_buffer *buffer = pw_stream_dequeue_buffer(stream);
	if (buffer == NULL)
		return;

	struct spa_buffer *spa_buffer = buffer->buffer;
	read_cursor_metadata(spa_buffer);
	if (spa_buffer->n_datas > 0) {
		struct spa_data *spa_data = &spa_buffer->datas[0];
		struct spa_chunk *chunk = spa_data->chunk;
		if (spa_data->data != NULL && chunk != NULL && chunk->size > 0) {
			pthread_mutex_lock(&frame_lock);
			int spare = 0;
			while (spare == latest_buffer || spare == writing_buffer)
				spare++;
			unsigned char *staging = buffers[spare];
			size_t stride = chunk->stride > 0 ? (size_t)chunk->stride : (size_t)frame_width * 4u;
			size_t row = (size_t)frame_width * 4u;
			/* The bytes a frame of the negotiated size needs. During a resize the format is
			 * renegotiated while buffers of the old size are still in flight, so a buffer can be
			 * smaller than frame_width x frame_height claims; composing it would read past its
			 * end. Such a frame is skipped and the previous one keeps the clock fed. */
			size_t needed = frame_height > 0 ? stride * (size_t)(frame_height - 1) + row : 0;
			bool fits = stride >= row && needed > 0 && chunk->size >= needed
					&& (size_t)chunk->offset + needed <= spa_data->maxsize;
			if (staging != NULL && buffer_bytes > 0 && frame_width > 0 && fits) {
				/* Pack and fit into the canvas in one pass: the source may be smaller,
				 * larger or a different aspect than the first frame. */
				capture_canvas_compose(&canvas, staging,
						(const unsigned char *)spa_data->data + chunk->offset, stride,
						(uint32_t)frame_width, (uint32_t)frame_height);
				/* Publish it: the writer picks up this frame next tick. */
				latest_buffer = spare;
			}
			pthread_mutex_unlock(&frame_lock);
		}
	}
	pw_stream_queue_buffer(stream, buffer);
}

static void on_state_changed(void *data, enum pw_stream_state old,
		enum pw_stream_state state, const char *error) {
	(void)data;
	if (state == PW_STREAM_STATE_ERROR) {
		fprintf(stderr, "ERROR screen stream failed: %s\n",
				error != NULL ? error : "?");
		running = 0;
		pw_main_loop_quit(pw_loop);
		return;
	}
	/* The compositor removed the screen node: the share was ended outside the app. Exiting is the
	 * signal the recorder watches for, so it can save what was captured instead of recording the
	 * last frame forever. */
	if (state == PW_STREAM_STATE_UNCONNECTED && old != PW_STREAM_STATE_CONNECTING
			&& old != PW_STREAM_STATE_UNCONNECTED) {
		fprintf(stderr, "ENDED the screen stream was disconnected\n");
		running = 0;
		pw_main_loop_quit(pw_loop);
	}
}

/* Drains the session bus without blocking and stops when the portal closes our session. */
static void check_session_closed(void) {
	if (bus == NULL || session_path == NULL)
		return;
	if (!dbus_connection_read_write(bus, 0)) {
		fprintf(stderr, "ENDED the session bus went away\n");
		running = 0;
		return;
	}
	DBusMessage *message;
	while ((message = dbus_connection_pop_message(bus)) != NULL) {
		if (dbus_message_is_signal(message, "org.freedesktop.portal.Session", "Closed")
				&& !strcmp(dbus_message_get_path(message), session_path)) {
			fprintf(stderr, "ENDED the screen share was ended from the desktop\n");
			running = 0;
		}
		dbus_message_unref(message);
	}
}

static void on_stream_destroy(void *data) {
	(void)data;
	running = 0;
}

static const struct pw_stream_events stream_events = {
	PW_VERSION_STREAM_EVENTS,
	.state_changed = on_state_changed,
	.process = on_process,
	.param_changed = on_param_changed,
	.destroy = on_stream_destroy,
};

/* Writes the newest frame at most `fps` times a second, repeating it when the screen is still.
 * Ticks missed while a write was blocked are dropped, not replayed: the encoder stamps every frame
 * with its arrival time (-use_wallclock_as_timestamps), so the recording keeps wall-clock length
 * either way, and replaying only fed a slow machine duplicate 8 MB frames it could not keep up
 * with. */
static void *writer_thread(void *argument) {
	int fps = *(int *)argument;
	int timer = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC);
	if (timer < 0) {
		fprintf(stderr, "ERROR timerfd: %s\n", strerror(errno));
		return NULL;
	}
	struct itimerspec period;
	memset(&period, 0, sizeof(period));
	long interval = 1000000000L / (fps > 0 ? fps : DEFAULT_FPS);
	period.it_value.tv_nsec = interval;
	period.it_interval.tv_nsec = interval;
	timerfd_settime(timer, 0, &period, NULL);

	while (running) {
		uint64_t ticks = 0;
		ssize_t got = read(timer, &ticks, sizeof(ticks));
		if (got != (ssize_t)sizeof(ticks))
			break;
		if (!running)
			break;
		if (paused)
			continue;

		/* Copy the pointer and length under the lock, then write outside it so the PipeWire
		 * callback is never blocked behind an 8 MB write. */
		pthread_mutex_lock(&frame_lock);
		writing_buffer = latest_buffer;
		unsigned char *frame = latest_buffer >= 0 ? buffers[latest_buffer] : NULL;
		size_t size = buffer_bytes;
		pthread_mutex_unlock(&frame_lock);
		if (frame == NULL)
			continue;

		bool failed = false;
		size_t written = 0;
		while (written < size) {
			ssize_t count = write(STDOUT_FILENO, frame + written, size - written);
			if (count < 0 && errno == EINTR)
				continue;
			if (count <= 0) {
				failed = true;
				break;
			}
			written += (size_t)count;
		}
		pthread_mutex_lock(&frame_lock);
		writing_buffer = -1;
		pthread_mutex_unlock(&frame_lock);
		if (failed) {
			running = 0;
			break;
		}
	}
	close(timer);
	return NULL;
}

/* stdin commands: pause / resume / stop, matching the COSMIC helper. */
static void *command_thread(void *argument) {
	(void)argument;
	/* poll() + read() rather than fgets(): the share can end from the desktop while the app keeps
	 * stdin open, and a thread parked in fgets() holds stdin's stdio lock, which exit() then waits
	 * on forever. Polling lets this thread see `running` drop and return. */
	char line[64];
	size_t length = 0;
	while (running) {
		struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
		int ready = poll(&input, 1, 200);
		if (ready < 0 && errno != EINTR)
			break;
		if (ready <= 0)
			continue;
		char byte;
		ssize_t count = read(STDIN_FILENO, &byte, 1);
		if (count <= 0)
			break;
		if (byte != '\n') {
			if (length < sizeof(line) - 1)
				line[length++] = byte;
			continue;
		}
		line[length] = '\0';
		length = 0;
		if (!strncmp(line, "pause", 5))
			paused = 1;
		else if (!strncmp(line, "resume", 6))
			paused = 0;
		else if (!strncmp(line, "stop", 4))
			break;
	}
	/* The main loop polls `running` once per iteration, so nothing cross-thread is needed. */
	running = 0;
	return NULL;
}

static void on_terminate(int signal_number) {
	(void)signal_number;
	running = 0;
}

static int capture(int fps) {
	if (open_portal_stream() != 0)
		return 1;

	/* Report what the portal's dialog picked before any frame arrives: the recorder uses it to
	 * decide whether the pointer telemetry it sees is window-relative. */
	fprintf(stderr, "SOURCE %s\n",
			stream_source_type == SOURCE_TYPE_WINDOW ? "window" : "monitor");
	fflush(stderr);

	pw_init(NULL, NULL);
	pw_loop = pw_main_loop_new(NULL);
	struct pw_context *context = pw_context_new(pw_main_loop_get_loop(pw_loop), NULL, 0);
	if (context == NULL) {
		fprintf(stderr, "ERROR cannot create a PipeWire context\n");
		return 1;
	}
	struct pw_core *core = pw_context_connect(context, NULL, 0);
	if (core == NULL) {
		fprintf(stderr, "ERROR cannot connect to PipeWire\n");
		return 1;
	}

	pw_stream = pw_stream_new(core, "gravaai-capture", NULL);
	pw_stream_add_listener(pw_stream, &stream_listener, &stream_events, pw_stream);

	uint8_t buffer[1024];
	struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
	struct spa_pod_frame frame;
	spa_pod_builder_push_object(&builder, &frame, SPA_TYPE_OBJECT_Format,
			SPA_PARAM_EnumFormat);
	spa_pod_builder_add(&builder, SPA_FORMAT_mediaType,
			SPA_POD_Id(SPA_MEDIA_TYPE_video), 0);
	spa_pod_builder_add(&builder, SPA_FORMAT_mediaSubtype,
			SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw), 0);
	spa_pod_builder_add(&builder, SPA_FORMAT_VIDEO_format,
			SPA_POD_CHOICE_ENUM_Id(3, SPA_VIDEO_FORMAT_BGRx,
				SPA_VIDEO_FORMAT_BGRx, SPA_VIDEO_FORMAT_RGBx), 0);
	const struct spa_pod *params[1];
	params[0] = spa_pod_builder_pop(&builder, &frame);

	if (pw_stream_connect(pw_stream, PW_DIRECTION_INPUT, node_id,
				PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS,
				params, 1) < 0) {
		fprintf(stderr, "ERROR cannot connect the screen stream\n");
		return 1;
	}

	pthread_t writer, commander;
	pthread_create(&writer, NULL, writer_thread, &fps);
	pthread_create(&commander, NULL, command_thread, NULL);

	/* Iterate with a timeout instead of pw_main_loop_run so `running` (cleared by the stop
	 * command or a signal) is observed promptly and the stream closes on its own. */
	struct pw_loop *loop = pw_main_loop_get_loop(pw_loop);
	while (running) {
		int result = pw_loop_iterate(loop, 100);
		if (result < 0 && errno != EINTR) {
			fprintf(stderr, "ERROR screen stream loop failed: %s\n", strerror(errno));
			break;
		}
		check_session_closed();
	}
	running = 0;
	pthread_join(commander, NULL);
	pthread_join(writer, NULL);

	pw_stream_destroy(pw_stream);
	pw_context_destroy(context);
	pw_main_loop_destroy(pw_loop);
	pthread_mutex_lock(&frame_lock);
	for (int i = 0; i < FRAME_BUFFERS; i++) {
		free(buffers[i]);
		buffers[i] = NULL;
	}
	latest_buffer = -1;
	pthread_mutex_unlock(&frame_lock);
	return 0;
}
int main(int argc, char *argv[]) {
	if (argc >= 2 && !strcmp(argv[1], "--list"))
		return list_outputs();

	signal(SIGPIPE, SIG_IGN);
	signal(SIGTERM, on_terminate);
	signal(SIGINT, on_terminate);

	int fps = DEFAULT_FPS;
	if (argc >= 3) {
		int parsed = atoi(argv[2]);
		if (parsed > 0 && parsed <= 240)
			fps = parsed;
	}
	return capture(fps);
}
