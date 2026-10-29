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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * libpulse-simple playback stream through FFM (plan §4.4/§5.9): {@code pa_simple_new} with
 * {@code PA_STREAM_PLAYBACK}, float32le 48 kHz stereo and a ~40 ms target buffer length, then
 * {@code pa_simple_write}, {@code pa_simple_get_latency} and {@code pa_simple_flush} for the
 * audio-master clock.
 *
 * <p>libpulse is a system library (not bundled, like the recording side); the binding is
 * hand-written because jextract is only used for FFmpeg. The audio clock is
 * {@code samples written - latency}, so a blocking write is expected and the writer thread is the
 * playback/audio thread.
 */
final class PulsePlaybackStream implements PlaybackAudioSink {

	private static final System.Logger LOG =
			System.getLogger(PulsePlaybackStream.class.getName());

	/** {@code PA_STREAM_PLAYBACK}. */
	private static final int PA_STREAM_PLAYBACK = 1;
	/** {@code PA_SAMPLE_FLOAT32LE}. */
	private static final int PA_SAMPLE_FLOAT32LE = 5;
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final int BYTES_PER_FRAME = CHANNELS * Float.BYTES;
	/** plan §5.9: {@code tlength ≈ 40 ms}. */
	private static final int TLENGTH_BYTES = SAMPLE_RATE / 25 * BYTES_PER_FRAME;

	private static final MemoryLayout SAMPLE_SPEC = MemoryLayout.structLayout(
			JAVA_INT.withName("format"),
			JAVA_INT.withName("rate"),
			JAVA_BYTE.withName("channels"),
			MemoryLayout.paddingLayout(3));
	private static final MemoryLayout BUFFER_ATTR = MemoryLayout.structLayout(
			JAVA_INT.withName("maxlength"),
			JAVA_INT.withName("tlength"),
			JAVA_INT.withName("prebuf"),
			JAVA_INT.withName("minreq"),
			JAVA_INT.withName("fragsize"));

	private static final List<Path> LIBRARY_CANDIDATES = List.of(
			Path.of("/usr/lib/x86_64-linux-gnu/libpulse-simple.so.0"),
			Path.of("/usr/lib/libpulse-simple.so.0"),
			Path.of("/lib/x86_64-linux-gnu/libpulse-simple.so.0"));

	/** {@code pa_strerror} lives in libpulse, not libpulse-simple. */
	private static final List<Path> PULSE_LIBRARY_CANDIDATES = List.of(
			Path.of("/usr/lib/x86_64-linux-gnu/libpulse.so.0"),
			Path.of("/usr/lib/libpulse.so.0"),
			Path.of("/lib/x86_64-linux-gnu/libpulse.so.0"));

	/** libpulse-simple / libpulse entry points, loaded together on first use. */
	private record Bindings(MethodHandle paSimpleNew, MethodHandle paSimpleWrite,
			MethodHandle paSimpleGetLatency, MethodHandle paSimpleFlush,
			MethodHandle paSimpleFree, MethodHandle paStrerror) {}

	/** The loaded entry points; {@code null} until the first {@link #open()}. */
	private static @Nullable Bindings bindings;

	private final Bindings handles;
	private final Arena arena;
	private final String streamName;
	private final MemorySegment handle;
	private final MemorySegment errorPtr;
	/** Scratch buffer for the float32 copy; {@link MemorySegment#NULL} until the first write. */
	private MemorySegment scratch = MemorySegment.NULL;
	private int scratchSamples;
	private boolean closed;

	private PulsePlaybackStream(Bindings handles, Arena arena, String streamName,
			MemorySegment handle, MemorySegment errorPtr) {
		this.handles = handles;
		this.arena = arena;
		this.streamName = streamName;
		this.handle = handle;
		this.errorPtr = errorPtr;
	}

	/** The configured target buffer length in bytes (40 ms of float32 stereo). */
	static int tlengthBytes() {
		return TLENGTH_BYTES;
	}

	/** The sink's channel count (2) and sample format (float32le, 48 kHz). */
	static int channels() {
		return CHANNELS;
	}

	static PulsePlaybackStream open() {
		Bindings handles = ensureLoaded();
		// The playback owner opens the sink on the attach thread but writes on the render thread
		// and flushes on seek/pause from the FX thread. A confined arena rejects every one of those
		// cross-thread calls (WrongThreadException), so use a shared arena and serialize the
		// pa_simple calls below (libpulse-simple is not thread-safe, but is thread-agnostic).
		Arena arena = Arena.ofShared();
		try {
			MemorySegment sampleSpec = arena.allocate(SAMPLE_SPEC);
			sampleSpec.set(JAVA_INT, 0, PA_SAMPLE_FLOAT32LE);
			sampleSpec.set(JAVA_INT, 4, SAMPLE_RATE);
			sampleSpec.set(JAVA_BYTE, 8, (byte) CHANNELS);
			MemorySegment bufferAttr = arena.allocate(BUFFER_ATTR);
			for (int index = 0; index < 5; index++) {
				bufferAttr.set(JAVA_INT, index * 4L, -1);
			}
			bufferAttr.set(JAVA_INT, 4, TLENGTH_BYTES);
			MemorySegment errorPtr = arena.allocate(JAVA_INT);
			MemorySegment applicationName = cString(arena, "Gravaai");
			MemorySegment device = MemorySegment.NULL;
			MemorySegment streamName = cString(arena, "Editor preview");
			MemorySegment handle;
			try {
				handle = (MemorySegment) handles.paSimpleNew().invokeExact(MemorySegment.NULL,
						applicationName,
						PA_STREAM_PLAYBACK, device, streamName, sampleSpec, MemorySegment.NULL,
						bufferAttr, errorPtr);
			} catch (Throwable failure) {
				throw new IllegalStateException("pa_simple_new(playback) failed", failure);
			}
			if (handle.address() == 0) {
				int error = errorPtr.get(JAVA_INT, 0);
				throw new IllegalStateException(
						"cannot open PulseAudio playback stream: " + strerror(handles, error));
			}
			LOG.log(System.Logger.Level.DEBUG, () -> "opened PulseAudio playback stream");
			return new PulsePlaybackStream(handles, arena, "Editor preview", handle, errorPtr);
		} catch (RuntimeException failure) {
			arena.close();
			throw failure;
		}
	}

	@Override
	public synchronized void write(float[] interleavedStereo, int frameCount) {
		if (closed) {
			throw new IllegalStateException("playback stream is closed");
		}
		if (frameCount < 0 || interleavedStereo == null
				|| interleavedStereo.length < frameCount * CHANNELS) {
			throw new IllegalArgumentException("invalid frameCount: " + frameCount);
		}
		int samples = frameCount * CHANNELS;
		int requiredBytes = samples * Float.BYTES;
		ensureScratch(samples);
		MemorySegment.copy(MemorySegment.ofArray(interleavedStereo), ValueLayout.JAVA_FLOAT, 0,
				scratch, ValueLayout.JAVA_FLOAT, 0, samples);
		try {
			int result = (int) handles.paSimpleWrite().invokeExact(handle, scratch,
					(long) requiredBytes, errorPtr);
			if (result < 0) {
				throw new IllegalStateException(
						"pa_simple_write failed: " + strerror(handles, errorPtr.get(JAVA_INT, 0)));
			}
		} catch (RuntimeException failure) {
			throw failure;
		} catch (Throwable failure) {
			throw new IllegalStateException("pa_simple_write failed", failure);
		}
	}

	@Override
	public synchronized int latencyMicros() {
		if (closed) {
			return 0;
		}
		try {
			long latency = (long) handles.paSimpleGetLatency().invokeExact(handle, errorPtr);
			return (int) Math.max(0, Math.min(Integer.MAX_VALUE, latency));
		} catch (Throwable failure) {
			LOG.log(System.Logger.Level.WARNING, "pa_simple_get_latency failed: " + failure);
			return 0;
		}
	}

	@Override
	public synchronized void flush() {
		if (closed) {
			return;
		}
		try {
			int result = (int) handles.paSimpleFlush().invokeExact(handle, errorPtr);
			if (result < 0) {
				LOG.log(System.Logger.Level.WARNING,
						"pa_simple_flush failed: " + strerror(handles, errorPtr.get(JAVA_INT, 0)));
			}
		} catch (Throwable failure) {
			LOG.log(System.Logger.Level.WARNING, "pa_simple_flush failed: " + failure);
		}
	}

	private void ensureScratch(int samples) {
		if (scratch.address() != 0 && scratchSamples >= samples) {
			return;
		}
		scratch = arena.allocate((long) samples * Float.BYTES);
		scratchSamples = samples;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			handles.paSimpleFree().invokeExact(handle);
		} catch (Throwable failure) {
			LOG.log(System.Logger.Level.WARNING, "pa_simple_free failed: " + failure);
		}
		arena.close();
	}

	private static MemorySegment cString(Arena arena, String value) {
		MemorySegment segment = arena.allocate((long) value.length() + 1);
		MemorySegment copy = MemorySegment.ofArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		MemorySegment.copy(copy, 0, segment, 0, copy.byteSize());
		segment.set(JAVA_BYTE, copy.byteSize(), (byte) 0);
		return segment;
	}

	private static String strerror(Bindings handles, int error) {
		try {
			MemorySegment text = (MemorySegment) handles.paStrerror().invokeExact(error);
			if (text.address() == 0) {
				return "error " + error;
			}
			return text.reinterpret(Long.MAX_VALUE).getString(0);
		} catch (Throwable failure) {
			return "error " + error;
		}
	}

	private static synchronized Bindings ensureLoaded() {
		Bindings current = bindings;
		if (current != null) {
			return current;
		}
		Linker linker = Linker.nativeLinker();
		SymbolLookup simple = null;
		for (Path candidate : LIBRARY_CANDIDATES) {
			if (Files.isReadable(candidate)) {
				simple = SymbolLookup.libraryLookup(candidate, Arena.global());
				break;
			}
		}
		if (simple == null) {
			throw new IllegalStateException("libpulse-simple.so.0 not found (PulseAudio is not installed)");
		}
		MethodHandle paSimpleNew = linker.downcallHandle(
				simple.find("pa_simple_new").orElseThrow(() -> new IllegalStateException(
						"libpulse-simple lacks pa_simple_new")),
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
						ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		MethodHandle paSimpleWrite = linker.downcallHandle(
				simple.find("pa_simple_write").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
		MethodHandle paSimpleGetLatency = linker.downcallHandle(
				simple.find("pa_simple_get_latency").orElseThrow(),
				FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
		MethodHandle paSimpleFlush = linker.downcallHandle(
				simple.find("pa_simple_flush").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		MethodHandle paSimpleFree = linker.downcallHandle(
				simple.find("pa_simple_free").orElseThrow(),
				FunctionDescriptor.ofVoid(ADDRESS));
		SymbolLookup pulse = null;
		for (Path candidate : PULSE_LIBRARY_CANDIDATES) {
			if (Files.isReadable(candidate)) {
				pulse = SymbolLookup.libraryLookup(candidate, Arena.global());
				break;
			}
		}
		MemorySegment strerrorSymbol = simple.find("pa_strerror").orElse(null);
		if (strerrorSymbol == null && pulse != null) {
			strerrorSymbol = pulse.find("pa_strerror").orElse(null);
		}
		if (strerrorSymbol == null) {
			strerrorSymbol = SymbolLookup.loaderLookup().find("pa_strerror").orElse(null);
		}
		if (strerrorSymbol == null) {
			throw new IllegalStateException("libpulse lacks pa_strerror");
		}
		MethodHandle paStrerror = linker.downcallHandle(strerrorSymbol,
				FunctionDescriptor.of(ADDRESS, JAVA_INT));
		Bindings loaded = new Bindings(paSimpleNew, paSimpleWrite, paSimpleGetLatency,
				paSimpleFlush, paSimpleFree, paStrerror);
		bindings = loaded;
		return loaded;
	}
}
