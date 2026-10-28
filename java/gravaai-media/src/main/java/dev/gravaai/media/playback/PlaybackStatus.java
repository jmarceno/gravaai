package dev.gravaai.media.playback;

/** One status snapshot pushed to the preview controls. */
public record PlaybackStatus(
		PlaybackState state,
		double timelineSeconds,
		double durationSeconds,
		double rate) {

	public PlaybackStatus {
		if (state == null) {
			throw new IllegalArgumentException("state must not be null");
		}
		if (!Double.isFinite(timelineSeconds) || !Double.isFinite(durationSeconds)
				|| !Double.isFinite(rate)) {
			throw new IllegalArgumentException("status values must be finite");
		}
	}
}
