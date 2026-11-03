package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.media.audio.ClickSounds;

/** {@link ClickSoundWav}: the sparse mono bus the export mixes as a single region. */
@Timeout(15)
class ClickSoundWavTest {

	@TempDir
	Path tempDir;

	private static int le16(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
	}

	private static int le32(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
				| ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
	}

	@Test
	@DisplayName("writes a mono 48k 16-bit RIFF capped at the last hit, with the clicks audible")
	void writesTheClickBus() throws IOException {
		float[] pcm = ClickSounds.pcm(ClickSoundStyle.TICK);
		assertNotNull(pcm, "the bundled tick click must decode");
		int soundFrames = pcm.length / 2;
		Path wav = tempDir.resolve("clicks.wav");

		assertNotNull(ClickSoundWav.write(wav, ClickSoundStyle.TICK, 1,
				new long[] {0, 1_000}));
		byte[] bytes = Files.readAllBytes(wav);

		long lastHitEndFrames = 1_000L * 48_000 / 1_000 + soundFrames;
		assertEquals(44 + lastHitEndFrames * 2, bytes.length,
				"the file is capped at the last hit plus the sound length");
		assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
		assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
		assertEquals("fmt ", new String(bytes, 12, 4, StandardCharsets.US_ASCII));
		assertEquals(16, le32(bytes, 16));
		assertEquals(1, le16(bytes, 20), "PCM");
		assertEquals(1, le16(bytes, 22), "mono");
		assertEquals(48_000, le32(bytes, 24));
		assertEquals(96_000, le32(bytes, 28), "byte rate");
		assertEquals(2, le16(bytes, 32));
		assertEquals(16, le16(bytes, 34));
		assertEquals("data", new String(bytes, 36, 4, StandardCharsets.US_ASCII));
		assertEquals(lastHitEndFrames * 2, le32(bytes, 40));

		int firstHit = windowPeak(bytes, 44, 960);
		int between = windowPeak(bytes, 44 + 30_000 * 2, 960);
		int secondHit = windowPeak(bytes, 44 + 1_000 * 48 * 2, 960);
		assertTrue(firstHit > 1_000, "the first hit is audible at frame 0, got " + firstHit);
		assertEquals(0, between, "the stretch between hits is silent");
		assertTrue(secondHit > 1_000, "the second hit is audible at its time, got " + secondHit);
	}

	/** Peak absolute 16-bit sample over {@code frames} starting at {@code byteOffset}. */
	private static int windowPeak(byte[] bytes, int byteOffset, int frames) {
		int peak = 0;
		for (int index = 0; index < frames; index++) {
			peak = Math.max(peak, Math.abs(le16(bytes, byteOffset + index * 2)));
		}
		return peak;
	}

	@Test
	@DisplayName("no hits writes nothing")
	void emptyHits() {
		assertNull(ClickSoundWav.write(tempDir.resolve("none.wav"),
				ClickSoundStyle.CRISP, 1, new long[0]));
	}
}
