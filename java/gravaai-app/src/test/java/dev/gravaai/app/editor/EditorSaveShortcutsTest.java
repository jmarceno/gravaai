package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.ui.AppFonts;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Saving while editing (user report 2026-10-28): a finished recording opened as a new project with
 * no way to save it; the only path was forcing an exit, where the app offered to save. The header
 * Save button and Ctrl+S (⌘S on macOS) must run exactly the save the close prompt runs, with the
 * editor staying open.
 */
@Timeout(180)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class EditorSaveShortcutsTest {

	private static final Path RECORDING_FIXTURE =
			Path.of("/home/jmarceno/Projects/gravaai/java/parity/fixtures/media/h264-1080p60.mp4");

	private static final String PROJECT = """
			{"version":2,"projectId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
			 "videoPath": "missing-recording.mp4",
			 "createdAt":1700000000000,"updatedAt":1700000000000,"projectName":"Save flow fixture",
			 "editor":{"wallpaper":"/wallpapers/tahoe-light.jpg","shadowIntensity":0.67,
			 "backgroundBlur":0,"aspectRatio":"16:9","borderRadius":0,
			 "padding":{"top":20,"bottom":20,"left":20,"right":20,"linked":true},
			 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}]}}
			""";

	@Start
	void start(Stage testFxStage) {
		AppFonts.load();
	}

	@Test
	@DisplayName("the header Save button and Ctrl+S write the project and clear the unsaved marking")
	void savesTheProjectWithoutLeavingTheEditor(@TempDir Path dir) throws Exception {
		Path project = dir.resolve("Save flow fixture.gravaai");
		Files.writeString(project, PROJECT);
		EditorWindow window = openProjectInMainWindow(project, EditorWindow.Services.bare());
		try {
			assertFalse(window.session().dirty(), "a project opened from disk starts clean");
			await(() -> !headerDirty(window), "the Save button starts unmarked");

			// Edit, then save with the header button: the very save the close prompt runs.
			changeClipSpeed(window, 2);
			await(() -> window.session().dirty(), "the edit marks the project dirty");
			await(() -> headerDirty(window), "the Save button shows it is unsaved");
			runOnFx(() -> window.shell().header().saveButton().fire());
			await(() -> !window.session().dirty(), "the header Save wrote the project");
			assertEquals(2.0, ProjectDocument.load(project).editor().clipRegions().get(0).speed(),
					0.001, "the project file holds the edit");
			assertFalse(headerDirty(window), "saving clears the unsaved marking");
			assertTrue(window.stage().isShowing(), "the editor stays open; saving does not navigate");

			// Ctrl+S, fired as a real key press through the scene.
			changeClipSpeed(window, 4);
			await(() -> window.session().dirty(), "the second edit marks the project dirty");
			pressSaveShortcut(window);
			await(() -> !window.session().dirty(), "Ctrl+S wrote the project");
			assertEquals(4.0, ProjectDocument.load(project).editor().clipRegions().get(0).speed(),
					0.001, "the project file holds the second edit");

			// Saving a clean project stays harmless (plain rewrite).
			pressSaveShortcut(window);
			await(() -> !window.session().dirty(), "saving a clean project is a plain rewrite");
			assertEquals(4.0, ProjectDocument.load(project).editor().clipRegions().get(0).speed(),
					0.001);
		} finally {
			runOnFx(window::dispose);
		}
	}

	@Test
	@DisplayName("saving a fresh recording runs the save dialog and writes the new project")
	void savesAFreshRecordingFromTheEditor(@TempDir Path dir) throws Exception {
		assertTrue(Files.isRegularFile(RECORDING_FIXTURE), "parity fixture is committed");
		Path projects = dir.resolve("Projects");
		Files.createDirectories(projects);
		Path target = projects.resolve("My recording.gravaai");
		AtomicReference<String> askedFor = new AtomicReference<>();
		AtomicInteger updated = new AtomicInteger();
		EditorWindow.Services services = new EditorWindow.Services(null, null, path -> { },
				path -> updated.incrementAndGet(), () -> { },
				title -> Optional.empty(),
				title -> {
					askedFor.set(title);
					return Optional.of(target);
				},
				null, null, null);

		EditorWindow window = openRecordingInMainWindow(RECORDING_FIXTURE, services);
		try {
			assertTrue(window.session().isUnsaved(), "a recording starts with no project file");
			await(() -> headerDirty(window),
					"a project that never touched disk is marked unsaved");

			runOnFx(() -> window.shell().header().saveButton().fire());
			await(() -> Files.isRegularFile(target), "the Save button wrote the project file");
			assertEquals("Save project as", askedFor.get(), "the project chooser opens");
			assertFalse(window.session().isUnsaved(), "the project now has a file");
			await(() -> !window.session().dirty() && !headerDirty(window),
					"the save clears the unsaved state");
			assertEquals(1, updated.get(), "the app is told, so it can remember the recents");
			ProjectDocument saved = ProjectDocument.load(target);
			assertEquals(RECORDING_FIXTURE.toAbsolutePath().toString(), saved.videoPath(),
					"the project points at the recording");
			assertEquals("My recording", window.session().displayName());

			// Ctrl+S on a project that has a file writes it without a dialog.
			askedFor.set(null);
			changeClipSpeed(window, 2);
			await(() -> window.session().dirty(), "the edit marks the project dirty");
			pressSaveShortcut(window);
			await(() -> !window.session().dirty(), "Ctrl+S wrote the project");
			assertEquals(null, askedFor.get(), "no dialog once the project has a file");
			assertEquals(2.0, ProjectDocument.load(target).editor().clipRegions().get(0).speed(),
					0.001, "the second edit is in the same file");
		} finally {
			runOnFx(window::dispose);
		}
	}

	private static void changeClipSpeed(EditorWindow window, double speed) throws Exception {
		runOnFx(() -> {
			String clipId = window.session().store().state().clipRegions().get(0).id();
			window.session().store().apply(new ClipCommands.Select(clipId));
			window.session().store().apply(new ClipCommands.ChangeSpeed(speed));
		});
	}

	/** Ctrl+S through the scene, the way a real key press reaches the editor. */
	private static void pressSaveShortcut(EditorWindow window) throws Exception {
		runOnFx(() -> window.scene().getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "s", "s",
				KeyCode.S, false, true, false, false)));
	}

	private static boolean headerDirty(EditorWindow window) throws Exception {
		return onFxValue(() -> window.shell().header().dirty());
	}

	private static EditorWindow openProjectInMainWindow(Path project,
			EditorWindow.Services services) throws Exception {
		AtomicReference<EditorWindow> window = new AtomicReference<>();
		runOnFx(() -> window.set(EditorWindow.createIn(mainWindow(), project, services)));
		return window.get();
	}

	private static EditorWindow openRecordingInMainWindow(Path video,
			EditorWindow.Services services) throws Exception {
		AtomicReference<EditorWindow> window = new AtomicReference<>();
		runOnFx(() -> window.set(EditorWindow.createForRecordingIn(mainWindow(), video, null, 0,
				services)));
		return window.get();
	}

	private static Stage mainWindow() {
		Stage main = new Stage();
		main.setScene(new Scene(new StackPane(), 1280, 800));
		main.show();
		return main;
	}

	private static void await(ThrowingCondition condition, String message) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
		while (System.nanoTime() < deadline) {
			if (condition.get()) {
				return;
			}
			Thread.sleep(40);
		}
		throw new AssertionError("timed out: " + message);
	}

	private static void runOnFx(ThrowingRunnable work) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	private static <T> T onFxValue(java.util.function.Supplier<T> value) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		runOnFx(() -> result.set(value.get()));
		return result.get();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@FunctionalInterface
	private interface ThrowingCondition {
		boolean get() throws Exception;
	}
}
