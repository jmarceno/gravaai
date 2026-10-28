package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.gravaai.core.json.Json;

/**
 * Recordings library listing and reversible removal, ported from
 * {@code electron/ipc/recording/library.ts}.
 *
 * <p>The media server URL is not part of the entry: the Java app decodes local files directly
 * (plan §2.2). Removal stages a bundle by rename so Undo can restore it; the next removal or app
 * exit sends the bundle to the desktop Trash.
 *
 * <p>Methods are blocking file operations; call them on a virtual thread (plan §4.1).
 */
public final class RecordingLibrary {

	/** One file offered by the recordings library. */
	public record RecordingLibraryEntry(String path, String name, long createdAt, long bytes) {
	}

	/** Sends a staged bundle to the desktop Trash; the platform service implements it. */
	public interface Trash {
		void trashItem(Path path) throws IOException;
	}

	/** Hard-link creation, injectable so the copy fallback is testable. */
	public interface Linker {
		void link(Path existing, Path link) throws IOException;
	}

	private record UndoBatch(Path bundle, List<Path> files) {
	}

	private static final Pattern RECORDING_NAME =
			Pattern.compile(".*\\.(mp4|mov|webm|mkv|m4v)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern WEBCAM_NAME = Pattern.compile("[.-]webcam[.-]", Pattern.CASE_INSENSITIVE);
	private static final Pattern SOURCE_NAME = Pattern.compile(
			".*\\.(mp4|mov|webm|mkv|m4v|wav|m4a|mp3|ogg|flac)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TRASH_DIR = Pattern.compile("^\\.gravaai-trash-[A-Za-z0-9]{6}$");
	private static final Pattern COMPANION_NAME = Pattern.compile(
			"\\.(?:system|mic|microphone|audio|webcam)\\.(?:wav|webm|mp4|m4a)(?:\\.json)?$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern WEBCAM_TRACK_NAME =
			Pattern.compile("\\.(?:mp4|webm|mov|mkv|avi)$", Pattern.CASE_INSENSITIVE);

	private final Supplier<Path> recordingsDir;
	private final Trash trash;
	private final Linker linker;
	private final Map<String, UndoBatch> undoBatches = new HashMap<>();

	public RecordingLibrary(Supplier<Path> recordingsDir, Trash trash) {
		this(recordingsDir, trash, (existing, link) -> Files.createLink(link, existing));
	}

	RecordingLibrary(Supplier<Path> recordingsDir, Trash trash, Linker linker) {
		this.recordingsDir = recordingsDir;
		this.trash = trash;
		this.linker = linker;
	}

	private static boolean isRecording(String name) {
		return RECORDING_NAME.matcher(name).matches() && !WEBCAM_NAME.matcher(name).find();
	}

	/** Port of {@code listRecordings}: newest non-empty recordings first. */
	public synchronized List<RecordingLibraryEntry> listRecordings(boolean includeSources) throws IOException {
		Path root = recordingsDir.get().toRealPath();
		Set<Path> stagedBundles = new LinkedHashSet<>();
		for (UndoBatch batch : undoBatches.values()) {
			stagedBundles.add(batch.bundle());
		}
		List<Path> entries;
		try (Stream<Path> stream = Files.list(root)) {
			entries = stream.toList();
		}
		for (Path staged : entries) {
			String name = staged.getFileName().toString();
			if (Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)
					&& TRASH_DIR.matcher(name).matches()
					&& !stagedBundles.contains(staged)) {
				trash.trashItem(staged);
			}
		}
		List<RecordingLibraryEntry> result = new ArrayList<>();
		for (Path entry : entries) {
			String name = entry.getFileName().toString();
			boolean selected = includeSources ? SOURCE_NAME.matcher(name).matches() : isRecording(name);
			if (!selected || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			long size = Files.size(entry);
			if (size == 0) {
				continue;
			}
			result.add(new RecordingLibraryEntry(
					entry.toString(), name, Files.getLastModifiedTime(entry).toMillis(), size));
		}
		result.sort(Comparator.comparingLong(RecordingLibraryEntry::createdAt).reversed());
		return result;
	}

	// Only capture sidecars belonging to this exact recording, never adjacent recordings or projects.
	static boolean belongsToRecording(String name, String video) {
		int extensionStart = video.lastIndexOf('.');
		String stem = extensionStart > 0 ? video.substring(0, extensionStart) : video;
		return name.equals(video)
				|| name.equals(video + ".cursor.json")
				|| name.equals(video + ".keys.json")
				|| (name.startsWith(stem + ".") && COMPANION_NAME.matcher(name).find())
				|| name.equals(stem + ".gravaai-session.json")
				|| name.equals(stem + ".recording-session.json")
				|| (name.startsWith(stem + "-webcam.") && WEBCAM_TRACK_NAME.matcher(name).find());
	}

	private static String batchKey(List<String> paths) {
		List<String> uniqueSorted = new ArrayList<>(new LinkedHashSet<>(paths));
		uniqueSorted.sort(String::compareTo);
		return Json.stringify(uniqueSorted);
	}

	/** Stage a bundle by rename for Undo; the next removal or app exit sends it to Trash. */
	public synchronized void setRecordingsRemoved(List<String> paths, boolean removed) throws IOException {
		if (paths == null || paths.isEmpty() || paths.stream().anyMatch(path -> path == null)) {
			throw new IllegalArgumentException("Invalid recording selection");
		}
		Path root = recordingsDir.get().toRealPath();
		Set<String> distinct = new LinkedHashSet<>(paths);
		List<String> selected = new ArrayList<>(distinct);
		for (String candidate : selected) {
			Path path = Path.of(candidate);
			Path parent = path.getParent();
			if (parent == null || !parent.normalize().equals(root.normalize())
					|| !isRecording(path.getFileName().toString())) {
				throw new IllegalArgumentException("Recording is outside the Videos library");
			}
		}
		String key = batchKey(selected);
		if (!removed) {
			UndoBatch batch = undoBatches.get(key);
			if (batch == null) {
				throw new IllegalStateException(
						"This removal can no longer be undone. Restore the files from Trash.");
			}
			// Check every destination before restoring any file; never overwrite newer media.
			for (Path file : batch.files()) {
				if (Files.exists(file)) {
					throw new IllegalStateException(
							"A file with this name already exists. Restore it from Trash instead.");
				}
			}
			List<Path> restored = new ArrayList<>();
			try {
				for (Path file : batch.files()) {
					Path staged = batch.bundle().resolve(file.getFileName());
					try {
						linker.link(staged, file);
					} catch (IOException error) {
						String reason = error.getMessage() == null ? "" : error.getMessage();
						String code = reason.toLowerCase(Locale.ROOT);
						if (!code.contains("operation not permitted")
								&& !code.contains("not supported")
								&& !code.contains("invalid cross-device")) {
							throw error;
						}
						Files.copy(staged, file);
					}
					restored.add(file);
				}
			} catch (IOException error) {
				for (Path file : restored) {
					Files.deleteIfExists(file);
				}
				throw error;
			}
			undoBatches.remove(key);
			deleteRecursively(batch.bundle());
			return;
		}
		List<Path> available;
		try (Stream<Path> stream = Files.list(root)) {
			available = stream.toList();
		}
		for (String file : selected) {
			String name = Path.of(file).getFileName().toString();
			boolean present = available.stream().anyMatch(entry ->
					entry.getFileName().toString().equals(name)
							&& Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS));
			if (!present) {
				throw new IllegalStateException("Recording is missing or is not a regular file");
			}
		}
		List<Path> files = new ArrayList<>();
		for (Path entry : available) {
			String name = entry.getFileName().toString();
			if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
					&& selected.stream().anyMatch(file ->
							belongsToRecording(name, Path.of(file).getFileName().toString()))) {
				files.add(entry);
			}
		}
		finishPendingTrash();
		Path bundle = root.resolve(".gravaai-trash-" + randomTrashSuffix());
		Files.createDirectory(bundle);
		List<Path> moved = new ArrayList<>();
		try {
			for (Path file : files) {
				Files.move(file, bundle.resolve(file.getFileName()));
				moved.add(file);
			}
			undoBatches.put(key, new UndoBatch(bundle, files));
		} catch (IOException error) {
			for (Path file : moved) {
				Files.move(bundle.resolve(file.getFileName()), file);
			}
			Files.deleteIfExists(bundle);
			throw error;
		}
	}

	private void finishPendingTrash() throws IOException {
		for (Map.Entry<String, UndoBatch> entry : List.copyOf(undoBatches.entrySet())) {
			trash.trashItem(entry.getValue().bundle());
			undoBatches.remove(entry.getKey());
		}
	}

	/** Sends every staged bundle to Trash; throwing leaves the rest staged for a retry. */
	public synchronized void clearRecordingTrashUndo() throws IOException {
		finishPendingTrash();
	}

	private static String randomTrashSuffix() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		java.security.SecureRandom random = new java.security.SecureRandom();
		StringBuilder suffix = new StringBuilder(6);
		for (int index = 0; index < 6; index++) {
			suffix.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return suffix.toString();
	}

	private static void deleteRecursively(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) {
					throw error;
				}
				Files.deleteIfExists(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
