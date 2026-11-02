// Global pointer position for KDE Plasma (KWin), on X11 and Wayland alike.
//
// KWin exposes no D-Bus method for the pointer position, and its Wayland session gives ordinary
// clients no global pointer either (ext-image-copy-capture cursor sessions do not exist there).
// What KWin *does* provide is a scripting engine that runs inside the compositor, where
// workspace.cursorPos is readable. This helper installs a tiny KWin script that polls that value
// with a QTimer and reports each change back over D-Bus; this process owns the D-Bus name the
// script calls, and prints the positions on stdout in gravaai-wayland-cursor's format:
//
//   POS:<x>:<y>
//   READY
//
// The same output contract means the Java side needs no KDE-specific parsing: on COSMIC the
// position comes from the compositor directly, here it comes from a script inside KWin.
//
// Usage: gravaai-kwin-cursor
// Exits when stdin closes, like the other helpers.

#define _GNU_SOURCE
#include <dbus/dbus.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define SERVICE "org.gravaai.KWinCursor"
#define OBJECT "/org/gravaai/KWinCursor"
#define INTERFACE "org.gravaai.KWinCursor"
#define SCRIPT_NAME "gravaai-cursor-bridge"

#define MAX_INPUTS 16

static volatile sig_atomic_t running = 1;
static DBusConnection *bus;
static bool ready_printed;

static int input_fds[MAX_INPUTS];
static char input_paths[MAX_INPUTS][300];
static bool input_is_mouse[MAX_INPUTS];
static bool input_is_keyboard[MAX_INPUTS];
static int input_count;
static bool want_keys;

static void on_terminate(int signal_number) {
	(void)signal_number;
	running = 0;
}

/* Mouse buttons come from evdev, the same way gravaai-wayland-cursor gets them: KWin's
 * scripting engine can read the pointer position but not button presses. The app keeps one
 * cursor helper, so clicks have to be reported here rather than by a second process. */
static bool has_bit(const unsigned long *bits, int bit) {
	return (bits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1UL;
}

static void discover_mice(void) {
	DIR *directory = opendir("/dev/input");
	if (directory == NULL)
		return;
	struct dirent *entry;
	while ((entry = readdir(directory)) != NULL && input_count < MAX_INPUTS) {
		if (strncmp(entry->d_name, "event", 5) != 0)
			continue;
		char path[300];
		snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
		int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0)
			continue;
		unsigned long keys[(KEY_CNT + 8 * sizeof(long) - 1) / (8 * sizeof(long))] = {0};
		if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0) {
			close(fd);
			continue;
		}
		bool mouse = has_bit(keys, BTN_LEFT);
		/* A keyboard is a device that types letters; --keys adds those to the stream. */
		bool keyboard = want_keys && has_bit(keys, KEY_A) && has_bit(keys, KEY_SPACE)
				&& has_bit(keys, KEY_ENTER);
		if (!mouse && !keyboard) {
			close(fd);
			continue;
		}
		input_fds[input_count] = fd;
		input_is_mouse[input_count] = mouse;
		input_is_keyboard[input_count] = keyboard;
		snprintf(input_paths[input_count], sizeof(input_paths[0]), "%s", path);
		input_count++;
	}
	closedir(directory);
}

static void emit(const char *line) {
	printf("%s\n", line);
	fflush(stdout);
}

/* A press waits for the next position report before it is emitted. evdev delivers the press at
 * once, but KWin reports the move that preceded it a poll later, so a quick move-then-click was
 * recorded at the old position and the auto-zoom aimed at the wrong place. If the pointer is still
 * there is no next report, so the press goes out after PRESS_HOLD_MS anyway. A release that comes
 * while its press is still held waits with it, so the pair keeps its order. */
#define PRESS_HOLD_MS 60
static int pending_press;
static bool pending_release;
static long long pending_press_deadline;

static long long now_ms(void) {
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static void flush_press(void) {
	if (pending_press == 0)
		return;
	char line[40];
	snprintf(line, sizeof(line), "INTERACTION:mousedown:%d", pending_press);
	pending_press = 0;
	emit(line);
	if (pending_release) {
		pending_release = false;
		emit("INTERACTION:mouseup");
	}
}

/* How long poll() may sleep before a held press is due; -1 when none is held. */
static int press_timeout(void) {
	if (pending_press == 0)
		return -1;
	long long left = pending_press_deadline - now_ms();
	return left > 0 ? (int)left : 0;
}

static void read_device(int index) {
	struct input_event events[64];
	for (;;) {
		ssize_t count = read(input_fds[index], events, sizeof(events));
		if (count < 0) {
			if (errno == EAGAIN || errno == EINTR)
				return;
			close(input_fds[index]);
			input_fds[index] = -1;
			return;
		}
		if (count == 0)
			return;
		for (size_t i = 0; i < (size_t)count / sizeof(events[0]); i++) {
			struct input_event *event = &events[i];
			if (event->type != EV_KEY || event->value == 2)
				continue;
			if (event->code < BTN_MISC) {
				if (!want_keys || !input_is_keyboard[index])
					continue;
				char line[48];
				snprintf(line, sizeof(line), "KEY:%s:%d",
						event->value == 1 ? "down" : "up", event->code);
				emit(line);
				continue;
			}
			if (!input_is_mouse[index])
				continue;
			int button = event->code == BTN_LEFT ? 1
				: event->code == BTN_RIGHT ? 2
				: event->code == BTN_MIDDLE ? 3
				: 0;
			if (button == 0)
				continue;
			if (event->value == 1) {
				flush_press();
				pending_press = button;
				pending_press_deadline = now_ms() + PRESS_HOLD_MS;
			} else if (pending_press != 0) {
				pending_release = true;
			} else {
				emit("INTERACTION:mouseup");
			}
		}
	}
}

/* Writes the KWin script to a temporary directory and returns its path. */
static char *write_script(void) {
	char directory[] = "/tmp/gravaai-kwin-XXXXXX";
	if (mkdtemp(directory) == NULL) {
		fprintf(stderr, "ERROR cannot create a script directory: %s\n", strerror(errno));
		return NULL;
	}
	size_t size = strlen(directory) + 64;
	char *path = calloc(1, size);
	if (path == NULL)
		return NULL;
	snprintf(path, size, "%s/cursor.js", directory);

	FILE *file = fopen(path, "w");
	if (file == NULL) {
		fprintf(stderr, "ERROR cannot write the KWin script: %s\n", strerror(errno));
		free(path);
		return NULL;
	}
	/* QTimer is the only scheduling primitive the engine provides; setTimeout does not exist,
	 * and cursorPosChanged does not fire on KWin 6 for pointer motion, so poll instead. */
	fprintf(file,
		"var timer = new QTimer();\n"
		"var lastX = -1, lastY = -1;\n"
		"function push() {\n"
		"    var p = workspace.cursorPos;\n"
		"    if (p.x === lastX && p.y === lastY) return;\n"
		"    lastX = p.x; lastY = p.y;\n"
		"    callDBus(\"%s\", \"%s\", \"%s\", \"Position\", String(p.x), String(p.y));\n"
		"}\n"
		/* One frame at 60 Hz. Clicks come straight from evdev, so a slower poll attributed a
		 * quick move-then-click to the position before the move. Unchanged positions are not
		 * sent, so a still pointer costs nothing on the bus. */
		"timer.interval = 16;\n"
		"timer.timeout.connect(push);\n"
		"timer.start();\n"
		"push();\n",
		SERVICE, OBJECT, INTERFACE);
	fclose(file);
	return path;
}

/* Loads the script into KWin through org.kde.kwin.Scripting. */
static bool load_script(const char *path) {
	DBusError error;
	dbus_error_init(&error);

	DBusMessage *call = dbus_message_new_method_call("org.kde.KWin", "/Scripting",
			"org.kde.kwin.Scripting", "loadScript");
	if (call == NULL)
		return false;
	const char *name = SCRIPT_NAME;
	dbus_message_append_args(call, DBUS_TYPE_STRING, &path, DBUS_TYPE_STRING, &name,
			DBUS_TYPE_INVALID);
	DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, call, 5000, &error);
	dbus_message_unref(call);
	if (reply == NULL) {
		fprintf(stderr, "ERROR KWin refused the cursor script: %s\n",
				error.message != NULL ? error.message : "?");
		dbus_error_free(&error);
		return false;
	}
	dbus_message_unref(reply);

	DBusMessage *start = dbus_message_new_method_call("org.kde.KWin", "/Scripting",
			"org.kde.kwin.Scripting", "start");
	if (start == NULL)
		return false;
	reply = dbus_connection_send_with_reply_and_block(bus, start, 5000, &error);
	dbus_message_unref(start);
	if (reply == NULL) {
		fprintf(stderr, "ERROR KWin refused to start the cursor script: %s\n",
				error.message != NULL ? error.message : "?");
		dbus_error_free(&error);
		return false;
	}
	dbus_message_unref(reply);
	return true;
}

/* Handles the script's Position(x, y) call and prints it in the helper contract's format. */
static DBusHandlerResult on_message(DBusConnection *connection, DBusMessage *message,
		void *user_data) {
	(void)user_data;
	if (!dbus_message_is_method_call(message, INTERFACE, "Position"))
		return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

	DBusError error;
	dbus_error_init(&error);
	const char *x = NULL;
	const char *y = NULL;
	if (!dbus_message_get_args(message, &error, DBUS_TYPE_STRING, &x,
				DBUS_TYPE_STRING, &y, DBUS_TYPE_INVALID)) {
		fprintf(stderr, "ERROR cursor position arguments: %s\n",
				error.message != NULL ? error.message : "?");
		dbus_error_free(&error);
		return DBUS_HANDLER_RESULT_HANDLED;
	}

	printf("POS:%s:%s\n", x, y);
	fflush(stdout);
	flush_press();
	if (!ready_printed) {
		/* The first position means the script is live inside KWin. */
		ready_printed = true;
		printf("READY\n");
		fflush(stdout);
	}

	DBusMessage *reply = dbus_message_new_method_return(message);
	if (reply != NULL) {
		dbus_connection_send(connection, reply, NULL);
		dbus_message_unref(reply);
		/* Flush immediately. callDBus in the compositor's script engine waits for this
		 * reply, and the main loop below blocks in poll() on the bus socket, which is not
		 * writable-readable again until something else arrives. Leaving the reply queued
		 * deadlocks the script after its first call, so the cursor freezes at one spot -
		 * intermittently, since unrelated bus traffic can flush it by accident. */
		dbus_connection_flush(connection);
	}
	return DBUS_HANDLER_RESULT_HANDLED;
}

static const DBusObjectPathVTable vtable = {
	.message_function = on_message,
};

int main(int argc, char **argv) {
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--keys") || !strcmp(argv[i], "--keys-only"))
			want_keys = true;
	}
	signal(SIGPIPE, SIG_IGN);
	signal(SIGTERM, on_terminate);
	signal(SIGINT, on_terminate);

	DBusError error;
	dbus_error_init(&error);
	bus = dbus_bus_get(DBUS_BUS_SESSION, &error);
	if (bus == NULL) {
		fprintf(stderr, "ERROR no session bus: %s\n",
				error.message != NULL ? error.message : "?");
		return 1;
	}
	dbus_connection_set_exit_on_disconnect(bus, FALSE);

	if (!dbus_connection_register_object_path(bus, OBJECT, &vtable, NULL)) {
		fprintf(stderr, "ERROR cannot own %s\n", OBJECT);
		return 1;
	}
	int request = dbus_bus_request_name(bus, SERVICE, DBUS_NAME_FLAG_DO_NOT_QUEUE, &error);
	if (dbus_error_is_set(&error) || request != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER) {
		fprintf(stderr, "ERROR cannot own %s: %s\n", SERVICE,
				error.message != NULL ? error.message : "name taken");
		dbus_error_free(&error);
		return 1;
	}

	char *script = write_script();
	if (script == NULL)
		return 1;
	if (!load_script(script)) {
		free(script);
		return 1;
	}
	free(script);

	/* Serve the script's calls until stdin closes (the app stopping the recording). Both the
	 * bus and stdin are polled so a stop never waits for the next D-Bus timeout. */
	int bus_fd = -1;
	if (!dbus_connection_get_unix_fd(bus, &bus_fd)) {
		fprintf(stderr, "ERROR cannot poll the session bus\n");
		return 1;
	}
	dbus_connection_flush(bus);

	discover_mice();
	if (input_count == 0) {
		fprintf(stderr, "WARNING no mouse devices readable; clicks will not be recorded\n");
	}

	while (running) {
		struct pollfd fds[2 + MAX_INPUTS];
		int count = 2;
		fds[0] = (struct pollfd){ .fd = bus_fd, .events = POLLIN };
		fds[1] = (struct pollfd){ .fd = STDIN_FILENO, .events = POLLIN };
		for (int i = 0; i < input_count; i++) {
			if (input_fds[i] >= 0)
				fds[count++] = (struct pollfd){ .fd = input_fds[i], .events = POLLIN };
		}

		int ready = poll(fds, (nfds_t)count, press_timeout());
		if (ready < 0) {
			if (errno == EINTR)
				continue;
			break;
		}
		if (pending_press != 0 && now_ms() >= pending_press_deadline)
			flush_press();
		if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
			if (!dbus_connection_read_write(bus, 0))
				break;
			/* One socket read can carry several Position calls, and dispatch handles one message
			 * per call. Anything left in libdbus's queue is invisible to poll(), so without this
			 * drain each position waited for the next one to arrive: the recorded pointer lagged
			 * one move behind, and the last position of a still pointer never arrived at all. */
			while (dbus_connection_dispatch(bus) == DBUS_DISPATCH_DATA_REMAINS)
				;
		}
		if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
			char buffer[64];
			ssize_t read_count = read(STDIN_FILENO, buffer, sizeof(buffer));
			if (read_count <= 0)
				break;
		}
		for (int i = 2; i < count; i++) {
			if (fds[i].revents & (POLLIN | POLLERR)) {
				/* Map the poll slot back to the device index. */
				for (int device = 0; device < input_count; device++) {
					if (input_fds[device] == fds[i].fd)
						read_device(device);
				}
			}
		}
	}

	DBusError unload_error;
	dbus_error_init(&unload_error);
	DBusMessage *unload = dbus_message_new_method_call("org.kde.KWin", "/Scripting",
			"org.kde.kwin.Scripting", "unloadScript");
	if (unload != NULL) {
		const char *name = SCRIPT_NAME;
		dbus_message_append_args(unload, DBUS_TYPE_STRING, &name, DBUS_TYPE_INVALID);
		DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, unload, 2000,
				&unload_error);
		if (reply != NULL)
			dbus_message_unref(reply);
		dbus_message_unref(unload);
		dbus_error_free(&unload_error);
	}
	dbus_connection_flush(bus);
	return 0;
}
