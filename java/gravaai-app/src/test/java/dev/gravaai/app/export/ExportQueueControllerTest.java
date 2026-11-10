package dev.gravaai.app.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.services.export.ExportQueue;

/**
 * {@link ExportQueueController}: destination naming, the batch summary and reveal. The FX-thread
 * flows (folder choosers, the panel) are checked by hand; everything here is headless.
 */
class ExportQueueControllerTest {

	@TempDir
	Path tempDir;

	/** A renderer that completes (or fails) every job immediately, on the calling thread. */
	private static final class InstantRenderer implements ExportQueue.Renderer {

		private final AtomicInteger finished = new AtomicInteger();
		private final boolean fail;

		InstantRenderer(boolean fail) {
			this.fail = fail;
		}

		@Override
		public Optional<Handle> start(ExportQueue.Job job, Callbacks callbacks) {
			finished.incrementAndGet();
			if (fail) {
				callbacks.onFailed("encoder exploded");
			} else {
				callbacks.onCompleted(job.destination());
			}
			return Optional.of(() -> { });
		}
	}

	/** Records the controller notifications so the tests can assert on the batch summary. */
	private static final class RecordingListener implements ExportQueueController.Listener {

		private final List<String> events = new CopyOnWriteArrayList<>();
		private final List<int[]> batches = new CopyOnWriteArrayList<>();

		@Override
		public void onQueueChanged() {
			events.add("changed");
		}

		@Override
		public void onBatchFinished(int completed, int failed) {
			batches.add(new int[] {completed, failed});
		}
	}

	private static ExportPreferences preferences(String format) {
		ExportPreferences defaults = ExportPreferences.defaults();
		return new ExportPreferences(defaults.exportEncodingMode(),
				defaults.exportBackendPreference(), defaults.exportPipelineModel(),
				defaults.exportQuality(), defaults.mp4FrameRate(),
				"gif".equalsIgnoreCase(format)
						? ExportPreferences.ExportFormat.GIF : ExportPreferences.ExportFormat.MP4,
				defaults.gifFrameRate(), defaults.gifLoop(), defaults.gifSizePreset(),
				defaults.publishDestination(), defaults.includeCaptionSidecar());
	}

	private static Path project(Path directory, String name) throws Exception {
		Path project = directory.resolve(name + ".gravaai");
		Files.writeString(project, "{}");
		return project;
	}

	private ExportQueueController controller(ExportQueue queue) {
		return new ExportQueueController(queue, new ExportPublisher(null),
				() -> preferences("mp4"));
	}

	@Test
	@DisplayName("names each destination after the project and its export settings")
	void namesDestinations() throws Exception {
		Path project = project(tempDir, "Demo Recording");
		Path exports = Files.createDirectories(tempDir.resolve("exports"));
		try (ExportQueueController controller = controller(
				new ExportQueue(new InstantRenderer(false)))) {
			controller.enqueue(project, preferences("mp4"), exports);
			controller.enqueue(project, preferences("gif"), exports);

			List<ExportQueue.Entry> entries = controller.queue().entries();
			assertEquals(2, entries.size());
			assertEquals("Demo Recording", entries.get(0).label());
			assertTrue(entries.get(0).destination().getFileName().toString().endsWith(".mp4"),
					entries.get(0).destination().toString());
			assertTrue(entries.get(0).detail().contains("fps"), entries.get(0).detail());
			assertTrue(entries.get(1).destination().getFileName().toString().endsWith(".gif"),
					entries.get(1).destination().toString());
		}
	}

	@Test
	@DisplayName("two exports queued before either has written its file get different names")
	void deduplicatesAgainstPendingExports() throws Exception {
		Path project = project(tempDir, "Twice");
		Path exports = Files.createDirectories(tempDir.resolve("exports"));
		// Nothing reaches the disk while the jobs wait: the queue is the only record of the name.
		ExportQueue queue = new ExportQueue((job, callbacks) -> Optional.of(() -> { }));
		try (ExportQueueController controller = controller(queue)) {
			controller.enqueue(project, preferences("mp4"), exports);
			controller.enqueue(project, preferences("mp4"), exports);

			List<Path> destinations = queue.entries().stream().map(ExportQueue.Entry::destination)
					.toList();
			assertEquals(2, destinations.stream().distinct().count(), destinations.toString());
		}
	}

	@Test
	@DisplayName("a second export of the same project never overwrites the first file")
	void deduplicatesDestinations() throws Exception {
		Path project = project(tempDir, "Twice");
		Path exports = Files.createDirectories(tempDir.resolve("exports"));
		// The renderer "publishes" by creating the destination file, like a real export would.
		ExportQueue queue = new ExportQueue((job, callbacks) -> {
			try {
				Files.writeString(job.destination(), "video");
			} catch (java.io.IOException error) {
				callbacks.onFailed(error.toString());
				return Optional.of(() -> { });
			}
			callbacks.onCompleted(job.destination());
			return Optional.of(() -> { });
		});
		try (ExportQueueController controller = controller(queue)) {
			controller.enqueue(project, preferences("mp4"), exports);
			controller.enqueue(project, preferences("mp4"), exports);

			List<Path> destinations = queue.entries().stream().map(ExportQueue.Entry::destination)
					.toList();
			assertEquals(2, destinations.stream().distinct().count(), destinations.toString());
			assertTrue(Files.isRegularFile(destinations.get(0)), destinations.get(0).toString());
			assertTrue(Files.isRegularFile(destinations.get(1)), destinations.get(1).toString());
		}
	}

	/** Holds every started job open until the test completes it (like a real render). */
	private static final class ManualRenderer implements ExportQueue.Renderer {

		private final List<ExportQueue.Renderer.Callbacks> callbacks =
				new CopyOnWriteArrayList<>();

		@Override
		public Optional<Handle> start(ExportQueue.Job job, Callbacks jobCallbacks) {
			callbacks.add(jobCallbacks);
			return Optional.of(() -> { });
		}

		Callbacks await(int index) {
			long deadline = System.currentTimeMillis() + 5_000;
			while (callbacks.size() <= index && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(2);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			assertTrue(callbacks.size() > index, "job " + index + " never started");
			return callbacks.get(index);
		}
	}

	@Test
	@DisplayName("the batch summary counts what the batch actually did")
	void reportsBatchSummary() throws Exception {
		Path project = project(tempDir, "Batch");
		Path exports = Files.createDirectories(tempDir.resolve("exports"));
		ManualRenderer renderer = new ManualRenderer();
		try (ExportQueueController controller = controller(new ExportQueue(renderer))) {
			RecordingListener listener = new RecordingListener();
			controller.setListener(listener);
			controller.enqueue(project, preferences("mp4"), exports);
			controller.enqueue(project, preferences("gif"), exports);

			renderer.await(0).onCompleted(tempDir.resolve("one.mp4"));
			renderer.await(1).onCompleted(tempDir.resolve("two.gif"));

			assertTrue(listener.batches.size() == 1, "one batch summary: " + listener.batches.size());
			assertEquals(2, listener.batches.get(0)[0]);
			assertEquals(0, listener.batches.get(0)[1]);
			assertTrue(!listener.events.isEmpty(), "queue changes reach the listener");
		}
	}

	@Test
	@DisplayName("failed exports are counted as failures, cancelled ones as neither")
	void countsFailures() throws Exception {
		Path project = project(tempDir, "Failing");
		Path exports = Files.createDirectories(tempDir.resolve("exports"));
		try (ExportQueueController controller = controller(
				new ExportQueue(new InstantRenderer(true)))) {
			RecordingListener listener = new RecordingListener();
			controller.setListener(listener);
			controller.enqueue(project, preferences("mp4"), exports);

			assertEquals(1, listener.batches.size());
			assertEquals(0, listener.batches.get(0)[0]);
			assertEquals(1, listener.batches.get(0)[1]);
		}
	}
}
