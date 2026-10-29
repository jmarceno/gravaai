package dev.gravaai.media.ffmpeg;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Converts a {@link DecodedFrame} to packed RGBA with swscale (plan §5.7).
 *
 * <p>The target is a reusable native buffer: {@link #convert(DecodedFrame)} returns the same
 * direct {@link ByteBuffer} every time, rewound and filled with top-first RGBA rows, so the
 * compositor can upload it to Skia without an allocation per frame. Preview uses
 * {@code SWS_BILINEAR}, export uses {@code SWS_BICUBIC}.</p>
 *
 * <p>Colour handling follows {@link VideoColorSpace}: the target is the full-range sRGB
 * canvas, the source matrix comes from the stream (BT.709 vs BT.601 vs BT.2020) and the source
 * range from the stream's colour range, defaulting to the limited-range BT.709 fallback when
 * the stream carries no metadata. Those values are applied through
 * {@code sws_setColorspaceDetails}.</p>
 */
public final class FrameConverter implements AutoCloseable {

	/** Which swscale scaler to use. */
	public enum ScaleMode {
		/** Bilinear, for the preview (fast). */
		PREVIEW(ffmpeg_h.SWS_BILINEAR()),
		/** Bicubic, for export (higher quality). */
		EXPORT(ffmpeg_h.SWS_BICUBIC());

		private final int swsFlags;

		ScaleMode(int swsFlags) {
			this.swsFlags = swsFlags;
		}

		int swsFlags() {
			return swsFlags;
		}
	}

	private static final int MAX_PLANES = 8;

	private final int targetWidth;
	private final int targetHeight;
	private final int targetStrideBytes;
	private final ScaleMode mode;
	private final @Nullable ColourInfo sourceColour;
	private final Arena arena;
	private final MemorySegment targetBuffer;
	private final MemorySegment targetDataArray;
	private final MemorySegment targetStrideArray;
	private final MemorySegment sourceDataArray;
	private final MemorySegment sourceStrideArray;
	private final ByteBuffer rgba;
	/** libswscale context; {@link MemorySegment#NULL} until the first frame is converted. */
	private MemorySegment swsContext = MemorySegment.NULL;
	private int sourceWidth;
	private int sourceHeight;
	private int sourcePixelFormat;
	private boolean closed;

	/**
	 * Creates a converter.
	 *
	 * @param targetWidth the output width in pixels
	 * @param targetHeight the output height in pixels
	 * @param mode the scaler to use
	 * @param sourceColour the source stream's colour metadata, or {@code null} for the
	 *        limited-range BT.709 fallback
	 */
	public FrameConverter(int targetWidth, int targetHeight, ScaleMode mode, @Nullable ColourInfo sourceColour) {
		if (targetWidth <= 0 || targetHeight <= 0) {
			throw new IllegalArgumentException(
					"invalid target size: " + targetWidth + "x" + targetHeight);
		}
		this.targetWidth = targetWidth;
		this.targetHeight = targetHeight;
		this.targetStrideBytes = targetWidth * 4;
		this.mode = mode;
		this.sourceColour = sourceColour;
		this.arena = Arena.ofConfined();
		// swscale's SIMD output paths may write past the last pixel of the last line; without
		// padding that corrupts the native heap (a crash on a later free, seen with 1080-wide
		// portrait recordings). One spare line plus a vector of slack, 64-byte aligned.
		long frameBytes = (long) targetStrideBytes * targetHeight;
		this.targetBuffer = arena.allocate(frameBytes + targetStrideBytes + 64, 64);
		this.targetDataArray = arena.allocate((long) MAX_PLANES * ValueLayout.ADDRESS.byteSize());
		this.targetStrideArray = arena.allocate((long) MAX_PLANES * ValueLayout.JAVA_INT.byteSize());
		this.sourceDataArray = arena.allocate((long) MAX_PLANES * ValueLayout.ADDRESS.byteSize());
		this.sourceStrideArray = arena.allocate((long) MAX_PLANES * ValueLayout.JAVA_INT.byteSize());
		this.targetDataArray.setAtIndex(ValueLayout.ADDRESS, 0, targetBuffer);
		this.targetStrideArray.setAtIndex(ValueLayout.JAVA_INT, 0, targetStrideBytes);
		this.rgba = targetBuffer.asSlice(0, frameBytes).asByteBuffer();
	}

	/** Creates a preview converter (bilinear) for the given target size. */
	public static FrameConverter preview(int targetWidth, int targetHeight) {
		return new FrameConverter(targetWidth, targetHeight, ScaleMode.PREVIEW, null);
	}

	/** Creates an export converter (bicubic) for the given target size. */
	public static FrameConverter export(int targetWidth, int targetHeight) {
		return new FrameConverter(targetWidth, targetHeight, ScaleMode.EXPORT, null);
	}

	public int width() {
		return targetWidth;
	}

	public int height() {
		return targetHeight;
	}

	/** Bytes per RGBA row ({@code width * 4}). */
	public int strideBytes() {
		return targetStrideBytes;
	}

	/**
	 * Converts the frame into the reusable RGBA buffer.
	 *
	 * @return the same direct buffer on every call, position 0, limit {@code stride * height};
	 *         its contents stay valid until the next call or {@link #close()}
	 * @throws IllegalArgumentException when the frame has no usable planes
	 */
	public ByteBuffer convert(DecodedFrame frame) {
		ensureOpen();
		if (frame.planes().isEmpty()) {
			throw new IllegalArgumentException("frame has no planes");
		}
		if (swsContext.address() == 0
				|| frame.width() != sourceWidth
				|| frame.height() != sourceHeight
				|| frame.pixelFormat() != sourcePixelFormat) {
			createContext(frame);
		}
		fillSourcePlanes(frame);
		int scaled = ffmpeg_h.sws_scale(swsContext, sourceDataArray, sourceStrideArray, 0,
				sourceHeight, targetDataArray, targetStrideArray);
		if (scaled <= 0) {
			throw new FfmpegException("sws_scale", scaled);
		}
		rgba.position(0);
		rgba.limit(targetStrideBytes * targetHeight);
		return rgba;
	}

	private void createContext(DecodedFrame frame) {
		if (swsContext.address() != 0) {
			ffmpeg_h.sws_freeContext(swsContext);
		}
		sourceWidth = frame.width();
		sourceHeight = frame.height();
		sourcePixelFormat = frame.pixelFormat();
		swsContext = ffmpeg_h.sws_getContext(
				sourceWidth, sourceHeight, sourcePixelFormat,
				targetWidth, targetHeight, ffmpeg_h.AV_PIX_FMT_RGBA(),
				mode.swsFlags(),
				MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
		if (swsContext.address() == 0) {
			throw new IllegalStateException("sws_getContext returned NULL for "
					+ sourceWidth + "x" + sourceHeight + " format " + sourcePixelFormat);
		}
		applyColourDetails();
	}

	private void applyColourDetails() {
		ColourInfo colour = sourceColour == null ? VideoColorSpace.defaultSourceColour() : sourceColour;
		MemorySegment sourceTable = ffmpeg_h.sws_getCoefficients(coefficients(colour));
		MemorySegment targetTable = ffmpeg_h.sws_getCoefficients(ffmpeg_h.SWS_CS_DEFAULT());
		int sourceRange = colour.fullRange() ? 1 : 0;
		// The renderer canvas is full-range sRGB (VideoColorSpace.EXPORT_CANVAS_COLOR_SPACE).
		int targetRange = VideoColorSpace.EXPORT_CANVAS_COLOR_SPACE.fullRange() ? 1 : 0;
		// brightness/contrast/saturation are 16.16 fixed point; 0 / 1<<16 / 1<<16 are the
		// swscale defaults (contrast and saturation 0 would scale the picture to black).
		int result = ffmpeg_h.sws_setColorspaceDetails(swsContext, sourceTable, sourceRange,
				targetTable, targetRange, 0, 1 << 16, 1 << 16);
		if (result < 0) {
			throw new FfmpegException("sws_setColorspaceDetails", result);
		}
	}

	private static int coefficients(ColourInfo colour) {
		return switch (colour.spaceName()) {
			case "bt709" -> ffmpeg_h.SWS_CS_ITU709();
			case "bt470bg", "smpte170m" -> ffmpeg_h.SWS_CS_ITU601();
			case "bt2020nc", "bt2020c" -> ffmpeg_h.SWS_CS_BT2020();
			case "rgb" -> ffmpeg_h.SWS_CS_DEFAULT();
			// No metadata: use the encoded-H.264 fallback (limited-range BT.709).
			default -> ffmpeg_h.SWS_CS_ITU709();
		};
	}

	private void fillSourcePlanes(DecodedFrame frame) {
		for (int i = 0; i < MAX_PLANES; i++) {
			sourceDataArray.setAtIndex(ValueLayout.ADDRESS, i, MemorySegment.NULL);
			sourceStrideArray.setAtIndex(ValueLayout.JAVA_INT, i, 0);
		}
		List<DecodedFrame.Plane> planes = frame.planes();
		for (int i = 0; i < planes.size() && i < MAX_PLANES; i++) {
			DecodedFrame.Plane plane = planes.get(i);
			MemorySegment data = AVFrameUtils.planeAddress(frame.nativeFrame(), plane.index());
			sourceDataArray.setAtIndex(ValueLayout.ADDRESS, i, data);
			sourceStrideArray.setAtIndex(ValueLayout.JAVA_INT, i, plane.stride());
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("converter is closed");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			if (swsContext.address() != 0) {
				ffmpeg_h.sws_freeContext(swsContext);
				swsContext = MemorySegment.NULL;
			}
		} finally {
			arena.close();
		}
	}
}
