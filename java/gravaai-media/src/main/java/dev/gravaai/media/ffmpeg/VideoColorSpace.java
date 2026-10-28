package dev.gravaai.media.ffmpeg;

/**
 * Colour-metadata decisions ported from {@code src/lib/exporter/videoColorSpace.ts}.
 *
 * <p>The renderer composites into an sRGB canvas, whose pixels are full-range RGB. Encoded
 * H.264 normally becomes limited-range YUV, so that is the fallback description when a stream
 * does not carry its own colour metadata. {@link FrameConverter} uses these two facts when it
 * fills in {@code sws_setColorspaceDetails}.</p>
 *
 * @param primaries the colour primaries, as a {@code VideoColorSpace.primaries} string
 * @param transfer the transfer function, as a {@code VideoColorSpace.transfer} string
 * @param matrix the matrix coefficients, or {@code "rgb"} for the RGB canvas
 * @param fullRange whether the values are full range (0–255) rather than limited (16–235)
 */
public record VideoColorSpace(String primaries, String transfer, String matrix, boolean fullRange) {

	/** The sRGB canvas the renderer composites into: BT.709 primaries, full-range RGB. */
	public static final VideoColorSpace EXPORT_CANVAS_COLOR_SPACE =
			new VideoColorSpace("bt709", "iec61966-2-1", "rgb", true);

	/**
	 * The colour space an H.264 encoder normally produces. Use this only when the encoder does
	 * not report its own output colour metadata.
	 */
	public static final VideoColorSpace ENCODED_H264_COLOR_SPACE_FALLBACK =
			new VideoColorSpace("bt709", "bt709", "bt709", false);

	/** The source colour space to assume when a stream has no colour metadata. */
	public static final VideoColorSpace DEFAULT_SOURCE_COLOR_SPACE =
			ENCODED_H264_COLOR_SPACE_FALLBACK;

	/**
	 * The {@link ColourInfo} a decoder should assume when a stream carries no colour metadata:
	 * limited-range BT.709, the @{code ENCODED_H264_COLOR_SPACE_FALLBACK} decision.
	 */
	public static ColourInfo defaultSourceColour() {
		return new ColourInfo(
				dev.gravaai.media.ffmpeg.gen.ffmpeg_h.AVCOL_PRI_BT709(),
				dev.gravaai.media.ffmpeg.gen.ffmpeg_h.AVCOL_TRC_BT709(),
				dev.gravaai.media.ffmpeg.gen.ffmpeg_h.AVCOL_SPC_BT709(),
				dev.gravaai.media.ffmpeg.gen.ffmpeg_h.AVCOL_RANGE_MPEG());
	}
}
