package dev.gravaai.services.projects;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Renames a library project together with its sidecars (port of
 * {@code electron/ipc/project/renameLibraryProject.ts}).
 *
 * <p>An exclusive hard link prevents a rename from overwriting another project. Sidecars are copied
 * with {@code COPYFILE_EXCL} semantics and the old ones are removed once the project has moved; any
 * failure removes the partial target again.
 */
public final class RenameLibraryProject {

	private static final Pattern INVALID_NAME_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*]");
	private static final Pattern TRAILING_DOT_OR_SPACE = Pattern.compile("[. ]$");

	private RenameLibraryProject() {
	}

	/**
	 * @param source the project file being renamed
	 * @param name the new name without the extension
	 * @param allowedPaths the library entries; the source must be one of them
	 * @param sidecars maps a project path to the auxiliary files that move with it
	 * @return the renamed project path
	 * @throws IOException when the target exists or a file operation fails
	 * @throws IllegalArgumentException for a source outside the library or an invalid name
	 */
	public static Path renameLibraryProject(Path source, String name, List<Path> allowedPaths,
			Function<Path, List<Path>> sidecars) throws IOException {
		if (source == null || allowedPaths == null || !allowedPaths.contains(source)) {
			throw new IllegalArgumentException("Project is not in the library");
		}

		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty()
				|| clean.equals(".")
				|| clean.equals("..")
				|| INVALID_NAME_CHARACTERS.matcher(clean).find()
				|| clean.chars().anyMatch(character -> character < 32)
				|| TRAILING_DOT_OR_SPACE.matcher(clean).find()) {
			throw new IllegalArgumentException("Choose a valid project name");
		}

		Path parent = source.getParent() != null ? source.getParent() : Path.of(".");
		Path target = parent.resolve(clean + ProjectPaths.extname(source.toString()));
		if (target.equals(source)) {
			return target;
		}

		// An exclusive link prevents a rename from overwriting another project.
		Files.createLink(target, source);
		List<Path> copied = new ArrayList<>();
		try {
			List<Path> previousSidecars = sidecars.apply(source);
			List<Path> nextSidecars = sidecars.apply(target);
			for (int index = 0; index < previousSidecars.size(); index++) {
				try {
					Files.copy(previousSidecars.get(index), nextSidecars.get(index));
					copied.add(nextSidecars.get(index));
				} catch (java.nio.file.NoSuchFileException error) {
					// A missing sidecar is fine; anything else aborts the rename.
					continue;
				}
			}
			Files.delete(source);
		} catch (IOException | RuntimeException error) {
			deleteQuietly(target);
			copied.forEach(RenameLibraryProject::deleteQuietly);
			throw error;
		}

		// Auxiliary files can be cleaned up independently once the project has moved.
		for (Path file : sidecars.apply(source)) {
			deleteQuietly(file);
		}
		return target;
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// The TS cleanup uses fs.rm(..., { force: true }) and swallows failures.
		}
	}

	/** Exposed for tests: whether a target path already exists (exclusive-link check). */
	static boolean targetExists(Path target) {
		return Files.exists(target);
	}
}
