package dev.gravaai.media.playback;

import java.util.function.LongSupplier;

/**
 * The fallback playback clock used when the media has no audio track (plan §5.9): monotonic time
 * anchored at the last seek/play, paused on demand.
 */
public final class MonotonicMediaClock implements PlaybackClock {

	private final LongSupplier nanoTime;
	private long anchorNanos;
	private double anchorSeconds;
	private boolean running;

	public MonotonicMediaClock(LongSupplier nanoTime) {
		if (nanoTime == null) {
			throw new IllegalArgumentException("nanoTime must not be null");
		}
		this.nanoTime = nanoTime;
		this.anchorNanos = nanoTime.getAsLong();
	}

	@Override
	public synchronized void resetTo(double seconds) {
		this.anchorSeconds = Double.isFinite(seconds) ? seconds : 0;
		this.anchorNanos = nanoTime.getAsLong();
	}

	@Override
	public synchronized void setRunning(boolean running) {
		if (this.running == running) {
			return;
		}
		// Freeze the current position before switching modes so play/pause never jumps.
		if (!running) {
			anchorSeconds = positionSeconds();
		}
		this.running = running;
		this.anchorNanos = nanoTime.getAsLong();
	}

	public synchronized boolean isRunning() {
		return running;
	}

	@Override
	public synchronized double positionSeconds() {
		if (!running) {
			return anchorSeconds;
		}
		double elapsedSeconds = (nanoTime.getAsLong() - anchorNanos) / 1_000_000_000.0;
		return anchorSeconds + Math.max(0, elapsedSeconds);
	}
}
