package dev.gravaai.services.projects;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import dev.gravaai.core.project.AtomicProjectSave;

/**
 * Moves library projects to the system trash (port of
 * {@code electron/ipc/project/trashProjects.ts}).
 *
 * <p>Referenced recordings are only touched when {@code deleteRecordings} is set (the delete dialog
 * defaults to keeping them). Recordings are only deleted for projects that trash successfully.
 * Failures are collected per project so one locked file cannot stop the rest of the selection.
 */
public final class TrashLibraryProjects {

	private static final int MAXIMUM_SELECTION = 500;

	private TrashLibraryProjects() {
	}

	/** Port of {@code deps.list}: the current library entries (only their paths are used). */
	@FunctionalInterface
	public interface ListedProjects {
		List<Path> list() throws IOException;
	}

	/** Port of Electron's {@code shell.trashItem}: the platform trash implementation is injected. */
	@FunctionalInterface
	public interface Trash {
		void trash(Path path) throws IOException;
	}

	/** Port of {@code deps.resolveRecordingPaths}: the recordings a saved project references. */
	@FunctionalInterface
	public interface RecordingPathsResolver {
		List<Path> resolve(Path projectPath) throws IOException;
	}

	/**
	 * @param list the library listing
	 * @param trash the platform trash operation
	 * @param thumbnailPath the project preview path
	 * @param resolveRecordingPaths optional recorder of referenced recordings
	 */
	public record Dependencies(
			ListedProjects list,
			Trash trash,
			Function<Path, Path> thumbnailPath,
			RecordingPathsResolver resolveRecordingPaths) {
	}

	/** Port of the TS return value {@code { deleted, errors }}. */
	public record TrashResult(List<String> deleted, List<String> errors) {
	}

	/** {@code trashLibraryProjects(paths, deps, { deleteRecordings })}. */
	public static TrashResult trashLibraryProjects(List<String> paths, Dependencies dependencies,
			boolean deleteRecordings) throws IOException {
		if (paths == null || paths.isEmpty() || paths.size() > MAXIMUM_SELECTION
				|| paths.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Invalid project selection");
		}

		Set<Path> allowed = new LinkedHashSet<>();
		for (Path listed : dependencies.list().list()) {
			allowed.add(listed.toAbsolutePath().normalize());
		}
		Set<Path> selected = new LinkedHashSet<>();
		for (String path : paths) {
			selected.add(Path.of(path).toAbsolutePath().normalize());
		}
		for (Path projectPath : selected) {
			if (!allowed.contains(projectPath)) {
				throw new IllegalArgumentException("Project is not in the library");
			}
		}

		List<String> deleted = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		for (Path projectPath : selected) {
			// Deviation from the TS ordering (reported to the Lead): the Electron handler resolved the
			// recordings after trashing the project file, so reading the project to find its media
			// always failed and the opt-in never deleted a recording. Resolve first, then trash.
			List<Path> recordingPaths = List.of();
			if (deleteRecordings && dependencies.resolveRecordingPaths() != null) {
				try {
					recordingPaths = dependencies.resolveRecordingPaths().resolve(projectPath);
				} catch (IOException | RuntimeException error) {
					errors.add("Could not resolve recordings for " + projectPath.getFileName());
				}
			}
			try {
				dependencies.trash().trash(projectPath);
				deleted.add(projectPath.toString());
				for (Path sidecar : List.of(
						dependencies.thumbnailPath().apply(projectPath),
						AtomicProjectSave.getProjectBackupPath(projectPath))) {
					trashIfPresent(dependencies.trash(), sidecar,
							"Could not trash project sidecar: " + sidecar, errors);
				}
				for (Path recordingPath : recordingPaths) {
					trashIfPresent(dependencies.trash(), recordingPath,
							"Could not trash recording: " + recordingPath, errors);
				}
			} catch (IOException | RuntimeException error) {
				errors.add("Could not trash " + projectPath.getFileName());
			}
		}
		return new TrashResult(List.copyOf(deleted), List.copyOf(errors));
	}

	private static void trashIfPresent(Trash trash, Path file, String errorMessage, List<String> errors) {
		if (!Files.exists(file)) {
			return;
		}
		try {
			trash.trash(file);
		} catch (NoSuchFileException ignored) {
			// A file that disappeared between the check and the trashing is fine.
		} catch (IOException | RuntimeException error) {
			errors.add(errorMessage);
		}
	}
}