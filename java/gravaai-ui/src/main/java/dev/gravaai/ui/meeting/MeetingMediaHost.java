package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.Node;

/**
 * Playback seam for the meeting view (the media playback layer is P2.3; AppContext injects the
 * real player later). The React view shows {@code <video controls>} for video meetings and
 * {@code <audio controls>} for audio-only ones and seeks when a transcript timestamp is clicked.
 */
public interface MeetingMediaHost {

	/** Builds the player node for one meeting; {@code hasVideo} picks video vs audio controls. */
	Node createPlayer(Path mediaPath, boolean hasVideo);

	/** Plays the recording from the given position in seconds. */
	void seek(double seconds);

	/** Pauses when playing; the position is kept (the close-to-tray rule, like the editor). */
	void pausePlayback();

	/** Whether the current meeting is playing right now. */
	boolean isPlaying();

	/**
	 * Subscribes to playhead updates in seconds; invoked on the FX thread. Close the handle to
	 * unsubscribe (the transcript highlight rides on this).
	 */
	AutoCloseable onTime(Consumer<Double> listener);
}
