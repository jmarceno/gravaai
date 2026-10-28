package dev.gravaai.ui.editor.timeline;

/**
 * Seek seam for the editor playback engine (P2.3). The app wires {@code PlaybackEngine.seek} so the
 * timeline's {@link TimelineCommands#seek(double)} can move the playhead in seconds.
 */
@FunctionalInterface
public interface PlaybackSeek {

	void seek(double seconds);
}
