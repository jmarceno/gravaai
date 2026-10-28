package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * Stage 5b: the SkSL motion-blur shaders and their Compositor wiring. The compositor renders the
 * committed-export style sequential frames (same instance, 60 fps content time), so the motion
 * blur frame history produces the same directional/radial effect as the TS renderer.
 */
class CompositorMotionBlurTest {

	private static final int WIDTH = 128;
	private static final int HEIGHT = 72;
	private static final Background BACKGROUND = Background.black();
	private static final FrameStyle STYLE = new FrameStyle(0, false, 0);

	private record SequenceResult(List<RgbaFrame> frames, List<MotionBlur.Effect> effects) {
		RgbaFrame lastFrame() {
			return frames.get(frames.size() - 1);
		}
	}

	@Test
	@Timeout(90)
	@DisplayName("a zoom step produces radial zoom motion blur")
	void zoomStepProducesRadialBlur() throws Exception {
		RgbaFrame source = splitFrame();
		ZoomRegionSpec region = new ZoomRegionSpec("zoom", 1000, 6000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
		// A short custom zoom-in duration makes the per-frame scale change large, like a real
		// fast zoom, so the radial blur is unambiguous.
		ZoomSpec blurredSpec = new ZoomSpec(List.of(region), 0, false, true, 100.0, null, 1, 1,
				1, 1.0);
		ZoomSpec unblurredSpec = new ZoomSpec(List.of(region), 0, false, true, 100.0, null, 1, 1,
				1, 0.0);
		SequenceResult blurred = renderSequence(source, blurredSpec, 97);
		SequenceResult unblurred = renderSequence(source, unblurredSpec, 97);

		int activeFrame = firstActiveFrame(blurred, MotionBlur.Path.ZOOM);
		assertTrue(activeFrame >= 0, "no radial zoom blur was produced: " + blurred.effects());
		assertTrue(changedPixels(blurred.frames().get(activeFrame),
				unblurred.frames().get(activeFrame)) > 0,
				"zoomed frame did not change with motion blur");
	}

	@Test
	@Timeout(90)
	@DisplayName("a connected zoom handoff produces directional pan motion blur")
	void connectedHandoffProducesDirectionalBlur() throws Exception {
		RgbaFrame source = splitFrame();
		List<ZoomRegionSpec> regions = List.of(
				new ZoomRegionSpec("a", 0, 2000, ZoomDepth.D2, new ZoomFocus(0.2, 0.2),
						ZoomMode.MANUAL),
				new ZoomRegionSpec("b", 2500, 5000, ZoomDepth.D2, new ZoomFocus(0.8, 0.8),
						ZoomMode.MANUAL));
		ZoomSpec blurredSpec = new ZoomSpec(regions, 0, true, true, null, null, 1, 1, 1, 1.0);
		ZoomSpec unblurredSpec = new ZoomSpec(regions, 0, true, true, null, null, 1, 1, 1, 0);
		SequenceResult blurred = renderSequence(source, blurredSpec, 155);
		SequenceResult unblurred = renderSequence(source, unblurredSpec, 155);

		int activeFrame = firstActiveFrame(blurred, MotionBlur.Path.DIRECTIONAL);
		assertTrue(activeFrame >= 0, "no directional pan blur was produced: " + blurred.effects());
		assertTrue(changedPixels(blurred.frames().get(activeFrame),
				unblurred.frames().get(activeFrame)) > 100,
				"handoff frame did not change with motion blur");
	}

	@Test
	@Timeout(60)
	@DisplayName("no motion blur amount keeps the shader path disabled")
	void noMotionBlurProducesNoEffect() throws Exception {
		RgbaFrame source = splitFrame();
		ZoomRegionSpec region = new ZoomRegionSpec("zoom", 1000, 6000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
		SequenceResult result = renderSequence(source, zoomSpec(List.of(region), 0.0), 40);
		assertTrue(result.effects().stream().allMatch(
				effect -> effect.path() == MotionBlur.Path.NONE));
	}

	private static int firstActiveFrame(SequenceResult result, MotionBlur.Path path) {
		for (int index = 0; index < result.effects().size(); index++) {
			MotionBlur.Effect effect = result.effects().get(index);
			if (effect.path() == path && (effect.magnitude() > 0 || effect.zoomStrength() > 0)) {
				return index;
			}
		}
		return -1;
	}

	private static ZoomSpec zoomSpec(List<ZoomRegionSpec> regions, double motionBlur) {
		return new ZoomSpec(regions, 0, false, true, null, null, 1, 1, 1, motionBlur);
	}

	private static SequenceResult renderSequence(RgbaFrame source, ZoomSpec zoom, int frames)
			throws InterruptedException {
		List<RgbaFrame> capturedFrames = new ArrayList<>();
		List<MotionBlur.Effect> effects = new ArrayList<>();
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, wallTime) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						compositorRef.set(compositor);
					}
					double contentTime = frameIndex / 60.0;
					compositor.render(surface, FrameInput.ofSource(frameIndex, contentTime,
							source, BACKGROUND, 0, LayoutSpec.FULL_FRAME, zoom, STYLE));
					if (effects.size() <= frameIndex) {
						effects.add(compositor.lastMotionBlurEffect());
					} else {
						effects.set((int) frameIndex, compositor.lastMotionBlurEffect());
					}
				},
				(pixels, frameWidth, frameHeight, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(frameWidth * frameHeight * 4);
					ByteBuffer copy = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
					copy.put(from);
					copy.position(0);
					capturedFrames.add(new RgbaFrame(WIDTH, HEIGHT, copy));
					if (frameIndex >= frames - 1) {
						received.countDown();
					}
				}, Duration.ofSeconds(20));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(30, TimeUnit.SECONDS)) {
				fail("motion blur frames did not complete; failure=" + preview.failure());
			}
		} finally {
			preview.close();
			Compositor compositor = compositorRef.get();
			if (compositor != null) {
				compositor.close();
			}
		}
		return new SequenceResult(List.copyOf(capturedFrames),
				List.copyOf(effects.subList(0, Math.min(frames, effects.size()))));
	}

	private static int changedPixels(RgbaFrame left, RgbaFrame right) {
		ByteBuffer a = left.pixelsTopFirstRgba();
		ByteBuffer b = right.pixelsTopFirstRgba();
		int changed = 0;
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			boolean differs = false;
			for (int channel = 0; channel < 4; channel++) {
				int offset = pixel * 4 + channel;
				if (Math.abs(Byte.toUnsignedInt(a.get(offset))
						- Byte.toUnsignedInt(b.get(offset))) > 2) {
					differs = true;
					break;
				}
			}
			if (differs) {
				changed++;
			}
		}
		return changed;
	}

	private static RgbaFrame splitFrame() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				boolean left = x < WIDTH / 2;
				buffer.put((byte) (left ? 255 : 0)).put((byte) 0)
						.put((byte) (left ? 0 : 255)).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}
}
