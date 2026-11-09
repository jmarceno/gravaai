package dev.gravaai.platform.nativehelpers;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the native payload that ships outside the jars (plan §5.5).
 *
 * <p>Packaged app image: {@code <app image>/lib/native/linux-x64/<name>} (plan §5.19.3, written by
 * {@code tools/package-dist.sh}). Development: {@code java/native/build/<name>} relative to the
 * repository root (plan §5.5), with the transitional {@code java/native/build/linux-x64/} layout
 * still recognised. {@code -Dgravaai.nativeDir} / {@code $GRAVAAI_NATIVE_DIR} override both for
 * tests and measurement runs.
 *
 * <p>The app image root is derived from the {@code jpackage.app-path} system property that the
 * jpackage launcher sets to {@code <image>/bin/gravaai}.
 */
public final class NativeHelpers {

	public static final String NATIVE_DIR_PROPERTY = "gravaai.nativeDir";
	public static final String NATIVE_DIR_ENV = "GRAVAAI_NATIVE_DIR";
	public static final String APP_PATH_PROPERTY = "jpackage.app-path";

	/** The rewrite targets Linux x64 only (plan §2). */
	public static final String ARCH_TAG = "linux-x64";

	private static final String LIB_NATIVE = "lib/native";
	private static final String BUILD_DIR = "java/native/build";

	private NativeHelpers() {}

	/** Absolute path of a native helper; the file is not required to exist. */
	public static Path path(String name) {
		return nativeDir().resolve(requireName(name));
	}

	/**
	 * Absolute path of a payload file by its payload-relative path, such as
	 * {@code ffmpeg/bin/ffmpeg}; the file is not required to exist.
	 *
	 * <p>Unlike {@link #path(String)} this accepts subdirectories, because not every bundled tool is
	 * a flat helper next to the capture binaries.
	 */
	public static Path payloadPath(String relative) {
		return nativeDir().resolve(requireRelative(relative));
	}

	/** The helper path when the file exists. */
	public static Optional<Path> existing(String name) {
		Path path = path(name);
		return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
	}

	/** The helper path, failing when the payload is missing from this build (used by services). */
	public static Path require(String name) {
		Path path = path(name);
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException("native helper '" + name + "' is missing: " + path
					+ " (native payload does not look installed; rebuild with -Pdist)");
		}
		return path;
	}

	/**
	 * Resolves a payload subdirectory that may live either in the packaged payload or in a
	 * development build tree: {@code <nativeDir>/<relative>}, then
	 * {@code native/build/<relative>} at, one, two or three parent levels of the working directory.
	 *
	 * <p>{@code FfmpegCli}, {@code FfmpegLibs} and {@code OnnxLibs} each grew this ladder by hand,
	 * with the same four relative candidates in a different order and slightly different error
	 * text. Maven Surefire and IDE runs start in the module directory, the build helper starts in
	 * {@code java/}, and a packaged run finds the payload through {@link #nativeDir()}; the ladder
	 * only exists for those development layouts.
	 *
	 * @param relative the payload-relative path inside each candidate root (for example
	 *     {@code ffmpeg/bin}); must not be absolute
	 * @return the first existing directory, or null when no candidate exists
	 */
	public static @Nullable Path payloadDirectory(String relative) {
		Path bundled = nativeDir().resolve(relative);
		if (Files.isDirectory(bundled)) {
			return bundled;
		}
		for (Path candidate : developmentPayloadCandidates(relative)) {
			if (Files.isDirectory(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		return null;
	}

	/**
	 * Resolves a payload file the same way {@link #payloadDirectory(String)} resolves a directory.
	 *
	 * @param relative the payload-relative file path; must not be absolute
	 * @return the first existing file, or null when no candidate exists
	 */
	public static @Nullable Path payloadFile(String relative) {
		Path bundled = nativeDir().resolve(relative);
		if (Files.isRegularFile(bundled)) {
			return bundled;
		}
		for (Path candidate : developmentPayloadCandidates(relative)) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		return null;
	}

	/** The development-tree candidates for a payload-relative path, in search order. */
	public static List<Path> developmentPayloadCandidates(String relative) {
		Path relativePath = Path.of(relative);
		if (relativePath.isAbsolute()) {
			throw new IllegalArgumentException("payload path must be relative: " + relative);
		}
		Path fromRepoRoot = Path.of(BUILD_DIR).resolve(relativePath);
		return List.of(
				fromRepoRoot,
				Path.of("native", "build").resolve(relativePath),
				Path.of("..", "native", "build").resolve(relativePath),
				Path.of("..", "..", "native", "build").resolve(relativePath));
	}

	/** Directory that holds the native payload for this run; always present (it may not exist yet). */
	public static Path nativeDir() {
		String override = System.getProperty(NATIVE_DIR_PROPERTY);
		if (override == null || override.isBlank()) {
			override = System.getenv(NATIVE_DIR_ENV);
		}
		if (override != null && !override.isBlank()) {
			return Path.of(override).toAbsolutePath().normalize();
		}
		Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		return resolveNativeDir(appImageDir(), workingDirectory);
	}

	static Path resolveNativeDir(@Nullable Path appImageDir, Path workingDirectory) {
		if (appImageDir != null) {
			return appImageDir.resolve(LIB_NATIVE).resolve(ARCH_TAG);
		}
		Path repoBuild = workingDirectory.resolve(BUILD_DIR);
		if (Files.isDirectory(repoBuild)) {
			return preferArchDirectory(repoBuild);
		}
		// The build helper runs Maven from java/; accept that working directory too.
		Path fromJavaDirectory = workingDirectory.resolve("native/build");
		if (Files.isDirectory(fromJavaDirectory)) {
			return preferArchDirectory(fromJavaDirectory);
		}
		return repoBuild;
	}

	private static Path preferArchDirectory(Path buildDirectory) {
		Path archDirectory = buildDirectory.resolve(ARCH_TAG);
		return Files.isDirectory(archDirectory) ? archDirectory : buildDirectory;
	}

	/** The app image root, or {@code null} when this is not a jpackage launcher. */
	static @Nullable Path appImageDir() {
		String appPath = System.getProperty(APP_PATH_PROPERTY);
		if (appPath == null || appPath.isBlank()) {
			return null;
		}
		Path launcher = Path.of(appPath).toAbsolutePath().normalize();
		Path binDirectory = launcher.getParent();
		if (binDirectory == null || binDirectory.getFileName() == null
				|| !"bin".equals(binDirectory.getFileName().toString())) {
			return null;
		}
		return binDirectory.getParent();
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("native helper name must not be blank");
		}
		if (name.contains("/") || name.contains("\\")) {
			throw new IllegalArgumentException("native helper name must be a plain file name: " + name);
		}
		return name;
	}

	/** Guards a payload-relative path: relative, non-blank and never climbing out of the payload. */
	private static Path requireRelative(String relative) {
		if (relative == null || relative.isBlank()) {
			throw new IllegalArgumentException("payload path must not be blank");
		}
		Path path = Path.of(relative);
		if (path.isAbsolute()) {
			throw new IllegalArgumentException("payload path must be relative: " + relative);
		}
		if (path.normalize().startsWith("..")) {
			throw new IllegalArgumentException("payload path must not climb out of the payload: " + relative);
		}
		return path;
	}
}
