package dev.gravaai.services.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.services.export.ExportQueue.Renderer;
import dev.gravaai.services.export.ExportQueue.State;

/** {@link ExportQueue}: sequential dispatch, cancel, reorder, retry-on-busy and history. */
class ExportQueueTest {

	@TempDir
	Path tempDir;

	/** A renderer that holds every started job open until the test completes it. */
	private static final class ManualRenderer implements Renderer {

		private final List<Started> started = new CopyOnWriteArrayList<>();
		private final List<Long> order = new CopyOnWriteArrayList<>();
		private final AtomicInteger cancels = new AtomicInteger();

		/** Set by a test to simulate the shared coordinator being busy with another export. */
		private final AtomicBoolean pipelineBusy = new AtomicBoolean();

		record Started(long id, Callbacks callbacks) {}

		@Override
		public Optional<Handle> start(ExportQueue.Job job, Callbacks callbacks) {
			if (pipelineBusy.get()) {
				return Optional.empty();
			}
			order.add(job.id());
			started.add(new Started(job.id(), callbacks));
			return Optional.of(cancels::incrementAndGet);
		}

		Started awaitStarted(int index) {
			long deadline = System.currentTimeMillis() + 5_000;
			while (started.size() <= index && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(2);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			assertTrue(started.size() > index, "job " + index + " never started");
			return started.get(index);
		}

		List<Long> order() {
			return List.copyOf(order);
		}

		int cancelCount() {
			return cancels.get();
		}
	}

	private long enqueue(ExportQueue queue, String label) {
		return queue.enqueue(label, "MP4 · 1080p", tempDir.resolve(label + ".gravaai"),
				tempDir.resolve(label + ".mp4"), ExportPreferences.defaults());
	}

	private static List<State> states(ExportQueue queue) {
		List<State> states = new ArrayList<>();
		for (ExportQueue.Entry entry : queue.entries()) {
			states.add(entry.state());
		}
		return states;
	}

	@Test
	@DisplayName("a pump that arrives while the renderer answers busy is replayed, not lost")
	void pumpDuringABusyAnswerIsReplayed() {
		// The pipeline goes idle while the renderer is still answering "busy": the idle hook's
		// pump lands mid-start and must not be swallowed, or the job would wait forever.
		AtomicInteger attempts = new AtomicInteger();
		ExportQueue[] holder = new ExportQueue[1];
		ExportQueue queue = new ExportQueue((job, callbacks) -> {
			if (attempts.incrementAndGet() == 1) {
				holder[0].pump();
				return Optional.empty();
			}
			return Optional.of(() -> { });
		});
		holder[0] = queue;

		enqueue(queue, "a");

		assertEquals(2, attempts.get(), "the idle pump must trigger a second start");
		assertEquals(List.of(State.RUNNING), states(queue));
		queue.close();
	}

	@Test
	@DisplayName("queued exports run one after another without user action")
	void runsSequentially() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			enqueue(queue, "b");
			enqueue(queue, "c");
			ManualRenderer.Started first = renderer.awaitStarted(0);
			assertEquals(List.of(1L), renderer.order());
			assertEquals(List.of(State.RUNNING, State.QUEUED, State.QUEUED), states(queue));

			first.callbacks().onCompleted(tempDir.resolve("a.mp4"));
			ManualRenderer.Started second = renderer.awaitStarted(1);
			assertEquals(List.of(1L, 2L), renderer.order());
			assertEquals(List.of(State.COMPLETED, State.RUNNING, State.QUEUED), states(queue));

			second.callbacks().onCompleted(tempDir.resolve("b.mp4"));
			ManualRenderer.Started third = renderer.awaitStarted(2);
			assertEquals(List.of(1L, 2L, 3L), renderer.order());
			third.callbacks().onCompleted(tempDir.resolve("c.mp4"));

			assertEquals(List.of(State.COMPLETED, State.COMPLETED, State.COMPLETED), states(queue));
			assertFalse(queue.hasActive());
			assertFalse(queue.isRunning());
		}
	}

	@Test
	@DisplayName("a queued export can be cancelled before it starts")
	void cancelsQueuedJob() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			long a = enqueue(queue, "a");
			long b = enqueue(queue, "b");
			renderer.awaitStarted(0);

			assertTrue(queue.cancel(b));
			assertEquals(List.of(State.RUNNING, State.CANCELLED), states(queue));

			renderer.awaitStarted(0).callbacks().onCompleted(tempDir.resolve("a.mp4"));
			assertEquals(List.of(State.COMPLETED, State.CANCELLED), states(queue));
			assertEquals(1, renderer.order().size(), "the cancelled export must never start");
			assertFalse(queue.cancel(a), "a finished export cannot be cancelled again");
		}
	}

	@Test
	@DisplayName("cancelling the running export stops it and starts the next one")
	void cancelsRunningJob() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			long a = enqueue(queue, "a");
			long b = enqueue(queue, "b");
			renderer.awaitStarted(0);

			assertTrue(queue.cancel(a));
			assertEquals(1, renderer.cancelCount());
			assertEquals(List.of(State.CANCELLED, State.RUNNING), states(queue));
			assertEquals(List.of(1L, 2L), renderer.order());

			// The cancelled render reports late; its callbacks must not touch the queue any more.
			renderer.awaitStarted(0).callbacks().onCancelled();
			assertEquals(List.of(State.CANCELLED, State.RUNNING), states(queue));

			renderer.awaitStarted(1).callbacks().onCompleted(tempDir.resolve("b.mp4"));
			assertEquals(List.of(State.CANCELLED, State.COMPLETED), states(queue));
		}
	}

	@Test
	@DisplayName("a job stays queued while the pipeline is busy and starts on the next pump")
	void retriesWhenPipelineIsBusy() {
		ManualRenderer renderer = new ManualRenderer();
		renderer.pipelineBusy.set(true);
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			assertEquals(List.of(State.QUEUED), states(queue));
			assertTrue(queue.hasActive());

			renderer.pipelineBusy.set(false);
			queue.pump();
			ManualRenderer.Started started = renderer.awaitStarted(0);
			assertEquals(List.of(State.RUNNING), states(queue));
			started.callbacks().onCompleted(tempDir.resolve("a.mp4"));
			assertFalse(queue.hasActive());
		}
	}

	@Test
	@DisplayName("queued exports can be reordered")
	void reordersQueuedJobs() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			long b = enqueue(queue, "b");
			long c = enqueue(queue, "c");
			renderer.awaitStarted(0);

			assertTrue(queue.move(c, -1));
			assertEquals(List.of(1L, 3L, 2L), ids(queue));

			assertTrue(queue.move(b, -1));
			assertEquals(List.of(1L, 2L, 3L), ids(queue));

			assertFalse(queue.move(c, 5), "no queued neighbour that far away");
			assertFalse(queue.move(1L, -1), "the running export cannot move");
		}
	}

	private static List<Long> ids(ExportQueue queue) {
		return queue.entries().stream().map(ExportQueue.Entry::id).toList();
	}

	@Test
	@DisplayName("progress reaches the entry and is cleared when it finishes")
	void keepsProgress() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			ManualRenderer.Started started = renderer.awaitStarted(0);
			started.callbacks().onProgress(ExportProgress.of(10, 100, 1.0, 0.0));
			ExportProgress progress = java.util.Objects.requireNonNull(
					queue.entries().get(0).progress(), "progress must reach the entry");
			assertEquals(10, progress.currentFrame());

			started.callbacks().onCompleted(tempDir.resolve("a.mp4"));
			ExportQueue.Entry finished = queue.entries().get(0);
			assertEquals(State.COMPLETED, finished.state());
			assertEquals(tempDir.resolve("a.mp4"), finished.result());
			assertEquals(null, finished.progress());
			assertEquals(null, finished.error());
		}
	}

	@Test
	@DisplayName("a failing export is recorded and the queue continues")
	void continuesAfterFailure() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			enqueue(queue, "b");
			renderer.awaitStarted(0).callbacks().onFailed("codec exploded");
			ManualRenderer.Started second = renderer.awaitStarted(1);

			ExportQueue.Entry failed = queue.entries().get(0);
			assertEquals(State.FAILED, failed.state());
			assertEquals("codec exploded", failed.error());
			assertEquals(State.RUNNING, queue.entries().get(1).state());
			second.callbacks().onCompleted(tempDir.resolve("b.mp4"));
			assertFalse(queue.hasActive());
		}
	}

	@Test
	@DisplayName("a renderer that throws fails the entry and the queue moves on")
	void rendererThrows() {
		AtomicInteger calls = new AtomicInteger();
		ExportQueue queue = new ExportQueue((job, callbacks) -> {
			if (calls.getAndIncrement() == 0) {
				throw new IllegalStateException("no source media");
			}
			return Optional.empty();
		});
		try (queue) {
			enqueue(queue, "a");
			enqueue(queue, "b");
			assertEquals(List.of(State.FAILED, State.QUEUED), states(queue));
			assertEquals("no source media", queue.entries().get(0).error());
		}
	}

	@Test
	@DisplayName("clearFinished drops history and keeps pending work")
	void clearsFinished() {
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueue queue = new ExportQueue(renderer)) {
			enqueue(queue, "a");
			enqueue(queue, "b");
			renderer.awaitStarted(0).callbacks().onCompleted(tempDir.resolve("a.mp4"));
			renderer.awaitStarted(1);

			queue.clearFinished();
			assertEquals(List.of(2L), ids(queue));
			assertEquals(State.RUNNING, queue.entries().get(0).state());
		}
	}

	@Test
	@DisplayName("closing the queue cancels the running export and refuses new jobs")
	void closeCancels() {
		ManualRenderer renderer = new ManualRenderer();
		ExportQueue queue = new ExportQueue(renderer);
		enqueue(queue, "a");
		enqueue(queue, "b");
		renderer.awaitStarted(0);

		queue.close();
		assertEquals(1, renderer.cancelCount());
		assertEquals(List.of(State.CANCELLED, State.CANCELLED), states(queue));
		assertFalse(queue.hasActive());
		boolean rejected = false;
		try {
			enqueue(queue, "c");
		} catch (IllegalStateException expected) {
			rejected = true;
		}
		assertTrue(rejected, "a closed queue must reject new jobs");
	}
}
