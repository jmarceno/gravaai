package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import dev.gravaai.services.meeting.ports.RecordingDirectories;
import dev.gravaai.services.meeting.ports.Trash;

/**
 * The Meetings section of the library: list, register a recording as a meeting, import, rename
 * and trash. Ported from electron/ipc/meeting/library.ts.
 */
public final class MeetingLibrary {

	static final List<String> MEDIA_EXTENSIONS = List.of(
			"mp4", "mov", "webm", "mkv", "m4a", "mp3", "wav", "ogg", "opus", "flac", "aac");

	private static final List<String> VIDEO_EXTENSIONS = List.of("mp4", "mov", "webm", "mkv");

	private final RecordingDirectories directories;
	private final MeetingMetaStore metaStore;
	private final MeetingJobs jobs;
	private final MeetingSettingsService settings;
	private final Trash trash;
	private final Clock clock;
	private final Supplier<String> ids;

	public MeetingLibrary(RecordingDirectories directories, MeetingMetaStore metaStore,
			MeetingJobs jobs, MeetingSettingsService settings, Trash trash, Clock clock,
			Supplier<String> ids) {
		this.directories = directories;
		this.metaStore = metaStore;
		this.jobs = jobs;
		this.settings = settings;
		this.trash = trash;
		this.clock = clock;
		this.ids = ids;
	}

	public String defaultMeetingTitle(long createdAt) {
		ZonedDateTime date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault());
		Locale locale = Locale.getDefault();
		String day = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
				.format(date);
		String time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
				.format(date);
		return "Meeting " + day + " " + time;
	}

	public List<MeetingLibraryEntry> listMeetings() {
		Path directory = directories.recordingsDirectory();
		List<String> names;
		try (var files = Files.list(directory)) {
			names = files.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".meeting.json"))
					.toList();
		} catch (IOException error) {
			return List.of();
		}
		List<MeetingLibraryEntry> entries = new ArrayList<>();
		for (String name : names) {
			MeetingLibraryEntry entry = entryFor(directory.resolve(name));
			if (entry != null) {
				entries.add(entry);
			}
		}
		entries.sort(Comparator.comparingLong(MeetingLibraryEntry::createdAt).reversed());
		return List.copyOf(entries);
	}

	private MeetingLibraryEntry entryFor(Path metaPath) {
		Path mediaPath = findMediaForMeta(metaPath);
		if (mediaPath == null) {
			return null;
		}
		MeetingMeta meta = metaStore.read(mediaPath);
		if (!"meeting".equals(meta.kind())) {
			return null;
		}
		MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);
		long createdAt = meta.createdAt() != null ? parseMillis(meta.createdAt(), mediaPath)
				: lastModifiedMillis(mediaPath);
		Optional<MeetingJob> job = jobs.findActive(mediaPath);
		boolean interrupted = job.isEmpty() && jobs.isInterrupted(mediaPath);
		MeetingLibraryEntry.Status status;
		if (job.isPresent()) {
			status = job.get().status() == MeetingJob.Status.QUEUED
					? MeetingLibraryEntry.Status.QUEUED
					: MeetingLibraryEntry.Status.RUNNING;
		} else if (interrupted) {
			status = MeetingLibraryEntry.Status.INTERRUPTED;
		} else if (meta.lastJob() != null && "failed".equals(meta.lastJob().status())) {
			status = MeetingLibraryEntry.Status.FAILED;
		} else {
			status = MeetingLibraryEntry.Status.IDLE;
		}
		String title = meta.title() != null && !meta.title().strip().isEmpty()
				? meta.title().strip()
				: defaultMeetingTitle(createdAt);
		boolean failed = meta.lastJob() != null && "failed".equals(meta.lastJob().status());
		return new MeetingLibraryEntry(
				mediaPath,
				title,
				createdAt,
				meta.durationMs(),
				Boolean.TRUE.equals(meta.hasVideo()),
				Files.isRegularFile(files.transcript()),
				Files.isRegularFile(files.notes()),
				status,
				failed ? meta.lastJob().error() : null);
	}

	private static long parseMillis(String createdAt, Path mediaPath) {
		try {
			return Instant.parse(createdAt).toEpochMilli();
		} catch (DateTimeParseException error) {
			return lastModifiedMillis(mediaPath);
		}
	}

	private static long lastModifiedMillis(Path mediaPath) {
		try {
			return Files.getLastModifiedTime(mediaPath).toMillis();
		} catch (IOException error) {
			return 0L;
		}
	}

	private static Path findMediaForMeta(Path metaPath) {
		String meta = metaPath.toString();
		String base = meta.endsWith(".meeting.json")
				? meta.substring(0, meta.length() - ".meeting.json".length())
				: meta;
		for (String extension : MEDIA_EXTENSIONS) {
			Path candidate = Path.of(base + "." + extension);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** Marks a fresh recording as a meeting and, when enabled, starts processing it. */
	public void registerMeetingRecording(Path mediaPath, boolean hasVideo) {
		metaStore.update(mediaPath, Map.of(
				"kind", "meeting",
				"createdAt", Times.isoNow(clock),
				"hasVideo", hasVideo));
		if (settings.read().autoProcess()) {
			jobs.enqueue(mediaPath, MeetingJobMode.FULL);
		}
	}

	/** Copies an existing audio or video file into the library as a new meeting. */
	public Path importMeetingFile(Path sourcePath) {
		String fileName = sourcePath.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
		if (!MEDIA_EXTENSIONS.contains(extension)) {
			throw new IllegalStateException(
					"Gravaai cannot import ." + extension + " files as meetings.");
		}
		Path directory = directories.recordingsDirectory();
		String idSuffix = ids.get();
		idSuffix = idSuffix.length() >= 6 ? idSuffix.substring(0, 6) : idSuffix;
		Path target = directory.resolve(
				"meeting-" + System.currentTimeMillis() + "-" + idSuffix + "." + extension);
		try {
			Files.createDirectories(directory);
			Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		boolean hasVideo = VIDEO_EXTENSIONS.contains(extension);
		String title = dot >= 0 ? fileName.substring(0, dot) : fileName;
		metaStore.update(target, Map.of(
				"kind", "meeting",
				"createdAt", Times.isoNow(clock),
				"hasVideo", hasVideo,
				"title", title));
		if (settings.read().autoProcess()) {
			jobs.enqueue(target, MeetingJobMode.FULL);
		}
		return target;
	}

	public String renameMeeting(Path mediaPath, String title) {
		String trimmed = title == null ? "" : title.strip();
		if (trimmed.isEmpty()) {
			throw new IllegalStateException("The title cannot be empty.");
		}
		String stored = trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
		metaStore.update(mediaPath, Map.of("title", stored, "titleSource", "user"));
		return trimmed;
	}

	/** Moves a meeting's media and every sidecar to the system trash (recoverable). */
	public List<Path> trashMeeting(Path mediaPath) {
		MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);
		List<Path> candidates = List.of(
				mediaPath,
				files.transcript(),
				files.notes(),
				files.meta(),
				SpeakerTracks.speakerTrackPath(mediaPath, SpeakerRole.MIC),
				SpeakerTracks.speakerTrackPath(mediaPath, SpeakerRole.SYSTEM),
				Path.of(mediaPath + ".cursor.json"),
				Path.of(mediaPath + ".keys.json"));
		List<Path> trashed = new ArrayList<>();
		for (Path candidate : candidates) {
			if (Files.exists(candidate)) {
				trash.trash(candidate);
				trashed.add(candidate);
			}
		}
		return List.copyOf(trashed);
	}
}
