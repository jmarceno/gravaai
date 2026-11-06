package dev.gravaai.media.segmentation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;

/**
 * The one frame-to-mask pipeline behind both webcam segmentation paths: a single float input
 * tensor in, a single float person-confidence map out, shaped by the same temporal smoothing and
 * the same {@link MaskTuning} edge controls.
 *
 * <p>The fast path ({@link SelfieSegmenter}) and the slow path ({@link ModnetSegmenter}) differ only
 * in the model file, the intra-op thread count and the input normalisation; everything after the
 * inference (smoothing window, dilate/erode, Gaussian, sigmoid remap, RGBA packing) is shared, so a
 * project's Edge precision / Edge offset mean exactly the same thing on both paths. That is what
 * keeps a pause from snapping the silhouette to a differently-shaped edge.</p>
 *
 * <p>Per frame: stretch the RGBA frame to the model's square input (no letterboxing — the models
 * were trained on squashed frames and a letterbox would add dead borders and shift the person) →
 * inference → squeeze the single-channel confidence map → temporal mean over the last three masks
 * (hair-region flicker) → dilate + Gaussian + steep sigmoid remap (halo fix) → pack into an RGBA
 * buffer with the mask value in every channel.</p>
 *
 * <p>The mask stays at the model's resolution rather than being upscaled to the frame: the
 * compositor samples it through a matrix, so upscaling here would add a scalar bilinear pass over
 * frame-sized data per frame for no visible gain.</p>
 *
 * <p>Single-caller: an ORT session must not run concurrently; each consumer (preview decode,
 * export, filmstrips) owns its own instance. The input/output names and layouts are read from the
 * model at construction, so a different conversion of the same model does not need code changes.
 * Mask buffers handed out are immutable (a fresh allocation per frame), matching the preview
 * queue's stable-copy contract.</p>
 */
abstract class OnnxMaskSegmenter implements WebcamSegmenter, AutoCloseable {

	private static final System.Logger LOG =
			System.getLogger(OnnxMaskSegmenter.class.getName());

	/** Temporal smoothing window (masks); three frames remove shimmer without visible lag. */
	private static final int SMOOTHING_FRAMES = 3;
	/** Midpoint of the sigmoid remap (1/(1+e^(-slope·(m-0.5)))); the slope is per-project. */
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
	/**
	 * Whether the model expects [-1,1] input (MODNet's {@code mean 0.5, std 0.5}) instead of
	 * [0,1] (the selfie segmenter). Both produce a usable mask; the trained configuration gives a
	 * cleaner edge.
	 */
	private final boolean normaliseSymmetric;
	// Scratch (single-caller): the input tensor, the smoothing ring and the edge-shaped mask.
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

	/**
	 * @param modelFile the ONNX model to run
	 * @param threads intra-op threads for the ORT session
	 * @param fallbackInputSize the square input size to use when the model's input shape is
	 *        dynamic (MODNet publishes symbolic {@code height}/{@code width}); ignored when the
	 *        model has fixed dimensions
	 * @param normaliseSymmetric {@code true} for [-1,1] input, {@code false} for [0,1]
	 */
	OnnxMaskSegmenter(Path modelFile, int threads, int fallbackInputSize,
			boolean normaliseSymmetric) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("model file missing: " + modelFile);
		}
		this.normaliseSymmetric = normaliseSymmetric;
		this.api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		this.session = api.createSession(env, modelFile, threads);
		this.memoryInfo = api.createCpuMemoryInfo();
		MemorySegment allocator = api.defaultAllocator();
		if (api.sessionInputCount(session) != 1 || api.sessionOutputCount(session) != 1) {
			throw new IllegalArgumentException(modelFile
					+ " must have exactly one input and one output: " + modelFile);
		}
		this.inputName = api.sessionInputName(session, allocator, 0);
		this.outputName = api.sessionOutputName(session, allocator, 0);
		long[] inputShape = requireRank4(api.sessionInputShape(session, 0), "input");
		this.inputNHWC = isChannelsLast(inputShape, 3);
		// A symbolic dimension (-1 in the C API) means "anything": the caller's fallback is the
		// size the model is actually run at, which the card fixes at 256 for both paths.
		if (inputNHWC) {
			this.inputHeight = resolveDim(inputShape[1], fallbackInputSize);
			this.inputWidth = resolveDim(inputShape[2], fallbackInputSize);
			this.inputChannels = (int) inputShape[3];
		} else {
			this.inputChannels = (int) inputShape[1];
			this.inputHeight = resolveDim(inputShape[2], fallbackInputSize);
			this.inputWidth = resolveDim(inputShape[3], fallbackInputSize);
		}
		this.inputTensor = new float[inputWidth * inputHeight * inputChannels];
		this.inputSegment = arena.allocate((long) inputTensor.length * Float.BYTES,
				Float.BYTES);
		long[] outputShape = requireRank4(api.sessionOutputShape(session, 0), "output");
		this.outputNHWC = isChannelsLast(outputShape, 1);
		// The output follows the input for both models (a same-size confidence map), so a dynamic
		// output dimension resolves to the input dimension we are going to run at.
		if (outputNHWC) {
			this.maskHeight = resolveDim(outputShape[1], inputHeight);
			this.maskWidth = resolveDim(outputShape[2], inputWidth);
		} else {
			this.maskHeight = resolveDim(outputShape[2], inputHeight);
			this.maskWidth = resolveDim(outputShape[3], inputWidth);
		}
		this.outputElementCount = (long) maskWidth * maskHeight;
		for (int i = 0; i < SMOOTHING_FRAMES; i++) {
			maskRing[i] = new float[maskWidth * maskHeight];
		}
		this.scratchA = new float[maskWidth * maskHeight];
		this.scratchB = new float[maskWidth * maskHeight];
		this.smoothed = new float[maskWidth * maskHeight];
		LOG.log(System.Logger.Level.DEBUG, () -> modelDescription() + " ready: " + modelFile
				+ " input " + inputName + " " + inputWidth + "x" + inputHeight + "x" + inputChannels
				+ " (" + (inputNHWC ? "NHWC" : "NCHW") + ", "
				+ (normaliseSymmetric ? "[-1,1]" : "[0,1]") + "), output " + outputName + " "
				+ maskWidth + "x" + maskHeight + ", " + threads + " thread(s)");
	}

	/**
	 * A short label for logs and error messages ({@code "selfie segmenter"},
	 * {@code "MODNet segmenter"}).
	 */
	protected abstract String modelDescription();

	@Override
	public RgbaFrame maskFor(RgbaFrame frame, MaskTuning tuning) {
		if (closed) {
			throw new IllegalStateException(modelDescription() + " is closed");
		}
		preprocess(frame);
		MemorySegment input = inputTensorSegment();
		MemorySegment output = api.runSingle(session, inputName, input,
				(long) inputTensor.length * Float.BYTES,
				new long[] {1, inputShapeDim(1), inputShapeDim(2), inputShapeDim(3)},
				memoryInfo, outputName);
		try {
			float[] raw = api.getTensorFloats(output, outputElementCount);
			extractMask(raw);
			MaskPostProcess.average(maskRing, maskRingFilled, smoothed);
			// The edge shaping runs on the smoothed mask; the ring keeps the raw masks so the
			// smoothing window stays honest across the dilation/remap.
			System.arraycopy(smoothed, 0, scratchA, 0, smoothed.length);
			MaskPostProcess.shiftEdge(scratchA, scratchB, maskWidth, maskHeight,
					tuning.dilationRadius(maskWidth, maskHeight));
			MaskPostProcess.gaussianBlur(scratchA, scratchB, maskWidth, maskHeight,
					tuning.sigma());
			MaskPostProcess.sigmoidRemap(scratchA, tuning.slope(), REMAP_MIDPOINT);
			return maskFrame();
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

	/**
	 * Stretch-samples the frame into the model's input tensor. Nearest-neighbour on the source:
	 * bilinear detail is wasted at 256 px and the mask is upscaled bilinearly afterwards anyway.
	 */
	private void preprocess(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int frameWidth = frame.width();
		int frameHeight = frame.height();
		float scaleX = (float) frameWidth / inputWidth;
		float scaleY = (float) frameHeight / inputHeight;
		float scale = normaliseSymmetric ? 2f / 255f : 1f / 255f;
		float shift = normaliseSymmetric ? -1f : 0f;
		for (int y = 0; y < inputHeight; y++) {
			int sourceY = Math.min(frameHeight - 1, (int) ((y + 0.5f) * scaleY));
			int row = sourceY * frameWidth;
			int outRow = y * inputWidth;
			for (int x = 0; x < inputWidth; x++) {
				int sourceX = Math.min(frameWidth - 1, (int) ((x + 0.5f) * scaleX));
				int offset = (row + sourceX) * 4;
				float r = Byte.toUnsignedInt(pixels.get(offset)) * scale + shift;
				float g = Byte.toUnsignedInt(pixels.get(offset + 1)) * scale + shift;
				float b = Byte.toUnsignedInt(pixels.get(offset + 2)) * scale + shift;
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

	/**
	 * Packs the edge-shaped mask into a fresh RGBA frame buffer. The buffer stays at the mask's
	 * own resolution: the compositor samples it onto the card through a matrix, so upscaling here
	 * would be a scalar bilinear pass over frame-sized data per frame for no gain.
	 */
	private RgbaFrame maskFrame() {
		return new RgbaFrame(maskWidth, maskHeight, MaskPostProcess.toRgba(scratchA, maskWidth,
				maskHeight));
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
		return shape[3] == channels || shape[1] != channels;
	}

	private static long[] requireRank4(long[] shape, String what) {
		if (shape.length != 4) {
			throw new IllegalArgumentException("expected a 4-D " + what + " tensor shape, got rank "
					+ shape.length);
		}
		return shape;
	}

	/** A declared dimension when it is fixed, {@code fallback} when it is symbolic. */
	private static int resolveDim(long declared, int fallback) {
		int size = declared > 0 && declared <= Integer.MAX_VALUE ? (int) declared : fallback;
		if (size <= 0) {
			throw new IllegalArgumentException("cannot resolve a tensor dimension: " + declared);
		}
		return size;
	}
}
