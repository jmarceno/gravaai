package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/ipc/project/thumbnailFreshness.test.ts}. */
class ThumbnailFreshnessTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("rejects legacy, stale, missing and broken previews while accepting a fresh current-size or larger PNG")
	void rejectsLegacyStaleMissingAndBrokenPreviews() throws Exception {
		Path file = tempDir.resolve("preview.png");

		assertFalse(ThumbnailFreshness.hasFreshProjectThumbnail(file, 0));

		Files.write(file, ProjectTestFiles.pngHeader(320, 180));
		assertFalse(ThumbnailFreshness.hasFreshProjectThumbnail(file, 0));

		Files.write(file, ProjectTestFiles.pngHeader(640, 480));
		assertTrue(ThumbnailFreshness.hasFreshProjectThumbnail(file, 0));

		Files.write(file, ProjectTestFiles.pngHeader(1600, 1200));
		assertTrue(ThumbnailFreshness.hasFreshProjectThumbnail(file, 0));

		long future = Files.getLastModifiedTime(file).toMillis() + 10_000;
		assertFalse(ThumbnailFreshness.hasFreshProjectThumbnail(file, future));

		Files.writeString(file, "broken");
		assertFalse(ThumbnailFreshness.hasFreshProjectThumbnail(file, 0));
	}
}
