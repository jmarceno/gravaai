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
		// The 25 ms the FFT denoiser holds back is dropped again, so the track stays aligned.
		assertTrue(graph.contains("asetpts=PTS-STARTPTS,highpass=f=120,aresample=48000,"
				+ "afftdn=nr=10:nf=-50:tn=1,atrim=start_sample=1200,asetpts=PTS-1200/SR/TB,"
				+ "apad,atrim=duration=2.000,asetpts=PTS-STARTPTS,volume=1.500,"
				+ "volume=eval=frame:volume='if(between("),
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
		// The cleanup sees the recording as captured: it runs before the clip speed.
		assertTrue(graph.contains(":a]atrim=start=0.000:end=1.000,asetpts=PTS-STARTPTS,"
				+ "highpass=f=80,atempo=1.5[track0_seg0]"), graph);
		assertTrue(graph.contains("concat=n=1:v=0:a=1[track0_concat]"), graph);
		assertTrue(graph.contains(
				"[track0_concat]apad,atrim=duration=1.000,asetpts=PTS-STARTPTS,"
						+ "volume=1.250[track0]"), graph);
	}

	@Test
	@DisplayName("Strong plays the rendered denoised copy in place of the track")
	void strongPlaysTheRenderedCopy() {
		AudioCleanup strong = new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 80, 0);
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, strong, "/cache/mic-clean.wav")),
				List.of(), List.of(new ExportAudioSegments.Segment(1_000, 2_000, 2)), List.of(),
				500, 1.0, List.of(), 0, "/m/voice.rnnn"));
		String graph = plan.filterComplex();
		assertTrue(plan.inputs().stream().anyMatch(p -> p.toString().equals("/cache/mic-clean.wav")),
				plan.inputs().toString());
		// The copy is cut like the track, then takes the high-pass and the clip speed; the
		// ffmpeg denoisers and their latency handling are gone.
		assertTrue(graph.contains(":a]atrim=start=1.000:end=2.000,asetpts=PTS-STARTPTS,"
				+ "highpass=f=80,atempo=2[track0_seg0]"), graph);
		assertFalse(graph.contains("arnndn="), graph);
		assertFalse(graph.contains("afftdn="), graph);
		assertFalse(graph.contains("start_sample"), graph);
	}

	@Test
	@DisplayName("Balanced mixes the original back in at its attenuation limit")
	void balancedMixesTheOriginalBackIn() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, BALANCED, "/cache/mic-clean.wav")),
				List.of(), List.of(), List.of(), 2_000, 1.0));
		String graph = plan.filterComplex();
		// 24 dB limit: 6.3 % of the original, padded from container zero like the copy was.
		assertTrue(graph.contains(":a]aresample=48000:first_pts=0,asetpts=PTS-STARTPTS,"
				+ "volume=0.063[track0_dry];"), graph);
		assertTrue(graph.contains(":a]asetpts=PTS-STARTPTS,volume=0.937[track0_wet];"), graph);
		assertTrue(graph.contains("[track0_dry][track0_wet]amix=inputs=2:duration=longest:"
				+ "normalize=0,apad,atrim=duration=2.000"), graph);
	}

	@Test
	@DisplayName("a rendered copy is ignored while noise reduction is off")
	void copyIgnoredWithoutNoiseReduction() {
		AudioCleanup highPassOnly = new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 80, 0);
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, highPassOnly, "/cache/mic-clean.wav")),
				List.of(), List.of(), List.of(), 2_000, 1.0));
		assertFalse(plan.inputs().stream().anyMatch(p -> p.toString().contains("mic-clean")),
				plan.inputs().toString());
	}

	@Test
	@DisplayName("a denoised segment keeps its exact length: lead-in and latency are dropped")
	void denoisedSegmentKeepsItsLength() {
		List<ExportAudioSegments.Segment> segments = List.of(
				new ExportAudioSegments.Segment(0, 1_000, 1),
				new ExportAudioSegments.Segment(3_000, 4_000, 2));
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0, BALANCED)), List.of(), segments,
				List.of(), 1_500, 1.0, List.of(), 0, "/m/voice.rnnn"));
		String graph = plan.filterComplex();
		// The first cut starts at the head of the file: no lead-in, 10 ms of tail and drop.
		assertTrue(graph.contains(":a]atrim=start=0.000:end=1.010,asetpts=PTS-STARTPTS,"
				+ "aresample=48000,arnndn=m=/m/voice.rnnn:mix=0.85,atrim=start_sample=480:end_sample=48480,"
				+ "asetpts=PTS-480/SR/TB[track0_seg0]"), graph);
		// The second cut decodes 500 ms of lead-in (24000 samples) to warm the denoiser up,
		// drops it with the latency, and only then takes the 2x speed.
		assertTrue(graph.contains(":a]atrim=start=2.500:end=4.010,asetpts=PTS-STARTPTS,"
				+ "aresample=48000,arnndn=m=/m/voice.rnnn:mix=0.85,atrim=start_sample=24480:end_sample=72480,"
				+ "asetpts=PTS-24480/SR/TB,atempo=2[track0_seg1]"), graph);
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
