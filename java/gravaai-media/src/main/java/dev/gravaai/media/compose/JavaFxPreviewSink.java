package dev.gravaai.media.compose;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import dev.gravaai.media.gl.GlPreviewThread;

/**
 * Adapts read-back frames to JavaFX {@code PixelBuffer}s (plan §5.8, §5.9): two buffers, one
 * on screen, one being filled, so the GL thread never stalls on FX presentation.
 *
 * <p>Threading: {@link #frame} runs on the {@code gl-preview} thread, {@link #attach} and the
 * presentation run on the FX thread. Frames that arrive while the previous one has not come on
 * screen yet are coalesced, latest wins: the newest is presented as soon as FX is free, so the
 * last frame of a burst (a paused repaint) is never lost. The byte order is the
 * {@code PboFormat.BGRA} readback order,
 * matching {@code PixelFormat.getByteBgraPreInstance()}.</p>
 *
 * <p>Wired by the editor preview panel (P2.5, task-17): construct it, start a
 * {@link GlPreviewThread} with this sink as the consumer, and call {@link #attach} from the FX
 * thread once the {@code ImageView} exists. Nothing here touches GL.</p>
 */
public final class JavaFxPreviewSink implements GlPreviewThread.FrameConsumer {

	private static final System.Logger LOG = System.getLogger(JavaFxPreviewSink.class.getName());

	private final int width;
	private final int height;
	private final Semaphore presented = new Semaphore(1);
	private final AtomicLong framesReceived = new AtomicLong();
	private final AtomicLong framesDropped = new AtomicLong();
	private volatile PixelBuffer<ByteBuffer>[] buffers;
	private volatile WritableImage[] images;
	private volatile ImageView view;
	private volatile Runnable presentedListener;
	private volatile int target;
	private final Object latestLock = new Object();
	/** Newest frame that arrived while FX was presenting (latest wins). */
	private ByteBuffer latest;
	private boolean latestPending;

	public JavaFxPreviewSink(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid preview size: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.buffers = null;
		this.images = null;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Frames handed over by the GL thread. */
	public long framesReceived() {
		return framesReceived.get();
	}

	/** Frames dropped because the FX thread had not shown the previous one yet. */
	public long framesDropped() {
		return framesDropped.get();
	}

	/**
	 * Registers a callback run on the FX thread after each presented frame (the editor preview's
	 * annotation overlay follows the animated camera transform). Passing null clears it.
	 */
	public void setPresentedListener(Runnable listener) {
		this.presentedListener = listener;
	}

	/**
	 * Creates the two PixelBuffers and puts the first image on screen. FX thread only, once.
	 *
	 * @param imageView the preview control
	 */
	// Java has no generic array creation; the array never leaves this class.
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void attach(ImageView imageView) {
		if (imageView == null) {
			throw new IllegalArgumentException("imageView must not be null");
		}
		if (buffers != null) {
			throw new IllegalStateException("preview sink is already attached");
		}
		PixelBuffer<ByteBuffer>[] newBuffers = new PixelBuffer[2];
		WritableImage[] newImages = new WritableImage[2];
		for (int i = 0; i < 2; i++) {
			ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
			newBuffers[i] = new PixelBuffer<>(width, height, pixels,
					PixelFormat.getByteBgraPreInstance());
			newImages[i] = new WritableImage(newBuffers[i]);
		}
		this.buffers = newBuffers;
		this.images = newImages;
		this.view = imageView;
		this.view.setImage(newImages[0]);
		LOG.log(System.Logger.Level.DEBUG,
				() -> "preview sink attached at " + width + "x" + height);
	}

	/**
	 * Puts a filled PixelBuffer buffer back into the state Prism's texture upload expects
	 * ({@code remaining() == capacity()}): after the bulk put the position sits at the limit and
	 * an update would fail with "Upload requires N elements, but only 0 elements remain".
	 */
	static void rewindForUpload(ByteBuffer buffer) {
		buffer.position(0);
		buffer.limit(buffer.capacity());
	}

	@Override
	public void frame(ByteBuffer pixelsTopFirst, int frameWidth, int frameHeight, long frameIndex) {
		framesReceived.incrementAndGet();
		if (buffers == null) {
			framesDropped.incrementAndGet();
			return;
		}
		int frameBytes = frameWidth * frameHeight * 4;
		// The permit check and the pending hand-off share latestLock with the FX release, so a
		// frame parked here is always picked up by the presentation that is still running.
		synchronized (latestLock) {
			if (!presented.tryAcquire()) {
				// FX is still presenting: keep this frame as the latest and present it right after.
				// Dropping it lost the only frame of a paused repaint and left the preview stale.
				int bytes = frameBytes;
				if (latest == null || latest.capacity() < bytes) {
					latest = ByteBuffer.allocateDirect(bytes);
				}
				ByteBuffer source = pixelsTopFirst.duplicate();
				source.position(0);
				source.limit(bytes);
				latest.clear();
				latest.put(source);
				latestPending = true;
				framesDropped.incrementAndGet();
				return;
			}
		}
		present(pixelsTopFirst, frameBytes);
	}

	/** Copies into the free buffer and schedules its presentation; the caller holds the permit. */
	private void present(ByteBuffer pixelsTopFirst, int bytes) {
		PixelBuffer<ByteBuffer>[] localBuffers = buffers;
		WritableImage[] localImages = images;
		int index = target;
		ByteBuffer destination = localBuffers[index].getBuffer();
		ByteBuffer source = pixelsTopFirst.duplicate();
		source.position(0);
		source.limit(bytes);
		destination.position(0);
		destination.put(source);
		rewindForUpload(destination);
		target ^= 1;
		Platform.runLater(() -> {
			try {
				// Prism uploads the PixelBuffer's ByteBuffer when it renders the image and its
				// BaseTexture checkUpdateParams requires the bytes to be readable: position 0,
				// limit = capacity (ui-kit hit "Upload requires N elements, but only 0 remain").
				rewindForUpload(destination);
				localBuffers[index].updateBuffer(buffer -> null);
				if (view != null) {
					view.setImage(localImages[index]);
				}
				Runnable listener = presentedListener;
				if (listener != null) {
					try {
						listener.run();
					} catch (RuntimeException error) {
						// A UI listener must never break the presentation path.
						LOG.log(System.Logger.Level.DEBUG,
								() -> "preview presented listener failed: " + error);
					}
				}
			} finally {
				presentLatestOrRelease();
			}
		});
	}

	/** FX thread: presents a frame that arrived while busy, else frees the presentation slot. */
	private void presentLatestOrRelease() {
		synchronized (latestLock) {
			if (latestPending) {
				latestPending = false;
				// Keep the permit: the GL thread cannot write into target while we copy.
				present(latest, latest.position());
				return;
			}
			presented.release();
		}
	}
}
