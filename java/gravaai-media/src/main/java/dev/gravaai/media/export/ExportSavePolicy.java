package dev.gravaai.media.export;

/**
 * Legacy in-memory save policy (port of {@code src/lib/exporter/exportSavePolicy.ts}, plan §5.10).
 *
 * <p>Exports are always written to a temp file first on the Java path; this policy only decides
 * whether the legacy in-memory fallback would have been allowed, so the UI can explain why it is
 * not.
 */
public final class ExportSavePolicy {

	public static final long MAX_IN_MEMORY_EXPORT_BYTES = 0x7fff_ffffL;

	/** Options of {@code canUseInMemoryExportSaveFallback}. */
	public record FallbackOptions(long blobSize, String extension, boolean hasExportStreamApi) {}

	private ExportSavePolicy() {}

	/** {@code normalizeExportExtension}. */
	public static String normalizeExportExtension(String extension) {
		return extension == null ? "" : extension.trim().toLowerCase(java.util.Locale.ROOT);
	}

	/** {@code isExportTooLargeForInMemorySave}. */
	public static boolean isExportTooLargeForInMemorySave(long byteLength) {
		return byteLength > MAX_IN_MEMORY_EXPORT_BYTES;
	}

	/** {@code canUseInMemoryExportSaveFallback}. */
	public static boolean canUseInMemoryExportSaveFallback(FallbackOptions options) {
		if (isExportTooLargeForInMemorySave(options.blobSize())) {
			return false;
		}
		if (options.hasExportStreamApi()
				&& "mp4".equals(normalizeExportExtension(options.extension()))) {
			return false;
		}
		return true;
	}

	/** {@code describeBlockedInMemoryExportSave}. */
	public static String describeBlockedInMemoryExportSave(long blobSize, String extension) {
		String normalizedExtension = normalizeExportExtension(extension);
		String label = normalizedExtension.isEmpty()
				? "EXPORT"
				: normalizedExtension.toUpperCase(java.util.Locale.ROOT);
		if (isExportTooLargeForInMemorySave(blobSize)) {
			return "The " + label
					+ " export is too large to save through the legacy in-memory path. Please retry the export so Gravaai can save it through the temp-file streaming path.";
		}
		return "The " + label
				+ " export could not be saved through the temp-file streaming path, and Gravaai will not fall back to the legacy in-memory path for MP4 exports. Please retry the export.";
	}
}
