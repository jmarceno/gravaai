package dev.gravaai.spike;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Writes to stdout and ~/.cache/gravaai-spike/spike.log, so a run started from the binary leaves a record. */
final class Log {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final PrintWriter FILE = open();

	private Log() {}

	private static PrintWriter open() {
		try {
			Path dir = Path.of(System.getProperty("user.home"), ".cache", "gravaai-spike");
			Files.createDirectories(dir);
			return new PrintWriter(Files.newBufferedWriter(dir.resolve("spike.log")), true);
		} catch (IOException e) {
			return null;
		}
	}

	static synchronized void info(String message) {
		String line = LocalTime.now().format(TIME) + " " + message;
		System.out.println(line);
		if (FILE != null) {
			FILE.println(line);
		}
	}

	private static final long LAUNCHED_AT = Long.getLong("gravaai.spike.launchedAt", 0L);

	/**
	 * Milliseconds since launch. The launcher passes the wall-clock launch time in
	 * -Dgravaai.spike.launchedAt; ProcessHandle's start instant is off by ~0.9 s on this kernel.
	 */
	static long sinceProcessStart() {
		return LAUNCHED_AT > 0 ? System.currentTimeMillis() - LAUNCHED_AT : -1L;
	}

	static void error(String message, Throwable error) {
		info("ERROR " + message + ": " + error);
		if (FILE != null) {
			error.printStackTrace(FILE);
		}
		error.printStackTrace();
	}
}
