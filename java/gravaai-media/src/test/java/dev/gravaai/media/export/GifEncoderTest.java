package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-ffmpeg tests of {@link GifEncoder}: header dimensions and loop flag are checked. */
class GifEncoderTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("encodes synthetic frames to a looping GIF with the requested dimensions")
	void encodesLoopingGif() throws Exception {
		Path output = tempDir.resolve("export.gif");
		List<ExportProgress> progress = new CopyOnWriteArrayList<>();
		ExportResult result = GifEncoder.encode(
				new GifExportRequest(32, 24, 10, 0.5, true, output),
				new SyntheticFrameProvider(32, 24, 10), progress::add, ExportCancellation.NONE);
		assertTrue(Files.isRegularFile(output));
		assertTrue(result.bytes() > 0);
		byte[] header = Files.readAllBytes(output);
		assertTrue(header.length > 13);
		String signature = new String(header, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
		assertTrue(signature.startsWith("GIF"), signature);
		int width = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
		int height = (header[8] & 0xFF) | ((header[9] & 0xFF) << 8);
		assertEquals(32, width);
		assertEquals(24, height);
		assertTrue(contains(header, "NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
				"looping GIF should carry the NETSCAPE loop extension");
		assertFalse(progress.isEmpty());
		assertEquals(100.0, progress.get(progress.size() - 1).percentage(), 0.001);
	}

	@Test
	@DisplayName("encodes a play-once GIF without the infinite loop extension count")
	void encodesPlayOnceGif() throws Exception {
		Path output = tempDir.resolve("once.gif");
		GifEncoder.encode(new GifExportRequest(32, 24, 10, 0.3, false, output),
				new SyntheticFrameProvider(32, 24, 10), ExportProgressListener.NONE,
				ExportCancellation.NONE);
		byte[] header = Files.readAllBytes(output);
		assertTrue(header.length > 13);
		assertTrue(new String(header, 0, 6, java.nio.charset.StandardCharsets.US_ASCII)
				.startsWith("GIF"));
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		for (int index = 0; index + needle.length <= haystack.length; index++) {
			boolean match = true;
			for (int offset = 0; offset < needle.length; offset++) {
				if (haystack[index + offset] != needle[offset]) {
					match = false;
					break;
				}
			}
			if (match) {
				return true;
			}
		}
		return false;
	}
}
