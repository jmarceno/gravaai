package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.media.audio.AudioCleanupFilters;

/**
 * Microphone noise-reduction filter chains, ported from
 * {@code electron/ipc/recording/micNoiseReduction.ts}. The chains themselves live in
 * {@link AudioCleanupFilters} so record-time and edit-time cleanup names cannot drift; this class
 * keeps the record-time policy: an 80 Hz high-pass always rides along, and levels that need the
 * RNNoise model degrade to the light FFT chain when the model is missing.
 */
public final class MicNoiseReductionFilters {

	public static final String MIC_NOISE_REDUCTION_SETTING = "micNoiseReduction";

	/** The record-time high-pass, applied before the denoiser. */
	public static final String RECORDING_HIGH_PASS = "highpass=f=80";

	private MicNoiseReductionFilters() {
	}

	/** Keeps a known level, anything else falls back to the default. */
	public static MicNoiseReduction normalizeMicNoiseReduction(@Nullable Object value) {
		for (MicNoiseReduction level : MicNoiseReduction.values()) {
			if (level.wire().equals(value)) {
				return level;
			}
		}
		return MicNoiseReduction.DEFAULT;
	}

	/**
	 * Escapes a value for an unquoted filter option inside a filtergraph; see
	 * {@link AudioCleanupFilters#escapeFfmpegFilterValue(String)}.
	 */
	public static String escapeFfmpegFilterValue(String value) {
		return AudioCleanupFilters.escapeFfmpegFilterValue(value);
	}

	/**
	 * Filters applied to the microphone track after its alignment filter. Balanced and strong need
	 * the RNNoise model; without it they fall back to the light chain.
	 */
	public static List<String> buildMicNoiseFilters(MicNoiseReduction level,
			@Nullable String modelPath) {
		if (level == MicNoiseReduction.OFF) {
			return List.of();
		}
		AudioCleanup.NoiseReduction strength = switch (level) {
			case OFF -> AudioCleanup.NoiseReduction.OFF;
			case LIGHT -> AudioCleanup.NoiseReduction.LIGHT;
			case BALANCED -> AudioCleanup.NoiseReduction.BALANCED;
			case STRONG -> AudioCleanup.NoiseReduction.STRONG;
		};
		List<String> filters = new ArrayList<>();
		filters.add(RECORDING_HIGH_PASS);
		if (level == MicNoiseReduction.LIGHT || modelPath == null) {
			filters.addAll(AudioCleanupFilters.noiseReductionFilters(
					new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 0, 0), null));
			return List.copyOf(filters);
		}
		filters.addAll(AudioCleanupFilters.noiseReductionFilters(
				new AudioCleanup(strength, 0, 0), modelPath));
		return List.copyOf(filters);
	}
}
