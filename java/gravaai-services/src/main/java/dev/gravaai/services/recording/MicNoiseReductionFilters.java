package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Microphone noise-reduction filter chains, ported from
 * {@code electron/ipc/recording/micNoiseReduction.ts}.
 */
public final class MicNoiseReductionFilters {

	public static final String MIC_NOISE_REDUCTION_SETTING = "micNoiseReduction";

	private static final List<String> LIGHT_FILTERS = List.of("highpass=f=80", "afftdn=nr=10:nf=-50:tn=1");

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
	 * Escapes a value for an unquoted filter option inside a filtergraph. ffmpeg unescapes twice:
	 * once for the option value (backslash, quote, colon) and once for the graph (backslash, quote,
	 * brackets, comma, semicolon), so both levels are applied. Quoting with '...' cannot hold a
	 * literal quote, which breaks paths that contain one.
	 */
	public static String escapeFfmpegFilterValue(String value) {
		StringBuilder optionLevel = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (ch == '\\' || ch == '\'' || ch == ':') {
				optionLevel.append('\\');
			}
			optionLevel.append(ch);
		}
		StringBuilder graphLevel = new StringBuilder(optionLevel.length());
		for (int index = 0; index < optionLevel.length(); index++) {
			char ch = optionLevel.charAt(index);
			if (ch == '\\' || ch == '\'' || ch == '[' || ch == ']' || ch == ',' || ch == ';') {
				graphLevel.append('\\');
			}
			graphLevel.append(ch);
		}
		return graphLevel.toString();
	}

	/**
	 * Filters applied to the microphone track after its alignment filter. Balanced and strong need
	 * the RNNoise model; without it they fall back to the light chain.
	 */
	public static List<String> buildMicNoiseFilters(MicNoiseReduction level, @Nullable String modelPath) {
		if (level == MicNoiseReduction.OFF) {
			return List.of();
		}
		if (level == MicNoiseReduction.LIGHT || modelPath == null) {
			return List.copyOf(LIGHT_FILTERS);
		}
		String model = escapeFfmpegFilterValue(modelPath);
		List<String> filters = new ArrayList<>();
		filters.add("highpass=f=80");
		if (level == MicNoiseReduction.BALANCED) {
			filters.add("arnndn=m=" + model + ":mix=0.85");
		} else {
			filters.add("arnndn=m=" + model + ":mix=1");
			filters.add("afftdn=nr=6:nf=-50");
		}
		return List.copyOf(filters);
	}
}
