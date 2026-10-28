package dev.gravaai.media.ffmpeg;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * One decoded video frame from {@link VideoDecoder}: presentation timestamp, geometry and the
 * frame planes.
 *
 * <p><strong>Lifetime:</strong> the plane buffers are zero-copy views over the decoder's
 * {@code AVFrame}. They are valid only until the next {@link VideoDecoder#next()},
 * {@link VideoDecoder#seekPrecise(long)} or {@link VideoDecoder#close()} call — the compositor
 * copies/converts the frame within that window (that is how FFM decoding with a reused
 * {@code AVFrame} works).</p>
 *
 * <p>The native {@code AVFrame} segment is package-private: only the wrapper classes in this
 * package see FFmpeg memory (plan §4.4).</p>
 */
public final class DecodedFrame {

	/**
	 * One plane of a frame.
	 *
	 * @param index the plane index (0 = luma / packed, 1.. = chroma or packed planes)
	 * @param width the plane width in pixels
	 * @param height the plane height in pixels
	 * @param stride the row stride in bytes (may exceed {@code width * bytesPerPixel})
	 * @param data a view over the plane; see the class-level lifetime note
	 */
	public record Plane(int index, int width, int height, int stride, ByteBuffer data) {
	}

	private final long ptsUs;
	private final long durationUs;
	private final int width;
	private final int height;
	private final int pixelFormat;
	private final List<Plane> planes;
	private final MemorySegment nativeFrame;
	private final int[] lineSizes;

	DecodedFrame(long ptsUs, long durationUs, int width, int height, int pixelFormat,
			List<Plane> planes, MemorySegment nativeFrame, int[] lineSizes) {
		this.ptsUs = ptsUs;
		this.durationUs = durationUs;
		this.width = width;
		this.height = height;
		this.pixelFormat = pixelFormat;
		this.planes = List.copyOf(planes);
		this.nativeFrame = nativeFrame;
		this.lineSizes = lineSizes;
	}

	/** Presentation timestamp in microseconds ({@code av_rescale_q} of the best-effort ts). */
	public long ptsUs() {
		return ptsUs;
	}

	/** Frame duration in microseconds, or {@code 0} when the container does not report one. */
	public long durationUs() {
		return durationUs;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The FFmpeg {@code AVPixelFormat} value, for example {@code AV_PIX_FMT_YUV420P}. */
	public int pixelFormat() {
		return pixelFormat;
	}

	public List<Plane> planes() {
		return planes;
	}

	/** The raw {@code AVFrame *} — package-private on purpose (plan §4.4). */
	MemorySegment nativeFrame() {
		return nativeFrame;
	}

	/** All {@code AVFrame.linesize} entries, indexed by plane. */
	int[] lineSizes() {
		return lineSizes;
	}
}
