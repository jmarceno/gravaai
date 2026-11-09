package dev.gravaai.core.xdg;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The XDG base directories (https://specifications.freedesktop.org/basedir-spec/), resolved once
 * from a process environment.
 *
 * <p>Four classes independently re-derived {@code $XDG_CONFIG_HOME}, {@code $XDG_DATA_HOME},
 * {@code $XDG_DATA_DIRS} and {@code $XDG_RUNTIME_DIR} with slightly different fallbacks and
 * different blank-value handling. This is the one implementation: every fallback is the spec's own
 * default, and a blank value is treated as unset (the spec says "empty", and an empty path is never
 * a directory anyone means).
 *
 * <p>Instances are cheap records over immutable paths; {@link #current()} reads the real process
 * environment, and {@link #of(Map, Path)} takes an explicit one so tests and tooling can resolve a
 * different profile without mutating the process.
 *
 * @param configHome {@code $XDG_CONFIG_HOME}, default {@code ~/.config}
 * @param dataHome {@code $XDG_DATA_HOME}, default {@code ~/.local/share}
 * @param dataDirs {@code $XDG_DATA_DIRS}, default {@code /usr/local/share:/usr/share}, in order
 * @param runtimeDir {@code $XDG_RUNTIME_DIR}, or null when the session did not set one
 * @param home the user's home directory (the base every default is relative to)
 */
public record XdgDirectories(Path configHome, Path dataHome, List<Path> dataDirs,
		@Nullable Path runtimeDir, Path home) {

	/** The spec's default data directories, used when {@code $XDG_DATA_DIRS} is unset or blank. */
	private static final List<String> DEFAULT_DATA_DIRS = List.of("/usr/local/share", "/usr/share");

	public XdgDirectories {
		dataDirs = dataDirs == null ? List.of() : List.copyOf(dataDirs);
	}

	/** Resolves from the real process environment and {@code user.home}. */
	public static XdgDirectories current() {
		return of(System.getenv(), Path.of(System.getProperty("user.home", ".")));
	}

	/**
	 * Resolves from an environment map.
	 *
	 * @param env the process environment
	 * @param home the user's home directory, used for every XDG default
	 */
	public static XdgDirectories of(Map<String, String> env, Path home) {
		Path normalizedHome = home.toAbsolutePath().normalize();
		Path configHome = pathOrNull(env.get("XDG_CONFIG_HOME"));
		Path dataHome = pathOrNull(env.get("XDG_DATA_HOME"));
		Path runtimeDir = pathOrNull(env.get("XDG_RUNTIME_DIR"));
		List<Path> dataDirs = dataDirs(env.get("XDG_DATA_DIRS"));
		return new XdgDirectories(
				configHome == null ? normalizedHome.resolve(".config") : configHome,
				dataHome == null ? normalizedHome.resolve(".local/share") : dataHome,
				dataDirs,
				runtimeDir,
				normalizedHome);
	}

	/**
	 * {@code $XDG_DATA_DIRS}, defaulting to the spec list. Blank entries are skipped: a leading or
	 * trailing {@code :} is common in the wild and never means the current directory here.
	 */
	private static List<Path> dataDirs(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_DATA_DIRS.stream().map(Path::of).toList();
		}
		List<Path> directories = new ArrayList<>();
		for (String entry : value.split(":", -1)) {
			if (!entry.isBlank()) {
				directories.add(Path.of(entry.trim()));
			}
		}
		return directories.isEmpty()
				? DEFAULT_DATA_DIRS.stream().map(Path::of).toList()
				: directories;
	}

	private static @Nullable Path pathOrNull(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return Path.of(value.trim()).toAbsolutePath().normalize();
	}

	/** The user's home trash directory: {@code $XDG_DATA_HOME/Trash}, per the trash spec. */
	public Path homeTrash() {
		return dataHome.resolve("Trash");
	}

	/** A per-user runtime directory, or null when the session has no {@code $XDG_RUNTIME_DIR}. */
	public @Nullable Path runtimeDirectory() {
		return runtimeDir;
	}

	/** {@code $XDG_DATA_DIRS} joined the way the spec spells it, for messages and diagnostics. */
	public String dataDirsText() {
		return String.join(":", dataDirs.stream().map(Path::toString).toList());
	}

	/** The environment variables this record reads, for diagnostics. */
	public static List<String> keys() {
		return Arrays.asList("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_DATA_DIRS", "XDG_RUNTIME_DIR");
	}
}
