package dev.gravaai.media.detection;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;

/**
 * YuNet face detection (opencv_zoo {@code face_detection_yunet_2023mar.onnx}) through the bundled
 * ONNX Runtime — the face half of auto masking.
 *
 * <p>YuNet is a tiny single-shot detector (~230 KB, MIT) with twelve outputs: three stride levels
 * ({@code 8}, {@code 16}, {@code 32}) each contributing {@code cls_}, {@code obj_}, {@code bbox_}
 * and {@code kps_} heads. The model has already applied sigmoid to the class and objectness heads,
 * so the score is {@code sqrt(cls * obj)}; box centres are grid-relative offsets, the size is
 * {@code exp(w/h)} times the stride, and the landmark heads are offsets from the cell origin (the
 * landmarks are decoded for parity with OpenCV but not used — auto masking needs the box only).</p>
 *
 * <p>Pre-processing matches OpenCV's {@code blobFromImage} defaults: BGR channel order, 0..255
 * values, no mean subtraction, padded with zeros up to a multiple of 32. The frame is scaled to
 * the model's input size first, so decode scales the boxes back to source pixels.</p>
 *
 * <p>Single-caller, like the segmenters: an ORT session must not run concurrently, so each
 * analysis pass owns its own instance.</p>
 */
public final class FaceDetector implements AutoCloseable {

	/** Default input edge; YuNet handles any multiple-of-32 size, 320 is the model's own default. */
	public static final int DEFAULT_INPUT_SIZE = 320;
	/** Detection score below which a face is dropped (OpenCV's default for YuNet). */
	private static final float SCORE_THRESHOLD = 0.6f;
	/** NMS IoU above which a lower-scoring overlapping box is dropped. */
	private static final float NMS_THRESHOLD = 0.3f;
	/** Keeps the top K candidate boxes before NMS (OpenCV's default). */
	private static final int TOP_K = 5000;

	private static final int[] STRIDES = {8, 16, 32};
	/** Output name order the YuNet graph exposes; must match {@link #STRIDES}. */
	private static final List<String> OUTPUT_NAMES = List.of(
			"cls_8", "cls_16", "cls_32",
			"obj_8", "obj_16", "obj_32",
			"bbox_8", "bbox_16", "bbox_32",
			"kps_8", "kps_16", "kps_32");

	private final OnnxRuntimeApi api;
	private final MemorySegment session;
	private final MemorySegment memoryInfo;
	private final Arena inputArena = Arena.ofShared();
	private final MemorySegment inputSegment;
	private final String inputName;
	private final int inputWidth;
	private final int inputHeight;
	private final int paddedWidth;
	private final int paddedHeight;
	private final float[] inputTensor;
	private boolean closed;

	/**
	 * Opens the model at {@code modelFile}, running it at {@code inputSize} square pixels.
	 *
	 * @throws IllegalArgumentException when the model is missing or has an unexpected I/O layout
	 */
	public FaceDetector(Path modelFile, int inputSize) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("model file missing: " + modelFile);
		}
		if (inputSize <= 0 || inputSize % 32 != 0) {
			throw new IllegalArgumentException("input size must be a positive multiple of 32: "
					+ inputSize);
		}
		this.api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		MemorySegment ortsession = api.createSession(env, modelFile, 0);
		MemorySegment info = api.createCpuMemoryInfo();
		// ORT sessions and envs are native: the GC cannot reclaim them, so a rejected model (the
		// common failure with a corrupt or wrong download) must release what it already allocated
		// before throwing, or every retry leaks a session for the life of the process.
		try {
			MemorySegment allocator = api.defaultAllocator();
			if (api.sessionInputCount(ortsession) != 1) {
				throw new IllegalArgumentException("YuNet must have exactly one input: "
						+ modelFile);
			}
			if (api.sessionOutputCount(ortsession) != OUTPUT_NAMES.size()) {
				throw new IllegalArgumentException("YuNet must expose " + OUTPUT_NAMES.size()
						+ " outputs, found " + api.sessionOutputCount(ortsession) + ": "
						+ modelFile);
			}
			String input = api.sessionInputName(ortsession, allocator, 0);
			long[] shape = api.sessionInputShape(ortsession, 0);
			if (shape.length != 4 || shape[1] != 3) {
				throw new IllegalArgumentException("YuNet input must be NCHW RGB, found "
						+ java.util.Arrays.toString(shape) + ": " + modelFile);
			}
			// The 2023-03 export publishes a fixed 640x640 input; a dynamic export would take the
			// caller's size. Either way the decoder below works on whatever we run at.
			int height = shape[2] > 0 && shape[2] <= Integer.MAX_VALUE
					? (int) shape[2] : inputSize;
			int width = shape[3] > 0 && shape[3] <= Integer.MAX_VALUE
					? (int) shape[3] : inputSize;
			if (width % 32 != 0 || height % 32 != 0) {
				throw new IllegalArgumentException("YuNet input must be a multiple of 32, found "
						+ width + "x" + height + ": " + modelFile);
			}
			float[] tensor = new float[3 * width * height];
			MemorySegment segment = inputArena.allocate((long) tensor.length * Float.BYTES,
					Float.BYTES);
			this.session = ortsession;
			this.memoryInfo = info;
			this.inputName = input;
			this.inputWidth = width;
			this.inputHeight = height;
			this.paddedWidth = width;
			this.paddedHeight = height;
			this.inputTensor = tensor;
			this.inputSegment = segment;
		} catch (RuntimeException invalidModel) {
			api.releaseMemoryInfo(info);
			api.releaseSession(ortsession);
			api.close();
			throw invalidModel;
		}
	}

	/** Opens the bundled YuNet model at {@link #DEFAULT_INPUT_SIZE}. */
	public static FaceDetector openBundled() {
		return new FaceDetector(OnnxLibs.resolveFaceModelFile(), DEFAULT_INPUT_SIZE);
	}

	/**
	 * Detects faces in one frame, returning boxes in source pixels (origin top-left), highest score
	 * first after non-maximum suppression.
	 */
	public List<FaceBox> detect(RgbaFrame frame) {
		if (closed) {
			throw new IllegalStateException("face detector is closed");
		}
		preprocess(frame);
		MemorySegment[] outputs = api.runFloat(session, memoryInfo,
				List.of(new OnnxRuntimeApi.NamedFloat(inputName,
						new long[] {1, 3, paddedHeight, paddedWidth}, inputTensor)),
				OUTPUT_NAMES);
		try {
			List<FaceBox> candidates = new ArrayList<>();
			// Output order: cls_8, cls_16, cls_32, obj_8, obj_16, obj_32, bbox_*, kps_*.
			for (int level = 0; level < STRIDES.length; level++) {
				int stride = STRIDES[level];
				int cols = paddedWidth / stride;
				int rows = paddedHeight / stride;
				float[] cls = floats(outputs[level]);
				float[] obj = floats(outputs[level + STRIDES.length]);
				float[] bbox = floats(outputs[level + STRIDES.length * 2]);
				int cells = rows * cols;
				for (int cell = 0; cell < cells; cell++) {
					float classScore = Math.clamp(cls[cell], 0f, 1f);
					float objectScore = Math.clamp(obj[cell], 0f, 1f);
					float score = (float) Math.sqrt(classScore * objectScore);
					if (score < SCORE_THRESHOLD) {
						continue;
					}
					int row = cell / cols;
					int col = cell % cols;
					int box = cell * 4;
					double centerX = (col + bbox[box]) * stride;
					double centerY = (row + bbox[box + 1]) * stride;
					double width = Math.exp(bbox[box + 2]) * stride;
					double height = Math.exp(bbox[box + 3]) * stride;
					candidates.add(scaleToFrame(new FaceBox(centerX - width / 2,
							centerY - height / 2, width, height, score), frame));
				}
			}
			return nonMaximumSuppression(candidates);
		} finally {
			for (MemorySegment output : outputs) {
				api.releaseValue(output);
			}
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
		inputArena.close();
	}

	private float[] floats(MemorySegment value) {
		long[] shape = api.valueShape(value);
		long elements = 1;
		for (long dim : shape) {
			elements = Math.multiplyExact(elements, dim);
		}
		return api.getTensorFloats(value, elements);
	}

	/**
	 * Fills the NCHW input: nearest-neighbour from the source (the detector resizes internally to
	 * 0.8·score anyway, and the input is a letterbox-free stretch like OpenCV's pad-then-resize).
	 * Values stay in 0..255 BGR, OpenCV's {@code blobFromImage} defaults.
	 */
	private void preprocess(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		float scaleX = (float) frame.width() / inputWidth;
		float scaleY = (float) frame.height() / inputHeight;
		int plane = inputWidth * inputHeight;
		for (int y = 0; y < inputHeight; y++) {
			int sourceY = Math.min(frame.height() - 1, (int) ((y + 0.5f) * scaleY));
			int row = sourceY * frame.width();
			int outRow = y * inputWidth;
			for (int x = 0; x < inputWidth; x++) {
				int sourceX = Math.min(frame.width() - 1, (int) ((x + 0.5f) * scaleX));
				int offset = (row + sourceX) * 4;
				int index = outRow + x;
				inputTensor[index] = Byte.toUnsignedInt(pixels.get(offset + 2));
				inputTensor[plane + index] = Byte.toUnsignedInt(pixels.get(offset + 1));
				inputTensor[2 * plane + index] = Byte.toUnsignedInt(pixels.get(offset));
			}
		}
		MemorySegment.copy(inputTensor, 0, inputSegment, ValueLayout.JAVA_FLOAT, 0,
				inputTensor.length);
	}

	/** Maps a box decoded in model-input pixels back into source-frame pixels. */
	private FaceBox scaleToFrame(FaceBox box, RgbaFrame frame) {
		double scaleX = (double) frame.width() / inputWidth;
		double scaleY = (double) frame.height() / inputHeight;
		double x = Math.clamp(box.x() * scaleX, 0, frame.width() - 1.0);
		double y = Math.clamp(box.y() * scaleY, 0, frame.height() - 1.0);
		double width = Math.min(box.width() * scaleX, frame.width() - x);
		double height = Math.min(box.height() * scaleY, frame.height() - y);
		return new FaceBox(x, y, Math.max(1, width), Math.max(1, height), box.score());
	}

	/** Greedy NMS by score: keeps a box unless it overlaps an already-kept box above threshold. */
	static List<FaceBox> nonMaximumSuppression(List<FaceBox> boxes) {
		List<FaceBox> sorted = boxes.stream()
				.sorted(Comparator.comparingDouble(FaceBox::score).reversed())
				.limit(TOP_K)
				.toList();
		List<FaceBox> kept = new ArrayList<>();
		for (FaceBox candidate : sorted) {
			boolean suppressed = false;
			for (FaceBox winner : kept) {
				if (candidate.iou(winner) >= NMS_THRESHOLD) {
					suppressed = true;
					break;
				}
			}
			if (!suppressed) {
				kept.add(candidate);
			}
		}
		return List.copyOf(kept);
	}
}
