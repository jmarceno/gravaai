package dev.gravaai.media.audio;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * The edit-time cleanup filter chains ({@link AudioCleanup}) shared by the export graph and the
 * preview: noise reduction (RNNoise when the model is available, FFT denoise otherwise), a simple
 * high-pass and the EBU R128 loudness stage.
 *
 * <p>The chains are the record-time families from {@code MicNoiseReductionFilters}, minus its
 * automatic 80 Hz high-pass: at edit time the high-pass is a user-controlled stage, so a track
 * with noise reduction only must not also be rolled off. Every filter string here is plain ffmpeg
 * filtergraph syntax; the graph builders join them with commas.
 */
public final class AudioCleanupFilters {

	/** The RNNoise model shipped in the native payload, relative to the native directory. */
	public static final String RNNOISE_MODEL_PATH = "models/rnnoise/voice.rnnn";

	/** EBU R128 defaults for the loudness stage: -1.5 dBTP ceiling and the 11 LU range. */
	public static final double LOUDNORM_TRUE_PEAK_DB = -1.5;
	public static final double LOUDNORM_RANGE_LU = 11;

	/**
	 * The FFT denoise parameters used per strength when the RNNoise model is unavailable. The
	 * heavier levels drop the adaptive noise tracking ({@code tn=1}): with tracking on, afftdn
	 * absorbs a steady noise floor into its profile and the requested reduction never engages
	 * (~2 dB regardless of {@code nr}); with a static profile the reduction is honoured nearly
	 * one dB per {@code nr} dB. Measured against the shipped bundle: light ~2 dB, balanced ~12 dB
	 * and strong ~22 dB of white-noise reduction, each at a fraction of a dB of speech loss.
	 * Non-stationary noise is what the RNNoise path is for; these are the degraded-mode chains.
	 */
	private static final int FFT_LIGHT_NR = 10;
	private static final String FFT_LIGHT_CHAIN = "afftdn=nr=" + FFT_LIGHT_NR + ":nf=-50:tn=1";
	private static final int FFT_BALANCED_NR = 12;
	private static final String FFT_BALANCED_CHAIN = "afftdn=nr=" + FFT_BALANCED_NR + ":nf=-30";
	private static final int FFT_STRONG_NR = 22;
	private static final String FFT_STRONG_CHAIN = "afftdn=nr=" + FFT_STRONG_NR + ":nf=-30";

	private AudioCleanupFilters() {}

	/**
	 * The per-track filters for one cleanup: high-pass first (it also removes the rumble RNNoise
	 * would otherwise chase), then noise reduction. The loudness stage is not per-track: it belongs
	 * to the programme mix, see {@link #loudnormFilter(double)}.
	 */
	public static List<String> trackFilters(AudioCleanup cleanup,
			@Nullable String rnnoiseModelPath) {
		AudioCleanup resolved = cleanup == null ? AudioCleanup.NONE : cleanup;
		List<String> filters = new ArrayList<>();
		if (resolved.hasHighPass()) {
			filters.add("highpass=f=" + resolved.highPassHz());
		}
		filters.addAll(noiseReductionFilters(resolved, rnnoiseModelPath));
		return List.copyOf(filters);
	}

	/** The rate the noise-reduction stage runs at: RNNoise is 48 kHz only, and the delays below
	 *  are sample counts at this rate. */
	public static final int NOISE_REDUCTION_SAMPLE_RATE = 48_000;

	/**
	 * The latency of each denoiser at {@link #NOISE_REDUCTION_SAMPLE_RATE}, measured against the
	 * shipped ffmpeg by cross-correlating a clean signal with its filtered copy: {@code arnndn}
	 * holds back one 10 ms frame, {@code afftdn} 25 ms of overlap. Neither filter corrects its
	 * timestamps, so an uncompensated track plays late against the video and the other tracks.
	 */
	static final int ARNNDN_DELAY_SAMPLES = 480;
	static final int AFFTDN_DELAY_SAMPLES = 1200;

	/** How many samples (at 48 kHz) the noise-reduction stage of this cleanup delays the audio. */
	public static int delaySamples(AudioCleanup cleanup, @Nullable String rnnoiseModelPath) {
		int delay = 0;
		for (String filter : noiseReductionFilters(cleanup, rnnoiseModelPath)) {
			if (filter.startsWith("arnndn=")) {
				delay += ARNNDN_DELAY_SAMPLES;
			} else if (filter.startsWith("afftdn=")) {
				delay += AFFTDN_DELAY_SAMPLES;
			}
		}
		return delay;
	}

	/** {@link #delaySamples} in seconds: the extra input a trimmed span needs to stay full length. */
	public static double delaySeconds(AudioCleanup cleanup, @Nullable String rnnoiseModelPath) {
		return delaySamples(cleanup, rnnoiseModelPath) / (double) NOISE_REDUCTION_SAMPLE_RATE;
	}

	/**
	 * {@link #trackFilters} with the denoiser latency taken back out, so the cleaned track stays
	 * sample-aligned with the video and the other tracks: the audio is resampled to 48 kHz before
	 * the denoiser (which pins the latency to a known sample count), the delayed head is dropped
	 * and the timestamps are moved back by the same amount. The output is the input's length minus
	 * the latency; callers that need the full span feed {@link #delaySeconds} of extra input.
	 */
	public static List<String> alignedTrackFilters(AudioCleanup cleanup,
			@Nullable String rnnoiseModelPath) {
		AudioCleanup resolved = cleanup == null ? AudioCleanup.NONE : cleanup;
		return alignDelay(
				resolved.hasHighPass() ? List.of("highpass=f=" + resolved.highPassHz()) : List.of(),
				noiseReductionFilters(resolved, rnnoiseModelPath),
				delaySamples(resolved, rnnoiseModelPath));
	}

	/**
	 * Joins the stages before the denoiser, the denoiser and its latency compensation. Shared with
	 * the record-time mic chain, which has its own fixed high-pass.
	 */
	public static List<String> alignDelay(List<String> before, List<String> noiseReduction,
			int delaySamples) {
		return alignDelay(before, noiseReduction, delaySamples, 0);
	}

	/**
	 * {@link #alignDelay(List, List, int)} that also bounds the output to {@code keepSamples}
	 * (at 48 kHz) when positive. A span that is concatenated with others needs the bound: the FFT
	 * denoiser flushes a few milliseconds of extra tail at the end of its input, and every extra
	 * sample would push all later spans late.
	 */
	public static List<String> alignDelay(List<String> before, List<String> noiseReduction,
			int delaySamples, long keepSamples) {
		List<String> filters = new ArrayList<>(before);
		if (noiseReduction.isEmpty()) {
			return List.copyOf(filters);
		}
		filters.add("aresample=" + NOISE_REDUCTION_SAMPLE_RATE);
		filters.addAll(noiseReduction);
		if (delaySamples > 0 || keepSamples > 0) {
			filters.add("atrim=start_sample=" + delaySamples
					+ (keepSamples > 0 ? ":end_sample=" + (delaySamples + keepSamples) : ""));
			filters.add("asetpts=PTS-" + delaySamples + "/SR/TB");
		}
		return List.copyOf(filters);
	}

	/** The noise-reduction stage alone (empty for {@link AudioCleanup.NoiseReduction#OFF}). */
	public static List<String> noiseReductionFilters(AudioCleanup cleanup,
			@Nullable String rnnoiseModelPath) {
		AudioCleanup resolved = cleanup == null ? AudioCleanup.NONE : cleanup;
		String model = rnnoiseModelPath;
		return switch (resolved.noiseReduction()) {
			case OFF -> List.of();
			case LIGHT -> List.of(FFT_LIGHT_CHAIN);
			case BALANCED -> model == null
					? List.of(FFT_BALANCED_CHAIN)
					: List.of("arnndn=m=" + escapeFfmpegFilterValue(model) + ":mix=0.85");
			case STRONG -> model == null
					? List.of(FFT_STRONG_CHAIN)
					: List.of("arnndn=m=" + escapeFfmpegFilterValue(model) + ":mix=1",
							"afftdn=nr=6:nf=-50");
		};
	}

	/**
	 * The loudness filter for the programme mix, or {@code null} when the target is off. Single-pass
	 * {@code loudnorm} (dynamic): the preview has one ffmpeg process per segment and cannot measure
	 * the programme first, so each segment approximates the target dynamically. Exports should run
	 * {@link #loudnormFilter(double, MeasuredLoudness)} with the measurement pass instead.
	 */
	public static @Nullable String loudnormFilter(double targetLufs) {
		if (!Double.isFinite(targetLufs) || targetLufs >= 0) {
			return null;
		}
		return "loudnorm=I=" + number(targetLufs) + ":TP=" + number(LOUDNORM_TRUE_PEAK_DB)
				+ ":LRA=" + number(LOUDNORM_RANGE_LU);
	}

	/**
	 * The loudness filter for a measured programme: the two-pass form. The first pass renders the
	 * graph with {@code print_format=json} (see {@code ExportAudioLoudness}); this pass replays the
	 * exact same graph with {@code linear=true} and the measured values, so the stage is one
	 * constant gain (true-peak limited) instead of dynamic level riding, and the target is hit to
	 * a fraction of an LU regardless of how dynamic the programme is. When the programme's own
	 * loudness range exceeds the target range, ffmpeg degrades the stage to dynamic internally.
	 */
	public static @Nullable String loudnormFilter(double targetLufs,
			@Nullable MeasuredLoudness measured) {
		String dynamic = loudnormFilter(targetLufs);
		if (dynamic == null || measured == null) {
			return dynamic;
		}
		return dynamic + ":linear=true"
				+ ":measured_I=" + number2(measured.inputI())
				+ ":measured_TP=" + number2(measured.inputTp())
				+ ":measured_LRA=" + number2(measured.inputLra())
				+ ":measured_thresh=" + number2(measured.inputThresh())
				+ ":offset=" + number2(measured.targetOffset());
	}

	/** The {@code print_format=json} measurement values of one loudnorm pass over the programme. */
	public record MeasuredLoudness(double inputI, double inputTp, double inputLra,
			double inputThresh, double targetOffset) {}

	/** The RNNoise model in the native payload, or {@code null} when this build lacks it. */
	public static @Nullable Path rnnoiseModel() {
		Path model = NativeHelpers.nativeDir().resolve(RNNOISE_MODEL_PATH);
		return Files.isRegularFile(model) ? model : null;
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

	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** A loudness measurement value: loudnorm prints two decimals and expects the same back. */
	private static String number2(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
