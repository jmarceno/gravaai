package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.media.audio.AudioCleanupFilters;
import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * The measurement pass of the two-stage programme loudness normalization: the export graph is
 * rendered once into the null muxer with {@code loudnorm} in {@code print_format=json} mode, and
 * the measured values feed {@link AudioCleanupFilters#loudnormFilter(double,
 * AudioCleanupFilters.MeasuredLoudness)} so the real render normalizes linearly (one constant
 * gain) instead of dynamically.
 *
 * <p>The measurement renders the exact {@link ExportAudioPlan} graph with a silent input 0 in
 * place of the video stream the encoder convention reserves, so what is measured is precisely the
 * programme the export writes — cleanup, gains and all.
 */
public final class ExportAudioLoudness {

	/** One measured value of the loudnorm JSON block; these are strings like "-23.45". */
	private static final Pattern INPUT_I = Pattern.compile("\"input_i\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern INPUT_TP = Pattern.compile("\"input_tp\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern INPUT_LRA = Pattern.compile("\"input_lra\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern INPUT_THRESH =
			Pattern.compile("\"input_thresh\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TARGET_OFFSET =
			Pattern.compile("\"target_offset\"\\s*:\\s*\"([^\"]+)\"");

	/** An audio-only decode is one order faster than the export; ten minutes is plenty. */
	private static final Duration MEASURE_TIMEOUT = Duration.ofMinutes(10);

	private ExportAudioLoudness() {}

	/**
	 * Measures the programme of {@code plan} for the loudness target, or {@code null} when the
	 * measurement cannot run (no ffmpeg, decode error, silent programme) — the caller then keeps
	 * the plan's single-pass dynamic stage.
	 */
	public static AudioCleanupFilters.@Nullable MeasuredLoudness measure(Path ffmpeg,
			ExportAudioPlan plan, double targetLufs) {
		String dynamic = AudioCleanupFilters.loudnormFilter(targetLufs);
		String graph = plan.filterComplex();
		if (dynamic == null || graph == null || !graph.contains(dynamic)) {
			return null;
		}
		String measurementGraph = graph.replace(dynamic, dynamic + ":print_format=json");
		List<String> command = new ArrayList<>();
		command.add(ffmpeg.toString());
		command.addAll(List.of("-hide_banner", "-nostdin", "-loglevel", "info",
				"-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"));
		command.addAll(plan.inputArguments());
		command.addAll(List.of("-filter_complex", measurementGraph, "-map", "[aout]",
				"-f", "null", "-"));
		ProcessResult result;
		try {
			result = ProcessRunner.run(command, MEASURE_TIMEOUT);
		} catch (IOException | InterruptedException error) {
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
		}
		if (!result.succeeded()) {
			return null;
		}
		return parse(result.stderr());
	}

	/**
	 * The measurement values of the last loudnorm JSON block in ffmpeg's output, or {@code null}
	 * when the block is missing or the programme is silent ({@code input_i} of {@code -inf}).
	 */
	static AudioCleanupFilters.@Nullable MeasuredLoudness parse(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		String inputI = lastValue(INPUT_I, output);
		String inputTp = lastValue(INPUT_TP, output);
		String inputLra = lastValue(INPUT_LRA, output);
		String inputThresh = lastValue(INPUT_THRESH, output);
		String targetOffset = lastValue(TARGET_OFFSET, output);
		if (inputI == null || inputTp == null || inputLra == null || inputThresh == null
				|| targetOffset == null) {
			return null;
		}
		Double i = finite(inputI);
		Double tp = finite(inputTp);
		Double lra = finite(inputLra);
		Double thresh = finite(inputThresh);
		Double offset = finite(targetOffset);
		if (i == null || tp == null || lra == null || thresh == null || offset == null) {
			return null;
		}
		return new AudioCleanupFilters.MeasuredLoudness(i, tp, lra, thresh, offset);
	}

	/** The last match of {@code pattern} in {@code output}, or null. */
	private static @Nullable String lastValue(Pattern pattern, String output) {
		String value = null;
		Matcher matcher = pattern.matcher(output);
		while (matcher.find()) {
			value = matcher.group(1);
		}
		return value;
	}

	/** The parsed finite double of an ffmpeg loudness value, or null for {@code -inf}/junk. */
	private static @Nullable Double finite(String value) {
		try {
			double parsed = Double.parseDouble(value);
			return Double.isFinite(parsed) ? parsed : null;
		} catch (NumberFormatException error) {
			return null;
		}
	}
}
