package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * A {@link FrameSource} with a decode-ahead ring (plan §5.9: 3-6 frames per source on a decode
 * thread). The worker decodes sequentially; {@link #frameAt} consumes the newest frame whose pts is
 * at or before the requested source time, and requests a seek when the request moves backwards or
 * beyond the ring.
 *
 * <p>Only one consumer calls {@link #frameAt} (the playback render thread); the returned
 * {@link RgbaFrame} stays valid until the next {@link #frameAt} call.
 */
public final class DecoderFrameSource implements FrameSource {

	/** Default ring depth (plan §5.9: 3-6 frames per source). */
	public static final int DEFAULT_RING_CAPACITY = 4;

	private static final long SEEK_TOLERANCE_US = 250_000;
	/** Seek slightly before the target so the ring also holds the frame at or before it. */
	private static final long SEEK_BACKOFF_US = 50_000;
	/** A request farther than this from the ring head seeks forward instead of decoding through. */
	private static final long MAX_SEQUENTIAL_GAP_US = 500_000;
	private static final long WAIT_SLICE_MS = 200;
	/** Bounded, generous for a loaded build machine: the first 1080p decode can take seconds. */
	private static final long MAX_WAIT_MS = 20_000;
	/** First step back when a seek lands at or past the last frame; doubles until a frame decodes. */
	private static final double END_SEEK_BACKOFF_SECONDS = 0.25;

	/** The decoder is opened inside the worker so FFM arenas belong to the decode thread. */
	private final Supplier<VideoFrameDecoder> decoderSupplier;
	private final @Nullable Double durationOverrideSeconds;
	/** Written by the worker, read by {@link #durationSeconds} on consumer threads. */
	private volatile @Nullable VideoFrameDecoder decoder;
	private final int ringCapacity;
	private final Object lock = new Object();
	private final Deque<VideoFrame> ring = new ArrayDeque<>();
	private final Thread worker;
	private boolean closed;
	private boolean eof;
	private @Nullable RuntimeException failure;
	private @Nullable Double seekRequest;
	/** True while the worker is inside decoder.seek()+next(): consumers must not queue another. */
	private boolean seekInProgress;
	private long lastReturnedPtsUs = -1;
	/** Pts of the stream's last frame once the worker has decoded up to EOF; -1 while unknown. */
	private long endPtsUs = -1;
	/** Newest pts the worker decoded since the last seek; becomes {@link #endPtsUs} at EOF. */
	private long lastDecodedPtsUs = -1;
	// Decode profiling: number of seeks/decoded frames, logged once when the first frame lands.
	private long seekCount;
	private long framesDecoded;
	private long firstSeekStartedNanos = -1;
	private boolean profiledFirstFrame;
	private @Nullable VideoFrame lastReturned;

	private DecoderFrameSource(
			Supplier<VideoFrameDecoder> decoderSupplier,
			@Nullable Double durationOverrideSeconds,
			int ringCapacity,
			@Nullable VideoFrameDecoder preOpenedDecoder) {
		this.decoderSupplier = decoderSupplier;
		this.decoder = preOpenedDecoder;
		this.durationOverrideSeconds = durationOverrideSeconds != null
				&& Double.isFinite(durationOverrideSeconds)
				&& durationOverrideSeconds > 0
						? durationOverrideSeconds
						: null;
		this.ringCapacity = Math.max(1, ringCapacity);
		this.worker = Thread.ofPlatform().daemon().name("gravaai-playback-decode").start(this::decodeLoop);
	}

	public static DecoderFrameSource open(VideoFrameDecoder decoder) {
		return open(decoder, DEFAULT_RING_CAPACITY);
	}

	public static DecoderFrameSource open(VideoFrameDecoder decoder, int ringCapacity) {
		if (decoder == null) {
			throw new IllegalArgumentException("decoder must not be null");
		}
		return new DecoderFrameSource(() -> decoder, null, ringCapacity, decoder);
	}

	/** Opens the decoder inside the decode worker (FFM confined arenas are thread-owned). */
	public static DecoderFrameSource open(Supplier<VideoFrameDecoder> decoderSupplier) {
		return open(decoderSupplier, null, DEFAULT_RING_CAPACITY);
	}

	public static DecoderFrameSource open(Supplier<VideoFrameDecoder> decoderSupplier, int ringCapacity) {
		return open(decoderSupplier, null, ringCapacity);
	}

	/**
	 * Opens the decoder inside the decode worker; {@code durationOverrideSeconds} keeps the media
	 * duration available to callers before the worker has opened the decoder.
	 */
	public static DecoderFrameSource open(
			Supplier<VideoFrameDecoder> decoderSupplier, @Nullable Double durationOverrideSeconds,
			int ringCapacity) {
		if (decoderSupplier == null) {
			throw new IllegalArgumentException("decoderSupplier must not be null");
		}
		return new DecoderFrameSource(decoderSupplier, durationOverrideSeconds, ringCapacity, null);
	}

	private void decodeLoop() {
		try {
			while (true) {
				double seek;
				synchronized (lock) {
					// Idle at EOF or after a failure until a consumer asks for a new position; the
					// old loop kept calling next() at EOF and spun a core.
					while (!closed && seekRequest == null
							&& (ring.size() >= ringCapacity || eof || failure != null)) {
						lock.wait();
					}
					if (closed) {
						return;
					}
					if (seekRequest == null) {
						seek = Double.NaN;
					} else {
						seek = seekRequest;
						seekRequest = null;
						seekInProgress = true;
						failure = null;
						releaseRingLocked();
						eof = false;
						lastDecodedPtsUs = -1;
						releaseFrameLocked(lastReturned);
						lastReturned = null;
						lastReturnedPtsUs = -1;
					}
				}
				Step step;
				try {
					step = decodeStep(seek);
				} catch (RuntimeException error) {
					recoverFromFailure(error);
					continue;
				}
				synchronized (lock) {
					if (closed) {
						step.frames().forEach(VideoFrame::release);
						return;
					}
					if (!Double.isNaN(seek)) {
						seekCount++;
					}
					for (VideoFrame decoded : step.frames()) {
						framesDecoded++;
						lastDecodedPtsUs = decoded.ptsUs();
						ring.addLast(decoded);
					}
					if (step.eof()) {
						eof = true;
						if (lastDecodedPtsUs >= 0) {
							endPtsUs = lastDecodedPtsUs;
						}
					}
					seekInProgress = false;
					VideoFrame frame = step.frames().isEmpty() ? null : step.frames().get(0);
					if (!profiledFirstFrame && frame != null) {
						profiledFirstFrame = true;
						long elapsedMs = firstSeekStartedNanos < 0
								? 0
								: (System.nanoTime() - firstSeekStartedNanos) / 1_000_000;
						System.getLogger(DecoderFrameSource.class.getName()).log(
								System.Logger.Level.INFO,
								"first decoded preview frame after " + elapsedMs + " ms (seeks="
										+ seekCount + ", frames=" + framesDecoded + ", size="
										+ frame.width() + "x" + frame.height() + ")");
					}
					lock.notifyAll();
				}
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		} finally {
			closeDecoder();
		}
	}

	/**
	 * Seeks (when {@code seek} is a number) and decodes the next frame. A seek that lands at or
	 * past the last frame decodes nothing, so it steps back until a frame comes out: the source then
	 * holds the stream's final frame instead of reporting an empty end.
	 */
	private Step decodeStep(double seek) {
		if (decoder == null) {
			// Open on this thread: the FFmpeg decoder's FFM arena rejects access from any other.
			decoder = decoderSupplier.get();
		}
		if (Double.isNaN(seek)) {
			VideoFrame frame = decoder.next();
			return frame == null ? Step.END : new Step(List.of(frame), false);
		}
		if (firstSeekStartedNanos < 0) {
			firstSeekStartedNanos = System.nanoTime();
		}
		decoder.seek(seek);
		VideoFrame frame = decoder.next();
		if (frame != null) {
			return new Step(List.of(frame), false);
		}
		double backoff = END_SEEK_BACKOFF_SECONDS;
		double position = seek;
		while (frame == null && position > 0) {
			position = Math.max(0, seek - backoff);
			backoff *= 2;
			decoder.seek(position);
			frame = decoder.next();
		}
		if (frame == null) {
			return Step.END;
		}
		// The step back may land well before the end; decode through to EOF once, keeping only
		// the newest frames, so the end becomes known and the consumer stops re-seeking past it.
		Deque<VideoFrame> tail = new ArrayDeque<>();
		while (frame != null) {
			tail.addLast(frame);
			if (tail.size() > ringCapacity) {
				tail.pollFirst().release();
			}
			frame = decoder.next();
		}
		return new Step(List.copyOf(tail), true);
	}

	/** Frames one worker step produced, oldest first, and whether the stream ended after them. */
	private record Step(List<VideoFrame> frames, boolean eof) {
		static final Step END = new Step(List.of(), true);
	}

	/**
	 * A decode error no longer kills the source for good (the preview stayed empty until the
	 * editor was reopened): the decoder is closed and reopened by the next seek, which any
	 * consumer request triggers.
	 */
	private void recoverFromFailure(RuntimeException error) {
		System.getLogger(DecoderFrameSource.class.getName()).log(System.Logger.Level.WARNING,
				"video decode failed, reopening the decoder on the next request: " + error);
		closeDecoder();
		synchronized (lock) {
			failure = error;
			seekInProgress = false;
			releaseRingLocked();
			eof = false;
			lock.notifyAll();
		}
	}

	/** Closes the decoder on the worker thread (its FFM arenas belong to this thread). */
	private void closeDecoder() {
		VideoFrameDecoder current;
		synchronized (lock) {
			current = decoder;
			decoder = null;
		}
		if (current != null) {
			try {
				current.close();
			} catch (RuntimeException error) {
				System.getLogger(DecoderFrameSource.class.getName()).log(
						System.Logger.Level.WARNING, "could not close the video decoder: " + error);
			}
		}
	}

	@Override
	public @Nullable RgbaFrame frameAt(double sourceSeconds) {
		return frameAt(sourceSeconds, MAX_WAIT_MS);
	}

	/** Frame with an explicit wait budget (first frame/seek paths can fail fast). */
	@Override
	public @Nullable RgbaFrame frameAt(double sourceSeconds, long timeoutMs) {
		long requestedUs = Math.max(0, Math.round((Double.isFinite(sourceSeconds) ? sourceSeconds : 0) * 1_000_000));
		double knownDuration = durationSeconds();
		if (knownDuration > 0) {
			// The probed duration bounds the request before EOF has been seen once.
			requestedUs = Math.min(requestedUs, Math.round(knownDuration * 1_000_000));
		}
		long budgetMs = timeoutMs > 0 ? timeoutMs : MAX_WAIT_MS;
		long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000;
		synchronized (lock) {
			boolean recoveryRequested = false;
			while (true) {
				if (closed) {
					return null;
				}
				// Past the stream's last frame (a timeline longer than this file, a webcam that
				// stops before the screen recording): hold the final frame instead of going blank.
				long targetUs = endPtsUs >= 0 ? Math.min(requestedUs, endPtsUs) : requestedUs;
				double targetSeconds = targetUs / 1_000_000.0;
				if (failure != null) {
					if (recoveryRequested) {
						RuntimeException cause = failure;
						throw new IllegalStateException("video decode failed", cause);
					}
					// The worker reopens the decoder on this seek; one failed request becomes a gap
					// frame at most, never a permanently empty preview.
					recoveryRequested = true;
					requestSeekLocked(targetSeconds);
					waitLocked(deadlineNanos);
					continue;
				}
				if (ring.isEmpty()) {
					if (eof) {
						if (lastReturned != null && targetUs >= lastReturnedPtsUs) {
							// At the end: keep showing the last frame.
							return new RgbaFrame(lastReturned.width(), lastReturned.height(),
									lastReturned.rgbaTopFirst());
						}
						if (lastReturned == null && lastDecodedPtsUs < 0 && !seekInProgress
								&& seekRequest == null && endPtsUs < 0) {
							// A seek decoded nothing even at the start: the stream has no frames.
							return null;
						}
					}
					// Moving back from the end (or nothing decoded yet): the seek resets EOF. The
					// old code returned null here forever once EOF was reached.
					requestSeekLocked(targetSeconds);
					waitLocked(deadlineNanos);
					continue;
				}
				VideoFrame first = ring.peekFirst();
				boolean backward = lastReturnedPtsUs >= 0 && targetUs < lastReturnedPtsUs;
				boolean tooFarAhead = first.ptsUs() - targetUs > SEEK_TOLERANCE_US;
				// Don't decode hundreds of frames forward on a jump: seek the worker instead.
				boolean farBehind = targetUs - first.ptsUs() > MAX_SEQUENTIAL_GAP_US;
				if (backward || tooFarAhead || farBehind) {
					releaseRingLocked();
					requestSeekLocked(targetSeconds);
					waitLocked(deadlineNanos);
					continue;
				}
				VideoFrame chosen = null;
				while (!ring.isEmpty() && ring.peekFirst().ptsUs() <= targetUs) {
					VideoFrame next = ring.pollFirst();
					if (chosen != null) {
						chosen.release();
					}
					chosen = next;
				}
				if (chosen != null) {
					if (lastReturned != null) {
						lastReturned.release();
					}
					lastReturned = chosen;
					lastReturnedPtsUs = chosen.ptsUs();
					lock.notifyAll();
					return new RgbaFrame(chosen.width(), chosen.height(), chosen.rgbaTopFirst());
				}
				if (lastReturned != null) {
					// The requested time sits between two frames: hold the frame at or before it.
					lock.notifyAll();
					return new RgbaFrame(
							lastReturned.width(), lastReturned.height(), lastReturned.rgbaTopFirst());
				}
				if (eof) {
					chosen = ring.pollFirst();
					if (chosen == null) {
						return null;
					}
					if (lastReturned != null) {
						lastReturned.release();
					}
					lastReturned = chosen;
					lastReturnedPtsUs = chosen.ptsUs();
					lock.notifyAll();
					return new RgbaFrame(chosen.width(), chosen.height(), chosen.rgbaTopFirst());
				}
				requestSeekLocked(targetSeconds);
				waitLocked(deadlineNanos);
			}
		}
	}

	/** Releases every buffered ring frame (the decoder's pooled buffers become reusable). */
	private void releaseRingLocked() {
		VideoFrame frame;
		while ((frame = ring.pollFirst()) != null) {
			frame.release();
		}
	}

	private static void releaseFrameLocked(@Nullable VideoFrame frame) {
		if (frame != null) {
			frame.release();
		}
	}

	private void requestSeekLocked(double seconds) {
		// Do not queue another seek while the worker is already seeking: the old code let the
		// consumer replace the request mid-seek, which discarded every decoded frame and made
		// the ring livelock (thousands of seeks/frames, 8 MB direct buffers churning).
		if (seekRequest == null && !seekInProgress) {
			double safeSeconds = Double.isFinite(seconds) ? Math.max(0, seconds) : 0;
			// Back off slightly so the ring starts at or before the requested frame, never only after it.
			seekRequest = Math.max(0, (Math.round(safeSeconds * 1_000_000) - SEEK_BACKOFF_US) / 1_000_000.0);
		}
		lock.notifyAll();
	}

	/**
	 * Bounded wait: the ring can never hang a caller (or the build) forever. The consumer waits in
	 * slices and fails with a clear error after the budget instead of blocking on the monitor.
	 */
	private void waitLocked(long deadlineNanos) {
		long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000;
		if (remainingMs <= 0) {
			throw new IllegalStateException(
					"timed out waiting for the decode-ahead ring (" + MAX_WAIT_MS + " ms): ring="
							+ ring.size() + " eof=" + eof + " seek=" + seekRequest + " decoder="
							+ (decoder != null) + " worker=" + worker.getState() + " lastPtsUs="
							+ lastReturnedPtsUs);
		}
		try {
			lock.wait(Math.min(WAIT_SLICE_MS, remainingMs));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for a decoded frame", interrupted);
		}
	}

	@Override
	public double durationSeconds() {
		if (durationOverrideSeconds != null && Double.isFinite(durationOverrideSeconds)
				&& durationOverrideSeconds > 0) {
			return durationOverrideSeconds;
		}
		VideoFrameDecoder current = decoder;
		return current == null ? 0 : current.durationSeconds();
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (closed) {
				return;
			}
			closed = true;
			releaseRingLocked();
			releaseFrameLocked(lastReturned);
			lastReturned = null;
			lock.notifyAll();
		}
		try {
			// The worker closes the decoder in its finally block (FFM arena ownership).
			worker.join(2_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}