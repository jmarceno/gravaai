package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The recording countdown, ported from {@code electron/countdownController.ts}.
 *
 * <p>Every countdown exit settles the pending request, including cancellation before the
 * countdown window finished loading. The UI supplies {@link CountdownWindow} and receives ticks
 * through it plus {@code onRemaining} for the HUD.
 */
public final class CountdownService {

	/** The TS result shape {@code { success, cancelled?, error? }}. */
	public record CountdownResult(boolean success, boolean cancelled, @Nullable String error) {

		public static CountdownResult ok() {
			return new CountdownResult(true, false, null);
		}

		public static CountdownResult cancelledResult() {
			return new CountdownResult(false, true, null);
		}

		public static CountdownResult failed(String error) {
			return new CountdownResult(false, false, error);
		}
	}

	/** Timers are injectable so the countdown is testable without waiting. */
	public interface Scheduler {

		Cancelable schedule(Runnable task, long delayMs);

		interface Cancelable {
			void cancel();
		}

		/** A scheduler backed by a {@link ScheduledExecutorService}. */
		static Scheduler of(ScheduledExecutorService executor) {
			return (task, delayMs) -> {
				ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
				return () -> future.cancel(false);
			};
		}

		static Scheduler createDefault() {
			return of(Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "gravaai-countdown");
				thread.setDaemon(true);
				return thread;
			}));
		}
	}

	/** The countdown window the UI creates; listeners may fire more than once. */
	public interface CountdownWindow {

		void onClosed(Runnable listener);

		void onDidFailLoad(Runnable listener);

		void onRenderProcessGone(Runnable listener);

		boolean isLoadingMainFrame();

		void onLoaded(Runnable listener);

		void sendTick(int remaining);

		boolean isDestroyed();

		void close();
	}

	/** Creates the countdown window; implemented by {@code dev.gravaai.ui.overlays}. */
	public interface CountdownWindowFactory {
		CountdownWindow create();
	}

	private final CountdownWindowFactory windowFactory;
	private final Consumer<Integer> onRemaining;
	private final java.util.List<Consumer<Integer>> remainingListeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();
	private final Scheduler scheduler;
	private final Object lock = new Object();

	private @Nullable Runnable finishActive;
	private @Nullable Integer activeRemaining;

	public CountdownService(
			CountdownWindowFactory windowFactory,
			Consumer<Integer> onRemaining,
			Scheduler scheduler) {
		this.windowFactory = windowFactory;
		this.onRemaining = onRemaining;
		this.scheduler = scheduler;
	}

	/** Subscribes to countdown ticks ({@code null} when the countdown ends); close to unsubscribe. */
	public AutoCloseable onRemainingChanged(Consumer<Integer> listener) {
		remainingListeners.add(listener);
		return () -> remainingListeners.remove(listener);
	}

	private void notifyRemaining(@Nullable Integer remaining) {
		onRemaining.accept(remaining);
		for (Consumer<Integer> listener : remainingListeners) {
			listener.accept(remaining);
		}
	}

	/** Starts a countdown, or fails when one is already in progress or the delay is invalid. */
	public CompletableFuture<CountdownResult> start(double seconds) {
		CountdownState state;
		synchronized (lock) {
			if (finishActive != null) {
				return CompletableFuture.completedFuture(
						CountdownResult.failed("Countdown already in progress"));
			}
			if (!Double.isFinite(seconds) || seconds < 0) {
				return CompletableFuture.completedFuture(
						CountdownResult.failed("Invalid countdown delay"));
			}
			state = new CountdownState((int) Math.ceil(seconds));
			finishActive = state::cancel;
			activeRemaining = state.remaining;
			notifyRemaining(state.remaining);
		}
		state.open();
		return state.future;
	}

	/** Cancels the active countdown; the pending start settles with {@code cancelled}. */
	public CountdownResult cancel() {
		Runnable finish;
		synchronized (lock) {
			finish = finishActive;
		}
		if (finish != null) {
			finish.run();
		}
		return CountdownResult.ok();
	}

	/** Remaining seconds of the active countdown, or null when none is running. */
	public @Nullable Integer getActiveCountdown() {
		synchronized (lock) {
			return activeRemaining;
		}
	}

	private final class CountdownState {

		private final CompletableFuture<CountdownResult> future = new CompletableFuture<>();
		private @Nullable CountdownWindow window;
		private Scheduler.@Nullable Cancelable timer;
		private boolean settled;
		private boolean started;
		private int remaining;

		CountdownState(int remaining) {
			this.remaining = remaining;
		}

		void cancel() {
			finish(CountdownResult.cancelledResult());
		}

		private void finish(CountdownResult result) {
			synchronized (lock) {
				if (settled) {
					return;
				}
				settled = true;
				if (timer != null) {
					timer.cancel();
				}
				finishActive = null;
				activeRemaining = null;
			}
			notifyRemaining(null);
			future.complete(result);
			if (window != null && !window.isDestroyed()) {
				window.close();
			}
		}

		private void begin() {
			synchronized (lock) {
				if (settled || started) {
					return;
				}
				started = true;
				if (remaining == 0) {
					finish(CountdownResult.ok());
					return;
				}
			}
			tick();
			if (settled) {
				return;
			}
			scheduleNextTick();
		}

		private void scheduleNextTick() {
			synchronized (lock) {
				if (settled) {
					return;
				}
				timer = scheduler.schedule(this::tickTask, 1000);
			}
		}

		private void tickTask() {
			synchronized (lock) {
				if (settled) {
					return;
				}
				remaining--;
				if (remaining <= 0) {
					finish(CountdownResult.ok());
					return;
				}
			}
			tick();
			if (settled) {
				return;
			}
			scheduleNextTick();
		}

		private void tick() {
			synchronized (lock) {
				if (settled) {
					return;
				}
				activeRemaining = remaining;
			}
			notifyRemaining(remaining);
			CountdownWindow activeWindow = window;
			if (activeWindow == null) {
				return;
			}
			activeWindow.sendTick(remaining);
		}

		void open() {
			try {
				window = windowFactory.create();
				window.onClosed(this::cancel);
				window.onDidFailLoad(() ->
						finish(CountdownResult.failed("Countdown window failed to load")));
				window.onRenderProcessGone(this::cancel);
				if (window.isLoadingMainFrame()) {
					window.onLoaded(this::begin);
				} else {
					begin();
				}
			} catch (RuntimeException error) {
				finish(CountdownResult.failed(String.valueOf(error)));
			}
		}
	}
}
