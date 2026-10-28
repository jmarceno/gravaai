package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Recursive file helpers for temporary work directories and downloaded archives. */
public final class FileTrees {

	private FileTrees() {
	}

	public static boolean exists(Path path) {
		return path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS);
	}

	public static void deleteRecursively(Path path) {
		if (!exists(path)) {
			return;
		}
		try (var walk = Files.walk(path)) {
			List<Path> entries = walk.sorted(Comparator.reverseOrder()).toList();
			for (Path entry : entries) {
				Files.deleteIfExists(entry);
			}
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	/** Like {@link #deleteRecursively(Path)} but never fails; used in finally blocks. */
	public static void deleteQuietly(Path path) {
		try {
			deleteRecursively(path);
		} catch (RuntimeException ignored) {
			// Best effort cleanup.
		}
	}
}
