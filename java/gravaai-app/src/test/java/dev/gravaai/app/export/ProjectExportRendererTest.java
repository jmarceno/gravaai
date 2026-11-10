package dev.gravaai.app.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.SyntheticFrameProvider;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.services.export.ExportQueue;
import dev.gravaai.services.export.ExportQueue.State;
import dev.gravaai.services.export.ProjectExport;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ProjectExportRenderer}: the queue's render half through the synthetic frame-pipeline seam.
 *
 * <p>Every test drives a real {@link ExportQueue} and a real {@link ExportCoordinator}; only the
 * compositor is replaced by {@link SyntheticFrameProvider}, so no GL context is needed. The
 * fixtures are the parity media (a 2 s H.264 clip) and a project JSON written into a temp
 * directory, following {@code ProjectExportTest}.
 */
class ProjectExportRendererTest {

	@TempDir
	Path tempDir;

	private static Path fixturesDirectory() {
		Path directory = Path.of("").toAbsolutePath();
		for (int depth = 0; depth < 6 && directory != null; depth++) {
			Path candidate = directory.resolve(Path.of("parity", "fixtures", "media"));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		throw new IllegalStateException("parity fixtures not found");
	}

	private static Path media(String name) {
		return fixturesDirectory().resolve(name);
	}

	/** A 2 s project: one clip 0-2000 ms of the 2 s H.264 fixture. */
	private static ObjectNode twoSecondEditor() {
		ObjectNode editor = Json.nodeFactory().objectNode();
		editor.put("aspectRatio", "16:9");
		var clips = editor.putArray("clipRegions");
		var clip = clips.addObject();
		clip.put("id", "clip-1");
		clip.put("startMs", 0);
		clip.put("endMs", 2000);
		editor.putArray("trimRegions");
		editor.putArray("speedRegions");
		editor.putArray("audioRegions");
		editor.putArray("autoCaptions");
		return editor;
	}

	private static ExportPreferences mp4Preferences() {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("exportQuality", ExportQuality.MEDIUM.wire());
		node.put("exportEncodingMode", ExportEncodingMode.FAST.wire());
		node.put("exportFormat", ExportFormat.MP4.wire());
		node.put("mp4FrameRate", 30);
		node.put("gifFrameRate", 20);
		node.put("gifLoop", true);
		node.put("gifSizePreset", "medium");
		node.put("includeCaptionSidecar", false);
		return ExportPreferences.normalizeExportPreferences(node);
	}

	/** A project file whose videoPath is the absolute {@code video} path. */
	private Path writeProject(Path video) throws IOException {
		Path project = tempDir.resolve("queued-project.gravaai");
		ProjectDocument.create(video.toAbsolutePath().normalize().toString(),
				ProjectPersistence.normalizeProjectEditor(twoSecondEditor())).write(project);
		return project;
	}

	/** The renderer under test, with the compositor replaced by a deterministic frame provider. */
	private static ProjectExportRenderer renderer(ExportCoordinator coordinator) {
		return new ProjectExportRenderer(coordinator, (prepared, state, cursorSamples) ->
				ProjectExportRenderer.FramePipeline.of(new SyntheticFrameProvider(
						prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate())));
	}

	private static Optional<ExportQueue.Entry> entry(ExportQueue queue, long id) {
		return queue.entries().stream().filter(candidate -> candidate.id() == id).findFirst();
	}

	private static ExportQueue.Entry awaitFinished(ExportQueue queue, long id) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		while (System.nanoTime() < deadline) {
			Optional<ExportQueue.Entry> current = entry(queue, id);
			if (current.isPresent() && current.orElseThrow().isFinished()) {
				return current.orElseThrow();
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("the queued export did not finish: " + queue.entries());
	}

	@Test
	@DisplayName("renders a queued project to its destination and reports COMPLETED")
	void rendersQueuedProject() throws Exception {
		Path project = writeProject(media("h264-1080p60.mp4"));
		Path destination = tempDir.resolve("queued.mp4");
		List<ExportProgress> progress = new CopyOnWriteArrayList<>();
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null);
				ExportQueue queue = new ExportQueue(renderer(coordinator))) {
			// The app wires the queue's pump to the coordinator's idle hook (AppContext).
			coordinator.addIdleListener(queue::pump);
			queue.addChangeListener(() -> queue.entries().stream()
					.map(ExportQueue.Entry::progress)
					.filter(Objects::nonNull)
					.forEach(progress::add));

			long id = queue.enqueue("queued-project", "MP4", project, destination,
					mp4Preferences());
			ExportQueue.Entry finished = awaitFinished(queue, id);

			assertEquals(State.COMPLETED, finished.state(), finished.error());
			assertNull(finished.error());
			assertEquals(destination.toAbsolutePath().normalize(), finished.result());
			assertTrue(Files.isRegularFile(destination), "the export must be published");
			assertTrue(Files.size(destination) > 0, "the published file must not be empty");
			assertFalse(progress.isEmpty(),
					"the renderer must forward the coordinator's progress to the queue");
		}
	}

	@Test
	@DisplayName("reports FAILED with a useful message when the project's video is missing")
	void reportsMissingVideo() throws Exception {
		Path missingVideo = tempDir.resolve("gone.mp4");
		Path project = writeProject(missingVideo);
		Path destination = tempDir.resolve("missing.mp4");
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null);
				ExportQueue queue = new ExportQueue(renderer(coordinator))) {
			coordinator.addIdleListener(queue::pump);
			long id = queue.enqueue("missing-video", "MP4", project, destination,
					mp4Preferences());
			ExportQueue.Entry finished = awaitFinished(queue, id);

			assertEquals(State.FAILED, finished.state());
			assertNotNull(finished.error(), "the queue entry must carry the failure message");
			assertTrue(finished.error().contains("video file not found"), finished.error());
			assertFalse(Files.exists(destination), "a failed export publishes nothing");
		}
	}

	@Test
	@DisplayName("a job waits for a busy coordinator and starts on the idle hook")
	void waitsWhileCoordinatorIsBusy() throws Exception {
		Path project = writeProject(media("h264-1080p60.mp4"));
		Path destination = tempDir.resolve("after-busy.mp4");
		Path blockedDestination = tempDir.resolve("blocked.mp4");
		ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null);
		try (coordinator) {
			ExportQueue queue = new ExportQueue(renderer(coordinator));
			try (queue) {
				coordinator.addIdleListener(queue::pump);
				ProjectExport.Prepared prepared = ProjectExport.prepare(new ProjectExport.Input(
						ProjectPersistence.normalizeProjectEditor(twoSecondEditor()),
						media("h264-1080p60.mp4"), null, List.of(), mp4Preferences(),
						blockedDestination, false, false, 0, List.of()));
				BlockingFrameProvider blocking = new BlockingFrameProvider(prepared.outputWidth(),
						prepared.outputHeight(), prepared.frameRate());
				try {
					assertTrue(coordinator.start(prepared.request(), blocking,
							new ExportCoordinator.Listener() {}),
							"the blocking export must start");
					assertTrue(blocking.awaitRendering(30),
							"the blocking export never reached its first frame");
					assertTrue(coordinator.isBusy());

					// Renderer.start() answers empty while the pipeline is busy; the queue keeps
					// the job queued and retries from the idle hook once the blocker finishes.
					long id = queue.enqueue("after-busy", "MP4", project, destination,
							mp4Preferences());
					assertEquals(State.QUEUED, entry(queue, id).orElseThrow().state(),
							"a busy pipeline keeps the job queued");

					blocking.release();
					ExportQueue.Entry finished = awaitFinished(queue, id);
					assertEquals(State.COMPLETED, finished.state(), finished.error());
					assertTrue(Files.isRegularFile(destination));
				} finally {
					blocking.release();
				}
			}
		}
	}

	/** A frame provider that blocks in its first frame until the test releases it. */
	private static final class BlockingFrameProvider implements ExportFrameProvider {

		private final int width;
		private final int height;
		private final int frameRate;
		private final CountDownLatch rendering = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicBoolean blocked = new AtomicBoolean();

		BlockingFrameProvider(int width, int height, int frameRate) {
			this.width = width;
			this.height = height;
			this.frameRate = frameRate;
		}

		@Override
		public int width() {
			return width;
		}

		@Override
		public int height() {
			return height;
		}

		@Override
		public int frameRate() {
			return frameRate;
		}

		@Override
		public void renderFrame(long frameIndex, double timeSeconds, ByteBuffer rgba) {
			if (!blocked.compareAndSet(false, true)) {
				return;
			}
			rendering.countDown();
			boolean interrupted = false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException error) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		boolean awaitRendering(long seconds) throws InterruptedException {
			return rendering.await(seconds, TimeUnit.SECONDS);
		}

		void release() {
			release.countDown();
		}

		@Override
		public void close() {
			// The test owns the latches; nothing native to release.
		}
	}
}
