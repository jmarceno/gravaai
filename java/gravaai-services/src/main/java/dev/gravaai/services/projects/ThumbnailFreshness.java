package dev.gravaai.services.projects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a persisted project preview is usable (port of
 * {@code electron/ipc/project/thumbnailFreshness.ts}).
 *
 * <p>Older releases wrote 320px previews. Those are hidden, as are previews older than the project
 * file: the editor replaces them with a current-size render when returning home.
 */
public final class ThumbnailFreshness {

	/** Port of {@code PROJECT_THUMBNAIL_WIDTH} in {@code src/lib/projectThumbnail.ts}. */
	public static final int PROJECT_THUMBNAIL_WIDTH = 640;

	/** Port of {@code PROJECT_THUMBNAIL_HEIGHT} in {@code src/lib/projectThumbnail.ts}. */
	public static final int PROJECT_THUMBNAIL_HEIGHT = 480;

	private static final int HEADER_BYTES = 24;
	private static final byte[] PNG_SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

	private ThumbnailFreshness() {
	}

	/** {@code hasFreshProjectThumbnail(thumbnailPath, projectModifiedAt)}. */
	public static boolean hasFreshProjectThumbnail(Path thumbnailPath, long projectModifiedAt) {
		try (InputStream stream = Files.newInputStream(thumbnailPath)) {
			long modifiedAt = Files.getLastModifiedTime(thumbnailPath).toMillis();
			if (modifiedAt < projectModifiedAt) {
				return false;
			}

			byte[] header = stream.readNBytes(HEADER_BYTES);
			return header.length == HEADER_BYTES
					&& startsWithPngSignature(header)
					&& readUInt32BigEndian(header, 16) >= PROJECT_THUMBNAIL_WIDTH
					&& readUInt32BigEndian(header, 20) >= PROJECT_THUMBNAIL_HEIGHT;
		} catch (IOException | RuntimeException error) {
			return false;
		}
	}

	private static boolean startsWithPngSignature(byte[] header) {
		for (int index = 0; index < PNG_SIGNATURE.length; index++) {
			if (header[index] != PNG_SIGNATURE[index]) {
				return false;
			}
		}
		return true;
	}

	private static long readUInt32BigEndian(byte[] header, int offset) {
		return ((long) (header[offset] & 0xff) << 24)
				| ((long) (header[offset + 1] & 0xff) << 16)
				| ((long) (header[offset + 2] & 0xff) << 8)
				| (header[offset + 3] & 0xffL);
	}
}
