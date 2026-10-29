package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.gl.SkiaGlSurface;

/**
 * Compositor-backed {@link FrameAtTime} for filmstrip/thumbnail rendering: one compositor on a
 * dedicated offscreen GL thread, synchronously rendering the requested timeline time.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class CompositorFrameAtTime implements FrameAtTime {

	private static final Duration WAIT = Duration.ofSeconds(15);

	private final int width;
	private final int height;
	private final FrameInputFactory factory;
	private final Object lock = new Object();
	private final Compositor compositor;
	private final GlPreviewThread preview;
	private @Nullable FrameInput pendingInput;
	private @Nullable CountDownLatch pendingLatch;
	private @Nullable RgbaFrame pendingResult;
	private boolean renderedForRequest;
	/** GL tick that rendered the pending request; readback arrives one tick later. */
	private long renderedFrameIndex = -1;
	private boolean started;
	private volatile boolean closed;

	public CompositorFrameAtTime(int width, int height, FrameInputFactory factory) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid frame size: " + width + "x" + height);
		}
		if (factory == null) {
			throw new IllegalArgumentException("factory must not be null");
		}
		this.width = width;
		this.height = height;
		this.factory = factory;
		this.compositor = new Compositor(width, height);
		this.preview = new GlPreviewThread(width, height, PboFormat.RGBA,
				this::renderOnGlThread, this::readBack, Duration.ofSeconds(15));
	}

	@Override
	public @Nullable RgbaFrame frameAtTime(double timelineSeconds) {
		if (!Double.isFinite(timelineSeconds)) {
			throw new IllegalArgumentException("timelineSeconds must be finite");
		}
		ensureStarted();
		CountDownLatch latch = new CountDownLatch(1);
		synchronized (lock) {
			if (closed) {
				throw new IllegalStateException("frame renderer is closed");
			}
			pendingInput = factory.inputAtTime(timelineSeconds);
			if (pendingInput == null) {
				throw new IllegalArgumentException(
						"factory returned no input for " + timelineSeconds + "s");
			}
			pendingLatch = latch;
			pendingResult = null;
			renderedForRequest = false;
		}
		try {
			if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("timed out rendering the frame at "
						+ timelineSeconds + "s (glFailure=" + preview.failure()
						+ ", framesRendered=" + preview.framesRendered() + ")");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while rendering a filmstrip frame",
					interrupted);
		}
		synchronized (lock) {
			RgbaFrame result = pendingResult;
			pendingResult = null;
			return result;
		}
	}

	private void ensureStarted() {
		synchronized (lock) {
			if (started) {
				return;
			}
			started = true;
		}
		preview.start();
		if (!preview.awaitReady()) {
			throw new IllegalStateException(
					"GL context unavailable for filmstrip rendering: " + preview.failure());
		}
	}

	private void renderOnGlThread(SkiaGlSurface surface, long frameIndex, double timeSeconds) {
		FrameInput input;
		synchronized (lock) {
			// Render once per request: GlPreviewThread delivers the previous tick's pixels, so
			// re-rendering every tick would advance the matching frame index past the readback.
			input = pendingInput;
			if (input == null || pendingLatch == null || renderedForRequest) {
				return;
			}
		}
		compositor.render(surface, input);
		synchronized (lock) {
			renderedForRequest = true;
			renderedFrameIndex = frameIndex;
		}
	}

	private void readBack(ByteBuffer pixels, int frameWidth, int frameHeight, long frameIndex) {
		RgbaFrame result;
		synchronized (lock) {
			// GlPreviewThread delivers the PREVIOUS tick's pixels: wait for the readback whose
			// frameIndex matches the tick that rendered our request, else we would return stale
			// (e.g. background-only) pixels.
			if (!renderedForRequest || pendingLatch == null
					|| frameIndex != renderedFrameIndex) {
				return;
			}
			renderedForRequest = false;
			pendingInput = null;
			ByteBuffer copy = ByteBuffer.allocateDirect(frameWidth * frameHeight * 4);
			ByteBuffer from = pixels.duplicate();
			from.position(0);
			from.limit(frameWidth * frameHeight * 4);
			copy.put(from);
			copy.position(0);
			result = new RgbaFrame(frameWidth, frameHeight, copy);
			pendingResult = result;
			pendingLatch.countDown();
			pendingLatch = null;
		}
	}

	@Override
	public void close() {
		closed = true;
		try {
			preview.close();
		} finally {
			compositor.close();
		}
	}
}
