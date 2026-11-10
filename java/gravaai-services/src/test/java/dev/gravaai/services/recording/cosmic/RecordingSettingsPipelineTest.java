package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.KeystrokeTypes;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.RecordingFrameRate;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.SelectedSource;

/**
 * The Settings → Recording choices reach the capture/encode pipeline (card "Recording settings:
 * fps, quality and save location"): the capture helper is spawned at the chosen frame rate and the
 * encoder with the chosen quality, on every desktop backend (both helpers share this command line).
 */
class RecordingSettingsPipelineTest {

	@TempDir
	Path recordings;

	/** Records every spawned command and answers with pipes that keep the pipeline alive. */
	private static final class CommandCapture implements RecordingProcesses {

		final List<List<String>> commands = new ArrayList<>();

		@Override
		public SpawnedProcess spawn(List<String> command, SpawnOptions options) {
			commands.add(List.copyOf(command));
			if (command.getFirst().endsWith("ffmpeg")) {
				return new FakeProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
			}
			return new FakeProcess(InputStream.nullInputStream(),
					new ByteArrayInputStream("FORMAT 2 2 bgra 0\n".getBytes(StandardCharsets.UTF_8)));
		}
	}

	private static final class FakeProcess implements SpawnedProcess {

		private final InputStream stdout;
		private final InputStream stderr;
		private final CountDownLatch exited = new CountDownLatch(1);

		FakeProcess(InputStream stdout, InputStream stderr) {
			this.stdout = stdout;
			this.stderr = stderr;
		}

		@Override
		public OutputStream stdin() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream stdout() {
			return stdout;
		}

		@Override
		public InputStream stderr() {
			return stderr;
		}

		@Override
		public long pid() {
			return 1;
		}

		@Override
		public boolean isAlive() {
			return exited.getCount() > 0;
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public void destroy() {
			exited.countDown();
		}

		@Override
		public void destroyForcibly() {
			exited.countDown();
		}
	}

	private static RecordingEnvironment environment() {
		return new RecordingEnvironment() {
			@Override
			public Path captureHelper() {
				return Path.of("/helpers/gravaai-wayland-capture");
			}

			@Override
			public Path cursorHelper() {
				return Path.of("/helpers/gravaai-wayland-cursor");
			}

			@Override
			public Path ffmpeg() {
				return Path.of("/helpers/ffmpeg");
			}

			@Override
			public @Nullable Path rnnoiseModel() {
				return null;
			}

			@Override
			public Path command(String name) {
				return Path.of("/usr/bin", name);
			}
		};
	}

	@Test
	@DisplayName("the capture helper runs at the chosen frame rate and the encoder at the chosen quality")
	void settingsReachThePipeline() throws Exception {
		CommandCapture processes = new CommandCapture();
		CosmicRecorder recorder = new CosmicRecorder(
				() -> recordings,
				processes,
				environment(),
				new RecordingEvents(),
				new CursorTelemetrySource() {
					@Override
					public long cursorCaptureStartTimeMs() {
						return 0;
					}

					@Override
					public List<CursorTelemetryPoint> snapshotAndTakePendingSamples() {
						return List.of();
					}
				},
				() -> List.<KeystrokeTypes.KeystrokeEvent>of(),
				() -> MicNoiseReduction.OFF,
				() -> RecordingFrameRate.FPS_30,
				() -> RecordingQuality.LOSSLESS);
		try {
			CosmicRecorder.StartResult result = recorder.startCosmicRecording(
					SelectedSource.screen("cosmic:Virtual-1", "Screen", null),
					new CosmicRecorder.RecordingOptions(false, false, null, null, false));
			assertTrue(result.success(), String.valueOf(result.message()));

			List<String> capture = processes.commands.stream()
					.filter(command -> command.getFirst().endsWith("gravaai-wayland-capture"))
					.findFirst()
					.orElseThrow();
			assertEquals("30", capture.getLast(), "the helper captures at the chosen fps: " + capture);

			List<String> encoder = processes.commands.stream()
					.filter(command -> command.getFirst().endsWith("ffmpeg"))
					.findFirst()
					.orElseThrow();
			assertEquals("1", encoder.get(encoder.indexOf("-crf") + 1),
					"the encoder runs at the chosen quality: " + encoder);
			assertEquals("60", encoder.get(encoder.indexOf("-g") + 1),
					"the keyframe interval follows the chosen fps: " + encoder);
		} finally {
			recorder.discardCosmicRecording();
			recorder.close();
		}
	}
}
