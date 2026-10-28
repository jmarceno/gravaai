package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.export.ExportSavePolicy.FallbackOptions;

/** Port of {@code src/lib/exporter/exportSavePolicy.test.ts}. */
class ExportSavePolicyTest {

	@Test
	@DisplayName("normalizes export extensions before policy checks")
	void normalizesExtensions() {
		assertEquals("mp4", ExportSavePolicy.normalizeExportExtension(" MP4 "));
	}

	@Test
	@DisplayName("blocks the legacy in-memory save path above Node's Buffer limit")
	void blocksOversizedSaves() {
		assertTrue(ExportSavePolicy.isExportTooLargeForInMemorySave(
				ExportSavePolicy.MAX_IN_MEMORY_EXPORT_BYTES + 1));
		assertFalse(ExportSavePolicy.canUseInMemoryExportSaveFallback(new FallbackOptions(
				ExportSavePolicy.MAX_IN_MEMORY_EXPORT_BYTES + 1, "gif", false)));
	}

	@Test
	@DisplayName("keeps Electron MP4 exports on the temp-file save path")
	void keepsMp4OnTempFilePath() {
		assertFalse(ExportSavePolicy.canUseInMemoryExportSaveFallback(
				new FallbackOptions(1024, "mp4", true)));
	}

	@Test
	@DisplayName("allows small non-MP4 exports through the legacy fallback")
	void allowsSmallGif() {
		assertTrue(ExportSavePolicy.canUseInMemoryExportSaveFallback(
				new FallbackOptions(1024, "gif", false)));
	}

	@Test
	@DisplayName("explains blocked large saves without mentioning implementation stack traces")
	void explainsBlockedLargeSaves() {
		String message = ExportSavePolicy.describeBlockedInMemoryExportSave(
				ExportSavePolicy.MAX_IN_MEMORY_EXPORT_BYTES + 1, "mp4");
		assertTrue(message.contains("too large"));
	}
}
