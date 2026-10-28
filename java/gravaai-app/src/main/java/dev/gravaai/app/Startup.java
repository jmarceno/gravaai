package dev.gravaai.app;

/**
 * Start-up timing (plan §5.1, plan §3 gotcha 3).
 *
 * <p>{@code ProcessHandle.current().info().startInstant()} is unreliable on this kernel, so the
 * process start is taken from {@code -Dgravaai.launchedAt} (wall-clock epoch ms) or the
 * {@code GRAVAAI_LAUNCHED_AT} environment variable. The startup check and the AppImage
 * {@code AppRun} set the variable before the JVM boots.
 */
public final class Startup {

	private static final System.Logger LOG = System.getLogger("gravaai");
	private static long launchedAtMs = -1;

	private Startup() {}

	/** Forces resolution now; called first thing in {@link Main#main(String[])}. */
	public static void mark() {
		launchedAtMs();
	}

	public static long sinceLaunchMs() {
		return System.currentTimeMillis() - launchedAtMs();
	}

	/** Logs {@code startup: <what> at N ms}. The startup check parses these lines. */
	public static void log(String what) {
		LOG.log(System.Logger.Level.INFO, "startup: " + what + " at " + sinceLaunchMs() + " ms");
	}

	private static synchronized long launchedAtMs() {
		if (launchedAtMs < 0) {
			launchedAtMs = resolveLaunchedAt();
		}
		return launchedAtMs;
	}

	private static long resolveLaunchedAt() {
		String property = System.getProperty("gravaai.launchedAt");
		if (property != null && !property.isBlank()) {
			try {
				return Long.parseLong(property.strip());
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		String env = System.getenv("GRAVAAI_LAUNCHED_AT");
		if (env != null && !env.isBlank()) {
			try {
				return Long.parseLong(env.strip());
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		// Without an injected wall clock, assume the launch is now: this under-reports by the
		// JVM boot time (~30 ms, plan Appendix C) but never reports a negative number.
		return System.currentTimeMillis();
	}
}
