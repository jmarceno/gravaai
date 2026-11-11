package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.export.VideoEncoderProbe;
import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;

/**
 * The screen-recording hardware encode against the real GPU (card "GPU hardware encoding for export
 * and recording").
 *
 * <p>Opt-in, like {@code Mp4EncoderHardwareTest}: the ordinary suite must never open a GPU device.
 * Run it on this machine with
 * {@code -Dgravaai.gpuEncodeTests=true -Dgravaai.ffmpeg.bin=/usr/bin/ffmpeg}.
 *
 * <p>It builds the exact command the pipeline uses for a COSMIC capture (raw BGRA frames with
 * wallclock timestamps) and feeds it real frames, so the VA-API path is exercised end to end
 * without needing a compositor.
 */
@EnabledIfSystemProperty(named = "gravaai.gpuEncodeTests", matches = "true")
class CosmicHardwareRecordingTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("a COSMIC capture encodes on h264_vaapi and probes back as an H.264 MKV")
	void recordsOnTheGpu() throws Exception {
		VideoEncoderProbe probe = VideoEncoderProbe.bundled();
		VideoEncoderSpec hardware = probe.available().stream()
				.filter(VideoEncoderSpec::hardware)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no GPU encoder in " + probe.available()));
		CosmicVideoPipeline pipeline = new CosmicVideoPipeline(
				(command, options) -> {
					throw new UnsupportedOperationException("no processes in the command test");
				},
				new PlatformRecordingEnvironment(),
				new CosmicProcessIo((command, options) -> {
					throw new UnsupportedOperationException("no processes in the command test");
				}),
				VideoEncoderProbe.of(List.of(hardware)));
		String filters = String.join(",",
				dev.gravaai.media.export.VideoEncoderArguments.appendUpload(List.of("format=yuv420p"),
						hardware));
		List<String> command = pipeline.encoderCommand(new CaptureFormat(640, 360, "bgra", 0),
				filters, temp.resolve("capture.mkv"), 30, RecordingQuality.HIGH, hardware);

		// Feed the encoder from lavfi instead of a capture helper: the same raw BGRA frames, the
		// same wallclock timestamps and the same filters the recording path uses. The command was
		// built from the recording environment's ffmpeg path, which is relative to that module's
		// working directory, so resolve it against the configured binary instead.
		String ffmpeg = System.getProperty("gravaai.ffmpeg.bin");
		List<String> producer = List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
				"-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=1", "-vf", "format=bgra",
				"-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1");
		Path output = temp.resolve("capture.mkv");
		List<String> encode = new java.util.ArrayList<>(command);
		encode.set(0, ffmpeg);
		// The pipeline's binary resolves libva through LD_LIBRARY_PATH (the bundled shim dlopens it
		// by soname), which ProcessRunner adds for the app. This shell pipeline does not go through
		// ProcessRunner, so add the same directory here.
		String libraryPath = Path.of(ffmpeg).toAbsolutePath().getParent()
				.resolveSibling("lib").toString();
		ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c",
				String.join(" ", producer.stream().map(CosmicHardwareRecordingTest::quote).toList())
						+ " | " + String.join(" ",
								encode.stream().map(CosmicHardwareRecordingTest::quote).toList()))
				.redirectErrorStream(true);
		builder.environment().put("LD_LIBRARY_PATH", libraryPath
				+ (builder.environment().get("LD_LIBRARY_PATH") == null ? ""
						: ":" + builder.environment().get("LD_LIBRARY_PATH")));
		Process pipelineProcess = builder.start();
		assertTrue(pipelineProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS),
				"the encode did not finish in time");
		assertEquals(0, pipelineProcess.exitValue(), new String(
				pipelineProcess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(Files.isRegularFile(output) && Files.size(output) > 0);
		MediaInfo info = MediaProbe.probe(output);
		assertEquals("h264", info.video().orElseThrow().codec());
		assertEquals(640, info.video().orElseThrow().width());
		assertEquals(360, info.video().orElseThrow().height());
	}

	private static String quote(String argument) {
		return "'" + argument.replace("'", "'\\''") + "'";
	}
}
