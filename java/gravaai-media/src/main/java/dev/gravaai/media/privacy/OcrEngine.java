package dev.gravaai.media.privacy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 * PP-OCRv4 mobile text detection + recognition (ONNX) — the screen-text half of auto masking.
 *
 * <p>Two graphs, both bundled (~16 MB total): the DB detector finds text boxes, the CRNN
 * recognizer reads each box. The Java side owns the pre/post-processing that the PaddleOCR
 * pipeline normally does:</p>
 *
 * <ul>
 *   <li><b>Detection.</b> Stretch-resize so the long side is at most {@code limitSide} and both
 *       sides are multiples of 32, scale to 0..1 and normalise with the standard ImageNet mean/std,
 *       run NCHW. The output is a probability map; boxes come from thresholding it at 0.3,
 *       finding connected components, taking each one's rotated minimum-area rectangle and
 *       un-clipping it (DB's {@code unclip_ratio} 2.0).</li>
 *   <li><b>Recognition.</b> Each box is cropped, resized to 48 px tall and at most
 *       {@code recWidth} wide, normalised to [-1,1], run NCHW. The output is a CTC posterior over
 *       the checkpoint's own character list (read from the model metadata, so no 6,600-line
 *       dictionary file has to be shipped).</li>
 * </ul>
 *
 * <p>This is deliberately a focused implementation for screen text: one column per text line,
 * horizontal reading order, no perspective unwarping. Screen capture is flat and axis-aligned,
 * which is the case the DB + minimum-area-rect path handles without the full PaddleOCR geometry
 * stack.</p>
 *
 * <p>Single-caller, like the other ONNX wrappers.</p>
 */
public final class OcrEngine implements AutoCloseable {

	/** Detector long-side limit; the reference default for screen-sized captures. */
	private static final int DET_LIMIT_SIDE = 960;
	/** Detector binarisation threshold for the probability map. */
	private static final float DET_THRESHOLD = 0.3f;
	/** Minimum box score kept after the contour stage. */
	private static final float DET_BOX_THRESHOLD = 0.5f;
	/** DB's unclip ratio: how far the shrunk text mask grows back out. */
	private static final float UNCLIP_RATIO = 2.0f;
	/** Boxes below this short side (in detector pixels) are noise. */
	private static final int MIN_BOX_SIZE = 3;
	/** Recognizer input height the PP-OCR mobile models are trained at. */
	private static final int REC_HEIGHT = 48;
	/** Recognizer input width cap (dynamic in the graph, fixed here for one allocation). */
	private static final int REC_WIDTH = 320;
	/** CTC blank index in the recognizer's output alphabet. */
	private static final int CTC_BLANK = 0;

	private final OnnxRuntimeApi api;
	private final MemorySegment detSession;
	private final MemorySegment recSession;
	private final MemorySegment memoryInfo;
	private final String detInputName;
	private final String detOutputName;
	private final String recInputName;
	private final String recOutputName;
	/** The recognizer's character list including the leading CTC blank. */
	private final String[] characters;
	private final Arena detInputArena = Arena.ofShared();
	private final Arena recInputArena = Arena.ofShared();
	private final MemorySegment detInputBuffer;
	private final MemorySegment recInputBuffer;
	private final float[] detTensor;
	private final float[] recTensor;
	private boolean closed;

	/**
	 * One recognised text line: the text, its mean CTC confidence, and the box in source pixels.
	 *
	 * @param left   box left edge in source pixels
	 * @param top    box top edge in source pixels
	 * @param right  box right edge in source pixels
	 * @param bottom box bottom edge in source pixels
	 */
	public record TextLine(String text, float confidence, int left, int top, int right, int bottom) {

		public TextLine {
			if (right <= left || bottom <= top) {
				throw new IllegalArgumentException("invalid text box: " + left + "," + top + ".."
						+ right + "," + bottom);
			}
		}

		public int width() {
			return right - left;
		}

		public int height() {
			return bottom - top;
		}
	}

	/**
	 * Opens the two model files. The recognizer's alphabet is read from the model metadata (the
	 * PP-OCR exports embed it under {@code character}), so only the .onnx files are needed.
	 *
	 * @param detModel PP-OCR detection model
	 * @param recModel PP-OCR recognition model
	 */
	public OcrEngine(Path detModel, Path recModel, int threads) {
		if (!Files.isRegularFile(detModel)) {
			throw new IllegalArgumentException("OCR detection model missing: " + detModel);
		}
		if (!Files.isRegularFile(recModel)) {
			throw new IllegalArgumentException("OCR recognition model missing: " + recModel);
		}
		this.api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		MemorySegment det = api.createSession(env, detModel, threads);
		MemorySegment rec = api.createSession(env, recModel, threads);
		MemorySegment info = api.createCpuMemoryInfo();
		// Native sessions are not GC-reclaimable: a model rejected below (corrupt download, wrong
		// file) has to release what it already opened, or every retry leaks for the process's life.
		try {
			MemorySegment allocator = api.defaultAllocator();
			String detIn = api.sessionInputName(det, allocator, 0);
			String detOut = api.sessionOutputName(det, allocator, 0);
			String recIn = api.sessionInputName(rec, allocator, 0);
			String recOut = api.sessionOutputName(rec, allocator, 0);
			// The recognizer's own metadata carries the CTC alphabet; a model without it is not a
			// PP-OCR recogniser, and finding that out here must not leak the sessions.
			String[] alphabet = readCharacters(rec, allocator, recModel);
			float[] detBuffer = new float[3 * DET_LIMIT_SIDE * DET_LIMIT_SIDE];
			MemorySegment detSegment = detInputArena.allocate((long) detBuffer.length
					* Float.BYTES, Float.BYTES);
			float[] recBuffer = new float[3 * REC_HEIGHT * REC_WIDTH];
			MemorySegment recSegment = recInputArena.allocate((long) recBuffer.length
					* Float.BYTES, Float.BYTES);
			this.detSession = det;
			this.recSession = rec;
			this.memoryInfo = info;
			this.detInputName = detIn;
			this.detOutputName = detOut;
			this.recInputName = recIn;
			this.recOutputName = recOut;
			this.characters = alphabet;
			this.detTensor = detBuffer;
			this.detInputBuffer = detSegment;
			this.recTensor = recBuffer;
			this.recInputBuffer = recSegment;
		} catch (RuntimeException invalidModel) {
			api.releaseMemoryInfo(info);
			api.releaseSession(rec);
			api.releaseSession(det);
			api.close();
			throw invalidModel;
		}
	}

	/** Opens the bundled PP-OCR models ({@link OnnxLibs#resolveOcrDetModelFile()} and friends). */
	public static OcrEngine openBundled() {
		return new OcrEngine(OnnxLibs.resolveOcrDetModelFile(),
				OnnxLibs.resolveOcrRecModelFile(), 2);
	}

	/**
	 * Reads every text line in one frame, in reading order (top to bottom, then left to right).
	 *
	 * @param minConfidence lines below this mean CTC confidence are dropped
	 */
	public List<TextLine> read(RgbaFrame frame, float minConfidence) {
		if (closed) {
			throw new IllegalStateException("OCR engine is closed");
		}
		List<TextBox> boxes = detectBoxes(frame);
		List<TextLine> lines = new ArrayList<>(boxes.size());
		for (TextBox box : boxes) {
			TextLine line = recognize(frame, box, minConfidence);
			if (line != null) {
				lines.add(line);
			}
		}
		lines.sort(Comparator.comparingInt(TextLine::top).thenComparingInt(TextLine::left));
		return List.copyOf(lines);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(detSession);
		api.releaseSession(recSession);
		api.close();
		detInputArena.close();
		recInputArena.close();
	}

	/** A detected text rectangle in source pixels. */
	private record TextBox(int left, int top, int right, int bottom, float score) {}

	// -------------------------------------------------------------------------------------------
	// Detection
	// -------------------------------------------------------------------------------------------

	private List<TextBox> detectBoxes(RgbaFrame frame) {
		double scale = detScale(frame);
		int resizedHeight = roundUpTo32((int) Math.max(1, Math.round(frame.height() * scale)));
		int resizedWidth = roundUpTo32((int) Math.max(1, Math.round(frame.width() * scale)));
		int plane = resizedWidth * resizedHeight;
		if (detTensor.length < 3 * plane) {
			throw new IllegalStateException("frame too large for the OCR detector buffer: "
					+ resizedWidth + "x" + resizedHeight);
		}
		preprocessDet(frame, resizedWidth, resizedHeight);
		MemorySegment.copy(detTensor, 0, detInputBuffer,
				java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 3 * plane);
		MemorySegment[] outputs = api.runFloat(detSession, memoryInfo,
				List.of(new OnnxRuntimeApi.NamedFloat(detInputName,
						new long[] {1, 3, resizedHeight, resizedWidth}, detTensor)),
				List.of(detOutputName));
		try {
			long[] shape = api.valueShape(outputs[0]);
			if (shape.length != 4 || shape[1] != 1) {
				throw new IllegalStateException("unexpected OCR detection shape: "
						+ java.util.Arrays.toString(shape));
			}
			int mapHeight = (int) shape[2];
			int mapWidth = (int) shape[3];
			float[] probabilities = api.getTensorFloats(outputs[0],
					shape[0] * shape[1] * shape[2] * shape[3]);
			List<TextBox> boxes = mapToBoxes(probabilities, mapWidth, mapHeight);
			// Detector pixels back to source pixels.
			double scaleX = (double) frame.width() / mapWidth;
			double scaleY = (double) frame.height() / mapHeight;
			List<TextBox> scaled = new ArrayList<>(boxes.size());
			for (TextBox box : boxes) {
				int left = (int) Math.floor(box.left() * scaleX);
				int top = (int) Math.floor(box.top() * scaleY);
				int right = (int) Math.ceil(box.right() * scaleX);
				int bottom = (int) Math.ceil(box.bottom() * scaleY);
				left = Math.clamp(left, 0, frame.width() - 1);
				top = Math.clamp(top, 0, frame.height() - 1);
				right = Math.clamp(right, left + 1, frame.width());
				bottom = Math.clamp(bottom, top + 1, frame.height());
				scaled.add(new TextBox(left, top, right, bottom, box.score()));
			}
			return scaled;
		} finally {
			for (MemorySegment output : outputs) {
				api.releaseValue(output);
			}
		}
	}

	/** Scale factor that keeps the long side under the detector limit. */
	private static double detScale(RgbaFrame frame) {
		return Math.min(1.0, (double) DET_LIMIT_SIDE / Math.max(frame.width(), frame.height()));
	}

	private static int roundUpTo32(int value) {
		return Math.max(32, (value + 31) / 32 * 32);
	}

	/** Stretch-samples the frame into the detector's NCHW tensor with ImageNet normalisation. */
	private void preprocessDet(RgbaFrame frame, int width, int height) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int plane = width * height;
		float scaleX = (float) frame.width() / width;
		float scaleY = (float) frame.height() / height;
		float[] mean = {0.485f, 0.456f, 0.406f};
		float[] std = {0.229f, 0.224f, 0.225f};
		for (int y = 0; y < height; y++) {
			int sourceY = Math.min(frame.height() - 1, (int) ((y + 0.5f) * scaleY));
			int row = sourceY * frame.width();
			int outRow = y * width;
			for (int x = 0; x < width; x++) {
				int sourceX = Math.min(frame.width() - 1, (int) ((x + 0.5f) * scaleX));
				int offset = (row + sourceX) * 4;
				int index = outRow + x;
				// RGBA source, RGB channels in model order (the reference feeds RGB).
				detTensor[index] = (Byte.toUnsignedInt(pixels.get(offset)) / 255f
						- mean[0]) / std[0];
				detTensor[plane + index] = (Byte.toUnsignedInt(pixels.get(offset + 1)) / 255f
						- mean[1]) / std[1];
				detTensor[2 * plane + index] = (Byte.toUnsignedInt(pixels.get(offset + 2)) / 255f
						- mean[2]) / std[2];
			}
		}
	}

	/**
	 * Turns the DB probability map into axis-aligned boxes via connected components: threshold,
	 * flood-fill each component, take its bounding rectangle and grow it by the unclip distance
	 * (DB predicts a shrunken region, so the box has to be expanded back).
	 */
	private static List<TextBox> mapToBoxes(float[] probabilities, int width, int height) {
		boolean[] text = new boolean[width * height];
		for (int i = 0; i < text.length; i++) {
			text[i] = probabilities[i] > DET_THRESHOLD;
		}
		boolean[] visited = new boolean[width * height];
		int[] stack = new int[width * height];
		List<TextBox> boxes = new ArrayList<>();
		for (int start = 0; start < text.length; start++) {
			if (!text[start] || visited[start]) {
				continue;
			}
			// Iterative 8-connected flood fill; a component is one candidate text line.
			int top = 0;
			stack[top++] = start;
			visited[start] = true;
			int minX = width;
			int minY = height;
			int maxX = -1;
			int maxY = -1;
			long count = 0;
			double scoreSum = 0;
			while (top > 0) {
				int cell = stack[--top];
				int y = cell / width;
				int x = cell % width;
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				count++;
				scoreSum += probabilities[cell];
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
							continue;
						}
						int next = ny * width + nx;
						if (text[next] && !visited[next]) {
							visited[next] = true;
							stack[top++] = next;
						}
					}
				}
			}
			int boxWidth = maxX - minX + 1;
			int boxHeight = maxY - minY + 1;
			if (Math.min(boxWidth, boxHeight) < MIN_BOX_SIZE) {
				continue;
			}
			double score = scoreSum / count;
			if (score < DET_BOX_THRESHOLD) {
				continue;
			}
			// Unclip: DB's map is the eroded text region; grow by area/perimeter * ratio.
			double perimeter = 2.0 * (boxWidth + boxHeight);
			double area = (double) boxWidth * boxHeight;
			int expand = (int) Math.round(area * UNCLIP_RATIO / perimeter);
			boxes.add(new TextBox(minX - expand, minY - expand, maxX + expand + 1,
					maxY + expand + 1, (float) score));
		}
		boxes.sort(Comparator.comparingInt(TextBox::top).thenComparingInt(TextBox::left));
		return boxes;
	}

	// -------------------------------------------------------------------------------------------
	// Recognition
	// -------------------------------------------------------------------------------------------

	private TextLine recognize(RgbaFrame frame, TextBox box, float minConfidence) {
		int sourceWidth = Math.max(1, box.right() - box.left());
		int sourceHeight = Math.max(1, box.bottom() - box.top());
		// Preserve aspect ratio at a fixed 48 px height, capped at the model's width. The tensor
		// keeps the full REC_WIDTH stride and zero-pads the tail: the graph is fully convolutional
		// over width, and a per-text width would leave the channel planes misaligned with the
		// shape handed to ORT.
		int targetWidth = Math.clamp(Math.round((float) sourceWidth / sourceHeight * REC_HEIGHT),
				1, REC_WIDTH);
		preprocessRec(frame, box, targetWidth);
		MemorySegment.copy(recTensor, 0, recInputBuffer,
				java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, recTensor.length);
		MemorySegment[] outputs = api.runFloat(recSession, memoryInfo,
				List.of(new OnnxRuntimeApi.NamedFloat(recInputName,
						new long[] {1, 3, REC_HEIGHT, REC_WIDTH}, recTensor)),
				List.of(recOutputName));
		try {
			long[] shape = api.valueShape(outputs[0]);
			if (shape.length != 3) {
				throw new IllegalStateException("unexpected OCR recognition shape: "
						+ java.util.Arrays.toString(shape));
			}
			int steps = (int) shape[1];
			int classes = (int) shape[2];
			float[] posterior = api.getTensorFloats(outputs[0], shape[0] * shape[1] * shape[2]);
			StringBuilder text = new StringBuilder();
			double confidenceSum = 0;
			int confidenceCount = 0;
			int previous = -1;
			for (int step = 0; step < steps; step++) {
				int best = -1;
				float bestScore = -1;
				for (int c = 0; c < classes; c++) {
					float score = posterior[step * classes + c];
					if (score > bestScore) {
						bestScore = score;
						best = c;
					}
				}
				// CTC: drop blanks and immediate repeats; keep the step's confidence otherwise.
				if (best != CTC_BLANK && best != previous) {
					if (best < characters.length) {
						text.append(characters[best]);
					}
					confidenceSum += bestScore;
					confidenceCount++;
				}
				previous = best;
			}
			if (confidenceCount == 0) {
				return null;
			}
			float confidence = (float) (confidenceSum / confidenceCount);
			String recognised = text.toString().strip();
			if (recognised.isEmpty() || confidence < minConfidence) {
				return null;
			}
			return new TextLine(recognised, confidence, box.left(), box.top(), box.right(),
					box.bottom());
		} finally {
			for (MemorySegment output : outputs) {
				api.releaseValue(output);
			}
		}
	}

	/** Samples the box into the recognizer's [-1,1] NCHW tensor, padding the rest with zeros. */
	private void preprocessRec(RgbaFrame frame, TextBox box, int targetWidth) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int plane = REC_HEIGHT * REC_WIDTH;
		// Zero the whole tensor: the columns past targetWidth stay as padding.
		java.util.Arrays.fill(recTensor, 0f);
		float scaleX = (float) (box.right() - box.left()) / targetWidth;
		float scaleY = (float) (box.bottom() - box.top()) / REC_HEIGHT;
		for (int y = 0; y < REC_HEIGHT; y++) {
			int sourceY = Math.min(frame.height() - 1,
					box.top() + (int) ((y + 0.5f) * scaleY));
			int row = sourceY * frame.width();
			int outRow = y * REC_WIDTH;
			for (int x = 0; x < targetWidth; x++) {
				int sourceX = Math.min(frame.width() - 1,
						box.left() + (int) ((x + 0.5f) * scaleX));
				int offset = (row + sourceX) * 4;
				int index = outRow + x;
				recTensor[index] = Byte.toUnsignedInt(pixels.get(offset)) / 127.5f - 1f;
				recTensor[plane + index] = Byte.toUnsignedInt(pixels.get(offset + 1)) / 127.5f
						- 1f;
				recTensor[2 * plane + index] = Byte.toUnsignedInt(pixels.get(offset + 2))
						/ 127.5f - 1f;
			}
		}
	}

	/**
	 * Reads the recognizer's character list from the model's own metadata. PP-OCR ONNX exports
	 * embed it under {@code character}, newline separated and without the CTC blank, so index 0 is
	 * the blank and index {@code i} is {@code characters[i - 1]}.
	 */
	private String[] readCharacters(MemorySegment session, MemorySegment allocator,
			Path recModel) {
		String raw = api.modelMetadata(session, allocator, "character");
		if (raw == null || raw.isEmpty()) {
			throw new IllegalStateException("OCR recognition model carries no 'character'"
					+ " metadata: " + recModel);
		}
		String[] lines = raw.split("\n", -1);
		// The metadata is newline separated with a trailing separator, then the reference appends
		// a space (" ") before the blank; drop the empty tail the split produces.
		int count = lines.length;
		while (count > 0 && lines[count - 1].isEmpty()) {
			count--;
		}
		String[] out = new String[count + 2];
		out[CTC_BLANK] = "";
		System.arraycopy(lines, 0, out, 1, count);
		// The reference inserts a space piece right before the blank-adjacent tail; PP-OCR's
		// recognizer reserves the last class for " ", which the metadata does not list.
		out[out.length - 1] = " ";
		return out;
	}
}
