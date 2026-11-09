package dev.gravaai.platform.process;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a command name on {@code $PATH}.
 *
 * <p>Three call sites each grew their own "split PATH, stat each candidate, check executable" loop
 * with different error messages. This is the one implementation.
 *
 * <p>This application is Linux-only, so there is no {@code .exe}/{@code PATHEXT} handling; adding
 * another host OS means adding its lookup rules here rather than in each caller.
 */
public final class ExecutableLookup {

	private ExecutableLookup() {}

	/**
	 * Resolves an executable on the real process {@code $PATH}.
	 *
	 * @param name a plain command name (no separators)
	 * @return the absolute path when an executable file exists
	 */
	public static Optional<Path> find(String name) {
		return find(System.getenv(), name);
	}

	/**
	 * Resolves an executable on an explicit {@code $PATH}.
	 *
	 * @param env the environment holding {@code PATH}
	 * @param name a plain command name (no separators)
	 * @return the absolute path when an executable file exists
	 */
	public static Optional<Path> find(Map<String, String> env, String name) {
		String path = env.get("PATH");
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}
		for (String directory : path.split(":", -1)) {
			if (directory.isEmpty()) {
				continue;
			}
			Path candidate = Path.of(directory).resolve(name);
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return Optional.of(candidate.toAbsolutePath().normalize());
			}
		}
		return Optional.empty();
	}

	/**
	 * Resolves an executable and fails when it is not installed.
	 *
	 * @param env the environment holding {@code PATH}
	 * @param name the command name
	 * @throws IOException when no executable is found
	 */
	public static Path require(Map<String, String> env, String name) throws IOException {
		return find(env, name).orElseThrow(
				() -> new IOException("Command not found on PATH: " + name));
	}

	/**
	 * Resolves an executable, keeping the caller's own failure message.
	 *
	 * @param env the environment holding {@code PATH}
	 * @param name the command name
	 * @param errorMessage the message to throw when the command is missing
	 */
	public static Path require(Map<String, String> env, String name, String errorMessage)
			throws IOException {
		return find(env, name).orElseThrow(() -> new IOException(errorMessage));
	}

	/** The first existing executable among {@code names}, in order. */
	public static Optional<Path> findAny(Map<String, String> env, Iterable<String> names) {
		for (String name : names) {
			Optional<Path> found = find(env, name);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	/** True when {@code name} resolves to an executable on {@code $PATH}. */
	public static boolean exists(@Nullable Map<String, String> env, String name) {
		return env != null && find(env, name).isPresent();
	}
}
