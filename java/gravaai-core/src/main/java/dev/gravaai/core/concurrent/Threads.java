package dev.gravaai.core.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Helpers for the thread conventions (plan §4.1: virtual threads for blocking IO and process
 * waiting, platform threads for native code).
 *
 * <p>The logging rule: work started here is fire-and-forget, so a failure must not vanish into
 * a discarded future — it is logged with the thread's name instead (a silent failure left the
 * user on a stuck spinner).
 */
public final class Threads {

	private static final System.Logger LOG = System.getLogger("gravaai.threads");

	private Threads() {
	}

	/**
	 * Starts a named virtual thread for fire-and-forget work. Any failure is logged and cannot
	 * propagate; the thread dies as if the body had completed.
	 */
	public static Thread startVirtual(String name, Runnable body) {
		return Thread.ofVirtual().name(name).start(() -> {
			try {
				body.run();
			} catch (Throwable error) {
				LOG.log(System.Logger.Level.ERROR, name + " failed: " + error, error);
			}
		});
	}

	/**
	 * A single-thread daemon executor for serialized background work — the shared replacement
	 * for the {@code newSingleThreadExecutor(r -> { … setDaemon(true) … })} boilerplate.
	 */
	public static ExecutorService daemon(String name) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		});
	}

	/** {@link #daemon(String)} for the scheduled variants of the same boilerplate. */
	public static ScheduledExecutorService daemonScheduler(String name) {
		return Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		});
	}
}
