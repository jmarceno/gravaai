package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.media.compose.CameraGeometry;
import dev.gravaai.media.compose.Compositor;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.JavaFxPreviewSink;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import javafx.scene.image.ImageView;

/**
 * Drives the compositor for the editor preview (plan §5.8/§5.9, P2.5/P2.11): one
 * {@link GlPreviewThread} on the dedicated {@code gl-preview} thread, one {@link Compositor} per
 * thread (created and closed on it), and the media layer's {@link JavaFxPreviewSink} that uploads
 * the read-back BGRA frame to a JavaFX {@code PixelBuffer}.
 *
 * <p>Frame hand-off is non-blocking: {@link #submit(FrameInput)} copies the decoder-owned frames
 * into a {@link PreviewFrameQueue} and returns, so the engine's {@code setMedia}/{@code onFrame}
 * path never waits for the compositor (a slow first decode cannot stall the FX thread or the
 * engine lock). The 60 Hz GL loop draws the latest queued copy and only re-uploads when a new frame
 * arrived; older copies are coalesced away.
 */
public final class PreviewRenderer implements AutoCloseable {

	private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
	private static final long CLOSE_TIMEOUT_MS = 500;

	private final int width;
	private final int height;
	private final JavaFxPreviewSink sink;
	private final PreviewFrameQueue queue = new PreviewFrameQueue();
	private final AtomicReference<@Nullable Compositor> compositor = new AtomicReference<>();
	/** GL thread only: whether the tick with this parity drew a new frame. */
	private final boolean[] drewAtTick = new boolean[2];
	private final AtomicLong shutdownSerial = new AtomicLong();
	private final Object shutdownLock = new Object();

	private @Nullable GlPreviewThread thread;
	private volatile @Nullable Throwable failure;
	private volatile boolean closed;
	private volatile boolean shutdownRequested;
	/** Geometry of the last drawn frame (annotation overlay positions). */
	private volatile CameraGeometry geometry;
	/** Extension render hooks (P3.3): applied to every compositor this renderer creates. */
	private volatile FrameOverlayHooks overlayHooks = FrameOverlayHooks.NONE;

	private PreviewRenderer(int width, int height) {
		this.width = width;
		this.height = height;
		this.sink = new JavaFxPreviewSink(width, height);
		this.geometry = CameraGeometry.fullFrame(width, height);
	}

	/**
	 * Creates the renderer and blocks until the EGL/GL context is ready.
	 *
	 * @throws IllegalStateException when the surface and contexts cannot be created; the message
	 *         contains the GL failure
	 */
	public static PreviewRenderer start(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid preview size: " + width + "x" + height);
		}
		PreviewRenderer renderer = new PreviewRenderer(width, height);
		try {
			renderer.startThread();
			return renderer;
		} catch (RuntimeException error) {
			renderer.close();
			throw error;
		}
	}

	private void startThread() {
		// The PBO readback hands over GL tick N-1 while tick N renders, so "a frame was drawn" is
		// tracked per tick: presenting on the next readback instead would show the stale buffer and
		// lose the real frame, which left a paused preview black after any repaint.
		GlPreviewThread preview = new GlPreviewThread(width, height, PboFormat.BGRA,
				(surface, frameIndex, timeSeconds) ->
						drewAtTick[(int) (frameIndex & 1)] = drawFrame(surface),
				(pixels, frameWidth, frameHeight, frameIndex) -> {
					if (drewAtTick[(int) (frameIndex & 1)]) {
						sink.frame(pixels, frameWidth, frameHeight, frameIndex);
					}
				}, READY_TIMEOUT);
		preview.start();
		this.thread = preview;
		if (!preview.awaitReady()) {
			Throwable cause = preview.failure();
			throw new IllegalStateException("preview GL context is not available"
					+ (cause == null ? "" : ": " + cause), cause);
		}
	}

	/** GL thread: draws the latest queued input; returns true when the framebuffer changed. */
	private boolean drawFrame(dev.gravaai.media.gl.SkiaGlSurface surface) {
		if (shutdownRequested) {
			closeCompositorOnGlThread();
			shutdownSerial.incrementAndGet();
			signalShutdown();
			return false;
		}
		FrameInput next = queue.pollLatest();
		if (next == null) {
			// Keep the previous framebuffer content; skip the readback upload (see consumer).
			return false;
		}
		Compositor active = compositor.get();
		if (active == null) {
			active = new Compositor(width, height);
			active.setOverlayHooks(overlayHooks);
			compositor.set(active);
		}
		try {
			active.render(surface, next);
			geometry = active.geometry();
			return true;
		} catch (RuntimeException error) {
			failure = error;
			return false;
		} finally {
			queue.finishRendering();
		}
	}

	/** FX thread: puts the first PixelBuffer image on screen; call once. */
	public void attach(ImageView view) {
		sink.attach(view);
	}

	/**
	 * Registers the FX-thread callback that runs after each presented frame (annotation overlay
	 * geometry refresh). Safe to call before the sink is attached.
	 *
	 * @param listener null clears the callback
	 */
	public void setPresentedListener(@Nullable Runnable listener) {
		sink.setPresentedListener(listener);
	}

	/**
	 * Sets the extension render hooks used by this renderer's compositor (P3.3). Thread-safe; a
	 * compositor created later picks the value up, an existing one is updated immediately.
	 */
	public void setOverlayHooks(FrameOverlayHooks hooks) {
		this.overlayHooks = hooks == null ? FrameOverlayHooks.NONE : hooks;
		Compositor active = compositor.get();
		if (active != null) {
			active.setOverlayHooks(this.overlayHooks);
		}
	}

	/** Geometry of the last drawn frame, or the full frame before the first draw. */
	public CameraGeometry geometry() {
		CameraGeometry current = geometry;
		return current == null ? CameraGeometry.fullFrame(width, height) : current;
	}

	/**
	 * Queues one compositor input without waiting for the draw (engine thread). The queue copies
	 * the source/webcam frames before returning, so the caller may reuse its buffers immediately.
	 */
	public boolean submit(FrameInput input) {
		if (closed || input == null) {
			return false;
		}
		queue.submit(input);
		return true;
	}

	/** Background-only repaint that does not depend on decoder buffers (idle state). */
	public void renderIdle(FrameInput input) {
		submit(input);
	}

	/** Frames handed to the FX sink. */
	public long framesReceived() {
		return sink.framesReceived();
	}

	/** Frames dropped because the FX thread had not presented the previous one. */
	public long framesDropped() {
		return sink.framesDropped();
	}

	/** Frames copied by the engine thread since the renderer started (diagnostics). */
	public long framesSubmitted() {
		return queue.submittedCount();
	}

	/** Frames drawn by the GL thread since the renderer started (diagnostics). */
	public long framesDrawn() {
		return queue.drawnCount();
	}

	/** True when a submitted frame is waiting for the GL thread (diagnostics). */
	public boolean hasPendingFrame() {
		return queue.hasPending();
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The failure that stopped the compositor, or null. */
	public @Nullable Throwable failure() {
		return failure;
	}

	private void signalShutdown() {
		synchronized (shutdownLock) {
			shutdownLock.notifyAll();
		}
	}

	private void closeCompositorOnGlThread() {
		Compositor active = compositor.getAndSet(null);
		if (active != null) {
			active.close();
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		GlPreviewThread preview = thread;
		if (preview != null) {
			// Ask the GL thread to release the Skija compositor on its own thread first.
			long serial = shutdownSerial.get();
			shutdownRequested = true;
			synchronized (shutdownLock) {
				long deadline = System.nanoTime() + CLOSE_TIMEOUT_MS * 1_000_000L;
				while (shutdownSerial.get() <= serial && failure == null) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						break;
					}
					try {
						shutdownLock.wait(Math.max(1, remaining / 1_000_000L));
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
			preview.close();
			thread = null;
		}
	}
}
