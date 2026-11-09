package dev.gravaai.media.playback;

/**
 * The audio side of the meeting player: a {@link PlaybackAudioTrack} that also reports when its
 * sources have drained, so the transport can move to the ENDED state without wall-clock guesses.
 */
public interface MeetingAudioTrack extends PlaybackAudioTrack {

	/** True once every source hit end-of-file (and stays true until the next seek). */
	boolean ended();
}
