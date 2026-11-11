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

	private static Path writeRawStereo(Path file, int frames, boolean dualMono) throws Exception {
		ByteBuffer raw = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
		java.util.Random random = new java.util.Random(9);
		for (int frame = 0; frame < frames; frame++) {
			short left = (short) (random.nextGaussian() * 1500);
			short right = dualMono ? left : (short) (random.nextGaussian() * 1500);
			raw.putShort(left).putShort(right);
		}
		Files.write(file, raw.array());
		return file;
	}

	private static double rmsDb(Path rawStereo, int skipFrames) throws Exception {
		ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(rawStereo))
				.order(ByteOrder.LITTLE_ENDIAN);
		raw.position(skipFrames * 4);
		double sum = 0;
		long count = 0;
		while (raw.remaining() >= 2) {
			double value = raw.getShort() / 32768.0;
			sum += value * value;
			count++;
		}
		return 10 * Math.log10(sum / Math.max(1, count) + 1e-20);
	}

	@Test
	@DisplayName("a stereo capture with identical channels is recognised as dual mono")
	void detectsDualMono(@TempDir Path directory) throws Exception {
		assertTrue(DenoisedAudio.isDualMono(writeRawStereo(directory.resolve("a.raw"), 70_000,
				true)));
		assertTrue(!DenoisedAudio.isDualMono(writeRawStereo(directory.resolve("b.raw"), 70_000,
				false)));
	}

	@Test
	@org.junit.jupiter.api.Timeout(120)
	@DisplayName("a raw microphone capture comes back cleaned, same length, level baked in")
	void denoisesARawCapture(@TempDir Path directory) throws Exception {
		// The module's tests run from java/gravaai-media: point the payload at the staged build.
		Path staged = Path.of("..", "native", "build").toAbsolutePath().normalize();
		String property = dev.gravaai.platform.nativehelpers.NativeHelpers.NATIVE_DIR_PROPERTY;
		String previous = System.getProperty(property);
		if (Files.isDirectory(staged)) {
			System.setProperty(property, staged.toString());
		}
		try {
			org.junit.jupiter.api.Assumptions.assumeTrue(
					SpeechDenoiser.isInstalled(DenoisedAudio.modelDir()),
					"denoiser model not staged in the native payload");
			denoiseRawCapture(directory);
		} finally {
			if (previous == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, previous);
			}
		}
	}

	private static void denoiseRawCapture(Path directory) throws Exception {
		int frames = 3 * 48_000 + 77;
		Path input = writeRawStereo(directory.resolve("mic.raw"), frames, true);
		Path strong = directory.resolve("strong.raw");
		Path light = directory.resolve("light.raw");

		assertEquals(frames, DenoisedAudio.denoiseRaw(input, strong, 2, 0, () -> false));
		assertEquals(frames, DenoisedAudio.denoiseRaw(input, light, 2,
				DenoisedAudio.dryGain(AudioCleanup.NoiseReduction.LIGHT), () -> false));
		assertEquals(Files.size(input), Files.size(strong), "the mux needs the same length");
		assertEquals(Files.size(input), Files.size(light));
		assertTrue(DenoisedAudio.isDualMono(strong), "a dual-mono capture stays dual mono");

		double before = rmsDb(input, 48_000);
		double strongAfter = rmsDb(strong, 48_000);
		double lightAfter = rmsDb(light, 48_000);
		assertTrue(strongAfter < before - 25, "noise must go at full strength: " + before
				+ " -> " + strongAfter);
		// Light caps the reduction at 12 dB: the room stays, quieter.
		assertEquals(before - 12, lightAfter, 1.5, "Light is a 12 dB attenuation limit");
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
