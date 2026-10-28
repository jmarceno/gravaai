package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.gravaai.services.recording.CountdownService.CountdownResult;

/** Ported from {@code electron/countdownController.test.ts}; the UI window and timers are fakes. */
class CountdownServiceTest {

	private static final class FakeScheduler implements CountdownService.Scheduler {

		private final List<Task> tasks = new ArrayList<>();
		private long now;

		private final class Task implements Cancelable {

			private final long at;
			private final Runnable runnable;
			private boolean cancelled;

			Task(long at, Runnable runnable) {
				this.at = at;
				this.runnable = runnable;
			}

			@Override
			public void cancel() {
				cancelled = true;
			}
		}

		@Override
		public Cancelable schedule(Runnable task, long delayMs) {
			Task scheduled = new Task(now + delayMs, task);
			tasks.add(scheduled);
			return scheduled;
		}

		void advance(long millis) {
			long target = now + millis;
			while (true) {
				Task next = tasks.stream()
						.filter(task -> !task.cancelled && task.at <= target)
						.min(Comparator.comparingLong(task -> task.at))
						.orElse(null);
				if (next == null) {
					break;
				}
				now = next.at;
				tasks.remove(next);
				next.runnable.run();
			}
			now = target;
		}

		int pending() {
			return (int) tasks.stream().filter(task -> !task.cancelled).count();
		}
	}

	private static final class FakeWindow implements CountdownService.CountdownWindow {

		private final boolean loading;
		private final List<Integer> ticks = new ArrayList<>();
		private Runnable closed;
		private Runnable failedLoad;
		private Runnable gone;
		private Runnable loaded;
		private boolean destroyed;

		FakeWindow(boolean loading) {
			this.loading = loading;
		}

		@Override
		public void onClosed(Runnable listener) {
			closed = listener;
		}

		@Override
		public void onDidFailLoad(Runnable listener) {
			failedLoad = listener;
		}

		@Override
		public void onRenderProcessGone(Runnable listener) {
			gone = listener;
		}

		@Override
		public boolean isLoadingMainFrame() {
			return loading;
		}

		@Override
		public void onLoaded(Runnable listener) {
			loaded = listener;
		}

		@Override
		public void sendTick(int remaining) {
			ticks.add(remaining);
		}

		@Override
		public boolean isDestroyed() {
			return destroyed;
		}

		@Override
		public void close() {
			destroyed = true;
			if (closed != null) {
				closed.run();
			}
		}

		void emitClosed() {
			if (closed != null) {
				closed.run();
			}
		}

		void emitRenderProcessGone() {
			if (gone != null) {
				gone.run();
			}
		}

		void emitDidFailLoad() {
			if (failedLoad != null) {
				failedLoad.run();
			}
		}

		void emitLoaded() {
			if (loaded != null) {
				loaded.run();
			}
		}
	}

	private final FakeScheduler scheduler = new FakeScheduler();
	private final List<Integer> remaining = new ArrayList<>();

	private CountdownService service(FakeWindow window) {
		return new CountdownService(() -> window, remaining::add, scheduler);
	}

	@Test
	@DisplayName("cancels an active countdown immediately and allows another start")
	void cancelsActiveCountdownImmediately() {
		FakeWindow window = new FakeWindow(false);
		CountdownService service = service(window);
		CompletableFuture<CountdownResult> pending = service.start(3);
		service.cancel();

		assertEquals(CountdownResult.cancelledResult(), pending.join());
		assertEquals(null, remaining.get(remaining.size() - 1));
		assertEquals(0, scheduler.pending());

		FakeWindow nextWindow = new FakeWindow(false);
		CountdownService nextService = new CountdownService(() -> nextWindow, remaining::add, scheduler);
		CompletableFuture<CountdownResult> next = nextService.start(1);
		scheduler.advance(1000);
		assertEquals(CountdownResult.ok(), next.join());
	}

	@Test
	@DisplayName("settles cancellation before the countdown renderer finishes loading")
	void settlesCancellationBeforeLoadingFinishes() {
		FakeWindow window = new FakeWindow(true);
		CountdownService service = service(window);
		CompletableFuture<CountdownResult> pending = service.start(3);
		service.cancel();

		assertEquals(CountdownResult.cancelledResult(), pending.join());
		window.emitLoaded();
		assertEquals(List.of(), window.ticks);
		assertEquals(0, scheduler.pending());
	}

	@ParameterizedTest(name = "settles when the window exits through {0}")
	@ValueSource(strings = {"closed", "render-process-gone"})
	@DisplayName("settles when the window exits")
	void settlesWhenTheWindowExits(String event) {
		FakeWindow window = new FakeWindow(true);
		CountdownService service = service(window);
		CompletableFuture<CountdownResult> pending = service.start(3);
		if ("closed".equals(event)) {
			window.emitClosed();
		} else {
			window.emitRenderProcessGone();
		}
		assertEquals(CountdownResult.cancelledResult(), pending.join());
	}

	@Test
	@DisplayName("settles loading failures and releases the countdown lock")
	void settlesLoadingFailuresAndReleasesTheLock() {
		FakeWindow window = new FakeWindow(true);
		CountdownService service = service(window);
		CompletableFuture<CountdownResult> pending = service.start(3);
		window.emitDidFailLoad();
		CountdownResult failed = pending.join();
		assertEquals(false, failed.success());
		assertNotNull(failed.error());

		FakeWindow nextWindow = new FakeWindow(true);
		CountdownService nextService = new CountdownService(() -> nextWindow, remaining::add, scheduler);
		CompletableFuture<CountdownResult> next = nextService.start(1);
		nextWindow.emitLoaded();
		scheduler.advance(1000);
		assertEquals(CountdownResult.ok(), next.join());
	}

	@Test
	@DisplayName("rejects concurrent starts and emits ticks until normal completion")
	void rejectsConcurrentStartsAndEmitsTicks() {
		FakeWindow window = new FakeWindow(false);
		CountdownService service = service(window);
		CompletableFuture<CountdownResult> pending = service.start(2);
		CountdownResult concurrent = service.start(3).join();
		assertEquals(false, concurrent.success());
		assertNotNull(concurrent.error());

		scheduler.advance(2000);
		assertEquals(CountdownResult.ok(), pending.join());
		assertEquals(List.of(2, 1), window.ticks);
		assertEquals(0, scheduler.pending());
	}

	@Test
	@DisplayName("resets state if creating the countdown window fails")
	void resetsStateWhenCreatingTheWindowFails() {
		CountdownService service = new CountdownService(
				() -> {
					throw new IllegalStateException("window unavailable");
				},
				remaining::add,
				scheduler);
		CountdownResult result = service.start(3).join();
		assertEquals(false, result.success());
		assertNotNull(result.error());
		assertEquals(null, remaining.get(remaining.size() - 1));
	}
}
