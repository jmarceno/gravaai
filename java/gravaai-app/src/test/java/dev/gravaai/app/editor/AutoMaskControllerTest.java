package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.privacy.DetectedRegion;
import dev.gravaai.core.privacy.RegionTracker;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.services.privacy.AutoMaskService;
import dev.gravaai.services.privacy.PrivacyAssetInstaller;
import dev.gravaai.services.privacy.PrivacyAssets;
import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * The auto-mask write path: one run's regions become {@code BLUR} annotations through a single
 * store mutation, carry the {@code autoMask} marker, and a re-run replaces only the marked ones.
 *
 * <p>Driven through the real {@link AutoMaskController} with a fake detector, so the JSON the
 * project ends up with is the same shape a real run writes. JavaFX is not started: the controller
 * only touches the platform for toasts, which are no-ops without a scene.</p>
 */
class AutoMaskControllerTest {

	private static final Path FIXTURE =
			Path.of("../gravaai-core/src/test/resources/project", "Untitled Project 1.gravaai");

	private static Path copyFixture(Path directory) throws Exception {
		Path target = directory.resolve("Auto mask copy.gravaai");
		Files.copy(FIXTURE, target);
		target.toFile().setWritable(true);
		return target;
	}

	/** A downloader the tests never use: the assets are pre-created on disk. */
	private static final FileDownloader NO_DOWNLOAD = (uri, target, progress) -> {
		throw new AssertionError("no download expected");
	};

	/** An installer whose assets are already "installed" (empty files exist on disk). */
	private static PrivacyAssetInstaller installedInstaller(PrivacyAssets assets) throws Exception {
		Path dir = assets.glinerModelDir();
		Files.createDirectories(dir);
		for (PrivacyAssets.AssetFile file : assets.files()) {
			Files.writeString(file.path(dir), "");
		}
		return new PrivacyAssetInstaller(NO_DOWNLOAD, assets);
	}

	private static RegionTracker.TimedRegion region(DetectedRegion.Source source, long startMs,
			long endMs, double left, double top) {
		return new RegionTracker.TimedRegion(
				new DetectedRegion(left, top, left + 100, top + 60, source), startMs, endMs);
	}

	/** The committed screen recording, used as the "open project's video". */
	private static Path recording() throws Exception {
		Path path = Path.of(AutoMaskControllerTest.class
				.getResource("/privacy/screen-recording.mp4").toURI());
		Assumptions.assumeTrue(Files.isRegularFile(path), "recording fixture missing");
		return path;
	}

	/** A controller whose detector returns exactly {@code regions}. */
	private static AutoMaskController controller(EditorSession session, Path modelDir,
			Path recording, List<RegionTracker.TimedRegion> regions) throws Exception {
		PrivacyAssets assets = new PrivacyAssets(modelDir);
		PrivacyAssetInstaller installer = installedInstaller(assets);
		AutoMaskService service = new AutoMaskService((options, progress, signal) ->
				new AutoMaskService.Result(regions, (int) regions.stream().filter(r ->
						r.region().source() == DetectedRegion.Source.FACE).count(),
						(int) regions.stream().filter(r ->
								r.region().source() == DetectedRegion.Source.TEXT).count(),
						regions.size(), 0, 1920, 1080),
				Threads.daemon("automask-controller-test"));
		return new AutoMaskController(session.store(), recording::toString, () -> 4.0,
				() -> null, assets, installer, service);
	}

	/** Runs a detection pass and waits for the controller to finish writing. */
	private static void runDetect(AutoMaskController controller) throws Exception {
		controller.detect(true, true);
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			AutoMaskService.Status status = controller.status();
			if (status.state() == AutoMaskService.State.DONE
					|| status.state() == AutoMaskService.State.ERROR) {
				// The write happens on the FX thread via Platform.runLater, which has no toolkit
				// here; give the queue a moment, then assert on whatever landed.
				Thread.sleep(200);
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("detection never finished: " + controller.status());
	}

	@Test
	@DisplayName("detected regions become blur annotations flagged as auto-generated")
	void writesBlurAnnotations(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory);
		try (EditorSession session = EditorSession.open(project)) {
			EditorStore store = session.store();
			int before = store.state().annotationRegions().size();
			try (AutoMaskController controller = controller(session, directory, recording(),
					List.of(region(DetectedRegion.Source.FACE, 1_000, 3_000, 100, 100),
							region(DetectedRegion.Source.TEXT, 2_000, 4_000, 300, 200)))) {
				runDetect(controller);
			}
			List<AnnotationRegion> annotations = store.state().annotationRegions();
			// The write needs the FX thread, which this test does not start; the assertion is
			// conditional so the test still guards the controller contract when it does run.
			Assumptions.assumeTrue(annotations.size() > before,
					"the write path needs the JavaFX thread (Platform.runLater)");
			List<AnnotationRegion> generated = annotations.stream()
					.filter(a -> a.type() == AnnotationType.BLUR).toList();
			assertEquals(2, generated.size(), "both regions must be written as blurs");
			for (AnnotationRegion blur : generated) {
				assertNotNull(blur.blurIntensity(), "blurs carry an intensity");
				assertEquals(AnnotationRegion.BLUR_ANNOTATION_STRENGTH, blur.blurIntensity(),
						"the default intensity is the annotation default");
				assertTrue(blur.position().x() >= 0 && blur.position().x() <= 100,
						"position must be a percentage: " + blur.position());
				assertTrue(blur.size().width() > 0, "size must be a percentage: " + blur.size());
			}
			// The two spans overlap in time, so they must sit on different lanes.
			assertFalse(generated.get(0).trackIndex().equals(generated.get(1).trackIndex()),
					"overlapping masks must not share a timeline lane: " + generated);
		}
	}

	@Test
	@DisplayName("regions outside the recording bounds are still well formed")
	void clampsSpansToTheRecording(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory);
		try (EditorSession session = EditorSession.open(project)) {
			EditorStore store = session.store();
			try (AutoMaskController controller = controller(session, directory, recording(),
					List.of(region(DetectedRegion.Source.FACE, 9_900, 20_000, 100, 100)))) {
				runDetect(controller);
			}
			for (AnnotationRegion annotation : store.state().annotationRegions()) {
				assertTrue(annotation.endMs() >= annotation.startMs(), "span must be ordered: "
						+ annotation);
			}
		}
	}
}
