package dev.gravaai.media.denoise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.project.AudioCleanup;

/** {@link DenoisedAudio}: the cache key, the level mix and the file format of a rendered copy. */
class DenoisedAudioTest {

	@Test
	@DisplayName("the levels are attenuation limits: the share of the original mixed back in")
	void levelsAreAttenuationLimits() {
		assertEquals(1, DenoisedAudio.dryGain(AudioCleanup.NoiseReduction.OFF), 1e-9);
		// 12 dB and 24 dB of maximum reduction; Strong keeps nothing of the original.
		assertEquals(0.2512, DenoisedAudio.dryGain(AudioCleanup.NoiseReduction.LIGHT), 1e-4);
		assertEquals(0.0631, DenoisedAudio.dryGain(AudioCleanup.NoiseReduction.BALANCED), 1e-4);
		assertEquals(0, DenoisedAudio.dryGain(AudioCleanup.NoiseReduction.STRONG), 1e-9);
	}

	@Test
	@DisplayName("the cache name follows the source file: an edited recording renders again")
	void cacheNameFollowsTheSource(@TempDir Path directory) throws Exception {
		Path cache = directory.resolve("cache");
		Path source = directory.resolve("mic.wav");
		Files.write(source, new byte[] {1, 2, 3});
		Path first = DenoisedAudio.cacheFile(cache, source);
		assertEquals(first, DenoisedAudio.cacheFile(cache, source), "stable for the same file");
		assertTrue(first.getFileName().toString().endsWith(".wav"));
		assertTrue(DenoisedAudio.cached(cache, source).isEmpty(), "nothing rendered yet");

		Files.setLastModifiedTime(source, FileTime.fromMillis(
				Files.getLastModifiedTime(source).toMillis() + 5_000));
		assertNotEquals(first, DenoisedAudio.cacheFile(cache, source), "a newer file is new audio");

		Path other = directory.resolve("system.wav");
		Files.write(other, new byte[] {1, 2, 3});
		assertNotEquals(DenoisedAudio.cacheFile(cache, source),
				DenoisedAudio.cacheFile(cache, other), "one copy per source");
	}

	@Test
	@DisplayName("the copy is a plain 16-bit 48 kHz WAV the graphs read like any input")
	void wavHeaderIsCanonical() {
		ByteBuffer header = ByteBuffer.wrap(DenoisedAudio.wavHeader(2, 48_000))
				.order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(44, header.remaining());
		assertEquals('R', header.get(0));
		assertEquals(36 + 48_000 * 4, header.getInt(4));
		assertEquals(1, header.getShort(20), "PCM");
		assertEquals(2, header.getShort(22), "channels");
		assertEquals(48_000, header.getInt(24));
		assertEquals(16, header.getShort(34), "bits");
		assertEquals(48_000 * 4, header.getInt(40), "data bytes");
	}

	@Test
	@DisplayName("the decode reads the first audio stream from container time zero")
	void decodeCommandKeepsTheTimeline() {
		List<String> command = DenoisedAudio.decodeCommand(Path.of("/opt/ffmpeg"),
				Path.of("/rec/mic.m4a"), 1);
		assertEquals("0:a:0", command.get(command.indexOf("-map") + 1));
		// A stream that starts late is padded from zero so the copy shares the original's time.
		assertEquals("aresample=48000:first_pts=0", command.get(command.indexOf("-af") + 1));
		assertEquals("1", command.get(command.indexOf("-ac") + 1));
		assertEquals("pipe:1", command.getLast());
	}
}
