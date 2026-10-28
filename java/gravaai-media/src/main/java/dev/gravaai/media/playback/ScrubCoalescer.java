package dev.gravaai.media.playback;

/**
 * Latest-wins scrub coalescing (plan §5.9): the preview panel can submit scrub positions faster than
 * a precise seek completes; the engine takes only the newest pending position.
 */
public final class ScrubCoalescer {

	/** One pending scrub request. */
	public record Pending(long generation, double timelineSeconds) {
	}

	private boolean pending;
	private double pendingSeconds;
	private long generation;

	/** Records the newest scrub position; returns its generation (monotonic). */
	public synchronized long submit(double timelineSeconds) {
		this.pendingSeconds = Double.isFinite(timelineSeconds) ? timelineSeconds : 0;
		this.pending = true;
		this.generation++;
		return this.generation;
	}

	/** Returns the latest pending position and clears it, or null when there is nothing to do. */
	public synchronized Pending takeLatest() {
		if (!pending) {
			return null;
		}
		pending = false;
		return new Pending(generation, pendingSeconds);
	}

	public synchronized boolean hasPending() {
		return pending;
	}
}
