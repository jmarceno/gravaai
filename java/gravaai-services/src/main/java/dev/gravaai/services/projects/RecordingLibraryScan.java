package dev.gravaai.services.projects;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In-package recording scan behind {@link RecordingsReadModel} (read side of
 * {@code listRecordings} in {@code electron/ipc/recording/library.ts}).
 *
 * <p>Temporary implementation per the Lead's coordination note: the recording service owns the
 * {@code list-recordings} channel and this class will be replaced by its implementation. It keeps
 * the exact filters: app recordings are {@code mp4|mov|webm|mkv|m4v} without a {@code webcam}
 * sidecar marker; the raw library also accepts {@code wav|m4a|mp3|ogg|flac}; empty files are
 * skipped; entries are sorted by mtime, newest first. The stale {@code .gravaai-trash-*} bundle
 * cleanup and the delete/undo writes stay with the recording service.
 */
public final class RecordingLibraryScan implements RecordingsReadModel {

	private static final Pattern RECORDING = Pattern.compile("\\.(mp4|mov|webm|mkv|m4v)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern SOURCE = Pattern.compile("\\.(mp4|mov|webm|mkv|m4v|wav|m4a|mp3|ogg|flac)$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern WEBCAM_SIDECAR = Pattern.compile("[.-]webcam[.-]", Pattern.CASE_INSENSITIVE);

	private final RecordingsDirectory recordingsDirectory;

	public RecordingLibraryScan(RecordingsDirectory recordingsDirectory) {
		this.recordingsDirectory = recordingsDirectory;
	}

	@Override
	public List<RecordingEntry> list(boolean includeSources) throws IOException {
		Path root = recordingsDirectory.getRecordingsDir().toRealPath();
		List<RecordingEntry> entries = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
			for (Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				String name = file.getFileName().toString();
				boolean matches = includeSources
						? SOURCE.matcher(name).find()
						: RECORDING.matcher(name).find() && !WEBCAM_SIDECAR.matcher(name).find();
				if (!matches) {
					continue;
				}
				long bytes = Files.size(file);
				if (bytes == 0) {
					continue;
				}
				entries.add(new RecordingEntry(file.toString(), name,
						Files.getLastModifiedTime(file).toMillis(), bytes));
			}
		}
		entries.sort(Comparator.comparingLong(RecordingEntry::createdAt).reversed());
		return entries;
	}
}
