// gravaai-ctl: delivers a global shortcut action to the running Gravaai over
// its control socket. COSMIC runs it from the user's custom shortcuts.
//
//   gravaai-ctl <action> [app-path]
//
// When Gravaai is not running and app-path is given, start/meeting actions
// launch the app with --<action> instead.
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

static int socket_path(char *out, size_t size) {
	const char *runtime = getenv("XDG_RUNTIME_DIR");
	int n;
	if (runtime && runtime[0]) {
		n = snprintf(out, size, "%s/gravaai/control.sock", runtime);
	} else {
		const char *tmp = getenv("TMPDIR");
		n = snprintf(out, size, "%s/gravaai-%u/control.sock", tmp && tmp[0] ? tmp : "/tmp",
		             (unsigned)getuid());
	}
	return n > 0 && (size_t)n < size ? 0 : -1;
}

static int send_action(const char *action) {
	struct sockaddr_un addr = {.sun_family = AF_UNIX};
	if (socket_path(addr.sun_path, sizeof addr.sun_path) != 0) return -1;
	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) return -1;
	struct timeval timeout = {.tv_sec = 2};
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
	setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
	if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
		close(fd);
		return -1;
	}
	char line[80];
	int len = snprintf(line, sizeof line, "%s\n", action);
	char reply[16] = {0};
	ssize_t got = -1;
	if (write(fd, line, (size_t)len) == len) got = read(fd, reply, sizeof reply - 1);
	close(fd);
	if (got <= 0) return 1;
	return strncmp(reply, "ok", 2) == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
	if (argc < 2 || strlen(argv[1]) > 64) {
		fprintf(stderr, "usage: gravaai-ctl <action> [app-path]\n");
		return 2;
	}
	const char *action = argv[1];
	int result = send_action(action);
	if (result >= 0) return result;

	int launches = strcmp(action, "toggle-recording") == 0 || strcmp(action, "toggle-meeting") == 0;
	if (argc >= 3 && argv[2][0] && launches) {
		char flag[80];
		snprintf(flag, sizeof flag, "--%s", action);
		execl(argv[2], argv[2], flag, (char *)NULL);
		fprintf(stderr, "gravaai-ctl: could not start %s: %s\n", argv[2], strerror(errno));
		return 1;
	}
	fprintf(stderr, "gravaai-ctl: Gravaai is not running\n");
	return 1;
}
