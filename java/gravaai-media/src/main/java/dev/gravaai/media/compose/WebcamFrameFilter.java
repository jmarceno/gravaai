package dev.gravaai.media.compose;

/**
 * A per-frame edit of the decoded webcam picture, applied once to each frame as it is decoded and
 * before anything samples it (the person mask, the compositor). Eye-contact correction is the one
 * implementation; the port keeps the inference runtime out of the compose and playback code.
 *
 * <p>Both the preview's decode-ahead worker and the export's frame factory call it, so the
 * preview shows exactly what the export writes. Single caller per instance.</p>
 */
public interface WebcamFrameFilter extends AutoCloseable {

	/** Leaves every frame untouched. */
	WebcamFrameFilter NONE = (frame, webcamSeconds, timelineSeconds) -> {};

	/**
	 * Edits {@code frame}'s pixels in place.
	 *
	 * @param webcamSeconds   the frame's presentation time in the webcam recording
	 * @param timelineSeconds the editor timeline time the frame is shown at (per-segment settings)
	 */
	void apply(RgbaFrame frame, double webcamSeconds, double timelineSeconds);

	@Override
	default void close() {}
}
