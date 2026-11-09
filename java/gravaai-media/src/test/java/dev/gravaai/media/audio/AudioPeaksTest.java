package dev.gravaai.media.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.ffmpeg.AudioDecoder;

/** {@link AudioPeaks}: decode from a generated WAV and the persistent cache round-trip. */
class AudioPeaksTest {

	@TempDir
	Path temp;

	/**
	 * A 48 kHz mono PCM WAV: half a second of a 440 Hz sine at 0.8 amplitude, then half a second
	 * of silence — 48 000 frames, exactly 200 peak buckets.
	 */
	private Path writeSineThenSilenceWav() throws IOException {
		int sampleRate = 48000;
		int frames = sampleRate;
		short[] samples = new short[frames];
		for (int frame = 0; frame < frames; frame++) {
			samples[frame] = frame < frames / 2
					? (short) Math.round(0.8 * Short.MAX_VALUE
							* Math.sin(2 * Math.PI * 440.0 * frame / sampleRate))
					: 0;
		}
		ByteBuffer pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short sample : samples) {
			pcm.putShort(sample);
		}
		int dataBytes = frames * 2;
		ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
		wav.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
		wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
		wav.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
		wav.put("data".getBytes()).putInt(dataBytes).put(pcm.flip());
		Path file = temp.resolve("sine.wav");
		Files.write(file, wav.array(), StandardOpenOption.CREATE_NEW);
		return file;
	}

	@Test
	@DisplayName("decodes the sine file into 200 buckets, loud where the sine plays, silent after")
	void decodesSineThenSilence() throws IOException {
		Path wav = writeSineThenSilenceWav();
		float[] peaks = AudioPeaks.decode(wav);
		assertEquals(200, peaks.length, "one bucket per 5 ms of a 1 s file");
		assertEquals(1f, peaks[0], 0.01f, "the sine's maximum normalizes to 1");
		assertEquals(1f, peaks[99], 0.01f, "still loud at the sine's end (490 ms)");
		assertEquals(0f, peaks[150], "the silence half stays silent");
	}

	@Test
	@DisplayName("decoding a file without an audio stream throws")
	void rejectsMediaWithoutAudio() {
		Path file = temp.resolve("empty.bin");
		try {
			Files.write(file, new byte[] {1, 2, 3});
		} catch (IOException error) {
			throw new AssertionError(error);
		}
		assertThrows(RuntimeException.class, () -> AudioPeaks.decode(file));
	}

	@Test
	@DisplayName("loadPeaks caches beside the file: the second call is the cached decode")
	void cachesDecodeResult() throws IOException {
		Path wav = writeSineThenSilenceWav();
		Path cache = wav.resolveSibling("sine.wav.peaks");
		float[] decoded = AudioPeaks.loadPeaks(wav, cache);
		assertTrue(Files.isRegularFile(cache), "the cache file was written");
		float[] cached = AudioPeaks.loadPeaks(wav, cache);
		assertArrayEquals(decoded, cached, "cache and decode agree");

		// Corrupt the cache: loadPeaks must recover by re-decoding.
		Files.write(cache, new byte[] {'x', 'y', 'z'});
		float[] redecoded = AudioPeaks.loadPeaks(wav, cache);
		assertArrayEquals(decoded, redecoded, "a corrupt cache falls back to a decode");
	}

	@Test
	@DisplayName("a changed source file invalidates the cache")
	void invalidatesStaleCache() throws IOException {
		Path wav = writeSineThenSilenceWav();
		Path cache = wav.resolveSibling("sine.wav.peaks");
		AudioPeaks.loadPeaks(wav, cache);
		assertNotNull(AudioPeaks.readCache(cache, wav), "fresh cache reads back");
		Files.setLastModifiedTime(wav,
				FileTime.fromMillis(System.currentTimeMillis() + 5_000));
		assertNull(AudioPeaks.readCache(cache, wav), "a newer source file is stale");
	}

	@Test
	@DisplayName("readCache rejects missing, truncated and foreign caches")
	void readCacheRejectsGarbage() throws IOException {
		Path wav = writeSineThenSilenceWav();
		assertNull(AudioPeaks.readCache(temp.resolve("missing.peaks"), wav));
		Path truncated = temp.resolve("truncated.peaks");
		Files.write(truncated, new byte[8]);
		assertNull(AudioPeaks.readCache(truncated, wav));
		Path foreign = temp.resolve("foreign.peaks");
		Files.write(foreign, new byte[4096]);
		assertNull(AudioPeaks.readCache(foreign, wav));
	}

	@Test
	@DisplayName("a silent file yields all-zero peaks instead of NaNs")
	void silentFileStaysSilent() {
		float[] buckets = new float[10];
		float[] peaks = AudioPeaks.normalize(buckets, buckets.length);
		assertEquals(10, peaks.length);
		for (float peak : peaks) {
			assertEquals(0f, peak);
		}
	}

	@Test
	@DisplayName("the decoder contract the bucket math relies on: 48 kHz output")
	void decoderContractHolds() {
		assertEquals(48000, AudioDecoder.OUTPUT_SAMPLE_RATE);
		assertEquals(0, AudioDecoder.OUTPUT_SAMPLE_RATE % AudioPeaks.PEAKS_PER_SECOND,
				"buckets must split frames evenly");
		assertEquals(5, AudioPeaks.BUCKET_MILLIS_MS);
	}
}
