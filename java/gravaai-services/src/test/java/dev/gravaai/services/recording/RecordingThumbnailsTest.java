package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/** Ported from the thumbnail cases of {@code electron/ipc/recording/library.test.ts}. */
class RecordingThumbnailsTest {

	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

	@TempDir
	Path recordings;

	@TempDir
	Path userData;

	private final List<List<String>> commands = new ArrayList<>();

	private static final class FakeProcess implements SpawnedProcess {

		private final byte[] stdout;
		private final int exitCode;

		FakeProcess(byte[] stdout, int exitCode) {
			this.stdout = stdout;
			this.exitCode = exitCode;
		}

		@Override
		public OutputStream stdin() {
			return null;
		}

		@Override
		public InputStream stdout() {
			return new java.io.ByteArrayInputStream(stdout);
		}

		@Override
		public InputStream stderr() {
			return new java.io.ByteArrayInputStream(new byte[0]);
		}

		@Override
		public long pid() {
			return 1;
		}

		@Override
		public boolean isAlive() {
			return false;
		}

		@Override
		public int waitFor() {
			return exitCode;
		}

		@Override
		public void destroy() {
		}

		@Override
		public void destroyForcibly() {
		}
	}

	private RecordingThumbnails thumbnails() {
		RecordingProcesses processes = (command, options) -> {
			commands.add(command);
			return new FakeProcess(JPEG, 0);
		};
		RecordingEnvironment environment = new RecordingEnvironment() {
			@Override
			public Path captureHelper() {
				return Path.of("/native/gravaai-wayland-capture");
			}

			@Override
			public Path cursorHelper() {
				return Path.of("/native/gravaai-wayland-cursor");
			}

			@Override
			public Path ffmpeg() {
				return Path.of("/native/ffmpeg");
			}

			@Override
			public Path rnnoiseModel() {
				return null;
			}

			@Override
			public Path command(String name) throws IOException {
				return Path.of("/usr/bin/" + name);
			}
		};
		return new RecordingThumbnails(() -> recordings, () -> userData, processes, environment);
	}

	@BeforeEach
	void setUp() throws IOException {
		Files.writeString(recordings.resolve("recording-1.mp4"), "fixture", StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("decodes one 320x180 still and reuses the cache")
	void decodesOneStillAndReusesTheCache() throws Exception {
		RecordingThumbnails thumbnails = thumbnails();
		String first = thumbnails.getRecordingThumbnail(
				recordings.resolve("recording-1.mp4").toString()).get();
		assertTrue(first.startsWith("data:image/jpeg;base64,"));
		assertArrayEquals(
				JPEG,
				Base64.getDecoder().decode(first.substring("data:image/jpeg;base64,".length())));

		String second = thumbnails.getRecordingThumbnail(
				recordings.resolve("recording-1.mp4").toString()).get();
		assertEquals(first, second);
		assertEquals(1, commands.size());
		assertTrue(commands.get(0).contains("scale=320:180:force_original_aspect_ratio=increase,crop=320:180"));
		assertTrue(commands.get(0).contains("mjpeg"));
	}

	@Test
	@DisplayName("rejects recordings outside the Videos library")
	void rejectsRecordingsOutsideTheLibrary() {
		RecordingThumbnails thumbnails = thumbnails();
		ExecutionException error = assertThrows(
				ExecutionException.class,
				() -> thumbnails.getRecordingThumbnail("/tmp/unapproved.mp4").get());
		assertTrue(String.valueOf(error.getCause()).contains("outside the Videos library"));
	}

	@Test
	@DisplayName("fails when ffmpeg cannot decode a preview frame")
	void failsWithoutAPreviewFrame() throws Exception {
		RecordingProcesses processes = (command, options) -> new FakeProcess(new byte[0], 0);
		RecordingEnvironment environment = new RecordingEnvironment() {
			@Override
			public Path captureHelper() {
				return Path.of("/native/capture");
			}

			@Override
			public Path cursorHelper() {
				return Path.of("/native/cursor");
			}

			@Override
			public Path ffmpeg() {
				return Path.of("/native/ffmpeg");
			}

			@Override
			public Path rnnoiseModel() {
				return null;
			}

			@Override
			public Path command(String name) {
				return Path.of("/usr/bin/" + name);
			}
		};
		RecordingThumbnails thumbnails = new RecordingThumbnails(
				() -> recordings, () -> userData, processes, environment);
		ExecutionException error = assertThrows(
				ExecutionException.class,
				() -> thumbnails.getRecordingThumbnail(
						recordings.resolve("recording-1.mp4").toString()).get());
		assertTrue(String.valueOf(error.getCause()).contains("no preview frame"));
	}
}
