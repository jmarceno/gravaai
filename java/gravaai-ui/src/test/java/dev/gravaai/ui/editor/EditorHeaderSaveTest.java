package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import javafx.application.Platform;

/**
 * The header Save button (user report 2026-10-28): after a recording the project opened with no way
 * to save it, so the header must carry a save next to Export, run the same save the close prompt
 * runs (the {@link EditorViewActions#save()} seam) and mark changes that are not on disk.
 *
 * <p>Needs a JavaFX toolkit, so it runs only when {@code DISPLAY} is set, like the other UI tests.
 */
@Timeout(60)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EditorHeaderSaveTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	@Test
	@DisplayName("the header Save button sits next to Export and runs the project save")
	void saveButtonSitsNextToExportAndSaves() throws Exception {
		AtomicInteger saves = new AtomicInteger();
		EditorViewActions actions = new EditorViewActions() {
			@Override
			public void save() {
				saves.incrementAndGet();
			}
		};
		AtomicReference<EditorHeaderView> header = new AtomicReference<>();
		onFx(() -> header.set(new EditorHeaderView(store(), actions)));

		onFx(() -> {
			assertEquals("Save", header.get().saveButton().getText());
			assertEquals(header.get().getChildren().indexOf(header.get().exportButton()) - 1,
					header.get().getChildren().indexOf(header.get().saveButton()),
					"Save is the button right before Export");
			header.get().saveButton().fire();
		});
		assertEquals(1, saves.get(), "the Save button runs the project save");

		// The app wires its own save (EditorWindow.saveProject); that one wins over the seam.
		onFx(() -> header.get().setOnSave(saves::incrementAndGet));
		onFx(() -> header.get().saveButton().fire());
		assertEquals(2, saves.get(), "the app-wired save replaces the default seam");
	}

	@Test
	@DisplayName("the Save button marks, and clears, the changes that are not on disk")
	void saveButtonMarksUnsavedChanges() throws Exception {
		AtomicReference<EditorHeaderView> header = new AtomicReference<>();
		onFx(() -> header.set(new EditorHeaderView(store(), new EditorViewActions() { })));

		onFx(() -> {
			assertFalse(header.get().dirty(), "a project opens clean");
			assertFalse(marked(header.get()), "no unsaved marking while everything is on disk");

			header.get().setDirty(true);
			assertTrue(header.get().dirty());
			assertTrue(marked(header.get()), "the Save button is marked while changes are not on disk");
			assertFalse(header.get().saveButton().isDisabled(),
					"the Save button stays clickable in both states");

			header.get().setDirty(false);
			assertFalse(header.get().dirty());
			assertFalse(marked(header.get()), "saving clears the marking");
		});
	}

	@Test
	@DisplayName("a store edit marks the project dirty for the header to show")
	void storeEditsMarkTheProjectDirty() throws Exception {
		EditorStore store = store();
		onFx(() -> {
			store.apply(new ClipCommands.Select("clip-1"));
			store.apply(new ClipCommands.ChangeSpeed(2));
		});
		assertEquals(2.0, onFxValue(() -> store.state().clipRegions().get(0).speed()),
				2e-9, "the speed edit is in the store");
	}

	private static boolean marked(EditorHeaderView header) {
		return header.saveButton().getStyleClass().contains("is-dirty");
	}

	private static EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":6000,"speed":1}]}
						""")));
	}

	private static void onFx(ThrowingRunnable work) throws Exception {
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
		onFx(() -> result.set(value.get()));
		return result.get();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
