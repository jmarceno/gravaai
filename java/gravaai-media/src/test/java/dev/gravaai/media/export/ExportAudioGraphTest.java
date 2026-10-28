package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.project.ClipTransitions.FadeWindow;
import dev.gravaai.media.export.ExportAudioGraph.GraphInput;
import dev.gravaai.media.export.ExportAudioGraph.RegionInput;
import dev.gravaai.media.export.ExportAudioGraph.TrackInput;
import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;

/** {@link ExportAudioGraph} filter-graph construction (plan §5.10 audio routing). */
class ExportAudioGraphTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("returns no audio plan when there are no tracks or regions")
	void noAudio() {
		ExportAudioPlan plan = ExportAudioGraph.build(
				new GraphInput(List.of(), List.of(), List.of(), List.of(), 1_000, 1));
		assertTrue(!plan.hasAudio());
		assertEquals(ExportAudioPlan.NONE, plan);
	}

	@Test
	@DisplayName("silences the recording's audio over muted clips, not imported regions")
	void mutedClipsSilenceTracksOnly() {
		// The packaged export kept the audio of a muted clip (offlineAudioProcessor
		// mutedSourceOutputRangesSec was not ported).
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/screen.mp4", 1.0)),
				List.of(new ExportAudioGraph.RegionInput("/tmp/music.mp3", 0, 3_000, 1.0)),
				List.of(), List.of(), 3_000, 1.0,
				List.of(new ExportAudioGraph.MutedRange(0, 1_000),
						new ExportAudioGraph.MutedRange(2_000, 2_500))));
		String graph = plan.filterComplex();
		String trackChain = graph.substring(0, graph.indexOf("[track0]"));
		assertTrue(trackChain.contains(
				"volume=eval=frame:volume='if(between(t,0,1)+between(t,2,2.500),0,1)'"), graph);
		String regionChain = graph.substring(graph.indexOf("[track0]"), graph.indexOf("[region1]"));
		assertTrue(!regionChain.contains("between("), "imported audio regions stay audible: " + graph);
	}

	@Test
	@DisplayName("builds a single full-length track chain with the duration bound")
	void singleTrack() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), List.of(),
				2_000, 1.0));
		assertTrue(plan.hasAudio());
		assertEquals(1, plan.inputs().size());
		assertTrue(plan.filterComplex().contains("[1:a]asetpts=PTS-STARTPTS,apad,atrim=duration=2.000"),
				plan.filterComplex());
		assertTrue(plan.filterComplex().contains("amix=inputs=1:duration=longest:normalize=0"));
		assertTrue(plan.filterComplex().contains("aresample=48000"));
		assertTrue(plan.filterComplex().endsWith("[aout]"));
	}

	@Test
	@DisplayName("applies trims and atempo speed chains before the duration bound")
	void segmentsAndSpeed() {
		List<ExportAudioSegments.Segment> segments = List.of(
				new ExportAudioSegments.Segment(0, 1_000, 1),
				new ExportAudioSegments.Segment(1_000, 2_000, 1.5));
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), segments, List.of(),
				1_000, 1.0));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("atrim=start=0.000:end=1.000"), graph);
		assertTrue(graph.contains("atrim=start=1.000:end=2.000"), graph);
		assertTrue(graph.contains("atempo=1.5"), graph);
		assertTrue(graph.contains("concat=n=2:v=0:a=1"), graph);
		assertTrue(graph.contains("atrim=duration=1.000"), graph);
	}

	@Test
	@DisplayName("places audio regions with adelay and their gain")
	void audioRegion() {
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)),
				List.of(new RegionInput("/tmp/music.wav", 500, 1_500, 0.8)), List.of(), List.of(),
				2_000, 1.0));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("[2:a]atrim=duration=1.000,asetpts=PTS-STARTPTS,adelay=500|500,volume=0.800"),
				graph);
		assertTrue(graph.contains("amix=inputs=2:duration=longest:normalize=0"), graph);
	}

	@Test
	@DisplayName("applies the fade-black gain envelope to the whole mix")
	void fadeEnvelope() {
		List<FadeWindow> windows = List.of(
				new FadeWindow(1_000, 1_500, null, 2_000),
				new FadeWindow(3_000, 3_250, 3_400.0, 3_500));
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput("/tmp/mic.wav", 1.0)), List.of(), List.of(), windows,
				4_000, 1.0));
		String graph = plan.filterComplex();
		assertTrue(graph.contains("volume=eval=frame:volume='1-max("), graph);
		assertTrue(graph.contains("aresample=48000"), graph);
	}

	@Test
	@DisplayName("encodes an MP4 with the generated audio graph and the audio is audible")
	void encodesAudibleAudio() throws Exception {
		Path output = tempDir.resolve("audio-graph.mp4");
		List<ExportAudioSegments.Segment> segments = List.of(
				new ExportAudioSegments.Segment(0, 750, 1),
				new ExportAudioSegments.Segment(750, 1_500, 1.5));
		ExportAudioPlan plan = ExportAudioGraph.build(new GraphInput(
				List.of(new TrackInput(ExportFixtures.aac().toString(), 1.0)),
				List.of(new RegionInput(ExportFixtures.aac().toString(), 250, 750, 0.5)),
				segments, List.of(new FadeWindow(800, 1_000, null, 1_100)), 1_000, 1.0));
		Mp4Encoder.encode(new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, output, plan), new SyntheticFrameProvider(160, 90, 24),
				ExportProgressListener.NONE, ExportCancellation.NONE);
		MediaInfo info = MediaProbe.probe(output);
		assertTrue(info.hasAudio());
		assertEquals("aac", info.audio().orElseThrow().codec());
		assertEquals(1.0, info.durationSeconds(), 0.25);
		Path pcm = tempDir.resolve("audio.wav");
		var result = dev.gravaai.platform.process.ProcessRunner.run(List.of(
				FfmpegCli.binary().toString(), "-y", "-hide_banner", "-loglevel", "error",
				"-i", output.toString(), "-vn", "-ac", "2", "-ar", "48000", "-f", "wav",
				pcm.toString()), java.time.Duration.ofSeconds(60));
		assertTrue(result.succeeded(), result.stderr());
		byte[] bytes = Files.readAllBytes(pcm);
		int dataStart = 0;
		for (int index = 0; index + 8 <= bytes.length; index++) {
			if (bytes[index] == 'd' && bytes[index + 1] == 'a' && bytes[index + 2] == 't'
					&& bytes[index + 3] == 'a') {
				dataStart = index + 8;
				break;
			}
		}
		int max = 0;
		for (int index = dataStart; index + 2 <= bytes.length; index += 2) {
			int sample = (short) ((bytes[index] & 0xFF) | (bytes[index + 1] << 8));
			max = Math.max(max, Math.abs(sample));
		}
		assertTrue(max > 200, "decoded audio should be non-silent, peak " + max + " / 32768");
		assertTrue(bytes.length - dataStart >= 48_000 * 2 * 2 / 2,
				"decoded audio should cover about one second");
	}
}
