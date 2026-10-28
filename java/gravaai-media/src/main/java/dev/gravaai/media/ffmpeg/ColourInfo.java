package dev.gravaai.media.ffmpeg;

import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * The colour description of a decoded stream, read from {@code AVCodecParameters} /
 * {@code AVFrame} and exposed as both the raw FFmpeg enum values and their names.
 *
 * <p>The names follow the {@code VideoColorSpace.space/primaries/transfer/range} spellings
 * the renderer uses (for example {@code bt709}, {@code smpte170m}, {@code limited},
 * {@code full}), so a caller can write them into colour metadata without knowing FFmpeg's
 * enums.</p>
 */
public record ColourInfo(int primaries, int transfer, int space, int range) {

	/** A stream with no colour metadata at all. */
	public static final ColourInfo UNSPECIFIED =
			new ColourInfo(ffmpeg_h.AVCOL_PRI_UNSPECIFIED(), ffmpeg_h.AVCOL_TRC_UNSPECIFIED(),
					ffmpeg_h.AVCOL_SPC_UNSPECIFIED(), ffmpeg_h.AVCOL_RANGE_UNSPECIFIED());

	/** {@code true} for a JPEG/full-range stream, {@code false} for limited (MPEG) range. */
	public boolean fullRange() {
		return range == ffmpeg_h.AVCOL_RANGE_JPEG();
	}

	/** {@code true} for a limited-range (MPEG, 16-235) stream. */
	public boolean limitedRange() {
		return range == ffmpeg_h.AVCOL_RANGE_MPEG();
	}

	/** The matrix coefficients name, for example {@code bt709}. */
	public String spaceName() {
		int value = space;
		if (value == ffmpeg_h.AVCOL_SPC_BT709()) {
			return "bt709";
		}
		if (value == ffmpeg_h.AVCOL_SPC_BT470BG()) {
			return "bt470bg";
		}
		if (value == ffmpeg_h.AVCOL_SPC_SMPTE170M()) {
			return "smpte170m";
		}
		if (value == ffmpeg_h.AVCOL_SPC_BT2020_NCL()) {
			return "bt2020nc";
		}
		if (value == ffmpeg_h.AVCOL_SPC_BT2020_CL()) {
			return "bt2020c";
		}
		if (value == ffmpeg_h.AVCOL_SPC_RGB()) {
			return "rgb";
		}
		return "unknown";
	}

	/** The colour primaries name, for example {@code bt709}. */
	public String primariesName() {
		int value = primaries;
		if (value == ffmpeg_h.AVCOL_PRI_BT709()) {
			return "bt709";
		}
		if (value == ffmpeg_h.AVCOL_PRI_BT470M()) {
			return "bt470m";
		}
		if (value == ffmpeg_h.AVCOL_PRI_BT470BG()) {
			return "bt470bg";
		}
		if (value == ffmpeg_h.AVCOL_PRI_SMPTE170M()) {
			return "smpte170m";
		}
		if (value == ffmpeg_h.AVCOL_PRI_BT2020()) {
			return "bt2020";
		}
		return "unknown";
	}

	/** The transfer characteristic name, for example {@code bt709}. */
	public String transferName() {
		int value = transfer;
		if (value == ffmpeg_h.AVCOL_TRC_BT709()) {
			return "bt709";
		}
		if (value == ffmpeg_h.AVCOL_TRC_GAMMA22()) {
			return "gamma22";
		}
		if (value == ffmpeg_h.AVCOL_TRC_GAMMA28()) {
			return "gamma28";
		}
		if (value == ffmpeg_h.AVCOL_TRC_SMPTE170M()) {
			return "smpte170m";
		}
		if (value == ffmpeg_h.AVCOL_TRC_SMPTE240M()) {
			return "smpte240m";
		}
		if (value == ffmpeg_h.AVCOL_TRC_LINEAR()) {
			return "linear";
		}
		if (value == ffmpeg_h.AVCOL_TRC_SMPTE2084()) {
			return "smpte2084";
		}
		if (value == ffmpeg_h.AVCOL_TRC_ARIB_STD_B67()) {
			return "arib-std-b67";
		}
		return "unknown";
	}

	/** {@code limited}, {@code full} or {@code unknown}. */
	public String rangeName() {
		if (fullRange()) {
			return "full";
		}
		if (limitedRange()) {
			return "limited";
		}
		return "unknown";
	}
}
