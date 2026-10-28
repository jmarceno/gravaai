package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ported from the listing/trash/undo cases of {@code electron/ipc/recording/library.test.ts}. The
 * import and thumbnail cases are separately ported with their own services.
 */
class RecordingLibraryTest {

	private static final class FakeTrash implements RecordingLibrary.Trash {

		private final Path root;
		int failures;

		FakeTrash(Path root) {
			this.root = root;
		}

		@Override
		public void trashItem(Path path) throws IOException {
			if (failures > 0) {
				failures--;
				throw new IOException("Trash unavailable");
			}
			Path trashDir = root.resolve(".test-trash");
			Files.createDirectories(trashDir);
			Files.move(path, trashDir.resolve(path.getFileName().toString()));
		}
	}

	@TempDir
	Path dir;

	private Path root;
	private FakeTrash trash;
	private RecordingLibrary library;

	@BeforeEach
	void setUp() throws IOException {
		root = dir.toRealPath();
		trash = new FakeTrash(root);
		library = new RecordingLibrary(() -> root, trash);
	}

	@AfterEach
	void tearDown() {
		try {
			library.clearRecordingTrashUndo();
		} catch (IOException | RuntimeException ignored) {
			// A test may leave a staged bundle on purpose.
		}
	}

	private void write(String name, String content) throws IOException {
		Files.writeString(root.resolve(name), content);
	}

	private List<String> listedNames(boolean includeSources) throws IOException {
		return library.listRecordings(includeSources).stream()
				.map(RecordingLibrary.RecordingLibraryEntry::name)
				.sorted()
				.toList();
	}

	@Test
	@DisplayName("lists recordings, moves recordings and their companions to Trash with reversible removals, and excludes companion media and symlinks")
	void listsRecordingsAndStagesReversibleRemovals() throws IOException {
		Path first = root.resolve("recording-old.mp4");
		Path second = root.resolve("recording-new.mov");
		for (String name : List.of(
				"recording-old.mp4", "recording-new.mov", "recording-new.webcam.mp4", "recording-new.mic.wav")) {
			write(name, "fixture");
		}
		Files.createSymbolicLink(root.resolve("linked.mp4"), first);

		assertEquals(
				List.of(first.toString(), second.toString()).stream().sorted().toList(),
				library.listRecordings(false).stream()
						.map(RecordingLibrary.RecordingLibraryEntry::path)
						.sorted()
						.toList());

		library.setRecordingsRemoved(List.of(first.toString(), second.toString()), true);
		assertEquals(List.of(), library.listRecordings(false));
		assertFalse(Files.exists(first));
		assertFalse(Files.exists(root.resolve("recording-new.mic.wav")));
		try (Stream<Path> entries = Files.list(root)) {
			assertEquals(1, entries
					.filter(entry -> entry.getFileName().toString().startsWith(".gravaai-trash-"))
					.count());
		}

		library.setRecordingsRemoved(List.of(first.toString(), second.toString()), false);
		assertEquals(2, library.listRecordings(false).size());
		assertEquals("fixture", Files.readString(first));
		assertEquals("fixture", Files.readString(root.resolve("recording-new.mic.wav")));

		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> library.setRecordingsRemoved(List.of("/tmp/outside.mp4"), true));
		assertEquals("Recording is outside the Videos library", error.getMessage());
	}

	@Test
	@DisplayName("retains staged originals for undo when the OS refuses Trash")
	void retainsStagedOriginalsWhenTrashFails() throws IOException {
		Path file = root.resolve("recording-failure.mp4");
		write("recording-failure.mp4", "original");
		library.setRecordingsRemoved(List.of(file.toString()), true);

		trash.failures = 1;
		IOException error = assertThrows(IOException.class, library::clearRecordingTrashUndo);
		assertEquals("Trash unavailable", error.getMessage());

		library.setRecordingsRemoved(List.of(file.toString()), false);
		assertEquals("original", Files.readString(file));
	}

	@Test
	@DisplayName("undo never overwrites a new file at the original location")
	void undoNeverOverwritesANewFile() throws IOException {
		Path file = root.resolve("recording-conflict.mp4");
		write("recording-conflict.mp4", "original");
		library.setRecordingsRemoved(List.of(file.toString()), true);
		write("recording-conflict.mp4", "new recording");

		IllegalStateException error = assertThrows(
				IllegalStateException.class,
				() -> library.setRecordingsRemoved(List.of(file.toString()), false));
		assertEquals("A file with this name already exists. Restore it from Trash instead.", error.getMessage());
		assertEquals("new recording", Files.readString(file));
	}

	@Test
	@DisplayName("restores on volumes without hard links and preserves conflicts")
	void restoresOnVolumesWithoutHardLinks() throws IOException {
		RecordingLibrary linkless = new RecordingLibrary(
				() -> root,
				trash,
				(existing, link) -> {
					throw new IOException("Operation not supported");
				});
		Path file = root.resolve("recording-exfat.mp4");
		write("recording-exfat.mp4", "recording");
		linkless.setRecordingsRemoved(List.of(file.toString()), true);

		linkless.setRecordingsRemoved(List.of(file.toString()), false);

		assertEquals("recording", Files.readString(file));
	}

	@Test
	@DisplayName("Raw includes camera and audio sources without including metadata or symlinks")
	void rawIncludesCameraAndAudioSources() throws IOException {
		for (String name : List.of(
				"screen.mp4", "screen.webcam.mp4", "screen.mic.wav", "screen.system.m4a", "screen.cursor.json")) {
			write(name, "fixture");
		}
		Files.createSymbolicLink(root.resolve("linked.mp4"), root.resolve("screen.mp4"));

		assertEquals(
				List.of("screen.mic.wav", "screen.mp4", "screen.system.m4a", "screen.webcam.mp4"),
				listedNames(true));
		assertEquals(List.of("screen.mp4"), listedNames(false));
	}

	@Test
	@DisplayName("only sidecars of the exact recording belong to its removal bundle")
	void onlyExactSidecarsBelongToTheBundle() {
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.mp4", "recording-1.mp4"));
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.mp4.cursor.json", "recording-1.mp4"));
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.mp4.keys.json", "recording-1.mp4"));
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.webcam.mp4", "recording-1.mp4"));
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.mic.wav", "recording-1.mp4"));
		assertEquals(true, RecordingLibrary.belongsToRecording("recording-1.gravaai-session.json", "recording-1.mp4"));
		assertEquals(false, RecordingLibrary.belongsToRecording("recording-10.mp4", "recording-1.mp4"));
		assertEquals(false, RecordingLibrary.belongsToRecording("recording-2.mp4", "recording-1.mp4"));
	}
}
