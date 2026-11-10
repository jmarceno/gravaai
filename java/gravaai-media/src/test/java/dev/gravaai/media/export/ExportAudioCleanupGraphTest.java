package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.ClipTransitions.FadeWindow;
import dev.gravaai.media.audio.AudioCleanupFilters;
import dev.gravaai.media.export.ExportAudioGraph.GraphInput;
import dev.gravaai.media.export.ExportAudioGraph.MutedRange;
import dev.gravaai.media.export.ExportAudioGraph.RegionInput;
import dev.gravaai.media.export.ExportAudioGraph.TrackInput;

/**
 * Edit-time cleanup wiring in {@link ExportAudioGraph}: the per-track filter stages land between
 * the timeline shaping and the gain/mute envelope, and the programme loudness stage lands on the
 * mix between the master gain and the fade envelope.
 */
class ExportAudioCleanupGraphTest {

	private static final AudioCleanup LIGHT_HIGH_PASS =
			new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 120, 0);
	private static final AudioCleanup BALANCED =
			new AudioCleanup(AudioCleanup.NoiseReduction.BALANCED, 0, 0);

	@Test
	@DisplayName("runs the cleanup stages after the duration bound and before the gain and mute")
	void cleanupBeforeGainAndMute() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.5, LIGHT_HIGH_PASS)), List.of(),
				List.of(), List.of(), 2_000, 1.0,
				List.of(new MutedRange(0, 500), new MutedRange(1_000, 1_500))));
		String graph = plan.filterComplex();
		// The denoiser must see the recorder's levels, so both stages sit between the output
		// duration bound and the track gain; the mute envelope follows the gain.
		assertTrue(graph.contains("atrim=duration=2.000,asetpts=PTS-STARTPTS,highpass=f=120,"
				+ "afftdn=nr=10:nf=-50:tn=1,volume=1.500,volume=eval=frame:volume='if(between("),
				graph);
	}

	@Test
	@DisplayName("runs the cleanup stages after the clip concat of a segmented track")
	void cleanupAfterSegments() {
		List<ExportAudioSegments.Segment> segments = List.of(
				new ExportAudioSegments.Segment(0, 1_000, 1.5));
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 80, 0);
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.25, cleanup)), List.of(), segments,
				List.of(), 1_000, 1.0));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("atempo=1.5"), graph);
		assertTrue(graph.contains("concat=n=1:v=0:a=1[track0_concat]"), graph);
		assertTrue(graph.contains(
				"[track0_concat]apad,atrim=duration=1.000,asetpts=PTS-STARTPTS,highpass=f=80,"
						+ "volume=1.250[track0]"), graph);
	}

	@Test
	@DisplayName("leaves a track without cleanup untouched")
	void noneAddsNoFilter() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), List.of(),
				2_000, 1.0));
		String graph = plan.filterComplex();
		assertFalse(graph.contains("highpass="), graph);
		assertFalse(graph.contains("afftdn="), graph);
		assertFalse(graph.contains("arnndn="), graph);
		assertFalse(graph.contains("loudnorm="), graph);
	}

	@Test
	@DisplayName("falls back to the FFT denoiser when the RNNoise model is missing")
	void missingModelFallsBackToFft() {
		List<ExportAudioSegments.Segment> noSegments = List.of();
		// A missing model (null) is the documented degradation: the heavier level becomes a
		// stronger FFT denoise instead of failing the export.
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, BALANCED)), List.of(), noSegments,
				List.of(), 2_000, 1.0, List.of(), 0, null));
		assertTrue(plan.filterComplex().contains("afftdn=nr=12:nf=-30"),
				plan.filterComplex());
	}

	@Test
	@DisplayName("escapes the RNNoise model path for the filtergraph")
	void escapesModelPath() {
		String model = "/tmp/it's/a:b.rnnn";
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, BALANCED)), List.of(), List.of(),
				List.of(), 2_000, 1.0, List.of(), 0, model));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("arnndn=m=" + AudioCleanupFilters.escapeFfmpegFilterValue(model)
				+ ":mix=0.85"), graph);
		assertFalse(graph.contains("arnndn=m=" + model), "the raw path must not leak: " + graph);
	}

	@Test
	@DisplayName("puts the programme loudness stage between the master gain and the fades")
	void loudnormAfterMasterGainBeforeFades() {
		List<FadeWindow> windows = List.of(new FadeWindow(1_000, 1_500, null, 2_000));
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), windows,
				4_000, 1.5, List.of(), -16, null));
		String graph = plan.filterComplex();
		int masterGain = graph.indexOf("[mix]atrim=duration=4.000,asetpts=PTS-STARTPTS,"
				+ "volume=1.500,loudnorm=I=-16:TP=-1.5:LRA=11");
		int loudnorm = graph.indexOf("loudnorm=I=-16:TP=-1.5:LRA=11");
		int fade = graph.indexOf("volume=eval=frame");
		int format = graph.indexOf("aformat=sample_fmts=fltp:channel_layouts=stereo[aout]");
		assertTrue(masterGain >= 0, "master gain then loudness: " + graph);
		assertTrue(loudnorm < fade && fade < format, graph);
	}

	@Test
	@DisplayName("the measured loudness stage is the linear two-pass form")
	void measuredLoudnormIsLinear() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), List.of(),
				4_000, 1.0, List.of(), -16, null,
				new AudioCleanupFilters.MeasuredLoudness(-14.23, -8.51, 3.00, -24.77, 0.04)));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("loudnorm=I=-16:TP=-1.5:LRA=11:linear=true"
				+ ":measured_I=-14.23:measured_TP=-8.51:measured_LRA=3.00"
				+ ":measured_thresh=-24.77:offset=0.04"), graph);
		assertFalse(graph.contains("print_format"), graph);
	}

	@Test
	@DisplayName("adds no loudness stage when the target is off")
	void noLoudnormWhenOff() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), List.of(),
				2_000, 1.0, List.of(), 0, null));
		assertFalse(plan.filterComplex().contains("loudnorm="), plan.filterComplex());

		ExportAudioPlan defaults = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), List.of(),
				2_000, 1.0));
		assertFalse(defaults.filterComplex().contains("loudnorm="), defaults.filterComplex());
	}

	@Test
	@DisplayName("never applies track cleanup to imported audio regions")
	void regionsAreNotTracks() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, LIGHT_HIGH_PASS)),
				List.of(new RegionInput("/tmp/music.wav", 500, 1_500, 0.8)), List.of(), List.of(),
				2_000, 1.0));
		String graph = plan.filterComplex();
		String regionChain = graph.substring(graph.indexOf("[2:a]"),
				graph.indexOf("[region1]"));
		assertFalse(regionChain.contains("highpass="), regionChain);
		assertFalse(regionChain.contains("afftdn="), regionChain);
		assertTrue(graph.substring(0, graph.indexOf("[track0]")).contains("highpass=f=120"), graph);
	}

	@Test
	@DisplayName("keeps the 2-arg track input on a clean track")
	void trackInputCompatConstructor() {
		TrackInput track = new TrackInput("/tmp/mic.wav", 1.0);
		assertEquals(AudioCleanup.NONE, track.cleanup());
	}
}
