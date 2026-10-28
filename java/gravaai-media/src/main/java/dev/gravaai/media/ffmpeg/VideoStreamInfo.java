package dev.gravaai.media.ffmpeg;

/**
 * One video stream of a probed file.
 *
 * @param index the stream index inside the container
 * @param codec the codec short name, for example {@code h264}
 * @param width the coded width in pixels
 * @param height the coded height in pixels
 * @param fps the frame rate {@code av_guess_frame_rate} reports ({@code 0} when unknown)
 * @param durationSeconds the stream duration in seconds ({@code 0} when unknown)
 * @param rotationDegrees the rotation from the display matrix, normalised to [0, 360)
 * @param colour the stream colour metadata
 */
public record VideoStreamInfo(
		int index,
		String codec,
		int width,
		int height,
		double fps,
		double durationSeconds,
		int rotationDegrees,
		ColourInfo colour) {
}
