package dev.gravaai.platform.nativehelpers;

import java.nio.file.Files;
import java.nio.file.Path;
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

	/** Directory that holds the native payload for this run. */
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

	static Path resolveNativeDir(Path appImageDir, Path workingDirectory) {
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
	static Path appImageDir() {
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
}
