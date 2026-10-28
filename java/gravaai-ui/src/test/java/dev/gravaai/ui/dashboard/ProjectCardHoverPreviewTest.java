package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingLibraryScan;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/**
 * End-to-end hover state machine of {@link ProjectCardView} + {@link ProjectHoverPreview} (port of
 * the React {@code project-dashboard.spec.ts} hover test): the preview mounts on hover (after the
 * 300 ms delay), plays the muted compositor pass at 4:3 over the thumbnail, unmounts on mouse exit
 * and stops on its own when the pass ends; a later hover restarts it.
 *
 * <p>Needs a display and the real decoder/GL stack (the preview is the real engine), so it only
 * runs when {@code DISPLAY} is set. The project's recording is the 2 s parity fixture, so the
 * fallback full-range clip ends the pass by itself (React's ended -> onFinish).
 */
@Timeout(180)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class ProjectCardHoverPreviewTest {

	@TempDir
	Path tempDir;

	private ProjectManager projects;
	private RecordingsDirectory recordingsDirectory;
	private DashboardDependencies dependencies;
	private DashboardModel model;
	private ProjectCardView card;
	private Stage stage;

	@Start
	void start(Stage startStage) throws Exception {
		stage = startStage;
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		recordingsDirectory = new RecordingsDirectory(userData);
		projects = new ProjectManager(userData, recordingsDirectory,
				new RecentProjectsStore(userData.recentProjectsFile()), source -> null);
		dependencies = new DashboardDependencies(userData, projects,
				new RecordingLibraryScan(recordingsDirectory), List::of,
				new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")),
				new FakeActions(), null, null, null);
		model = new DashboardModel(dependencies);

		// One project with the 2 s parity recording and no clips: the hover preview plays one
		// full-range fallback clip (React's id "hover-preview") and ends on its own.
		Path project = projects.getProjectsDir().resolve("hover.gravaai");
		Json.writeFile(project, Map.of(
				"version", 2,
				"videoPath", fixture().toString(),
				"editor", Map.of()));
		model.applySnapshot(new DashboardDataLoader(dependencies).load(false));

		card = new ProjectCardView(model, dependencies, model.visibleEntries().get(0));
		Scene scene = new Scene(new KitRoot(card), 340, 340);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
	}

	@AfterEach
	void settle() throws Exception {
		new FxRobot().moveBy(600, 0);
		projects.awaitPendingThumbnails(20_000);
	}

	@Test
	@DisplayName("hover mounts the preview, mouse exit unmounts it")
	void hoverMountsAndExitUnmounts() throws Exception {
		assertFalse(hasPreviewNode(), "no preview before hover");

		new FxRobot().moveTo(card);
		await("preview node mounted", this::hasPreviewNode);

		// The React test polls the video element: playing, currentTime > 0, muted. The Java
		// equivalent of "playing" is the compositor's first presented frame (opacity 0 -> 1).
		await("preview playing (opacity 1)", () -> onFx(
				() -> findPreviewNode() != null && findPreviewNode().getOpacity() == 1));

		new FxRobot().moveBy(600, 0);
		await("preview node unmounted", () -> !hasPreviewNode());
		assertFalse(hasPreviewNode(), "preview stays unmounted after exit");
	}

	@Test
	@DisplayName("raw recording cards play the recording itself on hover")
	void rawCardHoverPreview() throws Exception {
		// Copy the parity fixture into the recordings library and switch the dashboard to Raw.
		java.nio.file.Files.copy(fixture(),
				recordingsDirectory.getRecordingsDir().resolve("raw-hover.mp4"));
		runOnFx(() -> {
			model.setSection(new dev.gravaai.ui.dashboard.DashboardSection.Raw());
			try {
				model.applySnapshot(new DashboardDataLoader(dependencies).load(true));
			} catch (java.io.IOException error) {
				throw new java.io.UncheckedIOException(error);
			}
			ProjectLibraryEntry raw = model.visibleEntries().stream()
					.filter(entry -> entry.path().endsWith("raw-hover.mp4"))
					.findFirst()
					.orElseThrow();
			card = new ProjectCardView(model, dependencies, raw);
			Scene rawScene = new Scene(new KitRoot(card), 340, 340);
			Theme.install(rawScene);
			stage.setScene(rawScene);
		});

		assertFalse(hasPreviewNode(), "no preview before hover");
		new FxRobot().moveTo(card);
		await("raw preview node mounted", this::hasPreviewNode);
		await("raw preview playing (opacity 1)", () -> onFx(
				() -> findPreviewNode() != null && findPreviewNode().getOpacity() == 1));
		new FxRobot().moveBy(600, 0);
		await("raw preview unmounted", () -> !hasPreviewNode());
	}

	@Test
	@DisplayName("the pass stops on its own and a later hover restarts it")
	void passStopsAloneAndRestarts() throws Exception {
		new FxRobot().moveTo(card);
		await("preview node mounted", this::hasPreviewNode);
		await("preview playing (opacity 1)", () -> onFx(
				() -> findPreviewNode() != null && findPreviewNode().getOpacity() == 1));

		// The 2 s fallback clip ends: React's ended -> onFinish removes the preview. While the
		// mouse stays on the card there is no previewActive edge, so it must NOT restart.
		await("preview self-finished", () -> !hasPreviewNode());
		Thread.sleep(400);
		assertFalse(hasPreviewNode(), "no restart while the mouse stays on the card");

		// Leave and re-enter: the previewActive false -> true edge restarts the pass.
		new FxRobot().moveBy(600, 0);
		await("preview fully unmounted", () -> !hasPreviewNode());
		new FxRobot().moveTo(card);
		await("preview restarted", this::hasPreviewNode);
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private boolean hasPreviewNode() {
		return onFx(() -> findPreviewNode() != null);
	}

	private ProjectHoverPreview findPreviewNode() {
		if (card == null || card.thumbnailHost == null) {
			return null;
		}
		return card.thumbnailHost.getChildren().stream()
				.filter(ProjectHoverPreview.class::isInstance)
				.map(ProjectHoverPreview.class::cast)
				.findFirst()
				.orElse(null);
	}

	/** Runs {@code work} on the FX thread and waits for it (model mutations are FX-only). */
	private static void runOnFx(Runnable work) {
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} finally {
				done.countDown();
			}
		});
		try {
			assertTrue(done.await(10, TimeUnit.SECONDS), "FX work timed out");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("FX work interrupted");
		}
	}

	private static boolean onFx(BooleanSupplier check) {
		AtomicBoolean hit = new AtomicBoolean();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				hit.set(check.getAsBoolean());
			} finally {
				done.countDown();
			}
		});
		try {
			assertTrue(done.await(5, TimeUnit.SECONDS), "FX check timed out");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("FX check interrupted");
		}
		return hit.get();
	}

	private static void await(String what, BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("timed out waiting for " + what);
	}

	/** Locates the parity fixture from the working directory or any parent. */
	private static Path fixture() {
		Path start = Path.of(System.getProperty("user.dir"));
		for (Path path = start; path != null; path = path.getParent()) {
			Path candidate = path.resolve("parity/fixtures/media/h264-1080p60.mp4");
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("parity fixture not found from " + start);
	}

	/** The app shell implements these; the card test does not navigate anywhere. */
	private static final class FakeActions implements DashboardDependencies.Actions {
		@Override
		public void openProject(String path) {
		}

		@Override
		public void openRecording(String path) {
		}

		@Override
		public void importFile() {
		}

		@Override
		public void revealInFolder(String path) {
		}

		@Override
		public void showRecordingHud() {
		}

		@Override
		public void openExternalUrl(String url) {
		}

		@Override
		public void recoverRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}

		@Override
		public void discardRecording(dev.gravaai.services.projects.RecoverableRecording recording) {
		}
	}
}
