package dev.gravaai.core.project;

import java.nio.file.Path;
import java.util.regex.Pattern;

import dev.gravaai.core.storage.StorageFiles;

/**
 * File names the app creates for projects it has never saved (debounced autosave, plan §5.11).
 *
 * <p>An unsaved recording must acquire exactly one {@code .gravaai} file in the Projects folder, so
 * the name has to be both filesystem-safe and free of collisions with the projects already there.
 * The character rules match {@link RenameLibraryProject} (the library rename), so a project created
 * by autosave can be renamed by the same rules without being rejected.
 */
public final class ProjectFileNaming {

	/** The name a project gets when it has nothing better to be called. */
	public static final String UNTITLED_BASE_NAME = "Untitled Project";

	/** Longest base name accepted; a recording file name can be long, a card title should not be. */
	public static final int MAX_BASE_NAME_LENGTH = 100;

	/** Same rejection set as the library rename: no path separators, globs or reserved characters. */
	private static final Pattern INVALID_NAME_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*]");

	/** Control characters (including tabs and newlines) cannot appear in a project file name. */
	private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

	/** Trailing dots and spaces break Windows tooling and make a name look empty. */
	private static final Pattern TRAILING_DOT_OR_SPACE = Pattern.compile("[. ]+$");

	/** Runs of whitespace collapse to a single space, as the rename field does. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	private ProjectFileNaming() {}

	/**
	 * Turns a display name into a base file name: invalid characters become {@code -}, whitespace
	 * collapses, trailing dots and spaces go, and an empty result falls back to
	 * {@link #UNTITLED_BASE_NAME}.
	 */
	public static String safeBaseName(String displayName) {
		if (displayName == null) {
			return UNTITLED_BASE_NAME;
		}
		String cleaned = CONTROL_CHARACTERS.matcher(displayName).replaceAll(" ");
		cleaned = INVALID_NAME_CHARACTERS.matcher(cleaned).replaceAll("-");
		cleaned = WHITESPACE_RUN.matcher(cleaned).replaceAll(" ");
		cleaned = TRAILING_DOT_OR_SPACE.matcher(cleaned.trim()).replaceAll("");
		if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
			return UNTITLED_BASE_NAME;
		}
		return cleaned.length() > MAX_BASE_NAME_LENGTH
				? cleaned.substring(0, MAX_BASE_NAME_LENGTH).trim()
				: cleaned;
	}

	/**
	 * The first free project path for {@code baseName} in {@code directory}:
	 * {@code Base.gravaai}, then {@code Base 1.gravaai}, {@code Base 2.gravaai}, …
	 *
	 * <p>Pure path arithmetic — nothing is created, so the caller writes the file it picked (the
	 * session writes it through {@link AtomicProjectSave} and keeps the path for every later save).
	 */
	public static Path uniqueProjectPath(Path directory, String baseName) {
		String base = safeBaseName(baseName);
		String extension = "." + StorageFiles.PROJECT_FILE_EXTENSION;
		Path candidate = directory.resolve(base + extension);
		for (int suffix = 1; java.nio.file.Files.exists(candidate); suffix++) {
			candidate = directory.resolve(base + " " + suffix + extension);
		}
		return candidate;
	}
}
