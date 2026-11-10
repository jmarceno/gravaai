package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.project.AudioCleanup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ffmpeg CLI argument builder for the preview audio PCM source (pure, no process starts). */
class FfmpegCliPcmSourceTest {

	private static final Path FFMPEG = Path.of("/opt/gravaai/ffmpeg");

	/** Locates the native ffmpeg build from the module or repo root (dev layout). */
	private static Path bundledFfmpeg() {
		for (Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath(); start != null;
				start = start.getParent()) {
			for (String relative : List.of(
					"native/build/ffmpeg/bin/ffmpeg",
					"native/build/linux-x64/ffmpeg/bin/ffmpeg",
					"java/native/build/ffmpeg/bin/ffmpeg",
					"java/native/build/linux-x64/ffmpeg/bin/ffmpeg")) {
				Path candidate = start.resolve(relative);
				if (Files.isExecutable(candidate)) {
					return candidate;
				}
			}
		}
		return Path.of("missing-ffmpeg");
	}

	@Test
	@DisplayName("decodes real audio from the bundled ffmpeg build (s16le muxer present)")
	void decodesAudioFixture() {
		Path ffmpeg = bundledFfmpeg();
		Path fixture = null;
		for (Path candidate : List.of(
				Path.of("parity", "fixtures", "media", "av-sync-2min.mp4"),
				Path.of("..", "parity", "fixtures", "media", "av-sync-2min.mp4"),
				Path.of("..", "..", "parity", "fixtures", "media", "av-sync-2min.mp4"))) {
			if (Files.isRegularFile(candidate)) {
				fixture = candidate.toAbsolutePath().normalize();
				break;
			}
		}
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);
		assumeTrue(fixture != null, "av-sync-2min.mp4 fixture not found");
		try (PcmSource source = FfmpegCliPcmSource.open(ffmpeg, fixture.toString(), 1.0, 1.0,
				List.of())) {
			float[] buffer = new float[1_920 * 2];
			int frames = source.read(buffer, 1_920);
			assertTrue(frames > 0, "the decoder must return audio frames");
			float peak = 0;
			for (int index = 0; index < frames * 2; index++) {
				peak = Math.max(peak, Math.abs(buffer[index]));
			}
			// Regression for the packaged "no audio" bug: the CLI used to fail with
			// "Requested output format 'f32le' is not known" and every preview read was silence.
			assertTrue(peak > 0.01f, "decoded preview audio must not be silent (peak=" + peak + ")");
		}
	}

	@Test
	@DisplayName("builds the 16-bit stereo 48 kHz pipe command at normal speed")
	void buildsNormalSpeedCommand() {
		List<String> command = FfmpegCliPcmSource.pcmCommand(
				FFMPEG, "/media/screen.mp4", 12.3456, 2.0, List.of());
		assertEquals(List.of(
				"/opt/gravaai/ffmpeg",
				"-hide_banner", "-loglevel", "error", "-nostdin",
				"-ss", "12.345600",
				"-t", "2.000000",
				"-i", "/media/screen.mp4",
				"-vn", "-f", "s16le", "-ar", "48000", "-ac", "2",
				"pipe:1"), command);
		assertFalse(command.contains("-af"));
	}

	@Test
	@DisplayName("adds the atempo chain before the output pipe")
	void addsAtempoChain() {
		List<String> command = FfmpegCliPcmSource.pcmCommand(
				FFMPEG, "/media/screen.mp4", 0, 1.5, List.of("atempo=2", "atempo=1.5"));
		int filterIndex = command.indexOf("-af");
		assertTrue(filterIndex > 0);
		assertEquals("atempo=2,atempo=1.5", command.get(filterIndex + 1));
		assertEquals("pipe:1", command.get(command.size() - 1));
	}

	@Test
	@DisplayName("omits the duration limit for unbounded segments")
	void omitsDurationLimit() {
		List<String> command = FfmpegCliPcmSource.pcmCommand(
				FFMPEG, "/media/screen.mp4", 0, 0, List.of());
		assertFalse(command.contains("-t"));
	}

	@Test
	@DisplayName("clamps negative or non-finite in-points to zero")
	void clampsInvalidInPoints() {
		List<String> negative = FfmpegCliPcmSource.pcmCommand(
				FFMPEG, "/media/screen.mp4", -3.5, 1, null);
		assertEquals("0.000000", negative.get(negative.indexOf("-ss") + 1));
		List<String> notFinite = FfmpegCliPcmSource.pcmCommand(
				FFMPEG, "/media/screen.mp4", Double.NaN, 1, List.of());
		assertEquals("0.000000", notFinite.get(notFinite.indexOf("-ss") + 1));
	}

	@Test
	@DisplayName("mixes every source track through its cleanup and gain into one s16le pipe")
	void buildsMixCommand() {
		List<String> command = FfmpegCliPcmSource.mixCommand(
				FFMPEG,
				List.of(new PreviewAudioTrack("/rec/system.wav", 0.5, AudioCleanup.NONE),
						new PreviewAudioTrack("/rec/mic.wav", 1,
								new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 80, 0))),
				0, 1.5, 2.0, List.of("atempo=2"));
		assertEquals("/opt/gravaai/ffmpeg", command.get(0));
		assertEquals(2, command.stream().filter("-i"::equals).count());
		// Every input is seeked/trimmed to the same source span before its own chain.
		assertEquals("1.500000",
				command.get(command.indexOf("-ss") + 1));
		String graph = command.get(command.indexOf("-filter_complex") + 1);
		assertTrue(graph.startsWith(
				"[0:a]atempo=2,volume=0.500,aresample=48000,aformat=sample_fmts=fltp:"
						+ "channel_layouts=stereo[ain0];"));
		assertTrue(graph.contains(
				// Cleanup first, delay-compensated, then the speed.
				"[1:a]highpass=f=80,aresample=48000,afftdn=nr=10:nf=-50:tn=1,"
						+ "atrim=start_sample=1200,asetpts=PTS-1200/SR/TB,atempo=2,aresample=48000,"
						+ "aformat=sample_fmts=fltp:channel_layouts=stereo[ain1];"));
		assertTrue(graph.contains("[ain0][ain1]amix=inputs=2:duration=longest:normalize=0[aout]"));
		assertFalse(graph.contains("loudnorm"));
		assertEquals("[aout]", command.get(command.indexOf("-map") + 1));
		assertTrue(command.contains("-vn"));
		assertEquals("s16le", command.get(command.indexOf("-f") + 1));
		assertEquals("pipe:1", command.get(command.size() - 1));
	}

	@Test
	@DisplayName("adds the programme loudness stage to the mix when a target is set")
	void mixAppliesLoudnorm() {
		List<String> command = FfmpegCliPcmSource.mixCommand(FFMPEG,
				List.of(PreviewAudioTrack.plain("/rec/mix.m4a")), -16, 0, 1, List.of());
		String graph = command.get(command.indexOf("-filter_complex") + 1);
		assertTrue(graph.contains("amix=inputs=1:duration=longest:normalize=0,"
				+ "loudnorm=I=-16:TP=-1.5:LRA=11[aout]"));
	}

	@Test
	@DisplayName("degrades the heavier cleanup levels when the RNNoise model is absent")
	void mixUsesFftFallback() {
		List<String> command = FfmpegCliPcmSource.mixCommand(FFMPEG,
				List.of(new PreviewAudioTrack("/rec/mic.wav", 1,
						new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 0, 0))),
				0, 0, 1, List.of());
		String graph = command.get(command.indexOf("-filter_complex") + 1);
		assertTrue(graph.contains("afftdn=nr=22:nf=-30"), graph);
	}

	@Test
	@DisplayName("the real bundled ffmpeg accepts the mix graph (two tracks, cleanup and loudness)")
	void decodesRealMix() {
		Path ffmpeg = bundledFfmpeg();
		Path fixture = null;
		for (Path candidate : List.of(
				Path.of("parity", "fixtures", "media", "av-sync-2min.mp4"),
				Path.of("..", "parity", "fixtures", "media", "av-sync-2min.mp4"),
				Path.of("..", "..", "parity", "fixtures", "media", "av-sync-2min.mp4"))) {
			if (Files.isRegularFile(candidate)) {
				fixture = candidate.toAbsolutePath().normalize();
				break;
			}
		}
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);
		assumeTrue(fixture != null, "av-sync-2min.mp4 fixture not found");
		List<PreviewAudioTrack> tracks = List.of(
				new PreviewAudioTrack(fixture.toString(), 0.7,
						new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 80, 0)),
				new PreviewAudioTrack(fixture.toString(), 0.5, AudioCleanup.NONE));
		try (PcmSource source = FfmpegCliPcmSource.openMix(ffmpeg, tracks, -16, 1.0, 1.0,
				List.of())) {
			float[] buffer = new float[1_920 * 2];
			int frames = source.read(buffer, 1_920);
			assertTrue(frames > 0, "the mixed decoder must return audio frames");
			float peak = 0;
			for (int index = 0; index < frames * 2; index++) {
				peak = Math.max(peak, Math.abs(buffer[index]));
			}
			assertTrue(peak > 0.01f, "mixed preview audio must not be silent (peak=" + peak + ")");
		}
	}
}
