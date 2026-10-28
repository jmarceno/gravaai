package dev.gravaai.media.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Locates the committed media fixtures for the export encoder tests (plan §6.1). */
final class ExportFixtures {

	private static final List<Path> CANDIDATES = List.of(
			Path.of("parity", "fixtures", "media"),
			Path.of("..", "parity", "fixtures", "media"),
			Path.of("..", "..", "parity", "fixtures", "media"));

	private ExportFixtures() {}

	static Path directory() {
		String explicit = System.getProperty("gravaai.media.fixtures.dir");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit);
		}
		for (Path candidate : CANDIDATES) {
			if (Files.isDirectory(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new IllegalStateException("media fixtures not found; run java/native/make-fixtures.sh");
	}

	static Path aac() {
		return fixture("aac-48k-stereo.m4a");
	}

	static Path h264() {
		return fixture("h264-1080p60.mp4");
	}

	private static Path fixture(String name) {
		Path file = directory().resolve(name);
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("fixture missing: " + file);
		}
		return file;
	}
}
