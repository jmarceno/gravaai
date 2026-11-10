package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.export.ExportAudioGraph.GraphInput;
import dev.gravaai.media.export.ExportAudioGraph.TrackInput;
import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * Acceptance for the programme loudness stage: the real bundled ffmpeg renders the
 * {@link ExportAudioGraph} filtergraph and the EBU R128 integrated loudness of the written WAV must
 * land within 1 LU of the requested target.
 *
 * <p>The stimulus is three mixed sines ({@code sine} + {@code amix} + {@code volume}), which only
 * needs filters the payload already carries for the export itself and is fully deterministic: a
 * steady programme level is the cleanest case for a single-pass {@code loudnorm} and cannot be
 * confused with a noisy source.
 */
class ExportAudioCleanupLoudnessTest {

	/** The {@code I: -16.0 LUFS} line of the ebur128 summary (and of every per-frame log line). */
	private static final Pattern INTEGRATED_LOUDNESS =
			Pattern.compile("I:\\s*(-?\\d+(?:\\.\\d+)?)\\s*LUFS");

	private static final double TARGET_LUFS = -16;
	private static final double TOLERANCE_LU = 1;

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("normalizes the mixed output to within 1 LU of the requested -16 LUFS target")
	void loudnessTargetIsReached() throws IOException, InterruptedException {
		Path ffmpeg = ffmpegBinary();
		// A payload without the loudness filters cannot exercise this feature; the export itself
		// would fail the same way, so that is a build problem, not a skip.
		assertTrue(hasFilter(ffmpeg, "loudnorm"),
				"the bundled ffmpeg has no loudnorm filter (java/native/build-ffmpeg.sh)");
		assertTrue(hasFilter(ffmpeg, "ebur128"),
				"the bundled ffmpeg has no ebur128 filter (java/native/build-ffmpeg.sh)");
		Path stimulus = tempDir.resolve("stimulus.wav");
		writeStimulus(ffmpeg, stimulus);

		// One full-length track with the loudness target on the mix; no cleanup stages and no
		// RNNoise model are involved, so the null model path is the documented "no model" case.
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput(stimulus.toString(), 1.0)), List.of(), List.of(), List.of(),
				4_000, 1.0, List.of(), TARGET_LUFS, null));
		Path rendered = tempDir.resolve("rendered.wav");
		render(ffmpeg, stimulus, plan, rendered);

		double integratedLufs = measureIntegratedLoudness(ffmpeg, rendered);
		assertEquals(TARGET_LUFS, integratedLufs, TOLERANCE_LU,
				"integrated loudness " + integratedLufs + " LUFS, target " + TARGET_LUFS + " LUFS");
	}

	/**
	 * Three simultaneous sines at 220/1000/3000 Hz, mixed at unity and turned down to 0.5: a steady
	 * broadband programme at a known level, so the only thing that can move the measured loudness
	 * is the loudness stage under test.
	 */
	private static void writeStimulus(Path ffmpeg, Path stimulus) throws IOException,
			InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(ffmpeg.toString());
		command.addAll(List.of("-y", "-hide_banner", "-loglevel", "error"));
		for (int frequency : List.of(220, 1_000, 3_000)) {
			command.addAll(List.of("-f", "lavfi", "-i",
					"sine=frequency=" + frequency + ":sample_rate=48000:duration=4"));
		}
		command.addAll(List.of("-filter_complex",
				"[0:a][1:a][2:a]amix=inputs=3:duration=longest:normalize=0,volume=0.5"
						+ ",aformat=sample_fmts=s16:channel_layouts=stereo[out]",
				"-map", "[out]", "-c:a", "pcm_s16le", stimulus.toString()));
		ProcessResult result = ProcessRunner.run(command, Duration.ofSeconds(120));
		assertTrue(result.succeeded(), "could not write the stimulus: " + result.stderr());
	}

	/**
	 * Runs the graph the exporter would run: input 0 stands in for the RGBA video pipe the graph
	 * convention reserves, so the track file is input 1 exactly as {@link Mp4Encoder} passes it.
	 */
	private static void render(Path ffmpeg, Path stimulus, ExportAudioPlan plan, Path rendered)
			throws IOException, InterruptedException {
		assertTrue(plan.hasAudio(), "the graph should have audio");
		List<String> command = new ArrayList<>();
		command.add(ffmpeg.toString());
		command.addAll(List.of("-y", "-hide_banner", "-loglevel", "error",
				"-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
				"-i", stimulus.toString(),
				"-filter_complex", plan.filterComplex(),
				"-map", "[aout]", "-c:a", "pcm_s16le", rendered.toString()));
		ProcessResult result = ProcessRunner.run(command, Duration.ofSeconds(120));
		assertTrue(result.succeeded(), "could not render the graph: " + result.stderr());
	}

	/**
	 * The ebur128 summary is written to stderr after the per-frame log lines; the last {@code I:}
	 * match is the integrated value of the whole file.
	 */
	private static double measureIntegratedLoudness(Path ffmpeg, Path rendered)
			throws IOException, InterruptedException {
		ProcessResult result = ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner",
				"-nostats", "-loglevel", "info", "-i", rendered.toString(),
				"-af", "ebur128=peak=true", "-f", "null", "-"), Duration.ofSeconds(120));
		assertTrue(result.succeeded(), "could not measure the output: " + result.stderr());
		Matcher matcher = INTEGRATED_LOUDNESS.matcher(result.stderr());
		String integrated = null;
		while (matcher.find()) {
			integrated = matcher.group(1);
		}
		assertTrue(integrated != null, "no integrated loudness in the ebur128 output: "
				+ result.stderr());
		return Double.parseDouble(integrated);
	}

	private static Path ffmpegBinary() {
		try {
			return FfmpegCli.binary();
		} catch (IllegalStateException missing) {
			Assumptions.assumeTrue(false, "bundled ffmpeg CLI not found: " + missing.getMessage());
			throw new AssertionError("unreachable: the assumption aborted the test");
		}
	}

	/** Whether the binary was built with {@code filter}; the payload is a whitelist build. */
	private static boolean hasFilter(Path ffmpeg, String filter) throws IOException,
			InterruptedException {
		ProcessResult result = ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner",
				"-filters"), Duration.ofSeconds(60));
		assertTrue(result.succeeded(), "ffmpeg -filters failed: " + result.stderr());
		for (String line : result.stdout().split("\n")) {
			// Lines look like " .. loudnorm          A->A       EBU R128 loudness normalization".
			String[] parts = line.trim().split("\\s+");
			if (parts.length >= 2 && parts[1].equals(filter)) {
				return true;
			}
		}
		return false;
	}
}
