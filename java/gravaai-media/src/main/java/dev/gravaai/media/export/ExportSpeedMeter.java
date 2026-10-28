package dev.gravaai.media.export;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Render-speed bookkeeping for the frame-pipe encoders: decides when to publish a progress
 * snapshot and measures the current frames per second and the time left.
 *
 * <p>Progress is published at least every {@link #NOTIFY_INTERVAL_NANOS} (the old fixed
 * "every 1% of frames" cadence left the speed label blank for half a minute on long exports), on
 * every 1% boundary and on the last frame. The speed is measured over the last
 * {@link #WINDOW_NANOS} so it follows the current section of the video instead of the whole-run
 * average.</p>
 */
public final class ExportSpeedMeter {

	static final long NOTIFY_INTERVAL_NANOS = 250_000_000L;
	static final long WINDOW_NANOS = 3_000_000_000L;
	private static final int PROGRESS_STEPS = 100;

	private record Sample(long nanos, long frames) {}

	private final long totalFrames;
	private final long notifyEvery;
	private final LongSupplier nanoTime;
	private final long startNanos;
	private final ArrayDeque<Sample> window = new ArrayDeque<>();
	private long lastNotifyNanos;

	public ExportSpeedMeter(long totalFrames) {
		this(totalFrames, System::nanoTime);
	}

	ExportSpeedMeter(long totalFrames, LongSupplier nanoTime) {
		this.totalFrames = Math.max(1, totalFrames);
		this.notifyEvery = Math.max(1, this.totalFrames / PROGRESS_STEPS);
		this.nanoTime = nanoTime;
		this.startNanos = nanoTime.getAsLong();
		this.lastNotifyNanos = startNanos;
		window.add(new Sample(startNanos, 0));
	}

	/**
	 * Records that {@code frameIndex} was written and returns the snapshot to publish, or
	 * {@code null} when it is too soon for another one.
	 */
	public Reading frameWritten(long frameIndex) {
		long now = nanoTime.getAsLong();
		long done = frameIndex + 1;
		boolean due = done >= totalFrames || frameIndex % notifyEvery == 0
				|| now - lastNotifyNanos >= NOTIFY_INTERVAL_NANOS;
		if (!due) {
			return null;
		}
		lastNotifyNanos = now;
		window.add(new Sample(now, done));
		while (window.size() > 2 && now - window.peekFirst().nanos() > WINDOW_NANOS) {
			window.removeFirst();
		}
		Sample oldest = window.peekFirst();
		double fps;
		if (oldest.frames() < done && now > oldest.nanos()) {
			fps = (done - oldest.frames()) / ((now - oldest.nanos()) / 1e9);
		} else {
			fps = done / Math.max(0.001, (now - startNanos) / 1e9);
		}
		double remaining = fps > 0 ? Math.max(0, totalFrames - done) / fps : 0;
		return new Reading(done, fps, remaining);
	}

	/** One published measurement: frames written so far, current fps, seconds left. */
	public record Reading(long framesDone, double fps, double secondsRemaining) {}
}
