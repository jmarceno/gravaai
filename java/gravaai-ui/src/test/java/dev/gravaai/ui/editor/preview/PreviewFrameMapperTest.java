package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.CursorClickEffect;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.CursorSpec;
import dev.gravaai.media.compose.CursorStyle;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.FrameStyle;
import dev.gravaai.media.compose.LayoutCrop;
import dev.gravaai.media.compose.LayoutPadding;
import dev.gravaai.media.compose.LayoutSpec;
import dev.gravaai.media.compose.ZoomDepth;
import dev.gravaai.media.compose.ZoomMode;
import dev.gravaai.media.compose.WebcamSpec;
import dev.gravaai.media.compose.ZoomSpec;
import dev.gravaai.media.playback.PlaybackFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Project editor state -> compositor FrameInput mapping (plan §5.8 stages 1-4/6, P2.5). */
class PreviewFrameMapperTest {

	private static ProjectEditorState state(String json) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(json));
	}

	@DisplayName("wallpaper, padding, crop, blur and frame style map to the compositor scene")
	@Test
	void sceneMapping() {
		ProjectEditorState state = state("""
				{
				  "wallpaper": "/wallpapers/tahoe-light.jpg",
				  "backgroundBlur": 24,
				  "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
				  "cropRegion": {"x": 0.1, "y": 0.05, "width": 0.8, "height": 0.9}
				}
				""");
		PreviewFrameMapper.PreviewScene scene = PreviewFrameMapper.sceneFor(state);
		assertInstanceOf(Background.Image.class, scene.background());
		// ProjectPersistence clamps backgroundBlur to 0..8 (the settings slider range).
		assertEquals(8, scene.backgroundBlur(), 1e-9);
		assertEquals(new LayoutPadding(20, 20, 20, 20, true), scene.layout().padding());
		assertEquals(new LayoutCrop(0.1, 0.05, 0.8, 0.9), scene.layout().crop());
		// borderRadius 0 and shadow 0.67 (the fixture defaults) map to the stage-4 style.
		assertEquals(0, scene.style().borderRadiusPercent(), 1e-9);
		assertTrue(scene.style().showShadow());
		assertEquals(0.67, scene.style().shadowIntensity(), 1e-9);
		assertTrue(scene.zoom().regions().isEmpty());
		assertSame(PreviewFrameMapper.CursorTrack.NONE, scene.cursor());
	}

	@DisplayName("colour and gradient wallpapers resolve through the media parser")
	@Test
	void colourAndGradientBackgrounds() {
		ProjectEditorState colour = state("{\"wallpaper\": \"#2563EB\"}");
		Background resolved = PreviewFrameMapper.sceneFor(colour).background();
		assertInstanceOf(Background.Colour.class, resolved);
		assertEquals(0xFF2563EB, ((Background.Colour) resolved).argb());

		ProjectEditorState gradient = state("""
				{"wallpaper": "linear-gradient(120deg, #d4fc79 0%, #96e6a1 100%)"}
				""");
		assertTrue(PreviewFrameMapper.sceneFor(gradient).background()
				instanceof Background.LinearGradient);
	}

	@DisplayName("missing padding/crop use the normaliser defaults; background-only frames have no zoom/cursor")
	@Test
	void defaults() {
		ProjectEditorState state = state("{}");
		LayoutSpec layout = PreviewFrameMapper.layoutFor(state);
		// The normaliser fills the React default padding (20% linked) even for an empty editor.
		assertEquals(new LayoutPadding(20, 20, 20, 20, true), layout.padding());
		assertEquals(LayoutCrop.FULL, layout.crop());
		FrameInput idle = PreviewFrameMapper.idleInput(0, 0,
				PreviewFrameMapper.sceneFor(state));
		assertSame(ZoomSpec.NONE, idle.zoom());
		assertTrue(idle.cursorSamples().isEmpty());
		assertInstanceOf(FrameStyle.class, PreviewFrameMapper.sceneFor(state).style());
	}

	@DisplayName("zoom regions map to the media ZoomSpec with the same depth/focus/mode")
	@Test
	void zoomMappingFromFixture() throws Exception {
		ProjectDocument document = ProjectDocument.load(fixture("03-zoom-motion-blur.gravaai"));
		ProjectEditorState state = document.editor();
		ZoomSpec zoom = PreviewFrameMapper.zoomSpecFor(state);
		assertEquals(2, zoom.regions().size());
		var first = zoom.regions().get(0);
		assertEquals("zoom-1", first.id());
		assertEquals(150, first.startMs());
		assertEquals(850, first.endMs());
		assertSame(ZoomDepth.D5, first.depth());
		assertEquals(3.5, first.scale(), 1e-9);
		assertEquals(0.3, first.focus().cx(), 1e-9);
		assertEquals(0.35, first.focus().cy(), 1e-9);
		assertSame(ZoomMode.MANUAL, first.mode());
		var second = zoom.regions().get(1);
		assertEquals("zoom-2", second.id());
		assertEquals(950, second.startMs());
		assertEquals(1750, second.endMs());
		assertSame(ZoomDepth.D3, second.depth());
		assertEquals(0.5, zoom.zoomSmoothness(), 1e-9);
		assertEquals(false, zoom.connectZooms());
		assertEquals(false, zoom.zoomClassicMode());
		assertEquals(200, zoom.zoomInDurationMs(), 1e-9);
		assertEquals(200, zoom.zoomOutDurationMs(), 1e-9);
		assertEquals(1.0, zoom.cameraSpringStiffnessMultiplier(), 1e-9);
		assertEquals(1.13, zoom.cameraSpringDampingMultiplier(), 1e-9);
		assertEquals(1.12, zoom.cameraSpringMassMultiplier(), 1e-9);
	}

	@DisplayName("editor cursor settings map to the compositor CursorSpec (stage 6)")
	@Test
	void cursorSpecFromFixture() throws Exception {
		ProjectDocument document = ProjectDocument.load(fixture("03-zoom-motion-blur.gravaai"));
		CursorSpec spec = PreviewFrameMapper.cursorSpecFor(document.editor());
		assertTrue(spec.showCursor());
		assertSame(CursorStyle.TAHOE_INVERTED, spec.style());
		// DEFAULT_CURSOR_CONFIG.dotRadius (28) * cursorSize (2.5).
		assertEquals(70, spec.dotRadius(), 1e-9);
		assertEquals(0.67, spec.smoothingFactor(), 1e-9);
		assertEquals(1.35, spec.stiffnessMultiplier(), 1e-9);
		assertEquals(0.79, spec.dampingMultiplier(), 1e-9);
		assertEquals(1.29, spec.massMultiplier(), 1e-9);
		assertEquals(0.6, spec.motionBlur(), 1e-9);
		assertSame(CursorClickEffect.ECHO, spec.clickEffect());
		assertEquals(0xFFA855F7, spec.clickColorArgb());
		assertEquals(1.8, spec.clickEffectScale(), 1e-9);
		assertEquals(1.0, spec.clickEffectOpacity(), 1e-9);
		assertEquals(800, spec.clickEffectDurationMs(), 1e-9);
		assertEquals(2.0, spec.clickBounce(), 1e-9);
		assertEquals(350, spec.clickBounceDurationMs(), 1e-9);
		assertEquals(0.4, spec.sway(), 1e-9);
	}

	@DisplayName("editor webcam settings map to the compositor WebcamSpec (stage 7)")
	@Test
	void webcamSpecFromFixture() throws Exception {
		ProjectDocument document = ProjectDocument.load(fixture("07-webcam-bottom-right.gravaai"));
		WebcamSpec webcam = PreviewFrameMapper.webcamSpecFor(document.editor());
		assertTrue(webcam.enabled());
		assertEquals("bottom-right", webcam.positionPreset());
		assertEquals("bottom-right", webcam.corner());
		assertEquals(1, webcam.positionX(), 1e-9);
		assertEquals(1, webcam.positionY(), 1e-9);
		assertEquals(32, webcam.size(), 1e-9);
		assertEquals(32, webcam.width(), 1e-9);
		assertEquals(32, webcam.height(), 1e-9);
		assertTrue(webcam.mirror());
		assertEquals(100, webcam.roundness(), 1e-9);
		assertEquals(0.35, webcam.shadow(), 1e-9);
		assertEquals(24, webcam.margin(), 1e-9);
		assertTrue(webcam.reactToZoom());
		assertEquals(LayoutCrop.FULL, webcam.cropRegion());
		assertEquals(1, webcam.visibleRanges().size());
		assertEquals(0, webcam.visibleRanges().get(0).startMs(), 1e-9);
		assertEquals(2_000, webcam.visibleRanges().get(0).endMs(), 1e-9);
		assertEquals(0, webcam.timeOffsetMs(), 1e-9);
	}

	@DisplayName("an editor without a webcam block maps to WebcamSpec.DISABLED")
	@Test
	void webcamSpecDisabledByDefault() {
		WebcamSpec webcam = PreviewFrameMapper.webcamSpecFor(state("{}"));
		assertFalse(webcam.enabled());
		assertTrue(webcam.visibleRanges().isEmpty());
	}

	@DisplayName("cursor samples hand the compositor the bracketing pair for a frame time")
	@Test
	void cursorTrackWindow() {
		PreviewFrameMapper.CursorTrack track = new PreviewFrameMapper.CursorTrack(List.of(
				new CursorSample(0, 0.1, 0.1), new CursorSample(1, 0.2, 0.2),
				new CursorSample(2, 0.3, 0.3)), true);
		assertEquals(List.of(new CursorSample(0, 0.1, 0.1)), track.around(-1));
		assertEquals(List.of(new CursorSample(0, 0.1, 0.1), new CursorSample(1, 0.2, 0.2)),
				track.around(0.5));
		assertEquals(List.of(new CursorSample(1, 0.2, 0.2), new CursorSample(2, 0.3, 0.3)),
				track.around(1.5));
		assertEquals(List.of(new CursorSample(2, 0.3, 0.3)), track.around(9));
		assertTrue(new PreviewFrameMapper.CursorTrack(track.samples(), false).around(0.5).isEmpty());
		assertTrue(PreviewFrameMapper.CursorTrack.NONE.around(0.5).isEmpty());
	}

	@DisplayName("a decoded frame carries its time/source frame, zoom and cursor window")
	@Test
	void frameInput() {
		ProjectEditorState state = state("""
				{
				  "zoomRegions": [{"id": "z", "startMs": 0, "endMs": 1000, "depth": 2,
				                   "focus": {"cx": 0.4, "cy": 0.6}, "mode": "auto"}]
				}
				""");
		PreviewFrameMapper.CursorTrack cursor = new PreviewFrameMapper.CursorTrack(
				List.of(new CursorSample(0, 0, 0), new CursorSample(1, 1, 1)), true);
		PreviewFrameMapper.PreviewScene scene = PreviewFrameMapper.sceneFor(state, cursor);
		PlaybackFrame frame = PlaybackFrame.gap(7, 0.5);
		FrameInput input = PreviewFrameMapper.frameInput(frame, scene);
		assertEquals(7, input.frameIndex());
		assertEquals(0.5, input.timeSeconds(), 1e-9);
		assertEquals(null, input.sourceFrame());
		assertSame(scene.background(), input.background());
		assertSame(scene.style(), input.style());
		assertSame(scene.zoom(), input.zoom());
		assertSame(scene.cursorSpec(), input.cursor());
		assertSame(scene.webcam(), input.webcam());
		assertEquals(1, input.zoom().regions().size());
		assertEquals(2, input.cursorSamples().size());
	}

	/** Locates a parity fixture from the module working directory (or any parent). */
	private static Path fixture(String name) {
		Path start = Path.of(System.getProperty("user.dir"));
		for (Path path = start; path != null; path = path.getParent()) {
			Path candidate = path.resolve("parity/fixtures/projects").resolve(name);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("parity fixture not found: " + name + " from " + start);
	}
}