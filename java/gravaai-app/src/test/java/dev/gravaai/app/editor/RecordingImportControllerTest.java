package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.RecordingImportService;
import dev.gravaai.services.recording.RecordingImportService.ImportResult;
import dev.gravaai.services.recording.RecordingImportService.RecordingWebcamSource;
import dev.gravaai.ui.editor.EditorViewActions.ClipImportResult;
import javafx.application.Platform;

/** P3.4: the {@code useRecordingLibrary.addToTimeline} port over fake import/commit seams. */
@Timeout(60)
class RecordingImportControllerTest {

	@TempDir
	Path dir;

	@BeforeAll
	static void toolkit() {
		try {
			Platform.startup(() -> { });
		} catch (IllegalStateException alreadyStarted) {
			// another test started the toolkit
		}
	}

	/**
	 * Import seam: scripted results, an optional failure index and a hook that can block a call
	 * until the controller's cancel token flips (a long ffmpeg step the user cancels).
	 */
	private static final class FakeLibrary implements RecordingImportController.Library {
		int failAt = -1;
		BiConsumer<Integer, BooleanSupplier> blockCall;
		final List<String> imports = new ArrayList<>();
		final List<String> discarded = new ArrayList<>();
		int calls;

		@Override
		public ImportResult importRecording(String currentPath, String recordingPath,
				RecordingWebcamSource currentWebcam, BooleanSupplier cancelled) throws IOException {
			int index = calls;
			if (blockCall != null) {
				blockCall.accept(index, cancelled);
			}
			if (cancelled.getAsBoolean()) {
				throw new IOException("Import canceled");
			}
			if (calls++ == failAt) {
				throw new IOException("ffmpeg exploded");
			}
			imports.add(recordingPath);
			long start = 6_000L * imports.size();
			return new ImportResult("/seq/" + imports.size() + ".mp4", start, 4_000, start + 4_000,
					new RecordingWebcamSource("/seq/webcam-" + imports.size() + ".mp4", 0,
							List.of(new RecordingImportService.VisibleRange(0, 4_000))));
		}

		@Override
		public void discardRecordingImport(String output) {
			discarded.add(output);
		}

		@Override
		public List<CursorTelemetryPoint> getCursorTelemetry(String videoPath) {
			return List.of(new CursorTelemetryPoint(7_000, 0.5, 0.5,
					dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType.CLICK, null));
		}
	}

	/** Session seam: a real store/document with a mutable source path. */
	private static final class FakeSession implements RecordingImportController.Session {
		final EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(
						"""
						{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}]}
						""")));
		final ProjectDocument document = ProjectDocument.create("/recording/source.mp4", store.state());
		final List<ProjectDocument> replaced = new ArrayList<>();
		final List<Double> seeks = new ArrayList<>();
		final List<String> paths = new ArrayList<>();
		String currentPath = "/recording/source.mp4";
		int pauses;
		boolean mediaReplaced;

		@Override
		public String videoPath() {
			return currentPath;
		}

		@Override
		public EditorStore store() {
			return store;
		}

		@Override
		public ProjectDocument document() {
			return document;
		}

		@Override
		public void replaceSourceMedia(ProjectDocument next, double seekSeconds) {
			replaced.add(next);
			seeks.add(seekSeconds);
			currentPath = next.videoPath();
			paths.add(next.videoPath());
		}

		@Override
		public void pausePlayback() {
			pauses++;
		}

		@Override
		public void onMediaReplaced() {
			mediaReplaced = true;
		}
	}

	private static RecordingImportController controller(FakeLibrary library, FakeSession session,
			boolean autoZooms) {
		return new RecordingImportController(library, session, () -> autoZooms);
	}

	@Test
	@DisplayName("commits one import: packed clip, selection, media replacement and seek")
	void commitsOneImport() throws Exception {
		FakeLibrary library = new FakeLibrary();
		FakeSession session = new FakeSession();
		RecordingImportController controller = controller(library, session, false);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4"), null, done::complete);

		ClipImportResult result = done.get(5, TimeUnit.SECONDS);
		assertEquals(new ClipImportResult(true, 1, null, false), result);
		assertEquals(2, session.store.state().clipRegions().size());
		assertEquals("clip-2", session.store.selectedClipId());
		assertEquals(6000, session.store.state().clipRegions().get(1).startMs());
		assertEquals(10000, session.store.state().clipRegions().get(1).endMs());
		assertEquals("/seq/1.mp4", session.paths.get(0));
		assertEquals(6.0, session.seeks.get(0));
		assertEquals(1, session.replaced.size());
		assertTrue(session.mediaReplaced);
		assertEquals(1, session.pauses);
		assertTrue(library.discarded.isEmpty());
	}

	@Test
	@DisplayName("adds cursor-based auto zooms for the inserted clip when enabled")
	void addsAutoZooms() throws Exception {
		FakeLibrary library = new FakeLibrary();
		FakeSession session = new FakeSession();
		RecordingImportController controller = controller(library, session, true);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4"), null, done::complete);

		ClipImportResult result = done.get(5, TimeUnit.SECONDS);
		assertEquals(new ClipImportResult(true, 1, null, false), result);
		assertEquals(1, session.store.state().zoomRegions().size());
		var zoom = session.store.state().zoomRegions().get(0);
		// The click at source 7000 ms rebases to 1000 ms inside the clip at offset 6000.
		assertEquals(6500, zoom.startMs());
		assertEquals(7500, zoom.endMs());
		assertEquals(ZoomMode.AUTO, zoom.mode());
		// The webcam of the import enables the overlay with the composed source.
		assertTrue(session.store.state().webcam().enabled());
		assertEquals("/seq/webcam-1.mp4", session.store.state().webcam().sourcePath());
	}

	@Test
	@DisplayName("a failing import discards the pending outputs and commits nothing")
	void failureDiscardsPending() throws Exception {
		FakeLibrary library = new FakeLibrary();
		library.failAt = 1;
		FakeSession session = new FakeSession();
		RecordingImportController controller = controller(library, session, false);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4", "/recording/b.mp4"), null,
				done::complete);

		ClipImportResult result = done.get(5, TimeUnit.SECONDS);
		assertEquals(new ClipImportResult(false, 1, "ffmpeg exploded", false), result);
		assertEquals(1, session.store.state().clipRegions().size());
		assertTrue(session.replaced.isEmpty());
		assertEquals(List.of("/seq/1.mp4"), library.discarded);
	}

	@Test
	@DisplayName("cancelling before the first import completes is a silent return")
	void cancelBeforeFirstImportIsSilent() throws Exception {
		FakeLibrary library = new FakeLibrary();
		library.blockCall = (index, cancelled) -> {
			if (index == 0) {
				awaitCancel(cancelled);
			}
		};
		FakeSession session = new FakeSession();
		RecordingImportController controller = controller(library, session, false);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4"), null, done::complete);
		controller.cancel();

		assertNull(done.get(5, TimeUnit.SECONDS));
		assertEquals(1, session.store.state().clipRegions().size());
		assertTrue(session.replaced.isEmpty());
	}

	@Test
	@DisplayName("cancelling after a completed import still commits the completed one")
	void cancelAfterCompletedImportStillCommits() throws Exception {
		FakeLibrary library = new FakeLibrary();
		library.blockCall = (index, cancelled) -> {
			if (index == 1) {
				awaitCancel(cancelled);
			}
		};
		FakeSession session = new FakeSession();
		RecordingImportController controller = controller(library, session, false);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4", "/recording/b.mp4"), null,
				done::complete);
		// Wait for the first import to land in the fake, then cancel.
		while (library.imports.isEmpty()) {
			Thread.sleep(10);
		}
		controller.cancel();

		ClipImportResult result = done.get(5, TimeUnit.SECONDS);
		assertEquals(new ClipImportResult(true, 1, null, true), result);
		assertEquals(2, session.store.state().clipRegions().size());
		assertEquals("/seq/1.mp4", session.paths.get(0));
		assertTrue(library.discarded.isEmpty());
	}

	@Test
	@DisplayName("a project without a source video returns silently")
	void withoutSourceVideoIsSilent() throws Exception {
		FakeLibrary library = new FakeLibrary();
		FakeSession session = new FakeSession();
		session.currentPath = null;
		RecordingImportController controller = controller(library, session, false);
		CompletableFuture<ClipImportResult> done = new CompletableFuture<>();

		controller.addToTimeline(List.of("/recording/a.mp4"), null, done::complete);

		assertNull(done.get(5, TimeUnit.SECONDS));
		assertTrue(library.imports.isEmpty());
	}

	private static void awaitCancel(BooleanSupplier cancelled) {
		while (!cancelled.getAsBoolean()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				return;
			}
		}
	}
}
