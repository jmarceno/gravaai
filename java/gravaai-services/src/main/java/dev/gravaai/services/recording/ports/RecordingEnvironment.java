package dev.gravaai.services.recording.ports;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Native helpers, the bundled ffmpeg CLI and the RNNoise model the recorder uses (plan §5.5,
 * §5.6). The app wires the platform {@code NativeHelpers} into this port.
 */
public interface RecordingEnvironment {

	/** {@code gravaai-wayland-capture}. */
	Path captureHelper();

	/** {@code gravaai-wayland-cursor}. */
	Path cursorHelper();

	/** The bundled {@code ffmpeg} CLI (plan §5.7). */
	Path ffmpeg();

	/** The RNNoise model file, or null when it is not bundled. */
	@Nullable Path rnnoiseModel();

	/** Resolves a system tool such as {@code parec} or {@code pactl} on PATH. */
	Path command(String name) throws IOException;
}
