package dev.gravaai.media.export;

import java.nio.ByteBuffer;

/**
 * Seam between the export encoder and the frame producer (plan §5.10). The encoder drives the
 * timeline in increasing content time and writes the returned top-first pixels straight into the
 * bundled ffmpeg CLI as {@code -f rawvideo}: I420 converted on the GPU when the provider
 * {@linkplain #supportsYuv420p supports it} (MP4), otherwise RGBA that ffmpeg converts.
 *
 * <p>A production implementation lives in the editor/media layer and renders through the Skia
 * compositor on the dedicated {@code gl-export} thread (see media-native's integration notes);
 * tests use {@link SyntheticFrameProvider}. Implementations must be deterministic for a given
 * {@code frameIndex}: the GIF exporter renders the clip twice (palettegen + paletteuse passes).
 */
public interface ExportFrameProvider extends AutoCloseable {

	/** Output width in pixels (must match the export request). */
	int width();

	/** Output height in pixels (must match the export request). */
	int height();

	/** Output frame rate (must match the export request). */
	int frameRate();

	/**
	 * Renders one output frame into {@code rgba}.
	 *
	 * @param frameIndex zero-based output frame index
	 * @param timeSeconds the output timeline time of the frame ({@code frameIndex / frameRate})
	 * @param rgba a top-first RGBA buffer of exactly {@code width * height * 4} bytes
	 */
	void renderFrame(long frameIndex, double timeSeconds, ByteBuffer rgba) throws Exception;

	/**
	 * Whether {@link #renderFrameYuv420p} is available. The MP4 encoder then pipes I420 frames
	 * converted on the GPU (1.5 bytes per pixel, no ffmpeg swscale pass) instead of RGBA.
	 */
	default boolean supportsYuv420p() {
		return false;
	}

	/**
	 * Renders one output frame as planar YUV 4:2:0 (I420: Y, then U, then V), BT.709 limited
	 * range, exactly ffmpeg's {@code -pix_fmt yuv420p} layout.
	 *
	 * @param yuv a buffer of exactly {@code width * height * 3 / 2} bytes
	 */
	default void renderFrameYuv420p(long frameIndex, double timeSeconds, ByteBuffer yuv)
			throws Exception {
		throw new UnsupportedOperationException("this provider renders RGBA only");
	}

	/**
	 * Resets any stateful render timeline so the next {@link #renderFrame} call starts a fresh pass
	 * at frame 0. The GIF exporter renders the clip twice (palette pass + output pass); a
	 * compositor-backed provider must recreate/clear its zoom springs and frame history here.
	 */
	default void reset() throws Exception {
		// Stateless providers need no reset.
	}

	@Override
	default void close() throws Exception {
		// Nothing to release by default.
	}
}
