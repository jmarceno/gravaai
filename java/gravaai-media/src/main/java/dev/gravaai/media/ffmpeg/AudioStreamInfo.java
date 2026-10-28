package dev.gravaai.media.ffmpeg;

/**
 * One audio stream of a probed file.
 *
 * @param index the stream index inside the container
 * @param codec the codec short name, for example {@code aac}
 * @param sampleRate the sample rate in Hz
 * @param channels the channel count
 * @param channelLayout the layout name {@code av_channel_layout_describe} reports
 * @param durationSeconds the stream duration in seconds ({@code 0} when unknown)
 */
public record AudioStreamInfo(
		int index,
		String codec,
		int sampleRate,
		int channels,
		String channelLayout,
		double durationSeconds) {
}
