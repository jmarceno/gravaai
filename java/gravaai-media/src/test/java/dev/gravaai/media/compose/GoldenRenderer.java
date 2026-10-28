package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;

import dev.gravaai.media.ffmpeg.ColourInfo;
import dev.gravaai.media.ffmpeg.DecodedFrame;
import dev.gravaai.media.ffmpeg.FrameConverter;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoDecoder;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.playback.WebcamSync;

/**
 * Renders one committed fixture frame end to end for the golden tests: project model ->
 * wallpaper asset -> layout/style/zoom -> decode at the project time -> composite -> PBO
 * readback, at the manifest's output size.
 *
 * <p>One frame is rendered per call with a fresh {@link Compositor}, which reproduces the
 * capture semantics of the committed references (the task-13 electron frames tool seeks and
 * renders one frame per case, so the zoom springs initialise to their target and no prior-frame
 * motion-blur history exists). Sequential export runs are covered by the synthetic GL tests.</p>
 */
final class GoldenRenderer {

	private static final double OUTPUT_FPS = 60;

	private GoldenRenderer() {}

	/** Renders one frame of a golden case at {@code timeSeconds} (sequential 60 fps warm-up). */
	static RgbaFrame renderFrame(GoldenFixtures.Case goldenCase, double timeSeconds)
			throws Exception {
		ProjectFixture.Loaded loaded = ProjectFixture.load(goldenCase.name());
		Background background = ProjectFixture.background(loaded.editor());
		LayoutSpec layout = ProjectFixture.layout(loaded.editor());
		double backgroundBlur = loaded.editor().backgroundBlur();
		ZoomSpec zoom = ProjectFixture.zoomSpec(loaded.editor());
		FrameStyle style = ProjectFixture.frameStyle(loaded.editor());
		CursorSpec cursor = ProjectFixture.cursorSpec(loaded.editor());
		WebcamSpec webcam = ProjectFixture.webcamSpec(loaded.editor());
		java.util.List<AnnotationSpec> annotations =
				ProjectFixture.annotationSpecs(loaded.editor());
		CaptionSpec captions = ProjectFixture.captionSpec(loaded.editor());
		KeystrokeSpec keystrokes = ProjectFixture.keystrokeSpec(loaded.editor(),
				loaded.videoPath());
		FadeSpec fade = ProjectFixture.fadeSpec(loaded.editor());
		SceneSpec scene = ProjectEditorInputs.sceneSpec(loaded.editor(), timeSeconds * 1000);
		RgbaFrame webcamFrame = decodeWebcamFrame(loaded.editor(), timeSeconds);
		List<CursorSample> cursorSamples = loadCursorSamples(loaded.videoPath());

		int width = goldenCase.width();
		int height = goldenCase.height();
		ByteBuffer snapshot = ByteBuffer.allocateDirect(width * height * 4);
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		GlPreviewThread preview = new GlPreviewThread(width, height, PboFormat.RGBA,
				(surface, frameIndex, wallTime) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(width, height);
						compositorRef.set(compositor);
					}
					try {
						RgbaFrame source = decodeSourceFrame(loaded.videoPath(), timeSeconds);
						compositor.render(surface, new FrameInput(0, timeSeconds, source,
								webcamFrame, cursorSamples, background, backgroundBlur, layout,
								zoom, style, cursor, webcam, annotations, captions, keystrokes,
								fade, scene));
					} catch (Exception exception) {
						throw new IllegalStateException(
								"failed to render " + goldenCase.name() + "@" + timeSeconds,
								exception);
					}
				},
				(pixels, frameWidth, frameHeight, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(frameWidth * frameHeight * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(30));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(30, TimeUnit.SECONDS)) {
				fail("no frame read back for " + goldenCase.name() + "@" + timeSeconds
						+ "s; failure=" + preview.failure());
			}
		} finally {
			preview.close();
			Compositor compositor = compositorRef.get();
			if (compositor != null) {
				compositor.close();
			}
		}
		ByteBuffer copy = ByteBuffer.allocateDirect(width * height * 4);
		ByteBuffer from = snapshot.duplicate();
		from.position(0);
		from.limit(width * height * 4);
		copy.put(from);
		copy.position(0);
		return new RgbaFrame(width, height, copy);
	}

	/** Decodes the webcam fixture at the timeline time, clamped like the preview does. */
	private static RgbaFrame decodeWebcamFrame(
			dev.gravaai.core.project.ProjectEditorState editor, double timeSeconds)
			throws Exception {
		var settings = editor.webcam();
		if (settings == null || !settings.enabled()) {
			return null;
		}
		String sourcePath = settings.sourcePath();
		if (sourcePath == null || sourcePath.isBlank()) {
			return null;
		}
		Path video = Path.of(sourcePath);
		if (!Files.isReadable(video)) {
			return null;
		}
		double duration = MediaProbe.probe(video).durationSeconds();
		double target = WebcamSync.getWebcamMediaTargetTimeSeconds(timeSeconds, duration,
				(double) settings.timeOffsetMs());
		return decodeSourceFrame(video, target);
	}

	/** Decodes the fixture video at a project time to a top-first RGBA frame. */
	private static RgbaFrame decodeSourceFrame(java.nio.file.Path video, double timeSeconds)
			throws Exception {
		MediaInfo info = MediaProbe.probe(video);
		ColourInfo colour = info.video().orElseThrow().colour();
		try (VideoDecoder decoder = VideoDecoder.open(video)) {
			decoder.seekPrecise(Math.round(timeSeconds * 1_000_000));
			DecodedFrame frame = decoder.next();
			assertNotNull(frame, "no source frame at " + timeSeconds + "s");
			try (FrameConverter converter = new FrameConverter(frame.width(), frame.height(),
					FrameConverter.ScaleMode.PREVIEW, colour)) {
				ByteBuffer rgba = converter.convert(frame);
				ByteBuffer copy = ByteBuffer.allocateDirect(rgba.remaining());
				ByteBuffer from = rgba.duplicate();
				from.position(0);
				from.limit(rgba.remaining());
				copy.put(from);
				copy.position(0);
				return new RgbaFrame(frame.width(), frame.height(), copy);
			}
		}
	}

	/** Loads the committed cursor telemetry sidecar ({@code .cursor.json}) next to the video. */
	private static List<CursorSample> loadCursorSamples(Path videoPath) throws Exception {
		Path sidecar = Path.of(videoPath.toString() + ".cursor.json");
		if (!Files.exists(sidecar)) {
			return List.of();
		}
		ObjectMapper mapper = new ObjectMapper();
		Map<?, ?> root = mapper.readValue(Files.readAllBytes(sidecar), Map.class);
		List<CursorSample> samples = new ArrayList<>();
		for (Object item : (List<?>) root.get("samples")) {
			Map<?, ?> sample = (Map<?, ?>) item;
			String interaction = sample.get("interactionType") == null
					? null
					: String.valueOf(sample.get("interactionType"));
			String type = sample.get("cursorType") == null
					? null
					: String.valueOf(sample.get("cursorType"));
			samples.add(new CursorSample(
					((Number) sample.get("timeMs")).doubleValue() / 1000.0,
					((Number) sample.get("cx")).doubleValue(),
					((Number) sample.get("cy")).doubleValue(), type, interaction));
		}
		return List.copyOf(samples);
	}

	/** {@code true} when every pixel is identical (a blank render). */
	static boolean isUniform(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int first = pixels.getInt(0);
		for (int pixel = 1; pixel < frame.width() * frame.height(); pixel++) {
			if (pixels.getInt(pixel * 4) != first) {
				return false;
			}
		}
		return true;
	}
}
