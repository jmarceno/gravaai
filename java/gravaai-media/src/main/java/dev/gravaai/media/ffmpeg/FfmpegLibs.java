package dev.gravaai.media.ffmpeg;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.platform.nativehelpers.NativeHelpers;

import dev.gravaai.media.ffmpeg.gen.av_log_set_callback$callback;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Loads the bundled FFmpeg shared libraries and installs the log bridge (plan §5.7).
 *
 * <p>The libraries are loaded through {@link SymbolLookup#libraryLookup(Path, Arena)} in
 * dependency order: avutil, swresample, swscale, avcodec, avformat, avfilter, avdevice. The
 * jextract-generated bindings resolve their symbols through
 * {@code SymbolLookup.loaderLookup()}, which sees the libraries loaded here (this is the
 * pattern the generated code expects), and every wrapper class calls
 * {@link #ensureLoaded()} before its first native call.</p>
 *
 * <p>FFmpeg's {@code av_log} callback is a C upcall that receives a {@code va_list}. Java
 * upcalls cannot expand varargs, so the callback forwards the pointer to libc
 * {@code vsnprintf}, exactly like a C callback would, and the formatted line is written to
 * {@link System.Logger}. The logger is configured by the app (JUL handler, plan §5.1).</p>
 *
 * <p>Threading: loading is one-shot and synchronised. The log buffer is a single native
 * segment because FFmpeg serialises {@code av_log} internally; do not call
 * {@link #setLogLevel(int)} concurrently with decoding.</p>
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class FfmpegLibs {

	private static final System.Logger LOG = System.getLogger(FfmpegLibs.class.getName());

	/**
	 * Dynamic-loader dependency order. Unversioned sonames are used on purpose: the app image
	 * ships the symlinks created by {@code make install}, so an FFmpeg point-release bump does
	 * not need a code change here.
	 */
	public static final List<String> LIBRARIES = List.of(
			"libavutil.so",
			"libswresample.so",
			"libswscale.so",
			"libavcodec.so",
			"libavformat.so",
			"libavfilter.so",
			"libavdevice.so");

	/** {@code AV_LOG_QUIET}: no log output. */
	public static final int AV_LOG_QUIET = -8;
	/** {@code AV_LOG_ERROR}: log errors only, the default level for the app. */
	public static final int AV_LOG_ERROR = 16;
	/** {@code AV_LOG_WARNING}: log warnings and errors. */
	public static final int AV_LOG_WARNING = 24;
	/** {@code AV_LOG_INFO}: log informational messages too. */
	public static final int AV_LOG_INFO = 32;

	private static final int LOG_BUFFER_BYTES = 4096;

	private static boolean loaded;
	private static Path libraryDir;
	private static String versionSummary;
	private static MethodHandle vsnprintf;
	private static final Arena LOG_ARENA = Arena.ofAuto();
	private static final MemorySegment LOG_BUFFER = LOG_ARENA.allocate(LOG_BUFFER_BYTES);

	private FfmpegLibs() {}

	/**
	 * Loads the FFmpeg libraries from a directory containing the bundled {@code .so} files and
	 * installs the log bridge. Idempotent for the same directory; loading a second, different
	 * directory is rejected because a process can only bind one FFmpeg build.
	 *
	 * @param ffmpegLibDir directory with libavutil.so … libavdevice.so
	 * @throws IllegalStateException when the directory or one of the libraries is missing
	 */
	public static synchronized void load(Path ffmpegLibDir) {
		Path dir = ffmpegLibDir.toAbsolutePath().normalize();
		if (loaded) {
			if (dir.equals(libraryDir)) {
				return;
			}
			throw new IllegalStateException(
					"FFmpeg is already loaded from " + libraryDir + "; cannot also load " + dir);
		}
		List<Path> missing = new ArrayList<>();
		for (String library : LIBRARIES) {
			Path file = dir.resolve(library);
			if (!Files.isRegularFile(file)) {
				missing.add(file);
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
					"FFmpeg shared libraries not found in " + dir + ": " + missing
							+ " (run java/native/build-ffmpeg.sh)");
		}
		for (String library : LIBRARIES) {
			Path file = dir.resolve(library);
			// libraryLookup binds the shared object to the process and gives the wrapper access
			// to its symbols; System.load additionally registers it with this class loader,
			// which is what the jextract generated SymbolLookup.loaderLookup() searches.
			SymbolLookup.libraryLookup(file, Arena.global());
			System.load(file.toAbsolutePath().toString());
		}
		libraryDir = dir;
		installLogCallback();
		versionSummary = buildVersionSummary();
		loaded = true;
		LOG.log(System.Logger.Level.DEBUG,
				() -> "FFmpeg loaded from " + libraryDir + " (" + versionSummary + ")");
	}

	/** Loads the libraries from {@link #resolveLibraryDir()} unless already loaded. */
	public static synchronized void ensureLoaded() {
		if (!loaded) {
			load(resolveLibraryDir());
		}
	}

	/** The directory the libraries were loaded from, or {@code null} before loading. */
	public static synchronized Path libraryDir() {
		return libraryDir;
	}

	/** The loaded FFmpeg version summary, for example {@code avutil 60.8.103, ...}. */
	public static synchronized String versionSummary() {
		ensureLoaded();
		if (versionSummary == null) {
			versionSummary = buildVersionSummary();
		}
		return versionSummary;
	}

	/**
	 * Resolves the FFmpeg library directory.
	 *
	 * <ol>
	 *   <li>{@code -Dgravaai.ffmpeg.lib.dir=<dir>} — explicit override (tests, tooling).</li>
	 *   <li>{@code -Dgravaai.native.dir=<dir>} — the bundled native payload root
	 *       ({@code <appdir>/lib/native/linux-x64}, plan §5.5), FFmpeg lives in its
	 *       {@code ffmpeg/lib} subdirectory.</li>
	 *   <li>Development layout relative to the working directory:
	 *       {@code native/build/ffmpeg/lib} (repo root) or {@code ../native/build/ffmpeg/lib}
	 *       (module directory), which is what Maven Surefire and the IDE use.</li>
	 * </ol>
	 */
	public static Path resolveLibraryDir() {
		String explicit = System.getProperty("gravaai.ffmpeg.lib.dir");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit);
		}
		// The canonical native payload locator (plan §5.5): packaged app image, -Dgravaai.nativeDir,
		// $GRAVAAI_NATIVE_DIR, or the development java/native/build directory.
		Path bundled = NativeHelpers.nativeDir().resolve("ffmpeg").resolve("lib");
		if (Files.isDirectory(bundled)) {
			return bundled;
		}
		// Module-directory working directory fallbacks (Maven Surefire, IDE runs from gravaai-media/).
		List<Path> candidates = List.of(
				Path.of("native", "build", "ffmpeg", "lib"),
				Path.of("..", "native", "build", "ffmpeg", "lib"),
				Path.of("..", "..", "native", "build", "ffmpeg", "lib"),
				Path.of("..", "..", "..", "native", "build", "ffmpeg", "lib"));
		for (Path candidate : candidates) {
			if (Files.isDirectory(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new IllegalStateException(
				"FFmpeg shared libraries not found. Run java/native/build-ffmpeg.sh, then pass"
						+ " -Dgravaai.ffmpeg.lib.dir=<dir> or -Dgravaai.nativeDir=<dir>."
						+ " Tried: " + bundled + " and " + candidates);
	}

	/**
	 * Sets the FFmpeg log level (one of the {@code AV_LOG_*} constants). The default is
	 * {@link #AV_LOG_ERROR}: decoder warnings go through System.Logger, not to stderr.
	 */
	public static void setLogLevel(int avLogLevel) {
		ensureLoaded();
		ffmpeg_h.av_log_set_level(avLogLevel);
	}

	/** Allocates a NUL-terminated UTF-8 C string in the given arena (for wrapper classes). */
	public static MemorySegment cString(Arena arena, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		MemorySegment segment = arena.allocate(bytes.length + 1L);
		MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
		segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
		return segment;
	}

	/** Reads a {@code const char *} return value, tolerating NULL (for wrapper classes). */
	public static @Nullable String readCString(MemorySegment segment) {
		if (segment == null || segment.address() == 0) {
			return null;
		}
		return segment.getString(0);
	}

	private static void installLogCallback() {
		// The callback stub must outlive every FFmpeg call, so it lives in the global arena.
		MemorySegment stub = av_log_set_callback$callback.allocate(
				FfmpegLibs::onLog, Arena.global());
		ffmpeg_h.av_log_set_level(AV_LOG_ERROR);
		ffmpeg_h.av_log_set_callback(stub);
	}

	/** Upcall from FFmpeg's av_log; must never throw back into native code. */
	private static void onLog(MemorySegment avcl, int level, MemorySegment format, MemorySegment vaList) {
		try {
			String message = formatMessage(level, format, vaList);
			if (message == null || message.isEmpty()) {
				return;
			}
			LOG.log(toLoggerLevel(level), message);
		} catch (Throwable ignored) {
			// Never let a logging failure escape into libavcodec.
		}
	}

	private static String formatMessage(int level, MemorySegment format, MemorySegment vaList) {
		if (format == null || format.address() == 0) {
			return "";
		}
		String text;
		try {
			MethodHandle handle = vsnprintfHandle();
			int written = (int) handle.invokeExact(LOG_BUFFER, (long) LOG_BUFFER_BYTES, format, vaList);
			text = written > 0 ? LOG_BUFFER.getString(0) : format.getString(0);
		} catch (Throwable unavailable) {
			text = format.getString(0);
		}
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return text.substring(0, end).strip();
	}

	private static synchronized MethodHandle vsnprintfHandle() {
		if (vsnprintf == null) {
			SymbolLookup libc = Linker.nativeLinker().defaultLookup();
			MemorySegment symbol = libc.find("vsnprintf").orElseGet(() -> {
				SymbolLookup loaded = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
				return loaded.find("vsnprintf").orElseThrow(
						() -> new IllegalStateException("libc vsnprintf not found"));
			});
			FunctionDescriptor descriptor = FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS,
					ValueLayout.JAVA_LONG,
					ValueLayout.ADDRESS,
					ValueLayout.ADDRESS);
			vsnprintf = Linker.nativeLinker().downcallHandle(symbol, descriptor);
		}
		return vsnprintf;
	}

	private static System.Logger.Level toLoggerLevel(int avLogLevel) {
		if (avLogLevel <= AV_LOG_ERROR) {
			return System.Logger.Level.ERROR;
		}
		if (avLogLevel <= AV_LOG_WARNING) {
			return System.Logger.Level.WARNING;
		}
		if (avLogLevel <= AV_LOG_INFO) {
			return System.Logger.Level.INFO;
		}
		return System.Logger.Level.DEBUG;
	}

	private static String buildVersionSummary() {
		// swresample_version()/swscale_version()/av_version_info() are generated on demand: the
		// FFM binding include list (java/native/ffmpeg-jextract-includes.txt) keeps only the
		// symbols the wrappers call, so the version line covers the libraries that expose one.
		return "avutil " + version(ffmpeg_h.avutil_version())
				+ ", avcodec " + version(ffmpeg_h.avcodec_version())
				+ ", avformat " + version(ffmpeg_h.avformat_version())
				+ ", avfilter " + version(ffmpeg_h.avfilter_version())
				+ ", avdevice " + version(ffmpeg_h.avdevice_version());
	}

	private static String version(int packed) {
		int major = (packed >> 16) & 0xff;
		int minor = (packed >> 8) & 0xff;
		int micro = packed & 0xff;
		return major + "." + minor + "." + micro;
	}
}
