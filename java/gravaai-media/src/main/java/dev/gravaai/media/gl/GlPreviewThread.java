package dev.gravaai.media.gl;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The dedicated {@code gl-preview} platform thread (plan §4.1, §5.8): creates the surfaceless
 * {@link GlContext}, the {@link GlFramebuffer}, the Skija surface and the PBO readback, then
 * runs a fixed-rate loop that renders through a caller-supplied {@link FrameRenderer} and
 * hands read-back frames to a {@link FrameConsumer}.
 *
 * <p>The consumer receives the same direct buffer every frame with one frame of latency (the
 * classic double-PBO hand-off); it must copy what it needs before returning. Threading rules:
 * the renderer and consumer both run on this thread, and nothing here touches JavaFX — the
 * preview panel adapts the consumer to a {@code PixelBuffer} on the FX thread
 * ({@code PixelBufferPreviewSink}).</p>
 */
public final class GlPreviewThread implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(GlPreviewThread.class.getName());
	private static final long FRAME_NANOS = 1_000_000_000L / 60;

	/** Draws one composited frame into the Skija surface. Runs on the GL thread. */
	@FunctionalInterface
	public interface FrameRenderer {
		void render(SkiaGlSurface surface, long frameIndex, double timeSeconds);
	}

	/** Receives a read-back frame (top-first, {@link PboFormat} byte order). Runs on the GL thread. */
	@FunctionalInterface
	public interface FrameConsumer {
		void frame(ByteBuffer pixelsTopFirst, int width, int height, long frameIndex);
	}

	private final int width;
	private final int height;
	private final PboFormat format;
	private final FrameRenderer renderer;
	private final FrameConsumer consumer;
	private final CountDownLatch ready = new CountDownLatch(1);
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong framesRendered = new AtomicLong();
	private final ByteBuffer consumerBuffer;
	private final Duration readyTimeout;
	private volatile @Nullable Throwable failure;
	private volatile String glDescription = "not started";
	private @Nullable Thread thread;

	/**
	 * @param width the render target width
	 * @param height the render target height
	 * @param format the readback byte order
	 * @param renderer the per-frame renderer (must paint every pixel it wants to keep)
	 * @param consumer the readback consumer, or {@code null} when only rendering is needed
	 * @param readyTimeout how long {@link #awaitReady()} waits for GL initialisation
	 */
	public GlPreviewThread(int width, int height, PboFormat format, FrameRenderer renderer,
			FrameConsumer consumer, Duration readyTimeout) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid preview size: " + width + "x" + height);
		}
		if (renderer == null) {
			throw new IllegalArgumentException("renderer must not be null");
		}
		this.width = width;
		this.height = height;
		this.format = format;
		this.renderer = renderer;
		this.consumer = consumer;
		this.readyTimeout = readyTimeout;
		this.consumerBuffer = ByteBuffer.allocateDirect(width * height * format.bytesPerPixel());
	}

	/** Starts the thread; call {@link #awaitReady()} before drawing depends on it. */
	public synchronized void start() {
		if (thread != null) {
			throw new IllegalStateException("preview thread already started");
		}
		running.set(true);
		thread = Thread.ofPlatform().name("gl-preview").daemon().start(this::run);
	}

	/**
	 * Waits for the GL context and surfaces to become ready.
	 *
	 * @return {@code true} when ready; {@code false} on timeout or initialisation failure
	 */
	public boolean awaitReady() {
		try {
			if (!ready.await(readyTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				return false;
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
		return failure == null && !"not started".equals(glDescription);
	}

	/** The GL renderer/version string, or {@code "not started"}. */
	public String glDescription() {
		return glDescription;
	}

	/** The failure that stopped the loop, or {@code null}. */
	public @Nullable Throwable failure() {
		return failure;
	}

	/** Number of rendered frames so far. */
	public long framesRendered() {
		return framesRendered.get();
	}

	private void run() {
		try (GlContext context = GlContext.create();
				GlFramebuffer framebuffer = new GlFramebuffer(width, height);
				SkiaGlSurface surface = SkiaGlSurface.create(framebuffer);
				PboDoubleBuffer readback = new PboDoubleBuffer(width, height, format)) {
			glDescription = context.description();
			ready.countDown();
			LOG.log(System.Logger.Level.DEBUG, () -> "gl-preview ready: " + glDescription
					+ " (" + width + "x" + height + ")");
			long origin = System.nanoTime();
			long nextFrame = origin;
			long frame = 0;
			while (running.get()) {
				long start = System.nanoTime();
				double timeSeconds = (start - origin) / 1e9;
				renderer.render(surface, frame, timeSeconds);
				surface.flush();
				framebuffer.bind();
				readback.startReadback();
				GlFramebuffer.unbind();
				if (frame > 0 && consumer != null) {
					if (readback.finishPreviousFrame(consumerBuffer)) {
						consumer.frame(consumerBuffer, width, height, frame - 1);
					}
				}
				// Raw GL touched bindings Skia tracks (PBO readback); resync before the next draw.
				surface.resetGlState();
				framesRendered.set(++frame);
				nextFrame += FRAME_NANOS;
				long wait = nextFrame - System.nanoTime();
				if (wait > 0) {
					LockSupport.parkNanos(wait);
				} else {
					nextFrame = System.nanoTime();
				}
			}
		} catch (Throwable failure) {
			this.failure = failure;
			LOG.log(System.Logger.Level.ERROR, "gl-preview failed", failure);
		} finally {
			ready.countDown();
		}
	}

	/** Stops the loop and joins the thread (the GL context is destroyed on that thread). */
	@Override
	public void close() {
		running.set(false);
		Thread current = thread;
		if (current == null) {
			return;
		}
		try {
			current.join(5_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		if (current.isAlive()) {
			LOG.log(System.Logger.Level.WARNING, "gl-preview thread did not stop within 5 s");
			current.interrupt();
		}
	}
}
