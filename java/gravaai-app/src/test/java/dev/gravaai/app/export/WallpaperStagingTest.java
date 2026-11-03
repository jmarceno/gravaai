package dev.gravaai.app.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WallpaperStagingTest {

	@DisplayName("an unknown wallpaper stages the default as its fallback")
	@Test
	void unknownWallpaperStagesDefault() throws Exception {
		Path root = WallpaperStaging.stage("/wallpapers/no-such-wallpaper.jpg",
				"gravaai-test-wallpapers");
		assertNotNull(root);
		try {
			assertTrue(Files.isRegularFile(root.resolve("wallpapers/morning-tide.jpg")));
			assertFalse(Files.exists(root.resolve("wallpapers/no-such-wallpaper.jpg")));
		} finally {
			delete(root);
		}
	}

	@DisplayName("a known wallpaper stages both the request and the default")
	@Test
	void knownWallpaperStagesBoth() throws Exception {
		Path root = WallpaperStaging.stage("/wallpapers/midnight-silk.jpg",
				"gravaai-test-wallpapers");
		assertNotNull(root);
		try {
			assertTrue(Files.isRegularFile(root.resolve("wallpapers/midnight-silk.jpg")));
			assertTrue(Files.isRegularFile(root.resolve("wallpapers/morning-tide.jpg")));
		} finally {
			delete(root);
		}
	}

	@DisplayName("a custom wallpaper value still gets the default fallback")
	@Test
	void customValueStagesDefault() throws Exception {
		Path root = WallpaperStaging.stage("/tmp/custom-wallpaper.jpg",
				"gravaai-test-wallpapers");
		assertNotNull(root);
		try {
			assertTrue(Files.isRegularFile(root.resolve("wallpapers/morning-tide.jpg")));
		} finally {
			delete(root);
		}
	}

	private static void delete(Path root) throws IOException {
		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		}
	}
}
