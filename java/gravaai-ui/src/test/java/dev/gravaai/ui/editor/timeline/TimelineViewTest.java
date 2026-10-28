package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.ui.editor.timeline.core.Span;
import dev.gravaai.ui.editor.timeline.core.TimelineShortcuts;
import dev.gravaai.ui.editor.timeline.core.TimelineLayout;
import dev.gravaai.ui.editor.timeline.core.TimelineRenderItem;
import dev.gravaai.ui.agent.AgentHighlightUpdate;
import dev.gravaai.ui.agent.AgentHighlightView;
import dev.gravaai.ui.agent.AgentHighlightsController;
import dev.gravaai.ui.agent.AgentTimelineHighlights;
import dev.gravaai.ui.agent.PlaceholderAgentUiService;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;
import javafx.geometry.Pos;
import javafx.scene.input.KeyCode;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;
import org.testfx.framework.junit5.Start;

/**
 * Deterministic Phase A harness for the Canvas timeline: fixture {@code ProjectEditorState} built
 * through the real project normaliser, fixture waveform peaks (no media decode), row/indicator
 * assertions and the UI-gallery snapshot requested by the Lead
 * ({@code java/parity/ui-java/timeline.png}).
 */
@ExtendWith(ApplicationExtension.class)
class TimelineViewTest {

	private static final double SCENE_WIDTH = 1200;
	private static final double SCENE_HEIGHT = 256;

	/** The fixture editor: clips (speed/fade/camera), zooms, annotations, audio, captions, trims,
	 * speed regions. Shaped like the editor object of a real project file, so the real normaliser
	 * fills the defaults. */
	private static final String FIXTURE_EDITOR = """
			{
			  "clipRegions": [
			    {"id":"c1","startMs":0,"endMs":4000,"speed":1,"showSourceAudio":true,
			     "transitionOut":{"kind":"fade-black","durationMs":1000}},
			    {"id":"c2","startMs":4000,"endMs":8000,"speed":1.5,"showSourceAudio":true,
			     "muted":true,
			     "transitionOut":{"kind":"fade-black","durationMs":600}},
			    {"id":"c3","startMs":8000,"endMs":12000,"speed":1,
			     "cameraLayout":"camera-only"}
			  ],
			  "zoomRegions": [
			    {"id":"z1","startMs":500,"endMs":2500,"depth":3,"focus":{"cx":0.5,"cy":0.5}},
			    {"id":"z2","startMs":6000,"endMs":7800,"depth":5,"mode":"manual",
			     "focus":{"cx":0.5,"cy":0.5}}
			  ],
			  "annotationRegions": [
			    {"id":"a1","startMs":1000,"endMs":3000,"type":"text","content":"Hello timeline"},
			    {"id":"a2","startMs":5000,"endMs":7000,"type":"image","content":"",
			     "trackIndex":1}
			  ],
			  "audioRegions": [
			    {"id":"au1","startMs":200,"endMs":3800,"audioPath":"/tmp/music.mp3",
			     "volume":0.8},
			    {"id":"au2","startMs":9000,"endMs":11800,"audioPath":"/tmp/voice.wav",
			     "volume":1,"trackIndex":1}
			  ],
			  "autoCaptions": [
			    {"id":"cap1","startMs":800,"endMs":2600,"text":"Hello timeline"},
			    {"id":"cap2","startMs":4200,"endMs":5600,"text":"Cut to the chase"},
			    {"id":"cap3","startMs":8800,"endMs":11200,"text":"Camera only section"}
			  ],
			  "trimRegions": [{"id":"t1","startMs":4200,"endMs":5200}],
			  "speedRegions": [{"id":"sp1","startMs":6000,"endMs":7000,"speed":1.5}],
			  "sourceAudioTrackSettingsByClip": {
			    "c1":{"mixed":{"volume":0.5,"normalize":true}},
			    "c2":{"mixed":{"volume":0.8,"normalize":false}}
			  }
			}
			""";

	/** Minimal fixture: one full-length clip and one zoom, so the zoom lane gets its full height. */
	private static final String MINIMAL_EDITOR = """
			{
			  "clipRegions": [
			    {"id":"c1","startMs":0,"endMs":6000,"speed":1}
			  ],
			  "zoomRegions": [
			    {"id":"z1","startMs":1000,"endMs":3000,"depth":3,"focus":{"cx":0.5,"cy":0.5}}
			  ]
			}
			""";

	private Stage stage;
	private TimelineView view;
	private RecordingCommands commands;
	private List<TimelineSourceAudioTrack> sourceAudioTracksForScene = List.of();
	private FilmstripProvider filmstripProviderForScene = FilmstripProvider.empty();
	private RecordingWaveforms waveforms;

	@Start
	void start(Stage stage) {
		this.stage = stage;
	}

	@Test
	@DisplayName("renders every timeline row and the fixture project indicators")
	void rendersEveryRow(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(List.of("row-clip", "row-zoom", "row-annotation-0", "row-annotation-1",
					"row-audio-0", "row-audio-1"), view.rowIds());
			assertEquals(6, view.rowCount());
			assertEquals(TimelineLayout.getTimelineContentMinHeightPx(6), view.contentHeight(), 0.001);

			// Clip row: the first clip starts at the range start and spans most of the left half.
			TimelineView.BlockBounds clip = view.blockBounds("c1").orElseThrow();
			assertEquals(0, clip.minX(), 0.6);
			assertTrue(clip.width() > 300 && clip.width() < 420,
					"unexpected clip width " + clip.width());
			// The camera-only badge/fade indicators live on c2/c3; both blocks are rendered.
			assertNotNull(view.blockBounds("c2").orElse(null));
			assertNotNull(view.blockBounds("c3").orElse(null));
			// Zoom lane: a block at 0.5 s with the full zoom height.
			TimelineView.BlockBounds zoom = view.blockBounds("z1").orElseThrow();
			assertTrue(zoom.minX() > 30 && zoom.minX() < 140, "zoom x=" + zoom.minX());
			// The zoom lane is compact (32 px row, 80% block) because annotation/audio lanes exist.
			assertTrue(zoom.height() >= 20 && zoom.height() <= 30,
					"zoom block height=" + zoom.height());
			// Annotation lanes: two tracks, blocks on both.
			assertNotNull(view.blockBounds("a1").orElse(null));
			assertNotNull(view.blockBounds("a2").orElse(null));
			// Audio lanes: waveforms were requested from the port for both files.
			assertTrue(waveforms.requestedPaths.contains("/tmp/music.mp3"));
			assertTrue(waveforms.requestedPaths.contains("/tmp/voice.wav"));
			// Captions are embedded in the clip row, not a separate lane.
			TimelineView.BlockBounds caption = view.captionBounds("cap1").orElseThrow();
			assertTrue(caption.minY() > 50 && caption.minY() < 80,
					"caption y=" + caption.minY());
		});
	}

	@Test
	@DisplayName("routes block selection and ruler seeks through the commands port")
	void routesGestures(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds annotation = view.blockBounds("a1").orElseThrow();
			view.clickAt(annotation.centerX(), annotation.centerY());
			assertEquals(new TimelineSelection(TimelineSelection.ANNOTATION, "a1"),
					commands.lastSelection);
			assertEquals(new TimelineSelection(TimelineSelection.ANNOTATION, "a1"), view.selection());

			// A click past the last block clears the selection and seeks the playhead there.
			view.clickAt(1190, 24);
			assertNull(view.selection());
			assertTrue(commands.lastSeek > 10000, "seek=" + commands.lastSeek);
			assertEquals(commands.lastSeek, view.currentTime(), 0.001);
		});
	}

	@Test
	@DisplayName("scrolls and zooms the visible range with clamps")
	void scrollsAndZooms(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(0, view.range().start(), 0.001);
			assertEquals(12000, view.range().end(), 0.001);
			double before = view.msPerPixel();
			view.zoomAt(2, 6000);
			assertTrue(view.msPerPixel() < before);
			assertTrue(view.range().duration() <= 6001);
			view.panByMs(-1_000_000);
			assertEquals(0, view.range().start(), 0.001);
			view.panByMs(1_000_000);
			assertEquals(12000, view.range().end(), 0.001);
			view.zoomAt(1_000_000, 6000);
			assertTrue(view.range().duration() >= 300, "minimum visible range must be kept");
		});
	}

	@Test
	@DisplayName("writes the deterministic timeline snapshot for the UI gallery")
	void writesGallerySnapshot(FxRobot robot) throws Exception {
		robot.interact(() -> {
			sourceAudioTracksForScene = List.of(new TimelineSourceAudioTrack("mixed", "Source",
					"/tmp/source.mp4"));
			filmstripProviderForScene = new RecordingFilmstripProvider(true);
			buildScene();
		});
		WaitForAsyncUtils.waitForFxEvents();
		AtomicReference<WritableImage> image = new AtomicReference<>();
		robot.interact(() -> {
			view.refreshData();
			image.set(stage.getScene().snapshot(null));
		});
		assertNotNull(image.get());
		Path target = writePng(image.get());
		assertTrue(Files.isRegularFile(target), "snapshot missing: " + target);
		BufferedImage buffered = ImageIO.read(target.toFile());
		assertNotNull(buffered);
		assertEquals((int) SCENE_WIDTH, buffered.getWidth());
		assertEquals((int) SCENE_HEIGHT, buffered.getHeight());
	}

	@Test
	@DisplayName("renders one row per source-audio track with waveform, volume and badges")
	void rendersSourceAudioLanes(FxRobot robot) {
		robot.interact(() -> {
			sourceAudioTracksForScene = List.of(new TimelineSourceAudioTrack("mixed", "Source",
					"/tmp/source.mp4"));
			buildScene();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(List.of("row-clip", "row-source-audio-mixed", "row-zoom",
					"row-annotation-0", "row-annotation-1", "row-audio-0", "row-audio-1"),
					view.rowIds());
			assertEquals(7, view.rowCount());
			assertEquals(TimelineLayout.getTimelineContentMinHeightPx(7), view.contentHeight(),
					0.001);

			TimelineView.BlockBounds block = view.blockBounds("source-audio-mixed-c1").orElseThrow();
			double rowTop = view.rowTop("row-source-audio-mixed");
			assertTrue(block.minY() >= rowTop && block.maxY() <= rowTop + 32,
					"source block y=" + block.minY() + " rowTop=" + rowTop);
			assertTrue(waveforms.requestedPaths.contains("/tmp/source.mp4"));

			List<TimelineRenderItem> sourceItems = view.renderItems().stream()
					.filter(item -> item.id().startsWith("source-audio-mixed-")).toList();
			assertEquals(2, sourceItems.size(), "only clips with showSourceAudio render a block");
			TimelineRenderItem first = sourceItems.get(0);
			assertEquals("Source", first.label());
			assertEquals("/tmp/source.mp4", first.audioPath());
			assertEquals(0.5, first.audioGain(), 0.001);
			assertEquals(Boolean.TRUE, first.audioNormalize());
			assertEquals(Boolean.TRUE, sourceItems.get(1).muted(), "c2 is muted");
			assertNotNull(view.blockBounds("source-audio-mixed-c2").orElse(null));
		});
	}

	@Test
	@DisplayName("clicking a source-audio block selects the owning clip and never drags it")
	void sourceAudioBlockSelectsOwningClip(FxRobot robot) {
		robot.interact(() -> {
			sourceAudioTracksForScene = List.of(new TimelineSourceAudioTrack("mixed", "Source",
					"/tmp/source.mp4"));
			buildScene();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds block = view.blockBounds("source-audio-mixed-c1").orElseThrow();
			view.clickAt(block.centerX(), block.centerY());
			assertEquals(new TimelineSelection(TimelineSelection.CLIP, "c1"),
					commands.lastSelection);

			view.pressAt(block.centerX(), block.centerY());
			view.dragTo(block.centerX() + 120, block.centerY());
			view.releaseAt(block.centerX() + 120, block.centerY());
			assertTrue(commands.moves.isEmpty(), "source-audio blocks are display-only");
			assertTrue(commands.resizes.isEmpty());
		});
	}

	@Test
	@DisplayName("renders no source-audio rows without tracks")
	void protectsWithoutSourceAudioTracks(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> assertTrue(view.rowIds().stream()
				.noneMatch(rowId -> rowId.startsWith("row-source-audio")), "unexpected source rows"));
	}

	@Test
	@DisplayName("mounts the agent highlights overlay above the canvas")
	void mountsAgentHighlightsOverlay(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			AgentHighlightsController controller = new AgentHighlightsController(
					new PlaceholderAgentUiService());
			view.setAgentHighlights(controller);
			view.applyCss();
			view.layout();
			AgentTimelineHighlights overlay = view.agentOverlay();
			assertNotNull(overlay);
			assertTrue(overlay.isMouseTransparent());

			controller.highlights().setAll(new AgentHighlightView("h1",
					AgentHighlightUpdate.Kind.ADDED, 1_000, 2_000, "added"));
			view.layout();
			assertTrue(overlay.getChildren().size() > 0, "overlay draws the highlight band");
			controller.close();
		});
	}

	@Test
	@DisplayName("draws filmstrip thumbnails sampled across the source span")
	void drawsFilmstripThumbnails(FxRobot robot) {
		robot.interact(() -> {
			filmstripProviderForScene = new RecordingFilmstripProvider(true);
			buildScene();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			RecordingFilmstripProvider provider = (RecordingFilmstripProvider) filmstripProviderForScene;
			List<Double> samples = view.filmstripSampleTimes("c1");
			assertFalse(samples.isEmpty(), "the clip row should request filmstrip samples");
			assertTrue(view.filmstripFrameCount("c1") > 0, "thumbnails should be drawn when ready");
			assertTrue(samples.stream().allMatch(sample -> sample >= 0 && sample <= 4_000),
					"samples must stay inside the clip source span: " + samples);
			long distinct = samples.stream().distinct().count();
			assertTrue(distinct >= 2, "samples should span several source times: " + samples);
			assertTrue(provider.frameAtCalls > 0);

			int callsBefore = provider.frameAtCalls;
			view.refreshData();
			assertEquals(callsBefore, provider.frameAtCalls,
					"same sample times must come from the view thumbnail cache");
		});
	}

	@Test
	@DisplayName("requests missing frames and repaints when the provider becomes ready")
	void requestsMissingFilmstripFrames(FxRobot robot) {
		robot.interact(() -> {
			filmstripProviderForScene = new RecordingFilmstripProvider(false);
			buildScene();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			RecordingFilmstripProvider provider = (RecordingFilmstripProvider) filmstripProviderForScene;
			assertEquals(0, view.filmstripFrameCount("c1"));
			assertTrue(provider.requestedTimes.size() > 0, "missing frames must be requested");
			provider.ready = true;
			provider.publishReady();
		});
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> assertTrue(view.filmstripFrameCount("c1") > 0,
				"a provider ready callback should repaint the cached thumbnails"));
	}

	@Test
	@DisplayName("keeps placeholder cells when no filmstrip provider is wired")
	void keepsFilmstripPlaceholderWithoutProvider(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(0, view.filmstripFrameCount("c1"));
			// Cells are still planned (so a late provider only needs a repaint), but no frames
			// can be drawn without one.
			assertFalse(view.filmstripSampleTimes("c1").isEmpty());
		});
	}

	/* ---------- fixture ---------- */

	@Test
	@DisplayName("drags the middle clip to the end of the clip sequence")
	void dragsClipIntoSequence(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("c2").orElseThrow();
			double centerY = bounds.centerY();
			view.pressAt(bounds.centerX(), centerY);
			view.dragTo(view.xForTime(11_900), centerY);
			view.releaseAt(view.xForTime(11_900), centerY);

			assertEquals(1, commands.moves.size());
			assertEquals("c2@row-clip", commands.moves.get(0));
			Span moved = commands.moveSpans.get(0);
			assertEquals(8_000, moved.start(), 0.001);
			assertEquals(12_000, moved.end(), 0.001);
		});
	}

	@Test
	@DisplayName("resizes a clip edge and commits the trimmed span")
	void resizesClipEdge(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("c1").orElseThrow();
			double centerY = bounds.centerY();
			view.pressAt(bounds.maxX() - 2, centerY);
			view.dragTo(bounds.maxX() - 102, centerY);
			view.releaseAt(bounds.maxX() - 102, centerY);

			assertEquals(List.of("c1"), commands.resizes);
			Span resized = commands.resizeSpans.get(0);
			assertEquals(0, resized.start(), 0.001);
			assertEquals(3_000, resized.end(), 0.001);
		});
	}

	@Test
	@DisplayName("resizes a zoom end which soft-snaps to the adjacent clip cut")
	void resizesZoomEndSnappingToCut(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("z2").orElseThrow();
			double centerY = bounds.centerY();
			view.pressAt(bounds.maxX() - 2, centerY);
			view.dragTo(bounds.maxX() + 18, centerY);
			view.releaseAt(bounds.maxX() + 18, centerY);

			assertEquals(List.of("z2"), commands.resizes);
			Span resized = commands.resizeSpans.get(0);
			assertEquals(6_000, resized.start(), 0.001);
			assertEquals(8_000, resized.end(), 0.001);
		});
	}

	@Test
	@DisplayName("shows the zoom add-ghost and adds a zoom block on click")
	void addsZoomFromLaneGhost(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			double x = view.xForTime(5_000);
			double y = view.rowTop("row-zoom") + view.rowHeight("row-zoom") / 2;
			view.moveTo(x, y);
			assertEquals("zoom", view.hoverGhostKind());
			assertNotNull(view.hoverGhostSpan());

			view.clickAt(x, y);
			assertEquals(1, commands.zooms.size());
			String[] parts = commands.zooms.get(0).split("-");
			double start = Double.parseDouble(parts[0]);
			double end = Double.parseDouble(parts[1]);
			assertEquals(4_998.8, start, 2);
			assertEquals(1_000, end - start, 0.001);
		});
	}

	@Test
	@DisplayName("shows the embedded caption ghost and adds a caption where it points")
	void addsCaptionFromEmbeddedGhost(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			double x = view.xForTime(6_000);
			double y = view.rowTop("row-clip") + 46;
			view.moveTo(x, y);
			assertEquals("caption", view.hoverGhostKind());
			assertNotNull(view.hoverGhostSpan());

			view.clickAt(x, y);
			assertEquals(1, commands.captions.size());
			String[] parts = commands.captions.get(0).split("-");
			double start = Double.parseDouble(parts[0]);
			double end = Double.parseDouble(parts[1]);
			assertEquals(5_998.8, start, 2);
			assertEquals(1_500, end - start, 0.001);
		});
	}

	@Test
	@DisplayName("splits at the playhead and deletes the selected block from the keyboard")
	void keyboardSplitsAndDeletes(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			view.setCurrentTimeMs(2_000);
			view.pressKey(KeyCode.C);
			assertEquals(List.of("c1"), commands.splits);

			TimelineView.BlockBounds zoom = view.blockBounds("z1").orElseThrow();
			view.clickAt(zoom.centerX(), zoom.centerY());
			assertEquals(new TimelineSelection(TimelineSelection.ZOOM, "z1"), view.selection());
			view.pressKey(KeyCode.DELETE);
			assertTrue(commands.deleted);

			view.clickAt(zoom.centerX(), zoom.centerY());
			view.pressKey(KeyCode.ESCAPE);
			assertNull(view.selection());
			assertNull(commands.lastSelection);
		});
	}

	@Test
	@DisplayName("keeps a lone clip packed at zero when dragged a few pixels")
	void keepsLoneClipPackedAtZero(FxRobot robot) {
		robot.interact(() -> buildScene(MINIMAL_EDITOR));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("c1").orElseThrow();
			double centerY = bounds.centerY();
			view.pressAt(bounds.centerX(), centerY);
			view.dragTo(bounds.centerX() + 40, centerY);
			view.releaseAt(bounds.centerX() + 40, centerY);

			assertEquals(1, commands.moves.size());
			Span moved = commands.moveSpans.get(0);
			assertEquals(0, moved.start(), 0.001);
			assertEquals(6_000, moved.end(), 0.001);
		});
	}

	@Test
	@DisplayName("ignores a clip click without movement")
	void ignoresClipClickWithoutMovement(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("c1").orElseThrow();
			view.pressAt(bounds.centerX(), bounds.centerY());
			view.releaseAt(bounds.centerX(), bounds.centerY());
			assertTrue(commands.moves.isEmpty());
		});
	}

	@Test
	@DisplayName("does not trim when a resize handle is pressed without dragging")
	void ignoresResizePressWithoutMovement(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds bounds = view.blockBounds("c1").orElseThrow();
			view.pressAt(bounds.maxX() - 2, bounds.centerY());
			view.releaseAt(bounds.maxX() - 2, bounds.centerY());
			assertTrue(commands.resizes.isEmpty());
		});
	}

	@Test
	@DisplayName("deletes the middle clip with Delete and the first clip with Backspace")
	void deletesSelectedClipsWithKeys(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds middle = view.blockBounds("c2").orElseThrow();
			view.clickAt(middle.centerX(), middle.centerY());
			assertEquals(new TimelineSelection(TimelineSelection.CLIP, "c2"), view.selection());
			view.pressKey(KeyCode.DELETE);
			assertEquals(1, commands.deleteCount);

			TimelineView.BlockBounds first = view.blockBounds("c1").orElseThrow();
			view.clickAt(first.centerX(), first.centerY());
			view.pressKey(KeyCode.BACK_SPACE);
			assertEquals(2, commands.deleteCount);
		});
	}

	@Test
	@DisplayName("ignores Delete without a selected block")
	void ignoresDeleteWithoutSelection(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			view.pressKey(KeyCode.ESCAPE);
			view.pressKey(KeyCode.DELETE);
			view.pressKey(KeyCode.BACK_SPACE);
			assertEquals(0, commands.deleteCount);
		});
	}

	@Test
	@DisplayName("the zoom lane is full height alone and compact beside annotation and audio lanes")
	void zoomLaneHeightAdaptsToExtraLanes(FxRobot robot) {
		robot.interact(() -> buildScene(MINIMAL_EDITOR));
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			assertEquals(64, view.rowHeight("row-zoom"), 0.001);
			TimelineView.BlockBounds clip = view.blockBounds("c1").orElseThrow();
			TimelineView.BlockBounds zoom = view.blockBounds("z1").orElseThrow();
			assertEquals(clip.height(), zoom.height(), 0.001);
		});
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertEquals(32, view.rowHeight("row-zoom"), 0.001);
			assertTrue(view.rowHeight("row-zoom") < 64);
		});
	}

	@Test
	@DisplayName("dragging a zoom block snaps its end to the cut and keeps its duration")
	void zoomDragSnapsEndKeepingDuration(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			TimelineView.BlockBounds zoom = view.blockBounds("z1").orElseThrow();
			double centerY = zoom.centerY();
			view.pressAt(zoom.centerX(), centerY);
			view.dragTo(zoom.centerX() + 150, centerY);
			view.releaseAt(zoom.centerX() + 150, centerY);

			assertEquals(1, commands.moves.size());
			assertEquals("z1@row-zoom", commands.moves.get(0));
			Span moved = commands.moveSpans.get(0);
			assertEquals(2_000, moved.start(), 1);
			assertEquals(4_000, moved.end(), 1);
			assertEquals(2_000, moved.duration(), 1);
		});
	}

	@Test
	@DisplayName("Ctrl+A selects all zooms and Delete deletes every one")
	void selectAllZoomsAndDelete(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			view.pressKey(KeyCode.A, true, false, false, false);
			assertTrue(view.selectAllBlocksActive());
			view.pressKey(KeyCode.DELETE);
			assertEquals(List.of("z1", "z2"), commands.deletedZooms);
			assertFalse(view.selectAllBlocksActive());
			assertFalse(commands.deleted);
		});
	}

	@Test
	@DisplayName("uses the configured split shortcut binding")
	void usesConfiguredSplitShortcut(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			view.setShortcutBindings(new TimelineShortcuts.Bindings(
					TimelineShortcuts.Binding.of("f", false, false, false),
					TimelineShortcuts.Binding.of("z", false, false, false),
					TimelineShortcuts.Binding.of("x", false, false, false),
					TimelineShortcuts.Binding.of("a", false, false, false),
					TimelineShortcuts.Binding.of("d", true, false, false)));
			view.setCurrentTimeMs(2_000);
			view.pressKey(KeyCode.X);
			assertEquals(List.of("c1"), commands.splits);
		});
	}

	@Test
	@DisplayName("Z adds a default-length zoom at the playhead when there is room")
	void keyboardAddsZoomAtPlayhead(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();

		robot.interact(() -> {
			view.setCurrentTimeMs(9_000);
			view.pressKey(KeyCode.Z);
			assertEquals(List.of("9000.0-10000.0"), commands.zooms);
		});
	}

	private void buildScene() {
		buildScene(FIXTURE_EDITOR);
	}

	@Test
	@DisplayName("rows taller than the panel scroll inside it with a vertical scroll bar")
	void rowsScrollInsideShortPanel(FxRobot robot) throws IOException {
		robot.interact(() -> {
			buildScene(FIXTURE_EDITOR, 160);
			stage.sizeToScene();
		});
		WaitForAsyncUtils.waitForFxEvents();

		AtomicReference<double[]> target = new AtomicReference<>();
		robot.interact(() -> {
			assertTrue(view.getHeight() <= 160, "the view never grows past its panel");
			assertTrue(view.maxScrollY() > 0, "six rows overflow a 160 px panel");
			assertTrue(view.rowScrollBar().isVisible());
			view.setScrollY(1e6);
			assertEquals(view.maxScrollY(), view.scrollY(), 0.001);
			assertEquals(view.maxScrollY(), view.rowScrollBar().getValue(), 0.001);
			// The last audio lane is below the fold at scroll 0; scrolled, it is clickable.
			TimelineView.BlockBounds block = view.blockBounds("au2").orElseThrow();
			javafx.geometry.Point2D screen = view.localToScreen(block.centerX(),
					block.centerY() - view.scrollY());
			target.set(new double[] {screen.getX(), screen.getY()});
		});
		robot.moveTo(new javafx.geometry.Point2D(target.get()[0], target.get()[1])).clickOn(
				javafx.scene.input.MouseButton.PRIMARY);
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertNotNull(view.selection());
			assertEquals("au2", view.selection().id());
		});

		// Gallery capture of both scroll bars in use (zoomed in time, rows scrolled).
		AtomicReference<WritableImage> image = new AtomicReference<>();
		robot.interact(() -> {
			view.zoomAt(3, 6_000);
			image.set(stage.getScene().snapshot(null));
		});
		assertTrue(Files.isRegularFile(writePng(image.get(), "timeline-scrollbars.png")));
	}

	@Test
	@DisplayName("the time scroll bar mirrors and drives the visible range")
	void timeScrollBarFollowsRange(FxRobot robot) {
		robot.interact(() -> buildScene());
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			assertTrue(view.timeScrollBar().isDisabled(), "whole timeline visible: nothing to scroll");
			view.zoomAt(4, 0);
			assertFalse(view.timeScrollBar().isDisabled());
			assertEquals(view.range().start(), view.timeScrollBar().getValue(), 0.001);
			double visible = view.range().duration();
			view.timeScrollBar().setValue(6_000);
			assertEquals(6_000, view.range().start(), 0.001);
			assertEquals(visible, view.range().duration(), 0.001);
			view.panByMs(1_000);
			assertEquals(7_000, view.timeScrollBar().getValue(), 0.001);
		});
	}

	private void buildScene(String editorJson) {
		buildScene(editorJson, SCENE_HEIGHT);
	}

	private void buildScene(String editorJson, double sceneHeight) {
		ProjectEditorState project = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(editorJson));
		waveforms = new RecordingWaveforms();
		commands = new RecordingCommands();
		FixtureDataSource dataSource = new FixtureDataSource(project, 12_000, 3_000, waveforms,
				sourceAudioTracksForScene, filmstripProviderForScene);
		view = new TimelineView(dataSource, commands);
		StackPane root = new StackPane(view);
		root.setStyle("-fx-background-color: #121418;");
		StackPane.setAlignment(view, Pos.TOP_LEFT);
		Scene scene = new Scene(root, SCENE_WIDTH, sceneHeight);
		stage.setScene(scene);
		stage.show();
		view.refreshData();
	}

	private static Path writePng(WritableImage image) throws IOException {
		return writePng(image, "timeline.png");
	}

	private static Path writePng(WritableImage image, String name) throws IOException {
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Path target = Path.of(System.getProperty("user.dir")).resolve("..").resolve("parity")
				.resolve("ui-java").resolve(name).normalize();
		Files.createDirectories(target.getParent());
		ImageIO.write(buffered, "png", target.toFile());
		return target;
	}

	/** Deterministic filmstrip thumbnails (solid tint): no media decode, no async threads. */
	private static final class RecordingFilmstripProvider implements FilmstripProvider {
		private final boolean initiallyReady;
		private final List<Double> requestedTimes = new ArrayList<>();
		private int frameAtCalls;
		private boolean ready;
		private Runnable onReady = () -> { };

		private RecordingFilmstripProvider(boolean ready) {
			this.initiallyReady = ready;
			this.ready = ready;
		}

		@Override
		public Image frameAt(String clipId, double sourceTimeMs, int thumbWidth, int thumbHeight) {
			frameAtCalls++;
			if (!ready) {
				return null;
			}
			WritableImage image = new WritableImage(thumbWidth, thumbHeight);
			double phase = (sourceTimeMs / 4_000.0) * 255;
			javafx.scene.paint.Color tint = javafx.scene.paint.Color.rgb(
					(int) Math.max(0, Math.min(255, phase)), 120, 200);
			for (int y = 0; y < thumbHeight; y++) {
				for (int x = 0; x < thumbWidth; x++) {
					image.getPixelWriter().setColor(x, y, tint);
				}
			}
			return image;
		}

		@Override
		public void requestFrame(String clipId, double sourceTimeMs, int thumbWidth,
				int thumbHeight) {
			requestedTimes.add(sourceTimeMs);
		}

		@Override
		public void setOnFrameReady(Runnable listener) {
			this.onReady = listener == null ? () -> { } : listener;
		}

		private void publishReady() {
			onReady.run();
		}
	}

	/** Fixture data source: normalised editor state, fixture peaks, fixed playhead. */
	private record FixtureDataSource(ProjectEditorState project, long totalDurationMs,
			double currentTimeMs, WaveformProvider waveforms,
			List<TimelineSourceAudioTrack> sourceAudioTracks,
			FilmstripProvider filmstripProvider) implements TimelineDataSource {

		@Override
		public List<TimelineSourceAudioTrack> sourceAudioTracks() {
			return sourceAudioTracks;
		}

		@Override
		public FilmstripProvider filmstripProvider() {
			return filmstripProvider;
		}
	}

	/** Deterministic fixture peaks (no media decode): a repeating envelope. */
	private static final class RecordingWaveforms implements WaveformProvider {
		private final List<String> requestedPaths = new ArrayList<>();

		@Override
		public float[] peaks(String audioPath, int peakCount) {
			requestedPaths.add(audioPath);
			float[] peaks = new float[Math.max(1, peakCount)];
			for (int index = 0; index < peaks.length; index++) {
				double phase = index * Math.PI * 2 / 23.0;
				peaks[index] = (float) (0.15 + 0.85 * Math.abs(Math.sin(phase)));
			}
			return peaks;
		}
	}

	/** Records every command the view issues; Phase A has no real editor store yet. */
	private static final class RecordingCommands implements TimelineCommands {
		private double lastSeek = -1;
		private TimelineSelection lastSelection;
		private final List<String> moves = new ArrayList<>();
		private final List<Span> moveSpans = new ArrayList<>();
		private final List<String> resizes = new ArrayList<>();
		private final List<Span> resizeSpans = new ArrayList<>();
		private final List<String> splits = new ArrayList<>();
		private boolean deleted;
		private int deleteCount;
		private final List<String> zooms = new ArrayList<>();
		private final List<String> captions = new ArrayList<>();
		private final List<String> suggestedZooms = new ArrayList<>();
		private final List<String> deletedZooms = new ArrayList<>();

		@Override
		public void seek(double timeMs) {
			lastSeek = timeMs;
		}

		@Override
		public void select(TimelineSelection selection) {
			lastSelection = selection;
		}

		@Override
		public void clearSelection() {
			lastSelection = null;
		}

		@Override
		public void move(String itemId, String rowId, Span span) {
			moves.add(itemId + "@" + rowId);
			moveSpans.add(span);
		}

		@Override
		public void resizeEnd(String itemId, Span span) {
			resizes.add(itemId);
			resizeSpans.add(span);
		}

		@Override
		public void splitClip(String clipId, double timeMs) {
			splits.add(clipId);
		}

		@Override
		public void deleteSelection() {
			deleted = true;
			deleteCount++;
		}

		@Override
		public void addZoom(double startMs, double endMs) {
			zooms.add(startMs + "-" + endMs);
		}

		@Override
		public void addSuggestedZoom(double startMs, double endMs,
				dev.gravaai.core.project.ZoomFocus focus) {
			suggestedZooms.add(startMs + "-" + endMs + "@" + focus.cx() + "," + focus.cy());
		}

		@Override
		public void deleteZoom(String id) {
			deletedZooms.add(id);
		}

		@Override
		public void addCaption(double startMs, double endMs) {
			captions.add(startMs + "-" + endMs);
		}
	}
}