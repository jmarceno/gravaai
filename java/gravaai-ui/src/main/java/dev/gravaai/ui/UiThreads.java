package dev.gravaai.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The UI module's background executor (plan §4.1: virtual threads for blocking IO). Views run
 * service and file work on {@link #background()} and hop the result back to the FX application
 * thread with {@code thenAcceptAsync(..., Platform::runLater)}.
 *
 * <p>Threads are named so a stuck view is identifiable in a thread dump. The executor is shared
 * for the app lifetime: virtual threads are daemon threads and a per-task executor parks nothing
 * between tasks, so there is no shutdown step.
 */
public final class UiThreads {

	private static final ExecutorService BACKGROUND =
			Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("gravaai-ui-", 0).factory());

	private UiThreads() {
	}

	/** The shared virtual-thread-per-task executor for UI background work. */
	public static Executor background() {
		return BACKGROUND;
	}
}
