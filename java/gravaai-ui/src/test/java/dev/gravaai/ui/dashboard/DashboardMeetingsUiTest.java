package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecordingLibraryScan;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dashboard Meetings section end to end (port of the React {@code useMeetingLibrary} +
 * {@code MeetingThumbnail} card rendering): selecting "Meetings" loads the meetings from the
 * meetings service, renders one card per meeting in the same grid as projects, applies the search
 * filter and opens a meeting through the app-shell action.
 *
 * <p>Needs a display, so it only runs when {@code DISPLAY} is set. Uses the plain
 * {@link Platform#startup(Runnable)} harness from {@code DashboardSnapshotTest} instead of TestFX's
 * {@code ApplicationExtension}, so the class can run in the same JVM as the snapshot test in any
 * order. The snapshot PNG lands in the module target directory, never in the repository.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class DashboardMeetingsUiTest {

	@TempDir
	Path tempDir;

	private DashboardMeetingFixtures.FakeMeetingUiService meetings;
	private DashboardDependencies dependencies;
	private final List<String> openedMeetings = new ArrayList<>();
	private final AtomicBoolean importMeetingCalled = new AtomicBoolean();
	private DashboardView view;
	private Scene scene;
	private Stage stage;

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			// Another test class started the toolkit earlier in this JVM; startJavaFx is idempotent.
			Platform.runLater(() -> { });
		}
		// Each test opens and closes its own stage; without this the toolkit exits after the first
		// one and the next Platform.runLater never runs.
		Platform.setImplicitExit(false);
	}

	@BeforeEach
	void setUp() {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
		ProjectManager projects = new ProjectManager(userData, recordingsDirectory,
				new RecentProjectsStore(userData.recentProjectsFile()), source -> null);
		meetings = new DashboardMeetingFixtures.FakeMeetingUiService();
		dependencies = new DashboardDependencies(
				userData,
				projects,
				new RecordingLibraryScan(recordingsDirectory),
				List::of,
				new DashboardLibraryStore(userData),
				new Trash(tempDir.resolve("data"), tempDir.resolve("home")),
				new RecordingActions(),
				null,
				null,
				null,
				null,
				() -> meetings);
	}

	@AfterEach
	void closeStage() throws Exception {
		Stage current = stage;
		if (current != null) {
			runFx(current::close);
		}
	}

	@Test
	@DisplayName("selecting Meetings renders one card per meeting in the dashboard grid")
	void rendersMeetingCards() throws Exception {
		openDashboard();
		awaitMeetingsLoaded();

		runFx(() -> {
			assertEquals(3, cardCount(), "one card per meeting");
			assertTrue(labelsContain("Design review"), "video meeting title missing");
			assertTrue(labelsContain("Weekly sync"), "audio meeting title missing");
			assertTrue(labelsContain("Retro notes"), "failed meeting title missing");
			// MeetingThumbnail.tsx state lines and badges.
			assertTrue(labelsContain(MeetingThumbnail.NOTES_READY), "notes line missing");
			assertTrue(labelsContain(MeetingThumbnail.TRANSCRIPT_READY), "transcript line missing");
			assertTrue(labelsContain(MeetingThumbnail.NOT_TRANSCRIBED), "empty state line missing");
			assertTrue(labelsContain("Processing…"), "running job badge missing");
			assertTrue(labelsContain("Failed"), "failed job badge missing");
			assertTrue(labelsContain("12 min 30 s"), "duration chip missing");
			assertTrue(labelsContain("30 min 0 s"), "audio duration chip missing");
			writeSnapshot("dashboard-meetings.png");
		});
	}

	@Test
	@DisplayName("search filters the meeting grid like the projects grid")
	void searchFiltersMeetings() throws Exception {
		openDashboard();
		awaitMeetingsLoaded();

		runFx(() -> {
			view.model().setQuery("sync");
			assertEquals(1, cardCount());
			assertTrue(labelsContain("Weekly sync"));
		});
		runFx(() -> {
			view.model().setQuery("");
			assertEquals(3, cardCount());
		});
	}

	@Test
	@DisplayName("clicking a meeting card opens the meeting through the app-shell action")
	void opensMeeting() throws Exception {
		openDashboard();
		awaitMeetingsLoaded();

		runFx(() -> {
			Node thumbnail = scene.getRoot().lookupAll(".project-thumbnail").iterator().next();
			assertNotNull(thumbnail, "meeting thumbnail missing");
			Event.fireEvent(thumbnail, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
					MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
					false, false, null));
		});

		assertEquals(List.of(DashboardMeetingFixtures.DESIGN_REVIEW.toString()), openedMeetings);
	}

	@Test
	@DisplayName("the Meetings toolbar Import goes through the meeting import action")
	void importsMeeting() throws Exception {
		openDashboard();
		awaitMeetingsLoaded();

		runFx(() -> {
			Button importButton = null;
			for (Node node : scene.getRoot().lookupAll(".button")) {
				if (node instanceof Button button && "Import".equals(button.getText())) {
					importButton = button;
					break;
				}
			}
			assertNotNull(importButton, "Import button missing");
			importButton.fire();
		});

		assertTrue(importMeetingCalled.get(), "meeting import action not called");
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private void openDashboard() throws Exception {
		runFx(() -> {
			view = new DashboardView(dependencies);
			scene = new Scene(new KitRoot(view.ui()), 1280, 800);
			Theme.install(scene);
			DashboardView.installStyles(scene);
			stage = new Stage();
			stage.setScene(scene);
			stage.show();
			view.model().setSection(DashboardSection.MEETINGS);
		});
	}

	private void awaitMeetingsLoaded() throws Exception {
		await("meetings loaded", () -> onFx(() -> view.model().visibleEntries().size() == 3
				&& !view.model().loadingProperty().get()));
	}

	private int cardCount() {
		return scene.getRoot().lookupAll(".project-card").size();
	}

	private boolean labelsContain(String text) {
		for (Node node : scene.getRoot().lookupAll(".label")) {
			if (node instanceof Label label && text.equals(label.getText())) {
				return true;
			}
		}
		return false;
	}

	private void writeSnapshot(String fileName) {
		try {
			WritableImage image = scene.snapshot(null);
			BufferedImage buffered = new BufferedImage((int) image.getWidth(),
					(int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
			PixelReader reader = image.getPixelReader();
			for (int y = 0; y < buffered.getHeight(); y++) {
				for (int x = 0; x < buffered.getWidth(); x++) {
					buffered.setRGB(x, y, reader.getArgb(x, y));
				}
			}
			String override = System.getProperty("gravaai.screenshot.dir");
			Path target = override != null && !override.isBlank()
					? Path.of(override).resolve(fileName)
					: Path.of(System.getProperty("java.io.tmpdir"), "gravaai-ui-screenshots",
							fileName);
			Files.createDirectories(target.getParent());
			ImageIO.write(buffered, "png", target.toFile());
			assertTrue(Files.isRegularFile(target), "snapshot missing: " + target);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static void runFx(ThrowingRunnable action) throws Exception {
		callFx(() -> {
			action.run();
			return null;
		});
	}

	private static <T> T callFx(Callable<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			} catch (Throwable failure) {
				result.completeExceptionally(failure);
			}
		});
		return result.get(30, TimeUnit.SECONDS);
	}

	private static boolean onFx(BooleanSupplier check) {
		try {
			return callFx(check::getAsBoolean);
		} catch (Exception failure) {
			throw new AssertionError(failure);
		}
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

	/** A runnable that may fail, for {@link #runFx}. */
	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** The app shell implements these; the test records meeting navigation and import. */
	private final class RecordingActions implements DashboardDependencies.Actions {
		@Override
		public void openProject(String path) {
		}

		@Override
		public void openRecording(String path) {
		}

		@Override
		public void openMeeting(String path) {
			openedMeetings.add(path);
		}

		@Override
		public void importFile() {
		}

		@Override
		public void importMeeting() {
			importMeetingCalled.set(true);
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
