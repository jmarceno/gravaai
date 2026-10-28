package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioInput;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxResult;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.PlatformRecordingProcesses;

/**
 * The stop-time mux against the bundled ffmpeg CLI (software libx264/aac only). Before the fix the
 * recorder spawned the ffmpeg arguments without the program ("Cannot run program -hide_banner"),
 * so every screen recording stopped without producing a file.
 */
class CosmicRecorderMuxTest {

	private static final int RATE = 48_000;

	@Test
	@DisplayName("muxes the captured video and the raw audio tracks into the recording")
	void muxesVideoAndAudioIntoTheRecording(@TempDir Path dir) throws Exception {
		Path nativeBuild = repoNativeBuild();
		assumeTrue(nativeBuild != null, "java/native/build not found");
		String previous = System.getProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
		System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, nativeBuild.toString());
		try {
			muxInto(dir, nativeBuild.getParent().getParent());
		} finally {
			if (previous == null) {
				System.clearProperty(NativeHelpers.NATIVE_DIR_PROPERTY);
			} else {
				System.setProperty(NativeHelpers.NATIVE_DIR_PROPERTY, previous);
			}
		}
	}

	/** Walks up from the module directory to {@code java/native/build}. */
	private static Path repoNativeBuild() {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve("java/native/build");
			if (Files.isDirectory(candidate.resolve("ffmpeg"))) {
				return candidate;
			}
			candidate = dir.resolve("native/build");
			if (Files.isDirectory(candidate.resolve("ffmpeg"))) {
				return candidate;
			}
		}
		return null;
	}

	private void muxInto(Path dir, Path javaRoot) throws Exception {
		PlatformRecordingEnvironment environment = new PlatformRecordingEnvironment();
		Path ffmpeg = environment.ffmpeg();
		assumeTrue(Files.isExecutable(ffmpeg), "bundled ffmpeg not built: " + ffmpeg);

		// The committed 2 s parity fixture stands in for the capture helper's MKV (the bundled
		// ffmpeg has no lavfi test sources); remuxed, not re-encoded.
		Path fixture = javaRoot
				.resolve("parity/fixtures/media/h264-1080p60.mp4");
		assumeTrue(Files.isRegularFile(fixture), "parity fixture missing: " + fixture);
		Path video = dir.resolve(".recording-1.video.mkv");
		run(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
				"-i", fixture.toString(), "-an", "-c:v", "copy", video.toString()));
		Path mic = dir.resolve(".recording-1.mic.pcm");
		Path system = dir.resolve(".recording-1.system.pcm");
		Files.write(mic, sinePcm(440, 2));
		Files.write(system, sinePcm(1000, 2));
		Path output = dir.resolve("recording-1.mp4");

		CosmicRecorder recorder = new CosmicRecorder(() -> dir, new PlatformRecordingProcesses(),
				environment, null, null, null, () -> MicNoiseReduction.OFF);
		CosmicMuxResult result = recorder.mux(new CosmicMuxInput(
				video.toString(),
				List.of(new CosmicAudioInput(CosmicAudioRole.MIC, mic.toString(), 1000),
						new CosmicAudioInput(CosmicAudioRole.SYSTEM, system.toString(), 1000)),
				1000, output.toString(), false, MicNoiseReduction.OFF, false));

		assertTrue(result.success(), () -> "mux failed: " + result.error());
		assertTrue(Files.size(output) > 0, "the recording file is empty");
		Path ffprobe = ffmpeg.resolveSibling("ffprobe");
		assumeTrue(Files.isExecutable(ffprobe), "bundled ffprobe not built");
		String streams = run(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
				"stream=codec_type", "-of", "csv=p=0", output.toString()));
		assertEquals(List.of("video", "audio"), streams.lines().map(String::trim).sorted(
				(a, b) -> a.equals("video") ? -1 : b.equals("video") ? 1 : 0).toList());
	}

	private static byte[] sinePcm(double frequency, int seconds) {
		ByteBuffer buffer = ByteBuffer.allocate(RATE * seconds * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < RATE * seconds; i++) {
			short sample = (short) (Math.sin(2 * Math.PI * frequency * i / RATE) * 8000);
			buffer.putShort(sample).putShort(sample);
		}
		return buffer.array();
	}

	private static String run(List<String> command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(60, TimeUnit.SECONDS), "process timed out: " + command);
		assertEquals(0, process.exitValue(), () -> command + " failed: " + output);
		return output;
	}
}
