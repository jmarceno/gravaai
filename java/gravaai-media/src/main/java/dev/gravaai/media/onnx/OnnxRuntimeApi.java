package dev.gravaai.media.onnx;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.onnx.gen.OrtApi;
import dev.gravaai.media.onnx.gen.OrtApiBase;
import dev.gravaai.media.onnx.gen.OrtAllocator;
import dev.gravaai.media.onnx.gen.onnxruntime_h;

/**
 * Typed binding over the ONNX Runtime C API (selfie/MODNet segmentation, the auto-mask detectors).
 *
 * <p>The C API is a struct of function pointers ({@code OrtApi}): jextract generates a getter
 * per field plus nested invoker classes; this wrapper resolves
 * {@code OrtGetApiBase()->GetApi(ORT_API_VERSION)} once and exposes the handful of entry points
 * the models use with typed signatures and status checking. One instance per process;
 * sessions outlive it via {@link #close()} (only when the owner is done with every session —
 * the env must outlive them, so {@code close()} is the owner's last call).</p>
 *
 * <p>The ORT_* enum constants are ABI-stable values from onnxruntime_c_api.h; jextract only
 * emits {@code #define}s and simple enum constants, and these enums have no explicit values.</p>
 *
 * <p>{@link #runSingle} is the original single-input/single-output float path the segmenters use;
 * {@link #run} is the general multi-input/multi-output form the face/OCR/PII detectors need
 * (YuNet has twelve outputs and GLiNER takes token-id tensors).</p>
 */
public final class OnnxRuntimeApi implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(OnnxRuntimeApi.class.getName());

	/** OrtLoggingLevel: error messages only (the segmenter runs per frame; silence the rest). */
	private static final int ORT_LOGGING_LEVEL_ERROR = 3;
	/** OrtAllocatorType: the standard device allocator CreateCpuMemoryInfo is called with. */
	private static final int ORT_DEVICE_ALLOCATOR = 0;
	/** OrtMemType: the default memory type for CPU input tensors. */
	private static final int ORT_MEM_TYPE_DEFAULT = 0;
	/**
	 * ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64. Not emitted by jextract
	 * (only FLOAT came through), but the value is ABI-frozen in onnxruntime_c_api.h.
	 */
	public static final int ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 = 7;
	/** ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL (one byte per element). */
	public static final int ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL = 9;

	/** Cap on a metadata value's scanned length (the OCR alphabet is a few kilobytes). */
	private static final int MAX_METADATA_BYTES = 1 << 20;
	/** Cap on a model I/O name; ORT names are short identifiers. */
	private static final int MAX_IO_NAME_BYTES = 4096;

	private final MemorySegment api;
	/** The OrtEnv backing every session; released in {@link #close()}. */
	private @Nullable MemorySegment env;

	private OnnxRuntimeApi(MemorySegment api) {
		this.api = api;
	}

	/**
	 * Binds the ONNX Runtime API (loading the library if needed).
	 *
	 * @throws IllegalStateException when the library or the API function table is unavailable
	 */
	public static OnnxRuntimeApi bind() {
		OnnxLibs.ensureLoaded();
		MemorySegment base = onnxruntime_h.OrtGetApiBase();
		if (base == null || base.address() == 0) {
			throw new IllegalStateException("OrtGetApiBase returned null");
		}
		MemorySegment getApi = OrtApiBase.GetApi(base);
		MemorySegment api = OrtApiBase.GetApi.invoke(getApi, onnxruntime_h.ORT_API_VERSION());
		if (api.address() == 0) {
			throw new IllegalStateException("OrtApiBase::GetApi(" + onnxruntime_h.ORT_API_VERSION()
					+ ") returned null: the bundled libonnxruntime.so is older than the bindings");
		}
		return new OnnxRuntimeApi(api);
	}

	/** Creates the ORT environment (error-level logging, "gravaai" logger id). */
	public MemorySegment createEnv() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = cString(arena, "gravaai");
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.CreateEnv.invoke(OrtApi.CreateEnv(api), ORT_LOGGING_LEVEL_ERROR,
					name, out), "CreateEnv");
			MemorySegment created = out.get(ValueLayout.ADDRESS, 0);
			if (created.address() == 0) {
				throw new IllegalStateException("ONNX Runtime CreateEnv produced a null OrtEnv");
			}
			env = created;
			return created;
		}
	}

	/**
	 * Loads a model file into a session, limiting intra-op parallelism to {@code threads}.
	 *
	 * <p>ORT defaults intra-op threads to the core count, so one 256x256 inference fans out across
	 * every core. On the preview path that inference shares the process with the audio pump and the
	 * GL thread, and the default pool both oversubscribes them and pays thread wake-up latency per
	 * frame. The segmenter is small enough to be bound by launch overhead rather than FLOPs, so a
	 * small fixed pool is both faster and far kinder to the other threads.
	 *
	 * @param threads intra-op thread count, or 0 for the ORT default
	 */
	public MemorySegment createSession(MemorySegment env, java.nio.file.Path modelFile,
			int threads) {
		if (threads < 0) {
			throw new IllegalArgumentException("threads must not be negative: " + threads);
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment path = cString(arena, modelFile.toAbsolutePath().toString());
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			if (threads == 0) {
				checkStatus(OrtApi.CreateSession.invoke(OrtApi.CreateSession(api), env, path,
						MemorySegment.NULL, out), "CreateSession(" + modelFile + ")");
			} else {
				MemorySegment optionsOut = arena.allocate(ValueLayout.ADDRESS);
				checkStatus(OrtApi.CreateSessionOptions.invoke(
						OrtApi.CreateSessionOptions(api), optionsOut), "CreateSessionOptions");
				MemorySegment options = optionsOut.get(ValueLayout.ADDRESS, 0);
				try {
					checkStatus(OrtApi.SetIntraOpNumThreads.invoke(
							OrtApi.SetIntraOpNumThreads(api), options, threads),
							"SetIntraOpNumThreads(" + threads + ")");
					// Inter-op parallelism would open a second pool on top of the intra-op one; one
					// worker is enough for this graph and keeps a frame's thread count predictable.
					checkStatus(OrtApi.SetInterOpNumThreads.invoke(
							OrtApi.SetInterOpNumThreads(api), options, 1),
							"SetInterOpNumThreads(1)");
					checkStatus(OrtApi.CreateSession.invoke(OrtApi.CreateSession(api), env, path,
							options, out), "CreateSession(" + modelFile + ")");
				} finally {
					OrtApi.ReleaseSessionOptions.invoke(OrtApi.ReleaseSessionOptions(api), options);
				}
			}
			MemorySegment created = out.get(ValueLayout.ADDRESS, 0);
			if (created.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime CreateSession produced a null OrtSession");
			}
			return created;
		}
	}

	/**
	 * Loads a model file into a session with the ORT default session options (thread pool left at
	 * the ORT default). Callers that run per-frame inference should use
	 * {@link #createSession(MemorySegment, java.nio.file.Path, int)} instead.
	 */
	public MemorySegment createSession(MemorySegment env, java.nio.file.Path modelFile) {
		return createSession(env, modelFile, 0);
	}

	/** The CPU memory info the input tensors are created with. */
	public MemorySegment createCpuMemoryInfo() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.CreateCpuMemoryInfo.invoke(OrtApi.CreateCpuMemoryInfo(api),
					ORT_DEVICE_ALLOCATOR, ORT_MEM_TYPE_DEFAULT, out), "CreateCpuMemoryInfo");
			MemorySegment created = out.get(ValueLayout.ADDRESS, 0);
			if (created.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime CreateCpuMemoryInfo produced a null OrtMemoryInfo");
			}
			return created;
		}
	}

	/** The default allocator (model I/O names are read and freed through it). */
	public MemorySegment defaultAllocator() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.GetAllocatorWithDefaultOptions.invoke(
					OrtApi.GetAllocatorWithDefaultOptions(api), out), "GetAllocatorWithDefaultOptions");
			MemorySegment allocator = out.get(ValueLayout.ADDRESS, 0);
			if (allocator.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime GetAllocatorWithDefaultOptions produced a null OrtAllocator");
			}
			// The address comes back as an unbounded segment; the allocator's Free function
			// pointer is read through it (OrtAllocator::Free(self, p)).
			return allocator.reinterpret(OrtAllocator.layout().byteSize());
		}
	}

	/** The number of model inputs (a well-formed segmenter model has exactly one). */
	public long sessionInputCount(MemorySegment session) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
			checkStatus(OrtApi.SessionGetInputCount.invoke(OrtApi.SessionGetInputCount(api),
					session, out), "SessionGetInputCount");
			return out.get(ValueLayout.JAVA_LONG, 0);
		}
	}

	/** The number of model outputs. */
	public long sessionOutputCount(MemorySegment session) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
			checkStatus(OrtApi.SessionGetOutputCount.invoke(OrtApi.SessionGetOutputCount(api),
					session, out), "SessionGetOutputCount");
			return out.get(ValueLayout.JAVA_LONG, 0);
		}
	}

	/** Model input name at {@code index} (copied to a Java string; the C copy is freed). */
	public String sessionInputName(MemorySegment session, MemorySegment allocator, long index) {
		return sessionNodeName(session, allocator, index, true);
	}

	/** Model output name at {@code index}. */
	public String sessionOutputName(MemorySegment session, MemorySegment allocator, long index) {
		return sessionNodeName(session, allocator, index, false);
	}

	/** The input tensor shape at {@code index} (one entry per dimension). */
	public long[] sessionInputShape(MemorySegment session, long index) {
		return sessionTensorShape(session, index, true);
	}

	/** The output tensor shape at {@code index}. */
	public long[] sessionOutputShape(MemorySegment session, long index) {
		return sessionTensorShape(session, index, false);
	}

	private long[] sessionTensorShape(MemorySegment session, long index, boolean input) {
		try (Arena arena = Arena.ofConfined()) {
			// Both shapes go through the type info: the C API has no SessionGetOutputShape, and
			// jextract skipped SessionGetInputShape's accessor outright (one of the opaque
			// forward-declared types it cannot lay out), while the type-info path is generated.
			MemorySegment typeOut = arena.allocate(ValueLayout.ADDRESS);
			sessionTypeInfo(session, index, input, typeOut);
			MemorySegment typeInfo = typeOut.get(ValueLayout.ADDRESS, 0);
			if (typeInfo.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime session type info produced a null type info");
			}
			try {
				MemorySegment tensorOut = arena.allocate(ValueLayout.ADDRESS);
				checkStatus(OrtApi.CastTypeInfoToTensorInfo.invoke(
						OrtApi.CastTypeInfoToTensorInfo(api), typeInfo, tensorOut),
						"CastTypeInfoToTensorInfo");
				MemorySegment info = tensorOut.get(ValueLayout.ADDRESS, 0);
				if (info.address() == 0) {
					throw new IllegalStateException("model "
							+ (input ? "input" : "output") + " is not a tensor");
				}
				return tensorInfoDimensions(info);
			} finally {
				releaseTypeInfo(typeInfo);
			}
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** Writes the {@code OrtTypeInfo*} for one model input/output into {@code out}. */
	private void sessionTypeInfo(MemorySegment session, long index, boolean input,
			MemorySegment out) {
		if (input) {
			checkStatus(OrtApi.SessionGetInputTypeInfo.invoke(
					OrtApi.SessionGetInputTypeInfo(api), session, index, out),
					"SessionGetInputTypeInfo");
		} else {
			checkStatus(OrtApi.SessionGetOutputTypeInfo.invoke(
					OrtApi.SessionGetOutputTypeInfo(api), session, index, out),
					"SessionGetOutputTypeInfo");
		}
	}

	/** Bytes per element for a tensor element type we feed (float32 or int64). */
	private static long elementBytes(Input input) {
		return input.elementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64
				? Long.BYTES : Float.BYTES;
	}

	/** The element count of a shape (the product of its dimensions). */
	private static long elementCount(long[] shape) {
		long count = 1;
		for (long dim : shape) {
			count = Math.multiplyExact(count, dim);
		}
		return count;
	}

	/**
	 * Runs the model with one float32 input tensor (the data segment is borrowed by ORT for the
	 * duration of the call) and returns the single output value; release it with
	 * {@link #releaseValue}.
	 */
	public MemorySegment runSingle(MemorySegment session, String inputName, MemorySegment inputData,
			long inputDataBytes, long[] shape, MemorySegment memoryInfo, String outputName) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment shapeSegment = arena.allocate(ValueLayout.JAVA_LONG, shape.length);
			for (int i = 0; i < shape.length; i++) {
				shapeSegment.setAtIndex(ValueLayout.JAVA_LONG, i, shape[i]);
			}
			MemorySegment inNames = arena.allocate(ValueLayout.ADDRESS);
			inNames.setAtIndex(ValueLayout.ADDRESS, 0, cString(arena, inputName));
			MemorySegment outNames = arena.allocate(ValueLayout.ADDRESS);
			outNames.setAtIndex(ValueLayout.ADDRESS, 0, cString(arena, outputName));
			MemorySegment inputs = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.CreateTensorWithDataAsOrtValue.invoke(
					OrtApi.CreateTensorWithDataAsOrtValue(api), memoryInfo, inputData,
					inputDataBytes, shapeSegment, shape.length,
					onnxruntime_h.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT(), inputs),
					"CreateTensorWithDataAsOrtValue");
			MemorySegment input = inputs.getAtIndex(ValueLayout.ADDRESS, 0);
			inputs.setAtIndex(ValueLayout.ADDRESS, 0, input);
			checkStatus(OrtApi.Run.invoke(OrtApi.Run(api), session, MemorySegment.NULL, inNames,
					inputs, 1, outNames, 1, out), "Run");
			MemorySegment output = out.get(ValueLayout.ADDRESS, 0);
			if (output.address() == 0) {
				throw new IllegalStateException("ONNX Runtime Run produced a null output value");
			}
			return output;
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/**
	 * One input of a general {@link #run} call: a tensor name, element type, shape and its data.
	 *
	 * <p>{@code data} is a native float array (float32 tensors) or a native long array (int64
	 * tensors); the primitive-array overloads on {@link OnnxRuntimeApi#run} copy into the arena
	 * the run owns. Element type is one of the {@code ONNX_TENSOR_ELEMENT_DATA_TYPE_*} values.</p>
	 */
	public record Input(String name, int elementType, long[] shape, MemorySegment data) {

		public Input {
			if (name.isBlank()) {
				throw new IllegalArgumentException("tensor name must not be blank");
			}
			shape = shape.clone();
			long elements = 1;
			for (long dim : shape) {
				if (dim <= 0) {
					throw new IllegalArgumentException("tensor " + name + " has a non-positive"
							+ " dimension: " + java.util.Arrays.toString(shape));
				}
				elements = Math.multiplyExact(elements, dim);
			}
			long bytes = elements * switch (elementType) {
				case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 -> Long.BYTES;
				case ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL -> 1;
				default -> Float.BYTES;
			};
			if (data.byteSize() < bytes) {
				throw new IllegalArgumentException("tensor " + name + " holds " + data.byteSize()
						+ " bytes, need " + bytes + " for " + java.util.Arrays.toString(shape));
			}
		}
	}

	/**
	 * Runs a session with any number of inputs and outputs. Output values are caller-owned: release
	 * each with {@link #releaseValue} (preferably in a finally block around the whole call).
	 *
	 * <p>The input data segments are borrowed by ORT for the duration of the call, so they must be
	 * alive in the caller's arena. Output order matches {@code outputNames}.</p>
	 */
	public MemorySegment[] run(MemorySegment session, List<Input> inputs, List<String> outputNames,
			MemorySegment memoryInfo) {
		if (inputs.isEmpty() || outputNames.isEmpty()) {
			throw new IllegalArgumentException("run needs at least one input and one output");
		}
		MemorySegment[] inputValues = new MemorySegment[inputs.size()];
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment inNames = arena.allocate(ValueLayout.ADDRESS, inputs.size());
			MemorySegment inputArray = arena.allocate(ValueLayout.ADDRESS, inputs.size());
			for (int i = 0; i < inputs.size(); i++) {
				Input input = inputs.get(i);
				inNames.setAtIndex(ValueLayout.ADDRESS, i, cString(arena, input.name()));
				MemorySegment shapeSegment = arena.allocate(ValueLayout.JAVA_LONG,
						input.shape().length);
				for (int d = 0; d < input.shape().length; d++) {
					shapeSegment.setAtIndex(ValueLayout.JAVA_LONG, d, input.shape()[d]);
				}
				MemorySegment valueOut = arena.allocate(ValueLayout.ADDRESS);
				checkStatus(OrtApi.CreateTensorWithDataAsOrtValue.invoke(
						OrtApi.CreateTensorWithDataAsOrtValue(api), memoryInfo, input.data(),
						elementBytes(input) * elementCount(input.shape()), shapeSegment,
						input.shape().length, input.elementType(), valueOut),
						"CreateTensorWithDataAsOrtValue(" + input.name() + ")");
				inputValues[i] = valueOut.get(ValueLayout.ADDRESS, 0);
				inputArray.setAtIndex(ValueLayout.ADDRESS, i, inputValues[i]);
			}
			MemorySegment outNames = arena.allocate(ValueLayout.ADDRESS, outputNames.size());
			for (int i = 0; i < outputNames.size(); i++) {
				outNames.setAtIndex(ValueLayout.ADDRESS, i, cString(arena, outputNames.get(i)));
			}
			MemorySegment outputs = arena.allocate(ValueLayout.ADDRESS, outputNames.size());
			try {
				checkStatus(OrtApi.Run.invoke(OrtApi.Run(api), session, MemorySegment.NULL, inNames,
						inputArray, inputs.size(), outNames, outputNames.size(), outputs), "Run");
			} finally {
				for (MemorySegment value : inputValues) {
					if (value != null) {
						OrtApi.ReleaseValue.invoke(OrtApi.ReleaseValue(api), value);
					}
				}
			}
			MemorySegment[] result = new MemorySegment[outputNames.size()];
			try {
				for (int i = 0; i < result.length; i++) {
					MemorySegment value = outputs.getAtIndex(ValueLayout.ADDRESS, i);
					if (value.address() == 0) {
						throw new IllegalStateException(
								"ONNX Runtime Run produced a null output value for "
										+ outputNames.get(i));
					}
					result[i] = value;
				}
			} catch (RuntimeException invalidOutput) {
				// The values already collected are owned by the caller and would otherwise leak:
				// release them before failing, since the caller never sees the array.
				for (MemorySegment value : result) {
					if (value != null) {
						OrtApi.ReleaseValue.invoke(OrtApi.ReleaseValue(api), value);
					}
				}
				throw invalidOutput;
			}
			return result;
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** Convenience: run with float inputs (copies each array into the run's arena). */
	public MemorySegment[] runFloat(MemorySegment session, MemorySegment memoryInfo,
			List<NamedFloat> inputs, List<String> outputNames) {
		try (Arena arena = Arena.ofConfined()) {
			List<Input> prepared = inputs.stream()
					.map(input -> new Input(input.name(), onnxruntime_h
							.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT(), input.shape(),
							arena.allocateFrom(ValueLayout.JAVA_FLOAT, input.values())))
					.toList();
			// run() opens its own arena; the data above must outlive it, hence the outer scope.
			return run(session, prepared, outputNames, memoryInfo);
		}
	}

	/** Convenience: run with int64 inputs (token ids), copying each array into the run's arena. */
	public MemorySegment[] runLong(MemorySegment session, MemorySegment memoryInfo,
			List<NamedLong> inputs, List<String> outputNames) {
		try (Arena arena = Arena.ofConfined()) {
			List<Input> prepared = inputs.stream()
					.map(input -> new Input(input.name(), ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
							input.shape(), arena.allocateFrom(ValueLayout.JAVA_LONG,
									input.values())))
					.toList();
			// run() opens its own arena; the data above must outlive it, hence the outer scope.
			return run(session, prepared, outputNames, memoryInfo);
		}
	}

	/**
	 * Convenience: run with int64 inputs and one {@code bool} input (GLiNER's {@code span_mask}),
	 * copying every array into the run's arena.
	 */
	public MemorySegment[] runMixed(MemorySegment session, MemorySegment memoryInfo,
			List<NamedLong> longs, List<NamedBool> bools, List<String> outputNames) {
		try (Arena arena = Arena.ofConfined()) {
			List<Input> prepared = new java.util.ArrayList<>(longs.size() + bools.size());
			for (NamedLong input : longs) {
				prepared.add(new Input(input.name(), ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
						input.shape(), arena.allocateFrom(ValueLayout.JAVA_LONG,
								input.values())));
			}
			for (NamedBool input : bools) {
				prepared.add(new Input(input.name(), ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL,
						input.shape(), arena.allocateFrom(ValueLayout.JAVA_BYTE,
								input.values())));
			}
			// run() opens its own arena; the data above must outlive it, hence the outer scope.
			return run(session, prepared, outputNames, memoryInfo);
		}
	}

	/** A bool tensor by name, shape and values (one byte per element, 0 or 1). */
	public record NamedBool(String name, long[] shape, byte[] values) {}

	/** A float tensor by name, shape and values. */
	public record NamedFloat(String name, long[] shape, float[] values) {}

	/** An int64 tensor by name, shape and values. */
	public record NamedLong(String name, long[] shape, long[] values) {}

	/**
	 * The element type of a model input or output: one of the
	 * {@code ONNX_TENSOR_ELEMENT_DATA_TYPE_*} values.
	 */
	public int tensorElementType(MemorySegment session, long index, boolean input) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment typeOut = arena.allocate(ValueLayout.ADDRESS);
			sessionTypeInfo(session, index, input, typeOut);
			MemorySegment typeInfo = typeOut.get(ValueLayout.ADDRESS, 0);
			try {
				MemorySegment tensorOut = arena.allocate(ValueLayout.ADDRESS);
				checkStatus(OrtApi.CastTypeInfoToTensorInfo.invoke(
						OrtApi.CastTypeInfoToTensorInfo(api), typeInfo, tensorOut),
						"CastTypeInfoToTensorInfo");
				MemorySegment info = tensorOut.get(ValueLayout.ADDRESS, 0);
				if (info.address() == 0) {
					throw new IllegalStateException("model "
							+ (input ? "input" : "output") + " is not a tensor");
				}
				try {
					MemorySegment elementOut = arena.allocate(ValueLayout.JAVA_INT);
					checkStatus(OrtApi.GetTensorElementType.invoke(
							OrtApi.GetTensorElementType(api), info, elementOut),
							"GetTensorElementType");
					return elementOut.get(ValueLayout.JAVA_INT, 0);
				} finally {
					releaseTensorTypeAndShapeInfo(info);
				}
			} finally {
				releaseTypeInfo(typeInfo);
			}
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/**
	 * The shape of an {@code OrtValue} produced by {@link #run} (dynamic dimensions resolved to the
	 * values actually used). Useful for outputs whose extent depends on the input.
	 */
	public long[] valueShape(MemorySegment value) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment infoOut = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.GetTensorTypeAndShape.invoke(OrtApi.GetTensorTypeAndShape(api),
					value, infoOut), "GetTensorTypeAndShape");
			MemorySegment info = infoOut.get(ValueLayout.ADDRESS, 0);
			try {
				return tensorInfoDimensions(info);
			} finally {
				releaseTensorTypeAndShapeInfo(info);
			}
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** The int64 data of an output tensor, copied into a Java array. */
	public long[] getTensorLongs(MemorySegment value, long elementCount) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.GetTensorMutableData.invoke(OrtApi.GetTensorMutableData(api),
					value, out), "GetTensorMutableData");
			MemorySegment data = out.get(ValueLayout.ADDRESS, 0);
			if (data.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime GetTensorMutableData produced a null pointer");
			}
			MemorySegment bounded = data.reinterpret(Math.multiplyExact(elementCount,
					Long.BYTES));
			long[] values = new long[Math.toIntExact(elementCount)];
			MemorySegment.copy(bounded, ValueLayout.JAVA_LONG, 0, values, 0, values.length);
			return values;
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** The float data of an output tensor, copied into a Java array. */
	public float[] getTensorFloats(MemorySegment value, long elementCount) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.GetTensorMutableData.invoke(OrtApi.GetTensorMutableData(api),
					value, out), "GetTensorMutableData");
			MemorySegment data = out.get(ValueLayout.ADDRESS, 0);
			if (data.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime GetTensorMutableData produced a null pointer");
			}
			// Unbounded address; bound it to exactly the tensor contents before the copy.
			MemorySegment bounded = data.reinterpret(Math.multiplyExact(elementCount,
					Float.BYTES));
			float[] values = new float[Math.toIntExact(elementCount)];
			MemorySegment.copy(bounded, ValueLayout.JAVA_FLOAT, 0, values, 0, values.length);
			return values;
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** Releases an {@code OrtValue} (output tensors are caller-owned). */
	public void releaseValue(MemorySegment value) {
		OrtApi.ReleaseValue.invoke(OrtApi.ReleaseValue(api), value);
	}

	/** Releases a session (before {@link #close()} releases the env). */
	public void releaseSession(MemorySegment session) {
		OrtApi.ReleaseSession.invoke(OrtApi.ReleaseSession(api), session);
	}

	/**
	 * Reads one custom metadata value from a session's model, or {@code null} when the key is
	 * absent. PP-OCR's recognizers embed their character list this way, which is why no dictionary
	 * file has to be shipped with them.
	 */
	public @Nullable String modelMetadata(MemorySegment session, MemorySegment allocator,
			String key) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment metadataOut = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.SessionGetModelMetadata.invoke(
					OrtApi.SessionGetModelMetadata(api), session, metadataOut),
					"SessionGetModelMetadata");
			MemorySegment metadata = metadataOut.get(ValueLayout.ADDRESS, 0);
			if (metadata.address() == 0) {
				return null;
			}
			try {
				MemorySegment valueOut = arena.allocate(ValueLayout.ADDRESS);
				checkStatus(OrtApi.ModelMetadataLookupCustomMetadataMap.invoke(
						OrtApi.ModelMetadataLookupCustomMetadataMap(api), metadata, allocator,
						cString(arena, key), valueOut),
						"ModelMetadataLookupCustomMetadataMap(" + key + ")");
				MemorySegment value = valueOut.get(ValueLayout.ADDRESS, 0);
				if (value.address() == 0) {
					return null;
				}
				try {
					// Bound the segment to the actual NUL-terminated length before reading it:
					// reinterpreting a fixed generous size would read past the allocation if a
					// model ever shipped a longer value.
					return readCString(value, MAX_METADATA_BYTES);
				} finally {
					MemorySegment freeFunction = OrtAllocator.Free(allocator);
					OrtAllocator.Free.invoke(freeFunction, allocator, value);
				}
			} finally {
				OrtApi.ReleaseModelMetadata.invoke(OrtApi.ReleaseModelMetadata(api), metadata);
			}
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	/** Releases a memory info object. */
	public void releaseMemoryInfo(MemorySegment memoryInfo) {
		OrtApi.ReleaseMemoryInfo.invoke(OrtApi.ReleaseMemoryInfo(api), memoryInfo);
	}

	/** Releases the env; the owner calls this last, after every session is released. */
	@Override
	public void close() {
		MemorySegment toRelease = env;
		env = null;
		if (toRelease != null) {
			OrtApi.ReleaseEnv.invoke(OrtApi.ReleaseEnv(api), toRelease);
		}
	}

	private long[] tensorInfoDimensions(MemorySegment info) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment countOut = arena.allocate(ValueLayout.JAVA_LONG);
			checkStatus(OrtApi.GetDimensionsCount.invoke(OrtApi.GetDimensionsCount(api), info,
					countOut), "GetDimensionsCount");
			long count = countOut.get(ValueLayout.JAVA_LONG, 0);
			if (count <= 0 || count > 16) {
				throw new IllegalStateException("implausible tensor dimension count: " + count);
			}
			long[] dims = new long[(int) count];
			MemorySegment dimsSegment = arena.allocate(ValueLayout.JAVA_LONG, dims.length);
			checkStatus(OrtApi.GetDimensions.invoke(OrtApi.GetDimensions(api), info, dimsSegment,
					dims.length), "GetDimensions");
			for (int i = 0; i < dims.length; i++) {
				dims[i] = dimsSegment.getAtIndex(ValueLayout.JAVA_LONG, i);
			}
			return dims;
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	private void releaseTensorTypeAndShapeInfo(MemorySegment info) {
		OrtApi.ReleaseTensorTypeAndShapeInfo.invoke(OrtApi.ReleaseTensorTypeAndShapeInfo(api),
				info);
	}

	private void releaseTypeInfo(MemorySegment info) {
		OrtApi.ReleaseTypeInfo.invoke(OrtApi.ReleaseTypeInfo(api), info);
	}

	private String sessionNodeName(MemorySegment session, MemorySegment allocator, long index,
			boolean input) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			if (input) {
				checkStatus(OrtApi.SessionGetInputName.invoke(OrtApi.SessionGetInputName(api),
						session, index, allocator, out), "SessionGetInputName");
			} else {
				checkStatus(OrtApi.SessionGetOutputName.invoke(OrtApi.SessionGetOutputName(api),
						session, index, allocator, out), "SessionGetOutputName");
			}
			MemorySegment name = out.get(ValueLayout.ADDRESS, 0);
			if (name.address() == 0) {
				throw new IllegalStateException("ONNX Runtime returned a null model I/O name");
			}
			try {
				return readCString(name, MAX_IO_NAME_BYTES);
			} finally {
				// OrtAllocator::Free(self, p): the generated invoker takes the function pointer
				// plus both arguments.
				MemorySegment freeFunction = OrtAllocator.Free(allocator);
				OrtAllocator.Free.invoke(freeFunction, allocator, name);
			}
		} catch (Throwable error) {
			throw (error instanceof RuntimeException runtime) ? runtime
					: new IllegalStateException(error);
		}
	}

	private void checkStatus(MemorySegment status, String action) {
		if (status != null && status.address() != 0) {
			String message;
			try {
				message = OrtApi.GetErrorMessage.invoke(OrtApi.GetErrorMessage(api), status)
						.getString(0);
			} catch (Throwable readError) {
				message = "<unavailable>";
			} finally {
				OrtApi.ReleaseStatus.invoke(OrtApi.ReleaseStatus(api), status);
			}
			throw new IllegalStateException("ONNX Runtime " + action + " failed: " + message);
		}
	}

	/** Allocates a NUL-terminated UTF-8 C string in the given arena. */
	private static MemorySegment cString(Arena arena, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		MemorySegment segment = arena.allocate(bytes.length + 1L);
		MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
		segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
		return segment;
	}

	/**
	 * Reads a NUL-terminated UTF-8 string from native memory without trusting its length: at most
	 * {@code limit} bytes are scanned, so a malformed or unexpectedly long value cannot read
	 * arbitrarily far past the allocation.
	 */
	private static String readCString(MemorySegment value, int limit) {
		MemorySegment bounded = value.reinterpret(limit);
		int length = 0;
		while (length < limit && bounded.get(ValueLayout.JAVA_BYTE, length) != 0) {
			length++;
		}
		byte[] bytes = new byte[length];
		MemorySegment.copy(bounded, ValueLayout.JAVA_BYTE, 0, bytes, 0, length);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
