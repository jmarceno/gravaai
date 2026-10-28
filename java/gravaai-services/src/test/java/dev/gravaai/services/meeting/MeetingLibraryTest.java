package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour of the meetings library (electron/ipc/meeting/library.ts): registration writes the
 * sidecar and starts processing, listing is newest-first and skips orphan metadata, imports copy
 * supported media with the file name as title, rename rejects blanks, and deleting only trashes
 * sidecars that exist.
 */
class MeetingLibraryTest {

	private Path userData;
	private Path recordings;
	private MeetingMetaStore metaStore;
	private MeetingSettingsService settings;
	private MeetingTestSupport.FakeTrash trash;
	private MeetingLibrary library;
	private final List<Path> processed = new ArrayList<>();

	@BeforeEach
	void setUp(@TempDir Path directory) throws Exception {
		userData = directory.resolve("user-data");
		recordings = directory.resolve("recordings");
		Files.createDirectories(userData);
		Files.createDirectories(recordings);
		metaStore = new MeetingMetaStore();
		settings = new MeetingSettingsService(new MeetingTestSupport.FakeSettingsStore(),
				new MeetingTestSupport.FakeApiKeyStore(), new MeetingAssets(userData));
		trash = new MeetingTestSupport.FakeTrash();
		AtomicInteger ids = new AtomicInteger();
		MeetingJobs jobs = new MeetingJobs((mediaPath, mode, settingsValue, signal, onStatus) -> {
			processed.add(mediaPath);
		}, settings::read, metaStore, MeetingTestSupport.CLOCK,
				() -> "0000" + ids.incrementAndGet(), MeetingTestSupport.DIRECT_EXECUTOR,
				path -> {
				});
		library = new MeetingLibrary(() -> recordings, metaStore, jobs, settings, trash,
				MeetingTestSupport.CLOCK, () -> "abc" + ids.incrementAndGet() + "def");
	}

	private Path media(String name) throws Exception {
		Path path = recordings.resolve(name);
		Files.writeString(path, "meeting-bytes");
		return path;
	}

	@Test
	@DisplayName("registers a recording and starts auto processing")
	void registersRecordingAndStartsProcessing() throws Exception {
		Path media = media("recording-1.mkv");
		library.registerMeetingRecording(media, true);
		MeetingMeta meta = metaStore.read(media);
		assertEquals("meeting", meta.kind());
		assertEquals(Boolean.TRUE, meta.hasVideo());
		assertEquals("2026-10-27T18:00:00.123Z", meta.createdAt());
		assertEquals(List.of(media), processed);
		List<MeetingLibraryEntry> meetings = library.listMeetings();
		assertEquals(1, meetings.size());
		assertEquals(media, meetings.get(0).mediaPath());
		assertFalse(meetings.get(0).hasTranscript());
		assertFalse(meetings.get(0).hasNotes());
	}

	@Test
	@DisplayName("registration skips processing when auto processing is off")
	void registrationSkipsProcessingWhenAutoProcessDisabled() throws Exception {
		settings.write(Map.of("autoProcess", false));
		library.registerMeetingRecording(media("recording-2.mkv"), false);
		assertTrue(processed.isEmpty());
	}

	@Test
	@DisplayName("lists meetings newest first")
	void listsMeetingsNewestFirst() throws Exception {
		Path older = media("older.mp4");
		Path newer = media("newer.mp4");
		library.registerMeetingRecording(older, true);
		library.registerMeetingRecording(newer, true);
		library.renameMeeting(older, "Old review");
		library.renameMeeting(newer, "New review");
		metaStore.update(older, Map.of("createdAt", "2026-10-26T10:00:00.000Z"));
		metaStore.update(newer, Map.of("createdAt", "2026-10-27T10:00:00.000Z"));
		List<MeetingLibraryEntry> meetings = library.listMeetings();
		assertEquals(List.of(newer, older),
				meetings.stream().map(MeetingLibraryEntry::mediaPath).toList());
		assertEquals(List.of("New review", "Old review"),
				meetings.stream().map(MeetingLibraryEntry::title).toList());
	}

	@Test
	@DisplayName("skips meeting metadata with no media file next to it")
	void skipsOrphanMeetingMetadata() throws Exception {
		Path orphan = recordings.resolve("gone.mp4");
		metaStore.update(orphan, Map.of("kind", "meeting", "createdAt",
				"2026-10-27T10:00:00.000Z"));
		assertTrue(library.listMeetings().isEmpty());
	}

	@Test
	@DisplayName("refuses to import unsupported file types")
	void refusesUnsupportedImportFileTypes() throws Exception {
		Path source = Files.writeString(userData.resolve("notes.txt"), "nope");
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> library.importMeetingFile(source));
		assertEquals("Gravaai cannot import .txt files as meetings.", error.getMessage());
	}

	@Test
	@DisplayName("imports a media file into the library")
	void importsMediaFileIntoLibrary() throws Exception {
		byte[] bytes = "audio-bytes".getBytes();
		Path source = Files.write(userData.resolve("Weekly sync.m4a"), bytes);
		Path imported = library.importMeetingFile(source);
		assertTrue(imported.startsWith(recordings));
		assertTrue(imported.getFileName().toString().startsWith("meeting-"));
		assertEquals(".m4a", imported.getFileName().toString().substring(
				imported.getFileName().toString().lastIndexOf('.')));
		assertArrayEquals(bytes, Files.readAllBytes(imported));
		MeetingMeta meta = metaStore.read(imported);
		assertEquals("meeting", meta.kind());
		assertEquals("Weekly sync", meta.title());
		assertEquals(Boolean.FALSE, meta.hasVideo());
	}

	@Test
	@DisplayName("renames a meeting with user title source")
	void renamesMeetingWithUserTitleSource() throws Exception {
		Path media = media("rename.mkv");
		library.registerMeetingRecording(media, false);
		assertEquals("Quarterly review", library.renameMeeting(media, "  Quarterly review  "));
		MeetingMeta meta = metaStore.read(media);
		assertEquals("Quarterly review", meta.title());
		assertEquals("user", meta.titleSource());
		assertThrows(IllegalStateException.class, () -> library.renameMeeting(media, "   "));
		String longTitle = "x".repeat(150);
		library.renameMeeting(media, longTitle);
		assertEquals(120, metaStore.read(media).title().length());
	}

	@Test
	@DisplayName("trash only moves media and sidecars that exist")
	void trashMovesOnlyExistingSidecars() throws Exception {
		Path media = media("delete-me.mkv");
		library.registerMeetingRecording(media, true);
		MeetingFiles files = MeetingFiles.meetingFilesFor(media);
		Files.writeString(files.transcript(), "transcript");
		Files.writeString(files.notes(), "notes");
		List<Path> trashed = library.trashMeeting(media);
		assertEquals(List.of(media, files.transcript(), files.notes(), files.meta()), trashed);
		assertEquals(trashed, trash.trashed);
	}
}
