package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.gl.GlContext;
import dev.gravaai.media.gl.GlFramebuffer;
import dev.gravaai.media.gl.GlYuv420Converter;
import dev.gravaai.media.gl.PboDoubleBuffer;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.gl.SkiaGlSurface;

/**
 * Compositor-backed {@link ExportFrameProvider} (plan §5.10): renders the same FrameInput pipeline
 * the preview and golden tests use, synchronously per export frame, on a dedicated offscreen
 * {@code gl-export} thread.
 *
 * <p>The thread is request driven: each {@link #renderFrame} renders, converts and reads back at
 * once. (It used to run on the preview's fixed 60 Hz loop with one tick of PBO latency, which
 * capped export at about 30 fps whatever the hardware.) For MP4 the frame is converted to I420
 * on the GPU ({@link GlYuv420Converter}, see {@link #renderFrameYuv420p}). While the GPU
 * renders frame N the caller thread already decodes frame N+1 (sequential export requests hit
 * that prefetch; any other request decodes on demand).</p>
 *
 * <p>The caller (services/export + AppContext wiring) supplies a
 * {@link FrameAtTime.FrameInputFactory} that resolves the project's FrameInput for a timeline time
 * exactly like the preview and golden renderer (source/webcam frames, cursor samples,
 * background/layout/zoom/style, webcam/annotations/captions/keystrokes/fade). Only one
 * {@code renderFrame}/{@code reset} call may be in flight at a time (the export thread is serial).
 * {@link #reset()} runs {@link Compositor#reset()} on the GL thread so the GIF exporter's
 * palette+output passes and reused providers start from a clean spring/motion-blur history.</p>
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class CompositorExportFrameProvider implements ExportFrameProvider {

	private static final System.Logger LOG =
			System.getLogger(CompositorExportFrameProvider.class.getName());
	private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration READY_TIMEOUT = Duration.ofSeconds(15);

	/** GL-thread state, only touched on the {@code gl-export} thread. */
	private static final class GlState {
		GlContext context;
		GlFramebuffer framebuffer;
		SkiaGlSurface surface;
		PboDoubleBuffer readback;
		GlYuv420Converter yuv;
		ByteBuffer rgbaStaging;
		ByteBuffer yuvStaging;
	}

	private final int width;
	private final int height;
	private final int frameRate;
	private final FrameAtTime.FrameInputFactory inputFactory;
	private final Compositor compositor;
	private final LinkedBlockingQueue<Consumer<GlState>> tasks = new LinkedBlockingQueue<>();
	private final Object startLock = new Object();
	private @Nullable Thread thread;
	private @Nullable CompletableFuture<Void> ready;
	private volatile boolean closed;
	// Caller thread only: the next frame's input, decoded while the GPU renders the current one.
	private @Nullable FrameInput prefetched;
	private double prefetchedTime = Double.NaN;

	/**
	 * @param width output width in pixels
	 * @param height output height in pixels
	 * @param frameRate output frame rate
	 * @param inputFactory resolves the compositor input for the requested timeline time
	 */
	public CompositorExportFrameProvider(int width, int height, int frameRate,
			FrameAtTime.FrameInputFactory inputFactory) {
		this(width, height, frameRate, inputFactory, FrameOverlayHooks.NONE);
	}

	/**
	 * @param width output width in pixels
	 * @param height output height in pixels
	 * @param frameRate output frame rate
	 * @param inputFactory resolves the compositor input for the requested timeline time
	 * @param overlayHooks extension render hooks for every frame (plan §5.18); null means none.
	 *        The exporter and the preview share the compositor and the same phase order.
	 */
	public CompositorExportFrameProvider(int width, int height, int frameRate,
			FrameAtTime.FrameInputFactory inputFactory, FrameOverlayHooks overlayHooks) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid export frame size: " + width + "x" + height);
		}
		if (frameRate <= 0) {
			throw new IllegalArgumentException("frameRate must be positive: " + frameRate);
		}
		this.width = width;
		this.height = height;
		this.frameRate = frameRate;
		this.inputFactory = Objects.requireNonNull(inputFactory, "inputFactory");
		this.compositor = new Compositor(width, height);
		this.compositor.setOverlayHooks(overlayHooks == null ? FrameOverlayHooks.NONE : overlayHooks);
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public int frameRate() {
		return frameRate;
	}

	@Override
	public void renderFrame(long frameIndex, double timeSeconds, ByteBuffer rgba) {
		render(frameIndex, timeSeconds, rgba, width * height * 4, false);
	}

	@Override
	public boolean supportsYuv420p() {
		return GlYuv420Converter.supports(width, height);
	}

	@Override
	public void renderFrameYuv420p(long frameIndex, double timeSeconds, ByteBuffer yuv) {
		if (!supportsYuv420p()) {
			throw new UnsupportedOperationException("no YUV 4:2:0 output for " + width + "x" + height);
		}
		render(frameIndex, timeSeconds, yuv, GlYuv420Converter.frameBytes(width, height), true);
	}

	private void render(long frameIndex, double timeSeconds, ByteBuffer target, int bytes,
			boolean yuv) {
		if (frameIndex < 0) {
			throw new IllegalArgumentException("frameIndex must not be negative: " + frameIndex);
		}
		if (!Double.isFinite(timeSeconds)) {
			throw new IllegalArgumentException("timeSeconds must be finite");
		}
		Objects.requireNonNull(target, "target");
		if (target.capacity() < bytes) {
			throw new IllegalArgumentException("frame buffer too small: " + target.capacity() + " < "
					+ bytes);
		}
		if (closed) {
			throw new IllegalStateException("export frame provider is closed");
		}
		ensureStarted();
		// Decode and resolve on the caller thread; the GL thread only composites and reads back.
		FrameInput input = takePrefetched(timeSeconds);
		if (input == null) {
			input = inputFactory.inputAtTime(timeSeconds);
		}
		if (input == null) {
			throw new IllegalArgumentException(
					"input factory returned no FrameInput for " + timeSeconds + "s");
		}
		FrameInput frameInput = input;
		CompletableFuture<Void> done = submit(gl -> {
			// render() flushes the surface itself.
			compositor.render(gl.surface, frameInput);
			ByteBuffer staging;
			if (yuv) {
				if (gl.yuv == null) {
					gl.yuv = new GlYuv420Converter(width, height);
					gl.yuvStaging = ByteBuffer.allocateDirect(bytes);
				}
				gl.yuv.convert(gl.framebuffer.colorTextureId(), gl.yuvStaging);
				staging = gl.yuvStaging;
			} else {
				gl.framebuffer.bind();
				gl.readback.readSynchronously(gl.rgbaStaging);
				GlFramebuffer.unbind();
				staging = gl.rgbaStaging;
			}
			gl.surface.resetGlState();
			ByteBuffer source = staging.duplicate();
			source.position(0);
			source.limit(bytes);
			target.clear();
			target.put(source);
		});
		// While the GPU composites this frame, decode the next one (the readers keep two buffers, so
		// this frame's pixels stay intact until its upload is done).
		prefetch(timeSeconds + 1.0 / frameRate);
		await(done, "render frame " + frameIndex + " at " + timeSeconds + "s");
	}

	/** The prefetched input for {@code timeSeconds}, or null (a non-sequential request). */
	private @Nullable FrameInput takePrefetched(double timeSeconds) {
		FrameInput input = prefetched;
		double time = prefetchedTime;
		prefetched = null;
		prefetchedTime = Double.NaN;
		return input != null && Math.abs(time - timeSeconds) < 1e-9 ? input : null;
	}

	private void prefetch(double timeSeconds) {
		try {
			prefetched = inputFactory.inputAtTime(timeSeconds);
			prefetchedTime = timeSeconds;
		} catch (RuntimeException error) {
			// Past the end of the timeline, or a factory that cannot look ahead: render on demand.
			prefetched = null;
			prefetchedTime = Double.NaN;
		}
	}

	@Override
	public void reset() {
		if (closed) {
			return;
		}
		ensureStarted();
		prefetched = null;
		prefetchedTime = Double.NaN;
		await(submit(gl -> compositor.reset()), "reset");
	}

	private CompletableFuture<Void> submit(Consumer<GlState> work) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		tasks.add(gl -> {
			try {
				work.accept(gl);
				done.complete(null);
			} catch (Throwable error) {
				done.completeExceptionally(error);
			}
		});
		return done;
	}

	private static void await(CompletableFuture<Void> done, String what) {
		try {
			done.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException timeout) {
			throw new IllegalStateException("timed out waiting for the compositor to " + what);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the compositor to "
					+ what, interrupted);
		} catch (ExecutionException failed) {
			Throwable cause = failed.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("compositor failed to " + what, cause);
		}
	}

	private void ensureStarted() {
		CompletableFuture<Void> started;
		synchronized (startLock) {
			if (ready == null) {
				ready = new CompletableFuture<>();
				thread = Thread.ofPlatform().name("gl-export").daemon().start(this::run);
			}
			started = ready;
		}
		try {
			started.get(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while starting the export GL context");
		} catch (ExecutionException | TimeoutException failure) {
			throw new IllegalStateException("GL context unavailable for compositor export: "
					+ (failure instanceof ExecutionException ? failure.getCause() : failure));
		}
	}

	private void run() {
		GlState gl = new GlState();
		try {
			gl.context = GlContext.create();
			gl.framebuffer = new GlFramebuffer(width, height);
			gl.surface = SkiaGlSurface.create(gl.framebuffer);
			gl.readback = new PboDoubleBuffer(width, height, PboFormat.RGBA);
			gl.rgbaStaging = ByteBuffer.allocateDirect(width * height * 4);
			LOG.log(System.Logger.Level.DEBUG, () -> "gl-export ready: " + gl.context.description()
					+ " (" + width + "x" + height + ")");
			ready.complete(null);
			while (!closed) {
				Consumer<GlState> task = tasks.poll(100, TimeUnit.MILLISECONDS);
				if (task != null) {
					task.accept(gl);
				}
			}
		} catch (Throwable failure) {
			ready.completeExceptionally(failure);
			LOG.log(System.Logger.Level.ERROR, "gl-export failed", failure);
		} finally {
			Consumer<GlState> pending;
			while ((pending = tasks.poll()) != null) {
				// Wake any caller still waiting; the GL resources are going away.
				final Consumer<GlState> orphan = pending;
				try {
					orphan.accept(null);
				} catch (RuntimeException ignored) {
					// The task records its own failure.
				}
			}
			closeQuietly(gl);
		}
	}

	private void closeQuietly(GlState gl) {
		try {
			compositor.close();
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "compositor close: " + error);
		}
		for (AutoCloseable resource : new AutoCloseable[] {gl.yuv, gl.readback, gl.surface,
				gl.framebuffer, gl.context}) {
			if (resource == null) {
				continue;
			}
			try {
				resource.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.DEBUG, "gl-export close: " + error);
			}
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		Thread current;
		synchronized (startLock) {
			current = thread;
		}
		if (current == null) {
			compositor.close();
			return;
		}
		try {
			current.join(5_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		if (current.isAlive()) {
			LOG.log(System.Logger.Level.WARNING, "gl-export thread did not stop within 5 s");
		}
	}
}
