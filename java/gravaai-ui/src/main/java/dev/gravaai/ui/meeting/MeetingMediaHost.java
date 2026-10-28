package dev.gravaai.ui.meeting;

import java.nio.file.Path;

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
}
