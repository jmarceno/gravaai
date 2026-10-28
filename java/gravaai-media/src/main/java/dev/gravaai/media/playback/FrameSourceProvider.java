package dev.gravaai.media.playback;

/**
 * Opens the screen and webcam media files for one {@link PlaybackMedia}. The production
 * implementation owns the decoder threads/decode-ahead ring (P2.3 next increment); tests return
 * fakes.
 */
@FunctionalInterface
public interface FrameSourceProvider {

	/** Opens one media file; the engine closes the source it opened. */
	FrameSource open(String mediaPath);
}
