package dev.gravaai.media.compose;

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
import org.junit.jupiter.api.Timeout;

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
			assertFalse(GoldenRenderer.isUniform(frame), "production resolver rendered a blank frame");
		}
	}


	@Test
	@Timeout(120)
	@DisplayName("static wallpapers resolve once; the video wallpaper loops over its duration")
	void videoWallpaperLoops() throws Exception {
		ProjectFixture.Loaded loaded = fixture("03-zoom-motion-blur");
		Path bundled = ProjectFixture.repoRoot().resolve("java/gravaai-ui/src/main/resources/ui/editor");
		assertTrue(Files.isRegularFile(bundled.resolve("wallpapers/wispysky.mp4")));
		try (ProjectFrameInputFactory images = new ProjectFrameInputFactory(
				loaded.editor(), loaded.videoPath(), null, List.of(), 0, 0,
				ProjectFixture.wallpaperRoot(), 60)) {
			assertTrue(images.inputAtTime(0.1).background()
					== images.inputAtTime(0.2).background(), "static wallpaper re-resolved per frame");
		}
		var editor = dev.gravaai.core.project.ProjectPersistence.normalizeProjectEditor(
				dev.gravaai.core.json.Json.parseJsonWithByteOrderMark(
						"{\"wallpaper\":\"/wallpapers/wispysky.mp4\"}"));
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(editor,
				loaded.videoPath(), null, List.of(), 0, 0, bundled, 60);
				MediaFrameReader reader = new MediaFrameReader(
						bundled.resolve("wallpapers/wispysky.mp4"), 0, true)) {
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
}
