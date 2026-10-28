package dev.gravaai.media.playback;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * One frame of decoded source media, ready to become a compositor {@code FrameInput} (plan §5.8).
 *
 * <p>Buffer lifetime: the decoded {@link RgbaFrame}s come from the decoders and are only valid until
 * the next decode. A consumer that keeps them must copy; the preview panel hands them to the
 * compositor synchronously (the compositor draws before the next frame is decoded).
 *
 * @param frameIndex the output frame number, monotonically increasing
 * @param timelineSeconds the editor timeline position of this frame
 * @param sourceSeconds the source timestamp, or null over a gap
 * @param clipId the active clip id, or null over a gap
 * @param sourceFrame the decoded screen frame, or null over a gap
 * @param webcamFrame the decoded webcam frame, or null when hidden/absent
 * @param webcamVisible whether the webcam overlay should be drawn for this frame
 */
public record PlaybackFrame(
		long frameIndex,
		double timelineSeconds,
		Double sourceSeconds,
		String clipId,
		RgbaFrame sourceFrame,
		RgbaFrame webcamFrame,
		boolean webcamVisible) {

	public PlaybackFrame {
		if (frameIndex < 0) {
			throw new IllegalArgumentException("frameIndex must not be negative: " + frameIndex);
		}
		if (!Double.isFinite(timelineSeconds)) {
			throw new IllegalArgumentException("timelineSeconds must be finite");
		}
	}

	/** A black gap frame (no source footage on the timeline at this time). */
	public static PlaybackFrame gap(long frameIndex, double timelineSeconds) {
		return new PlaybackFrame(frameIndex, timelineSeconds, null, null, null, null, false);
	}
}
