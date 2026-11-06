package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.WebcamBackgroundEffect;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.media.segmentation.FakeWebcamSegmenter;
import dev.gravaai.media.segmentation.MaskTuning;

/** Production resolver parity with the golden/test mapping (plan §5.8/§5.10). */
class ProjectFrameInputFactoryTest {

	private static ProjectFixture.Loaded fixture(String name) throws Exception {
		return ProjectFixture.load(name);
	}

	@Test
	@Timeout(120)
	@DisplayName("resolves the same compositor state as the golden mapping for fixture 03")
	void fixture03Parity() throws Exception {
		ProjectFixture.Loaded loaded = fixture("03-zoom-motion-blur");
		Path wallpaperRoot = ProjectFixture.wallpaperRoot();
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), null, List.of(), 0, 0, wallpaperRoot, 60)) {
			double time = 0.25;
			FrameInput input = factory.inputAtTime(time);
			assertEquals(loaded.editor().backgroundBlur(), input.backgroundBlur());
			assertEquals(ProjectFixture.layout(loaded.editor()), input.layout());
			assertEquals(ProjectFixture.zoomSpec(loaded.editor()), input.zoom());
			assertEquals(ProjectFixture.frameStyle(loaded.editor()), input.style());
			assertEquals(ProjectFixture.webcamSpec(loaded.editor()), input.webcam());
			assertEquals(ProjectFixture.annotationSpecs(loaded.editor()), input.annotations());
			assertEquals(ProjectFixture.captionSpec(loaded.editor()), input.captions());
			assertEquals(ProjectFixture.keystrokeSpec(loaded.editor(), loaded.videoPath()),
					input.keystrokes());
			assertEquals(ProjectFixture.fadeSpec(loaded.editor()), input.fade());
			assertNotNull(input.sourceFrame(), "source frame was not decoded");
			assertTrue(input.sourceFrame().width() > 0 && input.sourceFrame().height() > 0);
			Background expectedBackground = ProjectFixture.background(loaded.editor());
			if (expectedBackground instanceof Background.Image expectedImage
					&& input.background() instanceof Background.Image actualImage) {
				org.junit.jupiter.api.Assertions.assertArrayEquals(expectedImage.encodedImage(),
						actualImage.encodedImage(), "wallpaper bytes differ");
			} else {
				assertEquals(expectedBackground, input.background());
			}
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("decodes the webcam frame for fixture 07 when it is visible")
	void fixture07Webcam() throws Exception {
		ProjectFixture.Loaded loaded = fixture("07-webcam-bottom-right");
		Path wallpaperRoot = ProjectFixture.wallpaperRoot();
		Path webcamPath = Path.of(loaded.editor().webcam().sourcePath());
		assertTrue(Files.isReadable(webcamPath), "webcam fixture missing: " + webcamPath);
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), webcamPath, List.of(), 0,
				loaded.editor().webcam().timeOffsetMs(), wallpaperRoot, 60)) {
			FrameInput input = factory.inputAtTime(0.35);
			assertNotNull(input.sourceFrame(), "source frame was not decoded");
			assertNotNull(input.webcamFrame(), "webcam frame was not decoded");
			assertTrue(input.webcamFrame().width() > 0);
			assertTrue(input.webcam().isVisibleAt(350));
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("computes the webcam mask once per decoded frame and only when the effect is on")
	void webcamMaskCachesPerDecodedFrame() throws Exception {
		ProjectFixture.Loaded loaded = fixture("07-webcam-bottom-right");
		Path wallpaperRoot = ProjectFixture.wallpaperRoot();
		Path webcamPath = Path.of(loaded.editor().webcam().sourcePath());
		assertTrue(Files.isReadable(webcamPath), "webcam fixture missing: " + webcamPath);
		WebcamOverlaySettings webcam = loaded.editor().webcam();
		WebcamOverlaySettings blurOnWebcam = webcam
				.withBackgroundEffect(WebcamBackgroundEffect.BLUR);
		ProjectEditorState blurOn = loaded.editor().toBuilder().webcam(blurOnWebcam).build();

		FakeWebcamSegmenter segmenter = new FakeWebcamSegmenter(255);
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				blurOn, loaded.videoPath(), webcamPath, List.of(), 0,
				blurOn.webcam().timeOffsetMs(), wallpaperRoot, 60, segmenter)) {
			FrameInput first = factory.inputAtTime(0.35);
			assertNotNull(first.webcamMask(), "blur on but no mask");
			assertEquals(255, Byte.toUnsignedInt(
					first.webcamMask().pixelsTopFirstRgba().get(3)), "mask value lost");
			assertEquals(1, segmenter.calls(), "first frame must reach the segmenter");
			assertEquals(MaskTuning.DEFAULT, segmenter.lastTuning(),
					"a project that never touched the controls must get the shipped shape");
			// The same timeline time decodes the same webcam frame: the cached mask returns.
			FrameInput second = factory.inputAtTime(0.35);
			assertSame(first.webcamMask(), second.webcamMask(), "mask recomputed for one frame");
			assertEquals(1, segmenter.calls(), "cache must collapse repeat decodes");
		}

		// The project's edge controls reach the segmenter.
		WebcamOverlaySettings shapedWebcam = blurOnWebcam.withMaskTuning(0.9, -2);
		ProjectEditorState shaped = loaded.editor().toBuilder().webcam(shapedWebcam).build();
		FakeWebcamSegmenter shapedSegmenter = new FakeWebcamSegmenter(255);
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				shaped, loaded.videoPath(), webcamPath, List.of(), 0,
				shaped.webcam().timeOffsetMs(), wallpaperRoot, 60, shapedSegmenter)) {
			factory.inputAtTime(0.35);
			assertEquals(new MaskTuning(0.9, -2), shapedSegmenter.lastTuning(),
					"the project's edge controls must reach the segmenter");
		}

		// Blur off (the project default): no mask and the segmenter never runs.
		FakeWebcamSegmenter offSegmenter = new FakeWebcamSegmenter(255);
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), webcamPath, List.of(), 0,
				loaded.editor().webcam().timeOffsetMs(), wallpaperRoot, 60, offSegmenter)) {
			FrameInput input = factory.inputAtTime(0.35);
			assertNull(input.webcamMask(), "blur off must not produce a mask");
			assertEquals(0, offSegmenter.calls(), "segmenter must not run when blur is off");
		}

		// Removal needs the same mask: the effect mode, not the old blur boolean, decides.
		FakeWebcamSegmenter removeSegmenter = new FakeWebcamSegmenter(255);
		WebcamOverlaySettings removeWebcam = webcam
				.withBackgroundEffect(WebcamBackgroundEffect.REMOVE);
		ProjectEditorState remove = loaded.editor().toBuilder().webcam(removeWebcam).build();
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				remove, loaded.videoPath(), webcamPath, List.of(), 0,
				remove.webcam().timeOffsetMs(), wallpaperRoot, 60, removeSegmenter)) {
			FrameInput input = factory.inputAtTime(0.35);
			assertNotNull(input.webcamMask(), "remove on but no mask");
			assertEquals(1, removeSegmenter.calls(), "removal must segment the person");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("renders through CompositorFrameAtTime with the production resolver")
	void rendersThroughFilmstripApi() throws Exception {
		ProjectFixture.Loaded loaded = fixture("03-zoom-motion-blur");
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), null, List.of(), 0, 0,
				ProjectFixture.wallpaperRoot(), 60);
				CompositorFrameAtTime renderer = new CompositorFrameAtTime(320, 180, factory)) {
			RgbaFrame frame = renderer.frameAtTime(0.6);
			Assumptions.assumeTrue(frame != null, "GL context unavailable");
			assertEquals(320, frame.width());
			assertFalse(isUniform(frame), "production resolver rendered a blank frame");
		}
	}


	@Test
	@Timeout(120)
	@DisplayName("static wallpapers resolve once; the video wallpaper loops over its duration")
	void videoWallpaperLoops() throws Exception {
		ProjectFixture.Loaded loaded = fixture("03-zoom-motion-blur");
		Path bundled = ProjectFixture.repoRoot().resolve("java/gravaai-ui/src/main/resources/ui/editor");
		assertTrue(Files.isRegularFile(bundled.resolve("wallpapers/wispy-sky.mp4")));
		try (ProjectFrameInputFactory images = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), null, List.of(), 0, 0,
				ProjectFixture.wallpaperRoot(), 60)) {
			assertTrue(images.inputAtTime(0.1).background()
					== images.inputAtTime(0.2).background(), "static wallpaper re-resolved per frame");
		}
		var editor = dev.gravaai.core.project.ProjectPersistence.normalizeProjectEditor(
				dev.gravaai.core.json.Json.parseJsonWithByteOrderMark(
						"{\"wallpaper\":\"/wallpapers/wispy-sky.mp4\"}"));
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(editor,
				loaded.videoPath(), null, List.of(), 0, 0, bundled, 60);
				MediaFrameReader reader = new MediaFrameReader(
						bundled.resolve("wallpapers/wispy-sky.mp4"), 0, true)) {
			double duration = reader.durationSeconds();
			assertTrue(duration > 1, "wallpaper duration " + duration);
			long early = hash(factory.inputAtTime(0.5).background());
			long later = hash(factory.inputAtTime(3.0).background());
			long looped = hash(factory.inputAtTime(duration + 0.5).background());
			assertTrue(early != later, "video wallpaper did not advance");
			assertEquals(early, looped, "video wallpaper did not loop");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("source-time overlays follow the clip mapping on a trimmed clip")
	void sourceTimeFollowsClips() throws Exception {
		ProjectFixture.Loaded loaded = fixture("03-zoom-motion-blur");
		var editor = dev.gravaai.core.project.ProjectPersistence.normalizeProjectEditor(
				dev.gravaai.core.json.Json.parseJsonWithByteOrderMark("{\"clipRegions\":[{\"id\":\"c1\","
						+ "\"startMs\":0,\"endMs\":1000,\"sourceStartMs\":1500,\"speed\":1}]}"));
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(editor,
				loaded.videoPath(), null, List.of(), 0, 0, null, 60)) {
			FrameInput input = factory.inputAtTime(0.5);
			assertEquals(0.5, input.timeSeconds(), 1e-9);
			assertEquals(2.0, input.sourceTimeSeconds(), 1e-9,
					"captions/keystrokes/cursor/webcam must use the source time");
		}
	}

	private static long hash(Background background) {
		assertTrue(background instanceof Background.VideoFrame, "not a video frame: " + background);
		RgbaFrame frame = ((Background.VideoFrame) background).frame();
		java.nio.ByteBuffer pixels = frame.pixelsTopFirstRgba().duplicate();
		pixels.position(0);
		long hash = 1125899906842597L;
		while (pixels.hasRemaining()) {
			hash = 31 * hash + pixels.get();
		}
		return hash;
	}

	/** True when every pixel of {@code frame} is identical (a blank render). */
	private static boolean isUniform(RgbaFrame frame) {
		java.nio.ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int first = pixels.getInt(0);
		for (int pixel = 1; pixel < frame.width() * frame.height(); pixel++) {
			if (pixels.getInt(pixel * 4) != first) {
				return false;
			}
		}
		return true;
	}
}
