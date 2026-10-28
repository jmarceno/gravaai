package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
