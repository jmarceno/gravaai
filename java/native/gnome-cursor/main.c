// Global pointer position for GNOME (Mutter/gnome-shell).
//
// Mutter exposes no D-Bus method for the pointer position, its Wayland session gives
// ordinary clients no global pointer (ext-image-copy-capture cursor sessions do not
// exist there), and the ScreenCast stream's cursor metadata never reaches a consumer
// process on current mutter. What GNOME does provide is the shell's extension system:
// code running inside gnome-shell, where global.get_pointer() is readable. The
// gravaai-cursor extension polls that value and calls back over D-Bus; this process
// owns the D-Bus name the extension calls, and prints the positions on stdout in
// gravaai-wayland-cursor's format:
//
//   POS:<x>:<y>
//   READY
//
// The same output contract means the Java side needs no GNOME-specific parsing: on
// COSMIC the position comes from the compositor directly, on KDE from a KWin script,
// here from an extension inside gnome-shell.
//
// Usage: gravaai-gnome-cursor [--keys]
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

#define SERVICE "org.gravaai.GnomeCursor"
#define OBJECT "/org/gravaai/GnomeCursor"
#define INTERFACE "org.gravaai.GnomeCursor"

#define MAX_INPUTS 16

static volatile sig_atomic_t running = 1;
static DBusConnection *bus;
static bool ready_printed;
static long long started_at_ms;

static int input_fds[MAX_INPUTS];
static bool input_is_mouse[MAX_INPUTS];
static bool input_is_keyboard[MAX_INPUTS];
static int input_count;
static bool want_keys;

static void on_terminate(int signal_number) {
	(void)signal_number;
	running = 0;
}

/* Mouse buttons come from evdev, the same way gravaai-kwin-cursor gets them: the
 * extension can read the pointer position but not button presses. The app keeps one
 * cursor helper, so clicks have to be reported here rather than by a second process. */
static bool has_bit(const unsigned long *bits, int bit) {
	return (bits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1UL;
}

static void discover_input_devices(void) {
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
		input_count++;
	}
	closedir(directory);
}

/* A press waits for the next position report before it is emitted. evdev delivers the
 * press at once, but the extension reports the move that preceded it a poll later, so a
 * quick move-then-click was recorded at the old position and the auto-zoom aimed at the
 * wrong place. If the pointer stays still there is no next report, so the press goes out
 * after PRESS_HOLD_MS anyway. A release that comes while its press is still held waits
 * with it, so the pair keeps its order. */
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
	printf("%s\n", line);
	if (pending_release) {
		pending_release = false;
		printf("INTERACTION:mouseup\n");
	}
	fflush(stdout);
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
				printf("%s\n", line);
				fflush(stdout);
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
				printf("INTERACTION:mouseup\n");
				fflush(stdout);
			}
		}
	}
}

/* Handles the extension's Position(x, y) call and prints it in the helper contract's
 * format. Mirrors gravaai-kwin-cursor's D-Bus handler. */
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
		/* Answer anyway: a caller that waits for a reply must not hang on a bad call. */
		DBusMessage *failure = dbus_message_new_error(message,
				DBUS_ERROR_INVALID_ARGS, "Position expects two strings");
		if (failure != NULL) {
			dbus_connection_send(connection, failure, NULL);
			dbus_connection_flush(connection);
			dbus_message_unref(failure);
		}
		return DBUS_HANDLER_RESULT_HANDLED;
	}

	printf("POS:%s:%s\n", x, y);
	flush_press();
	if (!ready_printed) {
		/* The first position means the extension is live inside gnome-shell. */
		ready_printed = true;
		printf("READY\n");
	}
	fflush(stdout);

	DBusMessage *reply = dbus_message_new_method_return(message);
	if (reply == NULL)
		return DBUS_HANDLER_RESULT_NEED_MEMORY;
	dbus_connection_send(connection, reply, NULL);
	dbus_message_unref(reply);
	/* Flush immediately. A caller that waits for the reply (gdbus, or an extension
	 * using the synchronous form) would otherwise block forever: the main loop below
	 * only sleeps in poll() and never flushes by itself. Same failure mode as the KWin
	 * bridge, so the same fix. */
	dbus_connection_flush(connection);
	return DBUS_HANDLER_RESULT_HANDLED;
}

/* The extension ships next to this binary (<dir>/gnome-extension/). Copy it into the
 * user's extensions dir when missing or changed, then ask the shell to enable it. A
 * freshly installed extension is only picked up at the next login on Wayland, so the
 * first recording after install has no pointer track; the warning below says so. */
#define EXTENSION_UUID "gravaai-cursor@gravaai.dev"

static bool copy_if_changed(const char *from, const char *to) {
	FILE *in = fopen(from, "rb");
	if (in == NULL)
		return false;
	char source[65536];
	size_t length = fread(source, 1, sizeof(source), in);
	fclose(in);
	FILE *existing = fopen(to, "rb");
	if (existing != NULL) {
		char current[65536];
		size_t current_length = fread(current, 1, sizeof(current), existing);
		fclose(existing);
		if (current_length == length && memcmp(current, source, length) == 0)
			return false;
	}
	FILE *out = fopen(to, "wb");
	if (out == NULL)
		return false;
	fwrite(source, 1, length, out);
	fclose(out);
	return true;
}

static void install_extension(void) {
	char self[4096];
	ssize_t self_length = readlink("/proc/self/exe", self, sizeof(self) - 1);
	if (self_length <= 0)
		return;
	self[self_length] = '\0';
	char *slash = strrchr(self, '/');
	if (slash == NULL)
		return;
	*slash = '\0';

	const char *home = getenv("HOME");
	const char *data = getenv("XDG_DATA_HOME");
	char target[4096];
	if (data != NULL && data[0] == '/')
		snprintf(target, sizeof(target), "%s/gnome-shell/extensions/" EXTENSION_UUID, data);
	else if (home != NULL)
		snprintf(target, sizeof(target), "%s/.local/share/gnome-shell/extensions/" EXTENSION_UUID, home);
	else
		return;
	/* mkdir -p */
	for (char *p = target + 1; *p != '\0'; p++) {
		if (*p == '/') {
			*p = '\0';
			mkdir(target, 0755);
			*p = '/';
		}
	}
	mkdir(target, 0755);

	bool changed = false;
	const char *files[] = { "extension.js", "metadata.json" };
	for (size_t i = 0; i < sizeof(files) / sizeof(files[0]); i++) {
		char from[4400];
		char to[4400];
		snprintf(from, sizeof(from), "%s/gnome-extension/%s", self, files[i]);
		snprintf(to, sizeof(to), "%s/%s", target, files[i]);
		changed |= copy_if_changed(from, to);
	}
	if (changed)
		fprintf(stderr, "INFO installed the gravaai-cursor extension into %s\n", target);

	/* Enable it. EnableExtension also adds it to org.gnome.shell enabled-extensions,
	 * so after the next login it loads on its own. */
	DBusMessage *call = dbus_message_new_method_call("org.gnome.Shell",
			"/org/gnome/Shell", "org.gnome.Shell.Extensions", "EnableExtension");
	if (call == NULL)
		return;
	const char *uuid = EXTENSION_UUID;
	dbus_message_append_args(call, DBUS_TYPE_STRING, &uuid, DBUS_TYPE_INVALID);
	DBusError error;
	dbus_error_init(&error);
	DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, call, 2000, &error);
	dbus_message_unref(call);
	if (reply == NULL) {
		fprintf(stderr, "WARNING cannot enable the gravaai-cursor extension: %s\n",
				error.message != NULL ? error.message : "?");
		dbus_error_free(&error);
		return;
	}
	dbus_bool_t enabled = FALSE;
	dbus_message_get_args(reply, NULL, DBUS_TYPE_BOOLEAN, &enabled, DBUS_TYPE_INVALID);
	dbus_message_unref(reply);
	if (!enabled)
		fprintf(stderr, "WARNING gnome-shell has not loaded the gravaai-cursor extension yet; "
				"log out and back in once to activate it\n");
}

static const DBusObjectPathVTable vtable = {
	.message_function = on_message,
};

int main(int argc, char **argv) {
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--keys"))
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

	if (!dbus_connection_register_fallback(bus, OBJECT, &vtable, NULL)) {
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

	int bus_fd = -1;
	if (!dbus_connection_get_unix_fd(bus, &bus_fd)) {
		fprintf(stderr, "ERROR cannot poll the session bus\n");
		return 1;
	}
	dbus_connection_flush(bus);
	install_extension();

	discover_input_devices();
	if (input_count == 0) {
		fprintf(stderr, "WARNING no mouse devices readable; clicks will not be recorded\n");
	}
	started_at_ms = now_ms();

	while (running) {
		struct pollfd fds[1 + MAX_INPUTS];
		int count = 1;
		fds[0] = (struct pollfd){ .fd = bus_fd, .events = POLLIN };
		for (int i = 0; i < input_count; i++) {
			if (input_fds[i] >= 0)
				fds[count++] = (struct pollfd){ .fd = input_fds[i], .events = POLLIN };
		}

		/* While waiting for the first position, poll in short steps so a missing or
		 * disabled extension is reported instead of hanging silently. */
		int timeout = ready_printed ? press_timeout() : 100;
		int ready = poll(fds, (nfds_t)count, timeout);
		if (ready < 0) {
			if (errno == EINTR)
				continue;
			break;
		}
		if (pending_press != 0 && now_ms() >= pending_press_deadline)
			flush_press();
		if (!ready_printed && now_ms() - started_at_ms > 4000) {
			fprintf(stderr, "WARNING the gravaai-cursor extension is not reporting "
					"positions; install it and re-enable it in the shell\n");
			started_at_ms = now_ms();
		}
		if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
			if (!dbus_connection_read_write(bus, 0))
				break;
			/* One socket read can carry several Position calls, and dispatch handles one
			 * message per call. Anything left in libdbus's queue is invisible to poll(),
			 * so without this drain each position waited for the next one to arrive: the
			 * recorded pointer lagged one move behind. Same as the KWin bridge. */
			while (dbus_connection_dispatch(bus) == DBUS_DISPATCH_DATA_REMAINS)
				;
		}
		for (int i = 1; i < count; i++) {
			if (fds[i].revents & (POLLIN | POLLERR)) {
				/* Map the poll slot back to the device index. */
				for (int device = 0; device < input_count; device++) {
					if (input_fds[device] == fds[i].fd)
						read_device(device);
				}
			}
		}
	}

	dbus_connection_flush(bus);
	return 0;
}
