package dev.gravaai.media.segmentation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;

/**
 * The MediaPipe selfie segmenter (ONNX) behind the webcam background blur: maps one decoded
 * webcam frame to a person mask at the frame's dimensions.
 *
 * <p>Pipeline per frame: stretch the RGBA frame to the model's square input ({@code [0,1]}
 * float, no letterboxing — MediaPipe resizes without preserving aspect and a letterbox would
 * pollute the borders) → inference → squeeze the single-channel confidence map → temporal mean
 * over the last three masks (hair-region flicker) → dilate + Gaussian + steep sigmoid remap
 * (halo fix: the soft 256px edge is collapsed to about a pixel before upsampling) → bilinear
 * upscale into a fresh RGBA buffer with the mask value in every channel.</p>
 *
 * <p>Single-caller: an ORT session must not run concurrently; each consumer (preview decode,
 * export, filmstrips) owns its own instance. The input/output names, layouts and sizes are read
 * from the model at construction, so a different conversion of the same model does not need code
 * changes. Mask buffers handed out are immutable (a fresh allocation per frame), matching the
 * preview queue's stable-copy contract.</p>
 */
public final class SelfieSegmenter implements WebcamSegmenter, AutoCloseable {

	private static final System.Logger LOG = System.getLogger(SelfieSegmenter.class.getName());

	/** Temporal smoothing window (masks); three frames remove shimmer without visible lag. */
	private static final int SMOOTHING_FRAMES = 3;
	/** Dilation radius in mask pixels before the edge remap (keeps the person edge inside). */
	private static final int DILATION_RADIUS = 2;
	/** Gaussian sigma of the mask blur before the remap. */
	private static final double MASK_SIGMA = 2.0;
	/** Steep sigmoid slope/midpoint of the remap (1/(1+e^(-12·(m-0.5)))). */
	private static final double REMAP_SLOPE = 12.0;
	private static final double REMAP_MIDPOINT = 0.5;

	private final OnnxRuntimeApi api;
	private final MemorySegment session;
	private final MemorySegment memoryInfo;
	private final String inputName;
	private final String outputName;
	private final int inputWidth;
	private final int inputHeight;
	private final int inputChannels;
	private final boolean inputNHWC;
	private final int maskWidth;
	private final int maskHeight;
	private final boolean outputNHWC;
	private final long outputElementCount;
	// Scratch (single-caller): the [0,1] input tensor, the smoothing ring and the edge-shaped mask.
	private final float[] inputTensor;
	/** Native buffer the input tensor is copied into (native byte order), reused every frame. */
	private final MemorySegment inputSegment;
	/** Owns {@link #inputSegment} for the segmenter's lifetime. */
	private final Arena arena = Arena.ofShared();
	private final float[][] maskRing = new float[SMOOTHING_FRAMES][];
	private int maskRingFilled;
	private int maskRingNext;
	private final float[] scratchA;
	private final float[] scratchB;
	private final float[] smoothed;
	private boolean closed;

	public SelfieSegmenter(Path modelFile) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("model file missing: " + modelFile);
		}
		this.api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		this.session = api.createSession(env, modelFile);
		this.memoryInfo = api.createCpuMemoryInfo();
		MemorySegment allocator = api.defaultAllocator();
		if (api.sessionInputCount(session) != 1 || api.sessionOutputCount(session) != 1) {
			throw new IllegalArgumentException(
					"selfie-segmenter.onnx must have exactly one input and one output: "
							+ modelFile);
		}
		this.inputName = api.sessionInputName(session, allocator, 0);
		this.outputName = api.sessionOutputName(session, allocator, 0);
		long[] inputShape = api.sessionInputShape(session, 0);
		this.inputNHWC = isChannelsLast(inputShape, 3);
		if (inputNHWC) {
			this.inputHeight = (int) inputShape[1];
			this.inputWidth = (int) inputShape[2];
			this.inputChannels = (int) inputShape[3];
		} else {
			this.inputChannels = (int) inputShape[1];
			this.inputHeight = (int) inputShape[2];
			this.inputWidth = (int) inputShape[3];
		}
		this.inputTensor = new float[inputWidth * inputHeight * inputChannels];
		this.inputSegment = arena.allocate((long) inputTensor.length * Float.BYTES,
				Float.BYTES);
		long[] outputShape = api.sessionOutputShape(session, 0);
		this.outputNHWC = isChannelsLast(outputShape, 1);
		if (outputNHWC) {
			this.maskHeight = (int) outputShape[1];
			this.maskWidth = (int) outputShape[2];
		} else {
			this.maskHeight = (int) outputShape[2];
			this.maskWidth = (int) outputShape[3];
		}
		this.outputElementCount = product(outputShape);
		for (int i = 0; i < SMOOTHING_FRAMES; i++) {
			maskRing[i] = new float[maskWidth * maskHeight];
		}
		this.scratchA = new float[maskWidth * maskHeight];
		this.scratchB = new float[maskWidth * maskHeight];
		this.smoothed = new float[maskWidth * maskHeight];
		LOG.log(System.Logger.Level.DEBUG, () -> "selfie segmenter ready: " + modelFile
				+ " input " + inputName + " " + inputWidth + "x" + inputHeight + "x" + inputChannels
				+ " (" + (inputNHWC ? "NHWC" : "NCHW") + "), output " + outputName + " "
				+ maskWidth + "x" + maskHeight);
	}

	/** Opens the bundled selfie-segmenter model ({@link OnnxLibs#resolveModelFile()}). */
	public static SelfieSegmenter openBundled() {
		return new SelfieSegmenter(OnnxLibs.resolveModelFile());
	}

	@Override
	public RgbaFrame maskFor(RgbaFrame frame) {
		if (closed) {
			throw new IllegalStateException("selfie segmenter is closed");
		}
		preprocess(frame);
		MemorySegment input = inputTensorSegment();
		MemorySegment output = api.runSingle(session, inputName, input,
				(long) inputTensor.length * Float.BYTES,
				new long[] {inputShapeDim(0), inputShapeDim(1), inputShapeDim(2), inputShapeDim(3)},
				memoryInfo, outputName);
		try {
			float[] raw = api.getTensorFloats(output, outputElementCount);
			extractMask(raw);
			MaskPostProcess.average(maskRing, maskRingFilled, smoothed);
			// The edge shaping runs on the smoothed mask; the ring keeps the raw masks so the
			// smoothing window stays honest across the dilation/remap.
			System.arraycopy(smoothed, 0, scratchA, 0, smoothed.length);
			MaskPostProcess.dilate(scratchA, scratchB, maskWidth, maskHeight, DILATION_RADIUS);
			MaskPostProcess.gaussianBlur(scratchA, scratchB, maskWidth, maskHeight, MASK_SIGMA);
			MaskPostProcess.sigmoidRemap(scratchA, REMAP_SLOPE, REMAP_MIDPOINT);
			return upscale(frame);
		} finally {
			api.releaseValue(output);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(session);
		api.close();
		arena.close();
	}

	private void preprocess(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int frameWidth = frame.width();
		int frameHeight = frame.height();
		float scaleX = (float) frameWidth / inputWidth;
		float scaleY = (float) frameHeight / inputHeight;
		// Stretch sampling (no letterbox): nearest-neighbour on the source, bilinear quality is
		// wasted at 256px and the mask is upscaled bilinearly afterwards anyway.
		for (int y = 0; y < inputHeight; y++) {
			int sourceY = Math.min(frameHeight - 1, (int) ((y + 0.5f) * scaleY));
			int row = sourceY * frameWidth;
			int outRow = y * inputWidth;
			for (int x = 0; x < inputWidth; x++) {
				int sourceX = Math.min(frameWidth - 1, (int) ((x + 0.5f) * scaleX));
				int offset = (row + sourceX) * 4;
				float r = Byte.toUnsignedInt(pixels.get(offset)) / 255f;
				float g = Byte.toUnsignedInt(pixels.get(offset + 1)) / 255f;
				float b = Byte.toUnsignedInt(pixels.get(offset + 2)) / 255f;
				if (inputNHWC) {
					int base = (outRow + x) * inputChannels;
					inputTensor[base] = r;
					inputTensor[base + 1] = g;
					inputTensor[base + 2] = b;
				} else {
					int plane = inputWidth * inputHeight;
					int index = outRow + x;
					inputTensor[index] = r;
					inputTensor[plane + index] = g;
					inputTensor[2 * plane + index] = b;
				}
			}
		}
	}

	/**
	 * Squeezes the raw output into {@code maskRing[next]} (the single-channel confidence map
	 * regardless of the tensor layout) and advances the ring.
	 */
	private void extractMask(float[] raw) {
		float[] target = maskRing[maskRingNext];
		int plane = maskWidth * maskHeight;
		int channelOffset = 0;
		if (!outputNHWC) {
			// NCHW: the mask plane follows the (single) channel plane.
			channelOffset = raw.length > plane ? plane : 0;
		}
		System.arraycopy(raw, channelOffset, target, 0, plane);
		maskRingFilled = Math.min(SMOOTHING_FRAMES, maskRingFilled + 1);
		maskRingNext = (maskRingNext + 1) % SMOOTHING_FRAMES;
	}

	/** Upscales the edge-shaped mask into a fresh RGBA frame buffer. */
	private RgbaFrame upscale(RgbaFrame frame) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(frame.width() * frame.height() * 4);
		MaskPostProcess.upscaleToRgba(scratchA, maskWidth, maskHeight, frame.width(),
				frame.height(), buffer);
		return new RgbaFrame(frame.width(), frame.height(), buffer);
	}

	/** Copies the scratch input tensor into the native buffer (native byte order) for ORT. */
	private MemorySegment inputTensorSegment() {
		// JAVA_FLOAT writes IEEE-754 in the platform's native order. A ByteBuffer view would not:
		// allocateDirect() defaults to BIG_ENDIAN, which silently byte-swaps every float and
		// makes the model return NaN on little-endian hosts.
		MemorySegment.copy(inputTensor, 0, inputSegment, ValueLayout.JAVA_FLOAT, 0,
				inputTensor.length);
		return inputSegment;
	}

	/** Input shape entry for the run call (N, H, W, C in the model's own layout). */
	private long inputShapeDim(int index) {
		return inputNHWC
				? switch (index) {
					case 0 -> 1;
					case 1 -> inputHeight;
					case 2 -> inputWidth;
					default -> inputChannels;
				}
				: switch (index) {
					case 0 -> 1;
					case 1 -> inputChannels;
					case 2 -> inputHeight;
					default -> inputWidth;
				};
	}

	/**
	 * Whether a 4-D tensor shape is channels-last ({@code [N,H,W,C]}) or channels-first
	 * ({@code [N,C,H,W]}): {@code channels} is the expected channel count, so a model with
	 * {@code [1,256,256,3]} input reads NHWC and one with {@code [1,3,256,256]} reads NCHW.
	 */
	private static boolean isChannelsLast(long[] shape, int channels) {
		if (shape.length != 4) {
			throw new IllegalArgumentException("expected a 4-D tensor shape, got rank "
					+ shape.length);
		}
		return shape[3] == channels || shape[1] != channels;
	}

	private static long product(long[] shape) {
		long product = 1;
		for (long dim : shape) {
			product *= Math.max(1, dim);
		}
		return product;
	}
}
