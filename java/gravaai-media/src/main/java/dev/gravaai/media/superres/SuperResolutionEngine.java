package dev.gravaai.media.superres;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.ZoomTransform;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.gen.onnxruntime_h;
import dev.gravaai.media.superres.SuperResolution.Crop;
import io.github.humbleui.types.Rect;

/**
 * The optional deep-zoom super-resolution pass: runs the pinned Real-ESRGAN x4 network over the
 * source window a deeply zoomed camera shows, so an exported 5&times; stage has real detail instead
 * of a stretched 1080p crop.
 *
 * <p><b>Export only.</b> One 5&times; window of a 1080p source takes about half a second on the
 * bundled (CPU) ONNX Runtime, which is fine inside an export and impossible in a 60 fps preview;
 * the preview keeps the compositor's cheap {@link dev.gravaai.media.compose.ZoomSharpen} pass. Both
 * paths draw through the same compositor, so an export with this engine simply has a sharper
 * texture for the same frames.</p>
 *
 * <p><b>Window caching.</b> The inference runs on a <em>padded</em> window (see
 * {@link SuperResolution#padded}) and the result is kept until the camera moves far enough that the
 * visible window no longer fits inside it. An eased zoom-in therefore pays for a handful of
 * inferences across the whole gesture instead of one per frame, and a held zoom pays once.</p>
 *
 * <p>Single-caller: an ORT session must not run concurrently, so one instance belongs to one
 * export (the same ownership as the webcam segmenter). Not thread-safe.</p>
 */
public final class SuperResolutionEngine implements AutoCloseable {

	private static final System.Logger LOG =
			System.getLogger(SuperResolutionEngine.class.getName());

	/**
	 * Intra-op threads for the upscaler. The network is small and fully convolutional, so it scales
	 * well up to a point: 8 threads measured ~0.5 s on a 5&times; 1080p window, 12 no better, and
	 * leaving it to the ORT default oversubscribes the export's GL and decode threads.
	 */
	private static final int THREADS = 8;

	private final OnnxRuntimeApi api;
	private final MemorySegment session;
	private final MemorySegment memoryInfo;
	private final String inputName;
	private final String outputName;
	private final Path modelFile;
	private boolean closed;

	// The cached upscaled window: the padded source crop it covers, and the pixels at 4x.
	private @Nullable Crop cachedCrop;
	private @Nullable RgbaFrame cachedUpscaled;
	private long inferenceCount;
	private long cacheHits;
	private long totalInferenceNanos;

	private SuperResolutionEngine(OnnxRuntimeApi api, MemorySegment session,
			MemorySegment memoryInfo, String inputName, String outputName, Path modelFile) {
		this.api = api;
		this.session = session;
		this.memoryInfo = memoryInfo;
		this.inputName = inputName;
		this.outputName = outputName;
		this.modelFile = modelFile;
	}

	/**
	 * Opens the pinned model (loading ONNX Runtime if needed).
	 *
	 * @param modelFile the installed {@code realesr-general-x4v3.onnx}
	 * @throws IllegalArgumentException when the file is missing
	 * @throws IllegalStateException    when the runtime or the model cannot be bound
	 */
	public static SuperResolutionEngine open(Path modelFile) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("super-resolution model missing: " + modelFile);
		}
		OnnxRuntimeApi api = OnnxRuntimeApi.bind();
		MemorySegment session = null;
		MemorySegment memoryInfo = null;
		try {
			MemorySegment env = api.createEnv();
			session = api.createSession(env, modelFile, THREADS);
			memoryInfo = api.createCpuMemoryInfo();
			MemorySegment allocator = api.defaultAllocator();
			String inputName = api.sessionInputName(session, allocator, 0);
			String outputName = api.sessionOutputName(session, allocator, 0);
			if (api.sessionInputCount(session) != 1 || api.sessionOutputCount(session) != 1) {
				throw new IllegalArgumentException(modelFile + " must have exactly one input and"
						+ " one output");
			}
			return new SuperResolutionEngine(api, session, memoryInfo, inputName, outputName,
					modelFile);
		} catch (RuntimeException error) {
			if (memoryInfo != null) {
				api.releaseMemoryInfo(memoryInfo);
			}
			if (session != null) {
				api.releaseSession(session);
			}
			api.close();
			throw error;
		}
	}

	/**
	 * The 4&times; upscale of {@code frame}'s visible window for one deep-zoom frame, or
	 * {@code null} when the frame should not be upscaled (mild zoom, no visible window) or the
	 * inference failed (the frame then draws from the plain source and never fails the export).
	 *
	 * <p>The returned window always contains the visible one; the caller maps it through the same
	 * source-to-box matrix the plain source would use, so the upscaled pixels land exactly where
	 * the stretched crop would have been.</p>
	 *
	 * @param frame        the decoded source frame for this frame slot
	 * @param source       the frame's extent in its own coordinate space
	 * @param destination  the video box on the canvas, before the camera transform
	 * @param transform    the camera transform applied around the box
	 * @param canvasWidth  output canvas width
	 * @param canvasHeight output canvas height
	 */
	public @Nullable Upscaled upscaleFor(RgbaFrame frame, Rect source, Rect destination,
			ZoomTransform.AppliedTransform transform, int canvasWidth, int canvasHeight) {
		if (closed) {
			throw new IllegalStateException("super-resolution engine is closed");
		}
		Crop visible = SuperResolution.cropFor(source, destination, transform, canvasWidth,
				canvasHeight);
		if (visible == null) {
			return null;
		}
		Crop fetch = SuperResolution.padded(visible, source);
		RgbaFrame upscaled;
		if (cachedCrop != null && cachedUpscaled != null && cachedCrop.contains(visible)) {
			cacheHits++;
			upscaled = cachedUpscaled;
		} else {
			upscaled = infer(frame, fetch);
			if (upscaled == null) {
				return null;
			}
		}
		return new Upscaled(fetch, upscaled);
	}

	/**
	 * One upscaled window: the padded source crop it was inferred from and the 4&times; pixels.
	 *
	 * @param crop     the source window, in source pixels
	 * @param upscaled the RGBA pixels, {@code crop.width() * 4} by {@code crop.height() * 4}
	 */
	public record Upscaled(Crop crop, RgbaFrame upscaled) {
	}

	private @Nullable RgbaFrame infer(RgbaFrame frame, Crop crop) {
		long started = System.nanoTime();
		try {
			ByteBuffer window = SuperResolution.copyWindow(frame.pixelsTopFirstRgba(),
					frame.width(), crop);
			float[] chw = SuperResolution.toChw(window, crop.width(), crop.height());
			MemorySegment[] outputs;
			// The tensor data must be native memory: ORT keeps the pointer for the duration of the
			// run, and a heap segment has no stable address to hand it. One confined arena per
			// inference is the simplest correct lifetime; the allocation is trivial next to the
			// inference itself.
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_FLOAT, chw);
				var input = new OnnxRuntimeApi.Input(inputName,
						onnxruntime_h.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT(),
						new long[] {1, 3, crop.height(), crop.width()}, data);
				outputs = api.run(session, List.of(input), List.of(outputName), memoryInfo);
			}
			try {
				long[] shape = api.valueShape(outputs[0]);
				if (shape.length != 4 || shape[2] <= 0 || shape[3] <= 0) {
					throw new IllegalStateException(
							"unexpected SR output shape " + java.util.Arrays.toString(shape));
				}
				int upHeight = Math.toIntExact(shape[2]);
				int upWidth = Math.toIntExact(shape[3]);
				float[] out = api.getTensorFloats(outputs[0], 3L * upWidth * upHeight);
				RgbaFrame result = new RgbaFrame(upWidth, upHeight,
						SuperResolution.fromChw(out, upWidth, upHeight));
				cachedCrop = crop;
				cachedUpscaled = result;
				inferenceCount++;
				totalInferenceNanos += System.nanoTime() - started;
				LOG.log(System.Logger.Level.DEBUG, () -> "SR " + crop.width() + "x"
						+ crop.height() + " -> " + upWidth + "x" + upHeight + " in "
						+ (System.nanoTime() - started) / 1_000_000 + " ms");
				return result;
			} finally {
				api.releaseValue(outputs[0]);
			}
		} catch (RuntimeException error) {
			// A failed frame must not fail the export: drop the cache and let the frame draw from
			// the source, exactly like an uninstalled model.
			cachedCrop = null;
			cachedUpscaled = null;
			LOG.log(System.Logger.Level.WARNING,
					"super-resolution inference failed, drawing the source: " + error);
			return null;
		}
	}

	/** Inferences actually run (tests and the export report). */
	public long inferenceCount() {
		return inferenceCount;
	}

	/**
	 * Drops the cached window and the counters, for a fresh pass over the timeline (the GIF
	 * exporter's palette run, a reused provider). The next deep zoom re-infers rather than handing
	 * out a window from the previous run.
	 */
	public void reset() {
		cachedCrop = null;
		cachedUpscaled = null;
		inferenceCount = 0;
		cacheHits = 0;
		totalInferenceNanos = 0;
	}

	/** Frames served from the cached window without an inference. */
	public long cacheHits() {
		return cacheHits;
	}

	/** Wall-clock nanoseconds spent inside the network, for the export report. */
	public long totalInferenceNanos() {
		return totalInferenceNanos;
	}

	public Path modelFile() {
		return modelFile;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		cachedCrop = null;
		cachedUpscaled = null;
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(session);
		api.close();
	}
}
