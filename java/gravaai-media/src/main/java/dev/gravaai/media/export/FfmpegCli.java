package dev.gravaai.media.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * Locates and runs the bundled software ffmpeg CLI (plan §5.10; software-only encode route).
 *
 * <p>Resolution order mirrors {@code dev.gravaai.media.ffmpeg.FfmpegLibs}: the native payload
 * locator, then the development {@code java/native/build/ffmpeg} directory from the repo or module
 * working directory. {@code -Dgravaai.ffmpeg.bin} overrides the binary for tests.
 */
public final class FfmpegCli {

	private FfmpegCli() {}

	/** The ffmpeg binary used for export encodes. */
	public static Path binary() {
		return resolve("ffmpeg");
	}

	/** The ffprobe binary (used by tests and diagnostics). */
	public static Path ffprobe() {
		return resolve("ffprobe");
	}

	/** {@code ffmpeg -version} first line, for doctor output and tests. */
	public static String version() {
		ProcessRunner runner = ProcessRunner.of(binary().toString(), "-hide_banner", "-version");
		try {
			var result = runner.run();
			if (!result.succeeded()) {
				throw new IllegalStateException("ffmpeg -version failed: " + result.stderr());
			}
			String stdout = result.stdout();
			int newline = stdout.indexOf('\n');
			return (newline < 0 ? stdout : stdout.substring(0, newline)).trim();
		} catch (IOException | InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("could not run ffmpeg: " + error.getMessage(), error);
		}
	}

	private static Path resolve(String name) {
		String explicit = System.getProperty("gravaai." + name + ".bin");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit).toAbsolutePath().normalize();
		}
		List<Path> candidates = List.of(
				NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve(name),
				Path.of("native", "build", "ffmpeg", "bin", name),
				Path.of("..", "native", "build", "ffmpeg", "bin", name),
				Path.of("..", "..", "native", "build", "ffmpeg", "bin", name),
				Path.of("..", "..", "..", "native", "build", "ffmpeg", "bin", name));
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new IllegalStateException("bundled ffmpeg CLI not found (tried " + candidates
				+ "); run java/native/build-ffmpeg.sh");
	}
}
