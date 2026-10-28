package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.compose.CameraGeometry;
import javafx.application.Platform;
import javafx.scene.layout.Pane;

/**
 * Behaviour of the preview annotation overlay (P3.4 gap 3): visible-set filtering, selection
 * cycling, drag and corner resize committing one undoable command.
 *
 * <p>Needs a JavaFX toolkit, so it only runs when {@code DISPLAY} is set like the other UI tests.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class AnnotationOverlayControllerTest {

	private static final double LAYER_WIDTH = 640;
	private static final double LAYER_HEIGHT = 360;
	private static final CameraGeometry IDENTITY =
			new CameraGeometry(0, 0, LAYER_WIDTH, LAYER_HEIGHT, 1, 0, 0, (int) LAYER_WIDTH,
					(int) LAYER_HEIGHT);

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

	/** Two text annotations spanning the whole timeline, z-order 1 then 2. */
	private static EditorStore store() {
		ProjectEditorState empty = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("{}"));
		EditorStore store = new EditorStore(empty);
		store.apply(new AnnotationCommands.Add(0, 10_000, null));
		store.apply(new AnnotationCommands.ChangePosition("annotation-1",
				new AnnotationPosition(50, 50)));
		store.apply(new AnnotationCommands.ChangeSize("annotation-1", new AnnotationSize(30, 20)));
		store.apply(new AnnotationCommands.Add(0, 10_000, null));
		store.apply(new AnnotationCommands.ChangePosition("annotation-2",
				new AnnotationPosition(10, 10)));
		store.apply(new AnnotationCommands.ChangeSize("annotation-2", new AnnotationSize(20, 10)));
		store.apply(new AnnotationCommands.Select(null));
		return store;
	}

	/** Runs {@code work} on the FX thread and waits for it. */
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

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static AnnotationOverlayController controller(EditorStore store, double timeSeconds) {
		AnnotationOverlayController controller = new AnnotationOverlayController(store,
				() -> IDENTITY, () -> timeSeconds);
		controller.layer().resizeRelocate(0, 0, LAYER_WIDTH, LAYER_HEIGHT);
		controller.refresh();
		return controller;
	}

	@DisplayName("only annotations active at the playhead get an overlay node")
	@Test
	void visibleSetFollowsThePlayhead() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			AnnotationOverlayController overlay = controller(store, 5);
			assertEquals(2, overlay.visibleAnnotations().size());
			assertNotNull(overlay.nodeFor("annotation-1"));
			assertNotNull(overlay.nodeFor("annotation-2"));

			AnnotationOverlayController later = controller(store, 20);
			assertTrue(later.visibleAnnotations().isEmpty());
			assertNull(later.nodeFor("annotation-1"));
		});
	}

	@DisplayName("clicking an unselected annotation selects it")
	@Test
	void clickSelectsUnselectedAnnotation() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			Pane node = overlay.nodeFor("annotation-2");
			assertNotNull(node);
			// Unselected nodes only select: they carry no handles and no drag gesture.
			assertTrue(overlay.handlesFor("annotation-2").isEmpty());
			assertTrue(overlay.handlesFor("annotation-1").size() == 4);

			node.getOnMousePressed().handle(mousePress());
			assertEquals("annotation-2", store.selectedAnnotationId());
		});
	}

	@DisplayName("clicking the selected annotation cycles through the visible ones")
	@Test
	void clickCyclesSelection() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			Pane node = overlay.nodeFor("annotation-1");
			overlay.beginDrag(find(overlay, "annotation-1"), node, 330, 190, false, 0);
			overlay.endGesture();
			assertEquals("annotation-2", store.selectedAnnotationId());

			// The last one wraps around to the first.
			overlay.refresh();
			overlay.beginDrag(find(overlay, "annotation-2"), overlay.nodeFor("annotation-2"), 100,
					100, false, 0);
			overlay.endGesture();
			assertEquals("annotation-1", store.selectedAnnotationId());
		});
	}

	@DisplayName("dragging a selected annotation commits one position command")
	@Test
	void dragCommitsPosition() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			Pane node = overlay.nodeFor("annotation-1");
			// Default 50%/50%, 30%/20% at identity: rect (320, 180, 192, 72).
			assertEquals(320, node.getLayoutX(), 1e-9);
			assertEquals(180, node.getLayoutY(), 1e-9);

			int undoSteps = store.history().past().size();
			overlay.beginDrag(find(overlay, "annotation-1"), node, 330, 190, false, 0);
			overlay.updateDrag(430, 240);
			assertTrue(overlay.isDragging());
			// Live gesture moves the overlay only; the store still holds the old position.
			assertEquals(420, node.getLayoutX(), 1e-9);
			assertEquals(230, node.getLayoutY(), 1e-9);
			assertEquals(50, annotation(store, "annotation-1").position().x(), 1e-9);

			overlay.endGesture();
			assertFalse(overlay.isDragging());
			assertEquals(undoSteps + 1, store.history().past().size());
			AnnotationRegion region = annotation(store, "annotation-1");
			assertEquals(65.625, region.position().x(), 1e-9);
			assertEquals(63.888888, region.position().y(), 1e-6);
			// A move never changes the size.
			assertEquals(30, region.size().width(), 1e-9);
			assertEquals(20, region.size().height(), 1e-9);
		});
	}

	@DisplayName("corner resize commits position and size together")
	@Test
	void resizeCommitsPositionAndSize() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			Pane node = overlay.nodeFor("annotation-1");
			assertTrue(overlay.handlesFor("annotation-1").size() == 4);

			// South-east handle: press at the bottom-right corner, drag +64/+36.
			overlay.beginDrag(find(overlay, "annotation-1"), node, 512, 252, true, 3);
			overlay.updateDrag(576, 288);
			assertTrue(overlay.isDragging());
			overlay.endGesture();

			AnnotationRegion region = annotation(store, "annotation-1");
			// Width 256/640 = 40%, height 108/360 = 30%, top-left unchanged.
			assertEquals(50, region.position().x(), 1e-9);
			assertEquals(50, region.position().y(), 1e-9);
			assertEquals(40, region.size().width(), 1e-9);
			assertEquals(30, region.size().height(), 1e-9);
		});
	}

	@DisplayName("north-west resize keeps the opposite corner fixed")
	@Test
	void northWestResizeAnchorsBottomRight() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			Pane node = overlay.nodeFor("annotation-1");
			// Press at (320,180), drag left/up by 64/36: the bottom-right stays at (512,252).
			overlay.beginDrag(find(overlay, "annotation-1"), node, 320, 180, true, 0);
			overlay.updateDrag(256, 144);
			overlay.endGesture();

			AnnotationRegion region = annotation(store, "annotation-1");
			assertEquals(256.0 / 640 * 100, region.position().x(), 1e-9);
			assertEquals(144.0 / 360 * 100, region.position().y(), 1e-9);
			assertEquals((512 - 256.0) / 640 * 100, region.size().width(), 1e-9);
			assertEquals((252 - 144.0) / 360 * 100, region.size().height(), 1e-9);
		});
	}

	@DisplayName("a click without movement only cycles the selection, never an edit")
	@Test
	void clickDoesNotEditGeometry() throws Exception {
		onFx(() -> {
			EditorStore store = store();
			store.apply(new AnnotationCommands.Select("annotation-1"));
			AnnotationOverlayController overlay = controller(store, 5);
			int undoSteps = store.history().past().size();
			overlay.beginDrag(find(overlay, "annotation-1"), overlay.nodeFor("annotation-1"), 330,
					190, false, 0);
			overlay.endGesture();
			// The click cycles to the next visible annotation (one selection step, no geometry).
			assertEquals("annotation-2", store.selectedAnnotationId());
			assertEquals(undoSteps + 1, store.history().past().size());
			assertEquals(50, annotation(store, "annotation-1").position().x(), 1e-9);
			assertEquals(30, annotation(store, "annotation-1").size().width(), 1e-9);
		});
	}

	private static AnnotationRegion find(AnnotationOverlayController overlay, String id) {
		for (AnnotationRegion region : overlay.visibleAnnotations()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		throw new AssertionError("not visible: " + id);
	}

	private static AnnotationRegion annotation(EditorStore store, String id) {
		for (AnnotationRegion region : store.state().annotationRegions()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		throw new AssertionError("missing annotation: " + id);
	}

	/** Synthetic press event for the unselected-node handler (only id consumption matters). */
	private static javafx.scene.input.MouseEvent mousePress() {
		return new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_PRESSED, 100,
				100, 100, 100, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false,
				false, true, false, false, false, false, false, null);
	}
}
