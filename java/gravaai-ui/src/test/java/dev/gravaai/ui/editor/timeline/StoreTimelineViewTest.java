package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.ui.editor.timeline.core.Span;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * End-to-end round trip: the Canvas timeline edits the real {@link EditorStore} through the public
 * StoreTimelineDataSource/StoreTimelineCommands adapters (no fake command recorder).
 */
@ExtendWith(ApplicationExtension.class)
class StoreTimelineViewTest {

	private static final String FIXTURE = """
			{
			  "clipRegions": [
			    {"id":"clip-1","startMs":0,"endMs":6000,"speed":1}
			  ],
			  "zoomRegions": [
			    {"id":"zoom-1","startMs":1000,"endMs":3000,"depth":3,"focus":{"cx":0.5,"cy":0.5}}
			  ],
			  "autoCaptions": [
			    {"id":"cap-1","startMs":1000,"endMs":2000,"text":"Hello caption"}
			  ]
			}
			""";

	private Stage stage;
	private EditorStore store;
	private TimelineView view;
	private StoreTimelineCommands commands;
	private final AtomicReference<Double> playhead = new AtomicReference<>(0.0);
	private final AtomicReference<Double> seekSeconds = new AtomicReference<>();
	private java.util.List<ZoomSuggestionUtils.CursorTelemetryPoint> telemetry = java.util.List
			.of();

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("adds a zoom from the lane ghost into the real editor store")
	void addsZoomIntoStore(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			double x = view.xForTime(4_000);
			double y = view.rowTop("row-zoom") + view.rowHeight("row-zoom") / 2;
			view.clickAt(x, y);

			assertEquals(2, store.state().zoomRegions().size());
			String addedId = store.state().zoomRegions().get(1).id();
			refresh();
			assertTrue(view.blockBounds(addedId).isPresent());
		});
	}

	@Test
	@DisplayName("drags a zoom block and resizes its end into the real editor store")
	void movesAndResizesZoomInStore(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("zoom-1").orElseThrow();
			view.pressAt(bounds.centerX(), bounds.centerY());
			double dx = 500 / view.msPerPixel();
			view.dragTo(bounds.centerX() + dx, bounds.centerY());
			view.releaseAt(bounds.centerX() + dx, bounds.centerY());
			refresh();
			assertEquals(1_500, store.state().zoomRegions().get(0).startMs(), 1);
			assertEquals(3_500, store.state().zoomRegions().get(0).endMs(), 1);

			TimelineView.BlockBounds moved = view.blockBounds("zoom-1").orElseThrow();
			view.pressAt(moved.maxX() - 2, moved.centerY());
			double grow = 200 / view.msPerPixel() - 2;
			view.dragTo(moved.maxX() + grow, moved.centerY());
			view.releaseAt(moved.maxX() + grow, moved.centerY());
			refresh();
			assertEquals(3_700, store.state().zoomRegions().get(0).endMs(), 1);
			assertNotNull(view.blockBounds("zoom-1").orElse(null));
		});
	}

	@Test
	@DisplayName("suggests interaction zooms from cursor telemetry and adds them to the store")
	void suggestsZoomFromTelemetry(FxRobot robot) {
		telemetry = java.util.List.of(
				new ZoomSuggestionUtils.CursorTelemetryPoint(0, 0.5, 0.5, "move", null),
				new ZoomSuggestionUtils.CursorTelemetryPoint(4_500, 0.4, 0.6, "click", null),
				new ZoomSuggestionUtils.CursorTelemetryPoint(6_000, 0.5, 0.5, "move", null));
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			ZoomSuggestionUtils.SuggestionResult result = view.suggestZooms();
			assertEquals(ZoomSuggestionUtils.SuggestionStatus.OK, result.status());
			assertEquals(1, result.suggestions().size());
			assertEquals(4_000, result.suggestions().get(0).startMs());
			assertEquals(5_000, result.suggestions().get(0).endMs());

			assertEquals(1, view.addSuggestedZooms());
			assertEquals(2, store.state().zoomRegions().size());
			refresh();
			assertNotNull(view.blockBounds(store.state().zoomRegions().get(1).id()).orElse(null));
		});
	}

	@Test
	@DisplayName("clicking a caption block selects the source cue and seeks to it")
	void selectsCaptionFromStrip(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.captionBounds("cap-1").orElseThrow();
			view.clickAt(bounds.centerX(), bounds.centerY());

			assertEquals("cap-1", store.selectedCaptionId());
			assertEquals(1.0, seekSeconds.get(), 0.001);
			assertEquals(1_000, view.currentTime(), 0.001);
			assertEquals(new TimelineSelection(TimelineSelection.CAPTION, "cap-1"),
					commands.store().selectedCaptionId() != null
							? new TimelineSelection(TimelineSelection.CAPTION, "cap-1")
							: null);
		});
	}

	@Test
	@DisplayName("clicking empty caption strip space adds a caption through the store")
	void addsCaptionFromStrip(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			double x = view.xForTime(4_000);
			double y = view.rowTop("row-clip") + 46;
			view.clickAt(x, y);

			assertEquals(2, store.state().autoCaptions().size());
		});
	}

	@Test
	@DisplayName("keeps the lone clip packed at zero through the real store")
	void loneClipStaysPackedInStore(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds clip = view.blockBounds("clip-1").orElseThrow();
			view.pressAt(clip.centerX(), clip.centerY());
			view.dragTo(clip.centerX() + 120, clip.centerY());
			view.releaseAt(clip.centerX() + 120, clip.centerY());
			refresh();

			assertEquals(1, store.state().clipRegions().size());
			assertEquals(0, store.state().clipRegions().get(0).startMs());
			assertEquals(6_000, store.state().clipRegions().get(0).endMs());
		});
	}

	private void refresh() {
		view.refreshData();
		view.applyCss();
		view.layout();
	}

	private void buildScene() {
		store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(FIXTURE)));
		StoreTimelineDataSource dataSource = new StoreTimelineDataSource(store, () -> 6_000,
				playhead::get, null).cursorTelemetry(() -> telemetry);
		commands = new StoreTimelineCommands(store, seekSeconds::set).sourceDuration(id -> 6_000);
		view = new TimelineView(dataSource, commands);
		StackPane root = new StackPane(view);
		root.setStyle("-fx-background-color: #121418;");
		root.setAlignment(Pos.TOP_LEFT);
		Scene scene = new Scene(root, 1200, 160);
		Theme.install(scene);
		stage.setScene(scene);
		stage.show();
		refresh();
	}
}