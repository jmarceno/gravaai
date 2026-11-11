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

	/**
	 * Denoises one raw microphone capture before the mux (the speech denoiser of
	 * {@code dev.gravaai.media.denoise}), or null when this build cannot: the mux then falls back
	 * to the ffmpeg filter chain.
	 */
	default @Nullable MicDenoiser micDenoiser() {
		return null;
	}

	/** Turns a raw microphone capture into a cleaned raw capture of the same length. */
	@FunctionalInterface
	interface MicDenoiser {

		/**
		 * @param rawInput 48 kHz signed 16-bit interleaved PCM
		 * @param rawOutput where the cleaned PCM goes, same format and length
		 * @param channels the capture's channel count
		 * @param dryGain how much of the original stays in the result (0 = fully denoised)
		 */
		void denoise(Path rawInput, Path rawOutput, int channels, double dryGain)
				throws IOException;
	}

	/** Resolves a system tool such as {@code parec} or {@code pactl} on PATH. */
	Path command(String name) throws IOException;
}
