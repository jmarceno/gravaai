package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.KeystrokeTypes;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.SelectedSource;

/**
 * A recording can end outside the app: the desktop's screen-sharing indicator ("End" on KDE) or
 * the captured monitor going away makes the capture helper exit on its own. The recorder must then
 * ask for a normal stop, so the file is saved, instead of recording a pipe that no longer delivers
 * frames until the user notices.
 */
class CaptureEndedStopTest {

	@TempDir
	Path recordings;

	/** A child process whose exit the test controls. */
	private static final class FakeProcess implements SpawnedProcess {
		private final InputStream stdout;
		private final InputStream stderr;
		private final CountDownLatch exited = new CountDownLatch(1);

		FakeProcess(InputStream stdout, InputStream stderr) {
			this.stdout = stdout;
			this.stderr = stderr;
		}

		void exit() {
			exited.countDown();
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
		public int waitFor() throws InterruptedException {
			exited.await();
			return 0;
		}

		@Override
		public void destroy() {
			exit();
		}

		@Override
		public void destroyForcibly() {
			exit();
		}
	}

	private record Started(CosmicRecorder recorder, FakeProcess capture, RecordingEvents events) {
	}

	private Started startRecording() throws IOException {
		PipedOutputStream frames = new PipedOutputStream();
		FakeProcess capture = new FakeProcess(new PipedInputStream(frames),
				new ByteArrayInputStream("FORMAT 2 2 bgra 0\n".getBytes(StandardCharsets.UTF_8)));
		FakeProcess encoder = new FakeProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
		RecordingEvents events = new RecordingEvents();
		CursorTelemetrySource noTelemetry = new CursorTelemetrySource() {
			@Override
			public long cursorCaptureStartTimeMs() {
				return 0;
			}

			@Override
			public List<CursorTelemetryPoint> snapshotAndTakePendingSamples() {
				return List.of();
			}
		};
		CosmicRecorder recorder = new CosmicRecorder(
				() -> recordings,
				(command, options) -> command.getFirst().endsWith("ffmpeg") ? encoder : capture,
				new RecordingEnvironment() {
					@Override
					public Path captureHelper() {
						return Path.of("/helpers/gravaai-portal-capture");
					}

					@Override
					public Path cursorHelper() {
						return Path.of("/helpers/gravaai-kwin-cursor");
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
				},
				events,
				noTelemetry,
				() -> List.<KeystrokeTypes.KeystrokeEvent>of(),
				() -> MicNoiseReduction.OFF);
		CosmicRecorder.StartResult result = recorder.startCosmicRecording(
				SelectedSource.screen("cosmic:Virtual-1", "Screen", null),
				new CosmicRecorder.RecordingOptions(false, false, null, null, false));
		assertTrue(result.success(), String.valueOf(result.message()));
		return new Started(recorder, capture, events);
	}

	@Test
	@DisplayName("a capture helper that exits on its own requests a normal stop")
	void helperExitRequestsStop() throws Exception {
		Started started = startRecording();
		CompletableFuture<RecordingEvents.RecordingEvent> requested = new CompletableFuture<>();
		started.events().onRecordingStopRequested(requested::complete);

		started.capture().exit();

		RecordingEvents.RecordingEvent event = requested.get(5, TimeUnit.SECONDS);
		assertEquals("capture-ended", event.reason());
		started.recorder().discardCosmicRecording();
	}

	@Test
	@DisplayName("a helper the app itself stopped does not request another stop")
	void appStopDoesNotRequestStop() throws Exception {
		Started started = startRecording();
		CompletableFuture<RecordingEvents.@Nullable RecordingEvent> requested = new CompletableFuture<>();
		started.events().onRecordingStopRequested(requested::complete);

		started.recorder().discardCosmicRecording();
		started.capture().exit();

		Thread.sleep(300);
		assertFalse(requested.isDone(), "the app's own stop must not loop back as a stop request");
	}
}
