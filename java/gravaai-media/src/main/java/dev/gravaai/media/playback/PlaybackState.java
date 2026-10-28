package dev.gravaai.media.playback;

/** Preview playback state machine (plan §5.9). */
public enum PlaybackState {
	/** No media loaded. */
	IDLE,
	PLAYING,
	PAUSED,
	/** A precise seek/scrub is in flight; frames may still be delivered. */
	SEEKING,
	/** The playhead reached the timeline end. */
	ENDED
}
