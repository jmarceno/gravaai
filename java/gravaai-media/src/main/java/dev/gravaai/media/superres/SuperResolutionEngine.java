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
import dev.gravaai.media.onnx.OnnxEpDevice;
import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;
import dev.gravaai.media.onnx.gen.onnxruntime_h;
import dev.gravaai.media.superres.SuperResolution.Crop;
import io.github.humbleui.types.Rect;

/**
 * The optional deep-zoom super-resolution pass: runs the pinned Real-ESRGAN x4 network over the
 * source window a deeply zoomed camera shows, so an exported 5&times; stage has real detail instead
 * of a stretched 1080p crop.
 *
 * <p><b>Export only.</b> One 5&times; window of a 1080p source costs 1.3–1.7 s on the bundled ONNX
 * Runtime (CPU) and 1.2–1.3 s on a GPU, which is fine inside an export and impossible in a 60 fps
 * preview; the preview keeps the compositor's cheap {@link dev.gravaai.media.compose.ZoomSharpen}
 * pass. Both paths draw through the same compositor, so an export with this engine simply has a
 * sharper texture for the same frames.</p>
 *
 * <p><b>GPU first, CPU guaranteed.</b> The engine runs on the vendor-neutral WebGPU plugin
 * execution provider (Dawn over Vulkan on Linux) whenever one enumerated a device, and on the
 * built-in CPU EP otherwise. The GPU is preferred even where its wall clock is a little slower
 * (above ~260k px on the baseline RX 560) because it uses a fraction of a core instead of eight,
 * which matters to the decode/composite/encode stages running alongside it — the measured numbers
 * are in {@link dev.gravaai.media.onnx.OnnxProviderSelection}. A GPU session that cannot be created,
 * or whose inference fails, is replaced by a CPU session for the rest of the run, so an export
 * never fails because the GPU path is broken.</p>
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
	 * Intra-op threads for the CPU upscaler. The network is small and fully convolutional, so it
	 * scales well up to a point: 8 threads measured ~0.5 s on a 5&times; 1080p window, 12 no
	 * better, and leaving it to the ORT default oversubscribes the export's GL and decode threads.
	 * A GPU session gets no intra-op pool (see {@code OnnxRuntimeApi.createSession}).
	 */
	private static final int THREADS = 8;

	/** Cap on session re-opens after a GPU failure: one retry on the CPU, then the source stays. */
	private static final int MAX_REOPEN_ATTEMPTS = 2;

	private final OnnxRuntimeApi api;
	private final MemorySegment env;
	private final Path modelFile;
	/** The provider the current session runs on; {@link #session} must match it. */
	private OnnxExecutionProvider provider;
	private MemorySegment session;
	private MemorySegment memoryInfo;
	private String inputName;
	private String outputName;
	private int reopened;
	private boolean closed;

	// The cached upscaled window: the padded source crop it covers, and the pixels at 4x.
	private @Nullable Crop cachedCrop;
	private @Nullable RgbaFrame cachedUpscaled;
	/** The source pixels the cached window was inferred from, to tell a held frame from a new one. */
	private @Nullable ByteBuffer cachedWindow;
	private long inferenceCount;
	private long cacheHits;
	private long totalInferenceNanos;

	private SuperResolutionEngine(OnnxRuntimeApi api, MemorySegment env, Path modelFile,
			OnnxExecutionProvider provider, MemorySegment session,
			MemorySegment memoryInfo, String inputName, String outputName) {
		this.api = api;
		this.env = env;
		this.modelFile = modelFile;
		this.provider = provider;
		this.session = session;
		this.memoryInfo = memoryInfo;
		this.inputName = inputName;
		this.outputName = outputName;
	}

	/**
	 * Opens the pinned model on the best available execution provider (loading ONNX Runtime if
	 * needed).
	 *
	 * <p>A GPU session that cannot be created (the device disappeared between probe and open, the
	 * append is rejected) is reported and reopened on the CPU, so a present model still runs.</p>
	 *
	 * @param modelFile the installed {@code realesr-general-x4v3.onnx}
	 * @throws IllegalArgumentException when the file is missing
	 * @throws IllegalStateException    when the runtime or the model cannot be bound on any provider
	 */
	public static SuperResolutionEngine open(Path modelFile) {
		return open(modelFile, OnnxWebGpuPlugin.probe());
	}

	/**
	 * Opens the model on the provider a {@link OnnxWebGpuPlugin.Selection} names. Used by tests to
	 * force the CPU or the GPU path; production code calls {@link #open(Path)}.
	 */
	public static SuperResolutionEngine open(Path modelFile, OnnxWebGpuPlugin.Selection selection) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("super-resolution model missing: " + modelFile);
		}
		OnnxRuntimeApi api = OnnxRuntimeApi.bind();
		MemorySegment env = null;
		MemorySegment session = null;
		MemorySegment memoryInfo = null;
		OnnxExecutionProvider provider = selection.provider();
		java.util.Optional<OnnxEpDevice> device = selection.device();
		try {
			env = api.createEnv();
			if (device.isPresent()) {
				MemorySegment gpuSession = null;
				try {
					gpuSession = api.createSession(env, modelFile, THREADS, provider,
							device.get());
					assertSessionOn(api, gpuSession, provider, device.get());
					session = gpuSession;
				} catch (RuntimeException gpuFailure) {
					// Presence is not proof: a rejected append or a session that silently landed on
					// the CPU EP is a GPU miss, and the CPU path is the guaranteed fallback.
					LOG.log(System.Logger.Level.WARNING, "super-resolution WebGPU session failed,"
							+ " falling back to the CPU: " + gpuFailure);
					if (gpuSession != null) {
						api.releaseSession(gpuSession);
					}
					provider = OnnxExecutionProvider.CPU;
				}
			}
			if (session == null) {
				session = api.createSession(env, modelFile, THREADS, provider);
			}
			memoryInfo = api.createCpuMemoryInfo();
			MemorySegment allocator = api.defaultAllocator();
			String inputName = api.sessionInputName(session, allocator, 0);
			String outputName = api.sessionOutputName(session, allocator, 0);
			if (api.sessionInputCount(session) != 1 || api.sessionOutputCount(session) != 1) {
				throw new IllegalArgumentException(modelFile + " must have exactly one input and"
						+ " one output");
			}
			return new SuperResolutionEngine(api, env, modelFile, provider, session,
					memoryInfo, inputName, outputName);
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
	 * Confirms the session was really bound to the requested provider.
	 *
	 * <p>Appending a provider can succeed while the graph still lands on the CPU EP (an
	 * unsupported op with CPU fallback enabled), and a session that only claims the GPU in its
	 * options is exactly the silent fallback this feature must not ship. Reading the device bound
	 * to the input tensor answers where the session actually runs.</p>
	 */
	private static void assertSessionOn(OnnxRuntimeApi api, MemorySegment session,
			OnnxExecutionProvider expected, OnnxEpDevice device) {
		java.util.Optional<String> reported = api.sessionInputProvider(session);
		if (reported.isEmpty()) {
			// The runtime had no answer: keep the GPU session (the inference itself is the proof).
			LOG.log(System.Logger.Level.DEBUG,
					"session did not report its execution provider; assuming requested");
			return;
		}
		String actual = reported.get();
		if (!actual.equals(device.epName())) {
			throw new IllegalStateException("session bound to " + actual + " instead of "
					+ expected.kind().runtimeName());
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
		Crop cached = cachedCrop;
		RgbaFrame cachedPixels = cachedUpscaled;
		ByteBuffer cachedSource = cachedWindow;
		// A hit needs the same pixels, not just the same place: the video keeps playing under a
		// held zoom, and a window inferred from an earlier frame would freeze it. The hit also
		// reports the window the cached pixels were inferred from (not this frame's padded one),
		// because that is where they belong on screen.
		if (cached != null && cachedPixels != null && cachedSource != null
				&& cached.contains(visible)
				&& SuperResolution.windowMatches(frame.pixelsTopFirstRgba(), frame.width(),
						frame.height(), cached, cachedSource)) {
			cacheHits++;
			return new Upscaled(cached, cachedPixels);
		}
		Crop fetch = SuperResolution.padded(visible, source);
		RgbaFrame upscaled = infer(frame, fetch);
		return upscaled == null ? null : new Upscaled(fetch, upscaled);
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
		if (session.address() == 0) {
			// A failed CPU fallback left no session: every remaining deep-zoom frame draws the
			// source instead of retrying an inference that has nowhere to run.
			return null;
		}
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
				cachedWindow = window;
				inferenceCount++;
				totalInferenceNanos += System.nanoTime() - started;
				LOG.log(System.Logger.Level.DEBUG, () -> "SR " + crop.width() + "x"
						+ crop.height() + " -> " + upWidth + "x" + upHeight + " in "
						+ (System.nanoTime() - started) / 1_000_000 + " ms on "
						+ provider.kind().label());
				return result;
			} finally {
				api.releaseValue(outputs[0]);
			}
		} catch (RuntimeException error) {
			// A failed frame must not fail the export: drop the cache and let the frame draw from
			// the source, exactly like an uninstalled model.
			cachedCrop = null;
			cachedUpscaled = null;
			cachedWindow = null;
			// A GPU session that fails at inference is the one case where presence was proved
			// wrong: rebuild the session on the CPU once, so the rest of the export still sharpens
			// instead of drawing the raw stretch for every remaining deep-zoom frame.
			if (provider.hardware() && reopened < MAX_REOPEN_ATTEMPTS) {
				LOG.log(System.Logger.Level.WARNING, "super-resolution WebGPU inference failed,"
						+ " moving the rest of this export to the CPU: " + error);
				reopenOnCpu();
				return null;
			}
			LOG.log(System.Logger.Level.WARNING,
					"super-resolution inference failed, drawing the source: " + error);
			return null;
		}
	}

	/**
	 * Replaces a broken GPU session with a CPU one. The next deep-zoom frame infers on the CPU;
	 * this frame still draws the source, because the failed run's output is gone.
	 */
	private void reopenOnCpu() {
		reopened++;
		// Release the old handles and clear them first: if the CPU session cannot be created, the
		// engine must not keep pointers that were already released (close() would free them twice
		// and the next inference would Run on a dangling session).
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(session);
		session = MemorySegment.NULL;
		memoryInfo = MemorySegment.NULL;
		try {
			// Keep the export's env: it is unrelated to the GPU failure and sessions share it.
			MemorySegment cpuSession = api.createSession(env, modelFile, THREADS,
					OnnxExecutionProvider.CPU);
			MemorySegment cpuMemoryInfo = api.createCpuMemoryInfo();
			MemorySegment allocator = api.defaultAllocator();
			inputName = api.sessionInputName(cpuSession, allocator, 0);
			outputName = api.sessionOutputName(cpuSession, allocator, 0);
			session = cpuSession;
			memoryInfo = cpuMemoryInfo;
			provider = OnnxExecutionProvider.CPU;
		} catch (RuntimeException error) {
			// Nothing left to fall back to: stop retrying and let every remaining deep-zoom frame
			// draw the source. The null handles make close() a no-op.
			reopened = MAX_REOPEN_ATTEMPTS;
			LOG.log(System.Logger.Level.WARNING,
					"super-resolution CPU fallback failed, drawing the source: " + error);
		}
	}

	/** The execution provider this engine's current session runs on. */
	public OnnxExecutionProvider provider() {
		return provider;
	}

	/** Whether the session is running on a GPU provider (what the export report states). */
	public boolean hardwareAccelerated() {
		return provider.hardware();
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
		cachedWindow = null;
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
		cachedWindow = null;
		// A failed CPU fallback leaves both handles null; releasing a null handle would crash.
		if (memoryInfo.address() != 0) {
			api.releaseMemoryInfo(memoryInfo);
			memoryInfo = MemorySegment.NULL;
		}
		if (session.address() != 0) {
			api.releaseSession(session);
			session = MemorySegment.NULL;
		}
		api.close();
	}
}
