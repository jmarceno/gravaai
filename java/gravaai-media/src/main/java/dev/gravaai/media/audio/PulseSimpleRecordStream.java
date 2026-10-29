package dev.gravaai.media.audio;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * libpulse-simple record stream through FFM (plan §4.4: libpulse-simple is called with
 * java.lang.foreign, not JNI). 48 kHz mono float32, ~20 ms server-side buffer.
 *
 * <p>libpulse is a system library: it stays a runtime dependency of the app image (unlike
 * FFmpeg, we do not bundle it). The binding is hand-written because jextract is only used for
 * FFmpeg; the surface here is three functions.</p>
 */
final class PulseSimpleRecordStream implements PulseRecordStream {

	private static final System.Logger LOG =
			System.getLogger(PulseSimpleRecordStream.class.getName());

	/** {@code PA_STREAM_RECORD}. */
	private static final int PA_STREAM_RECORD = 2;
	/** {@code PA_SAMPLE_FLOAT32LE}. */
	private static final int PA_SAMPLE_FLOAT32LE = 5;
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 1;
	private static final int FRAGMENT_BYTES = SAMPLE_RATE / 50 * Float.BYTES; // 20 ms

	private static final MemoryLayout SAMPLE_SPEC = MemoryLayout.structLayout(
			ValueLayout.JAVA_INT.withName("format"),
			ValueLayout.JAVA_INT.withName("rate"),
			ValueLayout.JAVA_BYTE.withName("channels"),
			MemoryLayout.paddingLayout(3));
	private static final MemoryLayout BUFFER_ATTR = MemoryLayout.structLayout(
			ValueLayout.JAVA_INT.withName("maxlength"),
			ValueLayout.JAVA_INT.withName("tlength"),
			ValueLayout.JAVA_INT.withName("prebuf"),
			ValueLayout.JAVA_INT.withName("minreq"),
			ValueLayout.JAVA_INT.withName("fragsize"));

	private static final List<Path> LIBRARY_CANDIDATES = List.of(
			Path.of("/usr/lib/x86_64-linux-gnu/libpulse-simple.so.0"),
			Path.of("/usr/lib/libpulse-simple.so.0"),
			Path.of("/lib/x86_64-linux-gnu/libpulse-simple.so.0"));

	/** libpulse-simple / libpulse entry points, loaded together on first use. */
	private record Bindings(MethodHandle paSimpleNew, MethodHandle paSimpleRead,
			MethodHandle paSimpleFree, MethodHandle paStrerror) {}

	/** The loaded entry points; {@code null} until the first {@link #open(String)}. */
	private static @Nullable Bindings bindings;

	private final Bindings handles;
	private final Arena arena;
	private final String sourceName;
	private final MemorySegment handle;
	private final MemorySegment errorPtr;
	/** Scratch buffer for the float32 copy; {@link MemorySegment#NULL} until the first read. */
	private MemorySegment scratch = MemorySegment.NULL;
	private int scratchSamples;
	private volatile boolean closed;

	private PulseSimpleRecordStream(Bindings handles, Arena arena, String sourceName,
			MemorySegment handle, MemorySegment errorPtr) {
		this.handles = handles;
		this.arena = arena;
		this.sourceName = sourceName;
		this.handle = handle;
		this.errorPtr = errorPtr;
	}

	static PulseRecordStream open(String sourceName) {
		if (sourceName == null || sourceName.isBlank()) {
			throw new IllegalArgumentException("sourceName must not be blank");
		}
		Bindings handles = ensureLoaded();
		// The HUD meter opens the stream on the FX thread and reads it on the level pump thread.
		// A confined arena rejects that cross-thread access (WrongThreadException) and its close
		// can free scratch memory another thread is using; libpulse then aborts inside
		// pa_stream_get_state. Use a shared arena like PulsePlaybackStream does, and serialize
		// read/close so the native handle is never freed under a blocked read.
		Arena arena = Arena.ofShared();
		try {
			MemorySegment sampleSpec = arena.allocate(SAMPLE_SPEC);
			sampleSpec.set(ValueLayout.JAVA_INT, 0, PA_SAMPLE_FLOAT32LE);
			sampleSpec.set(ValueLayout.JAVA_INT, 4, SAMPLE_RATE);
			sampleSpec.set(ValueLayout.JAVA_BYTE, 8, (byte) CHANNELS);
			MemorySegment bufferAttr = arena.allocate(BUFFER_ATTR);
			for (int i = 0; i < 5; i++) {
				bufferAttr.set(ValueLayout.JAVA_INT, i * 4L, -1);
			}
			bufferAttr.set(ValueLayout.JAVA_INT, 16, FRAGMENT_BYTES);
			MemorySegment errorPtr = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment name = cString(arena, "Gravaai");
			MemorySegment device = cString(arena, sourceName);
			MemorySegment streamName = cString(arena, "Editor microphone meter");
			MemorySegment handle;
			try {
				handle = (MemorySegment) handles.paSimpleNew().invokeExact(MemorySegment.NULL, name,
						PA_STREAM_RECORD, device, streamName, sampleSpec, MemorySegment.NULL,
						bufferAttr, errorPtr);
			} catch (Throwable failure) {
				throw new IllegalStateException("pa_simple_new failed for " + sourceName, failure);
			}
			if (handle.address() == 0) {
				int error = errorPtr.get(ValueLayout.JAVA_INT, 0);
				throw new IllegalStateException("cannot record from PulseAudio source '"
						+ sourceName + "': " + strerror(handles, error));
			}
			LOG.log(System.Logger.Level.DEBUG,
					() -> "opened PulseAudio record stream for " + sourceName);
			return new PulseSimpleRecordStream(handles, arena, sourceName, handle, errorPtr);
		} catch (RuntimeException failure) {
			arena.close();
			throw failure;
		}
	}

	@Override
	public synchronized int read(float[] mono48k) {
		if (closed) {
			return -1;
		}
		if (mono48k.length == 0) {
			return 0;
		}
		int requiredBytes = mono48k.length * Float.BYTES;
		ensureScratch(mono48k.length);
		try {
			int result = (int) handles.paSimpleRead().invokeExact(handle, scratch,
					(long) requiredBytes, errorPtr);
			if (result < 0) {
				throw new IllegalStateException("pa_simple_read failed for " + sourceName + ": "
						+ strerror(handles, errorPtr.get(ValueLayout.JAVA_INT, 0)));
			}
		} catch (RuntimeException failure) {
			throw failure;
		} catch (Throwable failure) {
			throw new IllegalStateException("pa_simple_read failed for " + sourceName, failure);
		}
		MemorySegment.copy(scratch, ValueLayout.JAVA_FLOAT, 0,
				MemorySegment.ofArray(mono48k), ValueLayout.JAVA_FLOAT, 0, mono48k.length);
		return mono48k.length;
	}

	private void ensureScratch(int samples) {
		if (scratch.address() != 0 && scratchSamples >= samples) {
			return;
		}
		scratch = arena.allocate((long) samples * Float.BYTES);
		scratchSamples = samples;
	}

	/**
	 * Frees the libpulse stream. Synchronized so it waits for a blocked {@link #read} to return:
	 * freeing a {@code pa_simple} while another thread is inside {@code pa_simple_read} makes
	 * libpulse abort on a destroyed stream.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			// pa_simple_free takes the handle by value (not a pointer to pointer).
			handles.paSimpleFree().invokeExact(handle);
		} catch (Throwable failure) {
			LOG.log(System.Logger.Level.WARNING, "pa_simple_free failed: " + failure);
		} finally {
			arena.close();
		}
	}

	private static synchronized Bindings ensureLoaded() {
		Bindings current = bindings;
		if (current != null) {
			return current;
		}
		Path simpleLibrary = findLibrary("libpulse-simple.so.0");
		Path pulseLibrary = findLibrary("libpulse.so.0");
		try {
			System.load(simpleLibrary.toAbsolutePath().toString());
			System.load(pulseLibrary.toAbsolutePath().toString());
		} catch (UnsatisfiedLinkError failure) {
			throw new IllegalStateException("cannot load " + simpleLibrary, failure);
		}
		SymbolLookup lookup = SymbolLookup.loaderLookup();
		Linker linker = Linker.nativeLinker();
		MethodHandle paSimpleNew = downcall(linker, lookup, "pa_simple_new", FunctionDescriptor.of(
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		MethodHandle paSimpleRead = downcall(linker, lookup, "pa_simple_read", FunctionDescriptor.of(
				ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
				ValueLayout.ADDRESS));
		MethodHandle paSimpleFree = downcall(linker, lookup, "pa_simple_free",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		MethodHandle paStrerror = downcall(linker, lookup, "pa_strerror", FunctionDescriptor.of(
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		Bindings loaded = new Bindings(paSimpleNew, paSimpleRead, paSimpleFree, paStrerror);
		bindings = loaded;
		return loaded;
	}

	private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol,
			FunctionDescriptor descriptor) {
		MemorySegment address = lookup.find(symbol).orElseThrow(
				() -> new IllegalStateException("PulseAudio symbol not found: " + symbol));
		return linker.downcallHandle(address, descriptor);
	}

	private static String strerror(Bindings handles, int error) {
		try {
			MemorySegment message = (MemorySegment) handles.paStrerror().invokeExact(error);
			if (message.address() != 0) {
				return message.getString(0);
			}
		} catch (Throwable ignored) {
			// fall through to the numeric error
		}
		return "error " + error;
	}

	private static Path findLibrary(String name) {
		for (Path candidate : LIBRARY_CANDIDATES) {
			if (candidate.getFileName().toString().equals(name) && Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		// Also accept the other soname directories (multiarch layouts differ).
		for (Path directory : List.of(Path.of("/usr/lib/x86_64-linux-gnu"),
				Path.of("/usr/lib"), Path.of("/lib/x86_64-linux-gnu"), Path.of("/lib"))) {
			Path candidate = directory.resolve(name);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException(name + " not found; PulseAudio is required for audio capture");
	}

	private static MemorySegment cString(Arena arena, String value) {
		byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		MemorySegment segment = arena.allocate(bytes.length + 1L);
		MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
		segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
		return segment;
	}
}
