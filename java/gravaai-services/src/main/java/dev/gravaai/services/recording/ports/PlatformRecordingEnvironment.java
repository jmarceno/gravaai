package dev.gravaai.services.recording.ports;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.process.ExecutableLookup;

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
		Path nativeDirectory = NativeHelpers.nativeDir();
		if (nativeDirectory != null) {
			Path binary = nativeDirectory.resolve("ffmpeg").resolve("bin").resolve("ffmpeg");
			if (Files.isRegularFile(binary)) {
				return binary;
			}
			Path flat = nativeDirectory.resolve("ffmpeg");
			if (Files.isRegularFile(flat)) {
				return flat;
			}
		}
		// Development builds may place the CLI one directory up from the bundled libs.
		return NativeHelpers.path("ffmpeg");
	}

	@Override
	public @Nullable Path rnnoiseModel() {
		Path nativeDirectory = NativeHelpers.nativeDir();
		if (nativeDirectory == null) {
			return null;
		}
		Path model = nativeDirectory.resolve("models").resolve("rnnoise").resolve("voice.rnnn");
		return Files.isRegularFile(model) ? model : null;
	}

	@Override
	public Path command(String name) throws IOException {
		return ExecutableLookup.require(System.getenv(), name);
	}
}
