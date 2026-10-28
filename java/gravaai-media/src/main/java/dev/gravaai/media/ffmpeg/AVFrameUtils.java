package dev.gravaai.media.ffmpeg;

import java.lang.foreign.MemorySegment;

import dev.gravaai.media.ffmpeg.gen.AVFrame;

/** Package-private helpers to read the raw data pointers of an {@code AVFrame}. */
final class AVFrameUtils {

	private AVFrameUtils() {}

	/** The data pointer for one plane of a frame. */
	static MemorySegment planeAddress(MemorySegment frame, int planeIndex) {
		return AVFrame.data(frame, planeIndex);
	}

	/** The data pointer array ({@code frame->extended_data}) of a frame. */
	static MemorySegment extendedData(MemorySegment frame) {
		return AVFrame.extended_data(frame);
	}

	/** The line size for one plane of a frame. */
	static int lineSize(MemorySegment frame, int planeIndex) {
		return AVFrame.linesize(frame, planeIndex);
	}
}
