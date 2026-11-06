package dev.gravaai.media.onnx;

import java.lang.foreign.Arena;


import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.onnx.gen.OrtApi;
import dev.gravaai.media.onnx.gen.OrtApiBase;
import dev.gravaai.media.onnx.gen.OrtAllocator;
import dev.gravaai.media.onnx.gen.onnxruntime_h;

/**
 * Typed binding over the ONNX Runtime C API for the selfie segmenter (webcam background blur).
 *
 * <p>The C API is a struct of function pointers ({@code OrtApi}): jextract generates a getter
 * per field plus nested invoker classes; this wrapper resolves
 * {@code OrtGetApiBase()->GetApi(ORT_API_VERSION)} once and exposes the handful of entry points
 * the segmenter uses with typed signatures and status checking. One instance per process;
 * sessions outlive it via {@link #close()} (only when the owner is done with every session —
 * the env must outlive them, so {@code close()} is the owner's last call).</p>
 *
 * <p>The ORT_* enum constants are ABI-stable values from onnxruntime_c_api.h; jextract only
 * emits {@code #define}s and simple enum constants, and these enums have no explicit values.</p>
 */
public final class OnnxRuntimeApi implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(OnnxRuntimeApi.class.getName());

	/** OrtLoggingLevel: error messages only (the segmenter runs per frame; silence the rest). */
	private static final int ORT_LOGGING_LEVEL_ERROR = 3;
	/** OrtAllocatorType: the standard device allocator CreateCpuMemoryInfo is called with. */
	private static final int ORT_DEVICE_ALLOCATOR = 0;
	/** OrtMemType: the default memory type for CPU input tensors. */
	private static final int ORT_MEM_TYPE_DEFAULT = 0;

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
	 * Loads a model file into a session (default session options; the caller closes the session
	 * with {@link #releaseSession}).
	 */
	public MemorySegment createSession(MemorySegment env, java.nio.file.Path modelFile) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment path = cString(arena, modelFile.toAbsolutePath().toString());
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			checkStatus(OrtApi.CreateSession.invoke(OrtApi.CreateSession(api), env, path,
					MemorySegment.NULL, out), "CreateSession(" + modelFile + ")");
			MemorySegment created = out.get(ValueLayout.ADDRESS, 0);
			if (created.address() == 0) {
				throw new IllegalStateException(
						"ONNX Runtime CreateSession produced a null OrtSession");
			}
			return created;
		}
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
			if (input) {
				checkStatus(OrtApi.SessionGetInputTypeInfo.invoke(
						OrtApi.SessionGetInputTypeInfo(api), session, index, typeOut),
						"SessionGetInputTypeInfo");
			} else {
				checkStatus(OrtApi.SessionGetOutputTypeInfo.invoke(
						OrtApi.SessionGetOutputTypeInfo(api), session, index, typeOut),
						"SessionGetOutputTypeInfo");
			}
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
				// The allocator hands out an unbounded address; model I/O names are short, so a
				// small bounded reinterpret covers the NUL scan.
				return name.reinterpret(256).getString(0);
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
}
