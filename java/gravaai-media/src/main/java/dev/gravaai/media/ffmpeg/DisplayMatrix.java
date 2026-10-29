package dev.gravaai.media.ffmpeg;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import dev.gravaai.media.ffmpeg.gen.AVPacketSideData;
import dev.gravaai.media.ffmpeg.gen.AVCodecParameters;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Reads the rotation from a stream's display-matrix side data (plan §5.7: "rotation from
 * display matrix"). Shared by {@link MediaProbe} and {@link VideoDecoder}.
 */
final class DisplayMatrix {

	private DisplayMatrix() {}

	/**
	 * The rotation in degrees, normalised to [0, 360), or {@code 0} when the stream has no
	 * display matrix.
	 */
	static int rotationDegrees(Arena arena, MemorySegment codecParameters) {
		MemorySegment sideData = AVCodecParameters.coded_side_data(codecParameters);
		int count = AVCodecParameters.nb_coded_side_data(codecParameters);
		if (sideData.address() == 0 || count <= 0) {
			return 0;
		}
		MemorySegment displayMatrix = ffmpeg_h.av_packet_side_data_get(
				sideData, count, ffmpeg_h.AV_PKT_DATA_DISPLAYMATRIX());
		if (displayMatrix.address() == 0) {
			return 0;
		}
		MemorySegment entry = AVPacketSideData.reinterpret(displayMatrix, arena, null);
		MemorySegment data = AVPacketSideData.data(entry);
		if (data.address() == 0) {
			return 0;
		}
		// av_display_rotation_get returns degrees counterclockwise; normalise to [0, 360).
		double degrees = ffmpeg_h.av_display_rotation_get(data);
		int rounded = (int) Math.round(degrees) % 360;
		return rounded < 0 ? rounded + 360 : rounded;
	}
}
