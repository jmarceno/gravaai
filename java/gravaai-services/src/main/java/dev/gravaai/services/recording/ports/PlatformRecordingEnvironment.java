package dev.gravaai.services.recording.ports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * {@link RecordingEnvironment} backed by the platform native-helper locator and PATH lookup
 * (plan §5.5, §5.7). The app wires it into {@code AppContext}.
 */
public final class PlatformRecordingEnvironment implements RecordingEnvironment {

	@Override
	public Path captureHelper() {
		return NativeHelpers.require("gravaai-wayland-capture");
	}

	@Override
	public Path cursorHelper() {
		return NativeHelpers.require("gravaai-wayland-cursor");
	}

	@Override
	public Path ffmpeg() {
		Path binary = NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg");
		if (Files.isRegularFile(binary)) {
			return binary;
		}
		Path flat = NativeHelpers.nativeDir().resolve("ffmpeg");
		if (Files.isRegularFile(flat)) {
			return flat;
		}
		// Development builds may place the CLI one directory up from the bundled libs.
		return NativeHelpers.path("ffmpeg");
	}

	@Override
	public Path rnnoiseModel() {
		Path model = NativeHelpers.nativeDir().resolve("models").resolve("rnnoise").resolve("voice.rnnn");
		return Files.isRegularFile(model) ? model : null;
	}

	@Override
	public Path command(String name) throws IOException {
		return findOnPath(name);
	}

	/** Resolves an executable on PATH; throws when it is not installed. */
	public static Path findOnPath(String name) throws IOException {
		String path = System.getenv("PATH");
		if (path != null) {
			for (String directory : path.split(java.io.File.pathSeparator)) {
				if (directory.isEmpty()) {
					continue;
				}
				Path candidate = Path.of(directory).resolve(name);
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
					return candidate;
				}
			}
		}
		throw new IOException("Command not found on PATH: " + name);
	}
}
