package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AudioDecoder} against the committed AAC fixture (plan §5.7). */
class AudioDecoderTest {

	@Test
	@DisplayName("decodes the AAC fixture to 48 kHz stereo float32")
	void decodesToFloat32Stereo48k() {
		float[] buffer = new float[4096];
		long frames = 0;
		float peak = 0;
		try (AudioDecoder decoder = AudioDecoder.open(MediaFixtures.aac())) {
			assertEquals(48_000, decoder.sampleRate());
			assertEquals(2, decoder.channels());
			int read;
			while ((read = decoder.read(buffer)) >= 0) {
				frames += read;
				for (int i = 0; i < read * 2; i++) {
					assertTrue(Float.isFinite(buffer[i]), "non-finite sample");
					assertTrue(Math.abs(buffer[i]) <= 1.0f, "sample out of range: " + buffer[i]);
					peak = Math.max(peak, Math.abs(buffer[i]));
				}
				if (frames > 200_000) {
					throw new AssertionError("decoder did not reach EOF");
				}
			}
		}
		// AAC adds encoder delay/padding: 96000 samples +/- two 1024-sample frames.
		assertEquals(2 * 48_000, frames, 4_096);
		assertTrue(peak > 0.05f, "audio is silent (peak " + peak + ")");
	}

	@Test
	@DisplayName("read returns -1 at end of stream")
	void returnsMinusOneAtEof() {
		try (AudioDecoder decoder = AudioDecoder.open(MediaFixtures.aac())) {
			float[] buffer = new float[4096];
			int read;
			while ((read = decoder.read(buffer)) >= 0) {
				// drain
			}
			assertEquals(-1, decoder.read(buffer));
			assertEquals(-1, decoder.read(buffer));
		}
	}

	@Test
	@DisplayName("seek restarts decoding in the middle of the file")
	void seekRestartsDecoding() {
		try (AudioDecoder decoder = AudioDecoder.open(MediaFixtures.aac())) {
			float[] buffer = new float[1024];
			assertTrue(decoder.read(buffer) > 0);
			decoder.seek(1_500_000);
			int read = decoder.read(buffer);
			assertTrue(read > 0, "no audio after seek");
		}
	}

	@Test
	@DisplayName("the interleaved array length must be a multiple of two")
	void rejectsOddArrayLength() {
		try (AudioDecoder decoder = AudioDecoder.open(MediaFixtures.aac())) {
			assertThrows(IllegalArgumentException.class, () -> decoder.read(new float[3]));
		}
	}
}
