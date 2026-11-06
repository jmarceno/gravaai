package dev.gravaai.media.segmentation;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * Keeps person segmentation off the playback render thread (webcam background blur, preview path).
 *
 * <p>Running the model inline cost about 27 ms per frame and the render thread paid it on every
 * decoded webcam frame, which starved the audio pump and made playback crackle. The cost is now
 * down to a few milliseconds, but inference still does not belong on a real-time thread: a slow
 * machine, a larger model or a busy GC pauses it, and the frame delivery has nothing to gain by
 * waiting.</p>
 *
 * <p>{@link #maskFor} therefore hands the frame to a single background worker and returns the most
 * recent completed mask immediately. Person masks change slowly and the segmenter already smooths
 * over three frames, so a mask that lags by a frame or two is invisible; an unsegmented frame would
 * not be (the background would visibly pop back into focus), which is why this returns the last
 * mask rather than {@code null} while a new one is in flight.</p>
 *
 * <p>Latest-wins: the worker holds one pending request and drops any that arrive while it is busy,
 * so a burst of frames never builds a backlog. Frames are copied into worker-owned buffers before
 * the request is published — the decoder reuses its buffers as soon as {@code maskFor} returns.</p>
 *
 * <p>Export and agent renders deliberately do not use this: they walk frames in order and want the
 * exact mask for the frame they are rendering.</p>
 */
public final class AsyncWebcamSegmenter implements WebcamSegmenter {

	private static final System.Logger LOG =
			System.getLogger(AsyncWebcamSegmenter.class.getName());

	/** How long {@link #close()} waits for an in-flight inference before giving up on it. */
	private static final long CLOSE_TIMEOUT_MS = 2000;

	private final WebcamSegmenter delegate;
	/** Guards the request slot and the recycled buffer. */
	private final Object requestLock = new Object();
	/** Buffer the worker has finished with, free to overwrite. Recycled to keep the path alloc-free. */
	private @Nullable ByteBuffer freeBuffer;
	private @Nullable RgbaFrame pendingFrame;
	/** Latest completed mask; written by the worker, read by the render thread. */
	private final AtomicReference<RgbaFrame> latestMask = new AtomicReference<>();
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final Thread worker;

	public AsyncWebcamSegmenter(WebcamSegmenter delegate) {
		this.delegate = delegate;
		this.worker = Thread.ofPlatform().daemon().name("gravaai-webcam-mask").start(this::pump);
	}

	@Override
	public @Nullable RgbaFrame maskFor(RgbaFrame frame) {
		if (!running.get()) {
			return null;
		}
		synchronized (requestLock) {
			// Replace any frame the worker has not started yet: only the newest is worth doing.
			pendingFrame = copyIntoStaging(frame);
			requestLock.notifyAll();
		}
		return latestMask.get();
	}

	/** True once a mask has been produced (diagnostics/tests). */
	public boolean hasMask() {
		return latestMask.get() != null;
	}

	/**
	 * Stops the worker and closes the delegate.
	 *
	 * <p>The worker can be mid-inference when this is called, and ORT session teardown while
	 * {@code Run} is executing is undefined behaviour, so this waits for the in-flight inference to
	 * return before closing. A native inference cannot be interrupted, so the wait is bounded: if
	 * it expires the delegate is deliberately left open — leaking one session at shutdown is far
	 * better than tearing it down under a running kernel.</p>
	 */
	public void close() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		synchronized (requestLock) {
			pendingFrame = null;
			requestLock.notifyAll();
		}
		boolean stopped = false;
		try {
			worker.join(CLOSE_TIMEOUT_MS);
			stopped = !worker.isAlive();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		if (stopped) {
			delegate.close();
		} else {
			LOG.log(System.Logger.Level.WARNING,
					"webcam mask worker still busy at close; leaving its segmenter open");
		}
	}

	private void pump() {
		while (running.get()) {
			RgbaFrame frame;
			synchronized (requestLock) {
				while (pendingFrame == null && running.get()) {
					try {
						requestLock.wait();
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (!running.get()) {
					return;
				}
				frame = pendingFrame;
				pendingFrame = null;
			}
			try {
				RgbaFrame mask = delegate.maskFor(frame);
				if (mask != null) {
					// Latest-wins: the render thread may read the previous mask mid-frame, so the
					// reference is swapped rather than mutated, and the old one is left to the GC.
					latestMask.set(mask);
				}
			} catch (RuntimeException error) {
				// A failed inference must not kill the worker: keep the last good mask and retry on
				// the next frame.
				LOG.log(System.Logger.Level.DEBUG, "webcam mask failed: " + error);
			} finally {
				// Return the frame's buffer only after the delegate is done reading it.
				synchronized (requestLock) {
					freeBuffer = frame.pixelsTopFirstRgba();
				}
			}
		}
	}

	/**
	 * Copies one decoder frame into a recyclable worker-owned buffer. The decoder reuses its own
	 * buffers as soon as {@code maskFor} returns, so the frame cannot be handed over by reference.
	 * The buffer the worker finished with is reused, so a steady stream allocates nothing; it is
	 * only recycled after inference returns, which is what keeps the worker from reading pixels the
	 * render thread has already overwritten.
	 */
	private RgbaFrame copyIntoStaging(RgbaFrame frame) {
		int bytes = frame.width() * frame.height() * 4;
		ByteBuffer target = freeBuffer;
		if (target == null || target.capacity() < bytes) {
			target = ByteBuffer.allocateDirect(bytes);
		}
		freeBuffer = null;
		ByteBuffer pixels = frame.pixelsTopFirstRgba().duplicate();
		pixels.position(0);
		pixels.limit(Math.min(bytes, pixels.capacity()));
		target.clear();
		target.put(pixels);
		target.position(0);
		target.limit(bytes);
		return new RgbaFrame(frame.width(), frame.height(), target);
	}
}
