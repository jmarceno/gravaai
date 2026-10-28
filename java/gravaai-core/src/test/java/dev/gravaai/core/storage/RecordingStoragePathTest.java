package dev.gravaai.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code electron/ipc/recording/storagePath.test.ts}. */
class RecordingStoragePathTest {

	private static final Path RECORDINGS_DIR = Path.of("recordings-root").toAbsolutePath().normalize();

	@ParameterizedTest(name = "accepts an app-generated recording name: {0}")
	@ValueSource(strings = {
			"recording-0.webm",
			"recording-1720588800000.mp4",
			"recording-1720588800000-webcam.webm",
			"recording-1720588800000-webcam.mp4",
	})
	@DisplayName("accepts an app-generated recording name")
	void acceptsAppGeneratedRecordingNames(String fileName) {
		assertEquals(
				RECORDINGS_DIR.resolve(fileName),
				RecordingStoragePath.resolveRecordedVideoStoragePath(RECORDINGS_DIR, fileName));
	}

	@ParameterizedTest(name = "rejects an untrusted recording name: {0}")
	@ValueSource(strings = {
			"",
			"../recording-1.webm",
			"..\\recording-1.webm",
			"nested/recording-1.webm",
			"nested\\recording-1.webm",
			"/tmp/recording-1.webm",
			"C:\\temp\\recording-1.webm",
			"\\\\server\\share\\recording-1.webm",
			"recording-1.webm:payload",
			"recording-1.webm\n",
			"recording-1.webm\0",
			"recording--1.webm",
			"recording-1.5.webm",
			"recording-1.mov",
			"other-1.webm",
			"recording-1-WEBCAM.webm",
	})
	@DisplayName("rejects an untrusted recording name")
	void rejectsUntrustedRecordingNames(String fileName) {
		assertThrows(IllegalArgumentException.class,
				() -> RecordingStoragePath.resolveRecordedVideoStoragePath(RECORDINGS_DIR, fileName),
				"Invalid recording file name");
	}

	@ParameterizedTest(name = "rejects a non-string recording name: {0}")
	@MethodSource("nonStringNames")
	@DisplayName("rejects a non-string recording name")
	void rejectsNonStringRecordingNames(String label, Object value) {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> RecordingStoragePath.resolveRecordedVideoStoragePath(RECORDINGS_DIR, value));
		assertEquals("Invalid recording file name", error.getMessage());
	}

	private static Stream<Arguments> nonStringNames() {
		return Stream.of(
				Arguments.of("undefined", null),
				Arguments.of("null", null),
				Arguments.of("number", 1),
				Arguments.of("object", Map.of()),
				Arguments.of("array", List.of()));
	}
}
