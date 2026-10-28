package dev.gravaai.media.playback;

/**
 * Receives decoded frames from the playback engine on its render thread. The compositor
 * (media-native's {@code Compositor}, plan §5.8) is the production consumer; the preview panel
 * hands the frames to the GL thread.
 */
@FunctionalInterface
public interface FrameConsumer {

	void accept(PlaybackFrame frame);
}
