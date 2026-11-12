package dev.gravaai.media.gaze;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.gravaai.media.onnx.OnnxEpDevice;
import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * One ONNX Runtime session of the eye-contact pipeline, opened GPU-first.
 *
 * <p>The session is created on the WebGPU plugin execution provider when the process has a WebGPU
 * device, with the CPU-EP fallback disabled so a graph the GPU cannot fully claim fails loudly
 * instead of silently splitting onto the CPU; the provider the input tensor is actually bound to
 * is then checked (presence of the provider in the options is not proof). Any GPU miss reopens
 * the model on the built-in CPU provider, which cannot be unavailable. {@link #provider()} reports
 * where the session really runs.</p>
 *
 * <p>Single caller, like every ORT session in the app: one instance belongs to one pipeline.</p>
 */
final class GazeSession implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(GazeSession.class.getName());

	private final OnnxRuntimeApi api;
	private final MemorySegment session;
	private final MemorySegment memoryInfo;
	private final OnnxExecutionProvider provider;
	private final List<String> inputs;
	private final List<String> outputs;
	private boolean closed;

	private GazeSession(OnnxRuntimeApi api, MemorySegment session, MemorySegment memoryInfo,
			OnnxExecutionProvider provider, List<String> inputs, List<String> outputs) {
		this.api = api;
		this.session = session;
		this.memoryInfo = memoryInfo;
		this.provider = provider;
		this.inputs = List.copyOf(inputs);
		this.outputs = List.copyOf(outputs);
	}

	/**
	 * Opens {@code modelFile} on the selection's provider, falling back to the CPU.
	 *
	 * @param cpuThreads intra-op threads when the session lands on the CPU
	 */
	static GazeSession open(Path modelFile, OnnxWebGpuPlugin.Selection selection,
			int cpuThreads) {
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("eye-contact model missing: " + modelFile);
		}
		OnnxRuntimeApi api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		MemorySegment session = MemorySegment.NULL;
		OnnxExecutionProvider provider = OnnxExecutionProvider.CPU;
		Optional<OnnxEpDevice> device = selection.device();
		if (device.isPresent()) {
			MemorySegment gpuSession = MemorySegment.NULL;
			try {
				gpuSession = api.createSession(env, modelFile, cpuThreads, selection.provider(),
						device.get());
				Optional<String> bound = api.sessionInputProvider(gpuSession);
				if (bound.isPresent() && !bound.get().equals(device.get().epName())) {
					throw new IllegalStateException("session bound to " + bound.get());
				}
				session = gpuSession;
				provider = selection.provider();
			} catch (RuntimeException gpuMiss) {
				LOG.log(System.Logger.Level.INFO, modelFile.getFileName()
						+ " cannot run on the GPU, using the CPU: " + gpuMiss.getMessage());
				if (gpuSession.address() != 0) {
					api.releaseSession(gpuSession);
				}
			}
		}
		if (session.address() == 0) {
			session = api.createSession(env, modelFile, cpuThreads, OnnxExecutionProvider.CPU);
		}
		MemorySegment allocator = api.defaultAllocator();
		List<String> inputs = new ArrayList<>();
		for (long i = 0; i < api.sessionInputCount(session); i++) {
			inputs.add(api.sessionInputName(session, allocator, i));
		}
		List<String> outputs = new ArrayList<>();
		for (long i = 0; i < api.sessionOutputCount(session); i++) {
			outputs.add(api.sessionOutputName(session, allocator, i));
		}
		LOG.log(System.Logger.Level.INFO, modelFile.getFileName() + " on " + provider.label()
				+ " outputs " + outputs);
		return new GazeSession(api, session, api.createCpuMemoryInfo(), provider, inputs,
				outputs);
	}

	/** Where the session really runs. */
	OnnxExecutionProvider provider() {
		return provider;
	}

	/** Number of graph outputs. */
	int outputCount() {
		return outputs.size();
	}

	/** The graph's input names, in order. */
	List<String> inputs() {
		return inputs;
	}

	/**
	 * The index of the output named {@code prefix}, else of the first one whose name starts with it
	 * (TF exports suffix names with {@code :0}).
	 */
	int outputIndex(String prefix) {
		int exact = outputs.indexOf(prefix);
		if (exact >= 0) {
			return exact;
		}
		for (int i = 0; i < outputs.size(); i++) {
			if (outputs.get(i).startsWith(prefix)) {
				return i;
			}
		}
		throw new IllegalArgumentException("model has no output named " + prefix + "*: "
				+ outputs);
	}

	/** The name of the first input whose name starts with {@code prefix}. */
	String input(String prefix) {
		for (String name : inputs) {
			if (name.startsWith(prefix)) {
				return name;
			}
		}
		throw new IllegalArgumentException("model has no input named " + prefix + "*: " + inputs);
	}

	/**
	 * Runs the graph and copies the requested outputs out as float arrays.
	 *
	 * @param outputCounts element count of each output, in output order; an entry of 0 skips it
	 */
	float[][] run(List<OnnxRuntimeApi.NamedFloat> feeds, long... outputCounts) {
		if (closed) {
			throw new IllegalStateException("eye-contact session is closed");
		}
		if (outputCounts.length != outputs.size()) {
			throw new IllegalArgumentException("expected " + outputs.size() + " output counts");
		}
		MemorySegment[] values = api.runFloat(session, memoryInfo, feeds, outputs);
		try {
			float[][] result = new float[values.length][];
			for (int i = 0; i < values.length; i++) {
				result[i] = outputCounts[i] == 0 ? new float[0]
						: api.getTensorFloats(values[i], outputCounts[i]);
			}
			return result;
		} finally {
			for (MemorySegment value : values) {
				api.releaseValue(value);
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
	}
}
