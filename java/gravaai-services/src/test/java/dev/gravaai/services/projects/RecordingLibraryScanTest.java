package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import dev.gravaai.core.storage.UserData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of the read side of {@code electron/ipc/recording/library.ts} (listRecordings). */
class RecordingLibraryScanTest {

	@TempDir
	Path tempDir;

	private UserData userData() {
		return new UserData(tempDir.resolve("Gravaai"));
	}

	@Test
	@DisplayName("lists non-empty recordings newest first and skips webcam sidecars and other files")
	void listsRecordingsNewestFirst() throws Exception {
		UserData userData = userData();
		Path root = userData.recordingsDir();
		Files.createDirectories(root);
		Path first = root.resolve("recording-1.mkv");
		Path second = root.resolve("recording-2.mp4");
		Path webcam = root.resolve("recording-1-webcam.mp4");
		Path empty = root.resolve("recording-3.mp4");
		Path notes = root.resolve("notes.txt");
		for (Path file : List.of(first, second, webcam, empty, notes)) {
			Files.writeString(file, file.equals(empty) ? "" : "abcdef");
		}
		long base = System.currentTimeMillis() - 50_000;
		Files.setLastModifiedTime(first, FileTime.fromMillis(base + 2_000));
		Files.setLastModifiedTime(second, FileTime.fromMillis(base + 3_000));
		Files.setLastModifiedTime(webcam, FileTime.fromMillis(base + 4_000));
		Files.setLastModifiedTime(empty, FileTime.fromMillis(base + 5_000));
		Files.setLastModifiedTime(notes, FileTime.fromMillis(base + 6_000));

		List<RecordingEntry> entries = new RecordingLibraryScan(new RecordingsDirectory(userData)).list(false);

		assertEquals(List.of(second, first), entries.stream().map(entry -> Path.of(entry.path())).toList());
		assertEquals("recording-2.mp4", entries.get(0).name());
		assertEquals(6, entries.get(0).bytes());
		assertEquals(base + 3_000, entries.get(0).createdAt());
		assertTrue(!entries.get(0).isAudio());
	}

	@Test
	@DisplayName("includes audio sources and webcam files for the raw library")
	void includesSourcesForRawLibrary() throws Exception {
		UserData userData = userData();
		Path root = userData.recordingsDir();
		Files.createDirectories(root);
		Path video = root.resolve("recording-1.mkv");
		Path webcam = root.resolve("recording-1-webcam.mp4");
		Path audio = root.resolve("music.wav");
		Path notes = root.resolve("notes.txt");
		for (Path file : List.of(video, webcam, audio, notes)) {
			Files.writeString(file, "abcdef");
		}
		long base = System.currentTimeMillis() - 50_000;
		Files.setLastModifiedTime(video, FileTime.fromMillis(base + 3_000));
		Files.setLastModifiedTime(webcam, FileTime.fromMillis(base + 2_000));
		Files.setLastModifiedTime(audio, FileTime.fromMillis(base + 1_000));

		List<RecordingEntry> entries = new RecordingLibraryScan(new RecordingsDirectory(userData)).list(true);

		assertEquals(List.of(video, webcam, audio), entries.stream().map(entry -> Path.of(entry.path())).toList());
		assertTrue(entries.get(2).isAudio());
	}
}
