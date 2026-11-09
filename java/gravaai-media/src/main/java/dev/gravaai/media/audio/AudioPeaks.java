package dev.gravaai.media.audio;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import dev.gravaai.media.ffmpeg.AudioDecoder;

/**
 * Audio peaks for the timeline waveforms: the whole file decoded once into fixed-resolution
 * max-amplitude buckets, persisted next to the media so a project reopens without re-decoding.
 *
 * <p>Buckets are {@value #BUCKET_MILLIS_MS} ms wide ({@link #PEAKS_PER_SECOND} per second at the
 * 48 kHz decoder rate); every value is the file's peak amplitude in its bucket normalized to the
 * file's global maximum, so a quiet mic recording renders as tall as a loud one. One hour of audio
 * is {@code 200 * 3600} floats — a few megabytes, exact enough for every timeline zoom level
 * ({@code 5 ms} buckets stay below the timeline's smallest visible bar pitch).
 *
 * <p>The cache file is a little-endian header plus the float array. It is validated against the
 * source file's size and modification time, so an edited or re-recorded media file recomputes
 * instead of showing stale peaks. Writes go through a temporary file and an atomic move, so a
 * crash mid-write can never leave a half-written cache that then reads as garbage.
 */
public final class AudioPeaks {

	/** Fixed peaks per second of audio (the cache and resampling resolution). */
	public static final int PEAKS_PER_SECOND = 200;

	/** One peak bucket in milliseconds ({@code 1000 / PEAKS_PER_SECOND}). */
	public static final int BUCKET_MILLIS_MS = 1000 / PEAKS_PER_SECOND;

	private static final int VERSION = 1;
	private static final byte[] MAGIC = {'G', 'R', 'A', 'V', 'A', 'P', 'E', 'K'};
	private static final int HEADER_BYTES = MAGIC.length + 4 + 4 + 8 + 8 + 4;

	private AudioPeaks() {}

	/**
	 * The peaks for {@code audioFile}, cache-first: reads {@code cacheFile} when it matches the
	 * source's size and mtime, otherwise decodes through the ffmpeg {@link AudioDecoder} and
	 * writes the cache (best effort — a read-only directory just means the next session decodes
	 * again). Never returns {@code null}; throws when the file cannot be decoded (no audio
	 * stream, unreadable container), which callers treat as "no waveform".
	 */
	public static float[] loadPeaks(Path audioFile, Path cacheFile) {
		float[] cached = readCache(cacheFile, audioFile);
		if (cached != null) {
			return cached;
		}
		float[] decoded = decode(audioFile);
		writeCache(cacheFile, audioFile, decoded);
		return decoded;
	}

	/**
	 * Decodes the best audio stream into one normalized peak per bucket. An hour of audio takes
	 * seconds of ffmpeg decode; callers run this off the FX thread.
	 */
	public static float[] decode(Path audioFile) {
		try (AudioDecoder decoder = AudioDecoder.open(audioFile)) {
			return decodeStream(decoder);
		}
	}

	private static float[] decodeStream(AudioDecoder decoder) {
		int framesPerBucket = AudioDecoder.OUTPUT_SAMPLE_RATE / PEAKS_PER_SECOND;
		float[] buffer = new float[16_384 * AudioDecoder.OUTPUT_CHANNELS];
		float[] buckets = new float[1024];
		int bucketCount = 0;
		long frameIndex = 0;
		int read;
		while ((read = decoder.read(buffer)) > 0) {
			for (int frame = 0; frame < read; frame++) {
				int bucket = (int) ((frameIndex + frame) / framesPerBucket);
				if (bucket >= buckets.length) {
					buckets = java.util.Arrays.copyOf(buckets,
							Math.max(bucket + 1, buckets.length * 2));
				}
				bucketCount = Math.max(bucketCount, bucket + 1);
				float amplitude = Math.max(Math.abs(buffer[frame * 2]),
						Math.abs(buffer[frame * 2 + 1]));
				if (amplitude > buckets[bucket]) {
					buckets[bucket] = amplitude;
				}
			}
			frameIndex += read;
		}
		return normalize(buckets, bucketCount);
	}

	/** Scales the buckets to 0..1 against the file's global maximum; silence stays silent. */
	static float[] normalize(float[] buckets, int bucketCount) {
		float[] peaks = new float[bucketCount];
		float maximum = 0;
		for (int index = 0; index < bucketCount; index++) {
			maximum = Math.max(maximum, buckets[index]);
		}
		if (maximum <= 0) {
			return peaks;
		}
		for (int index = 0; index < bucketCount; index++) {
			peaks[index] = Math.min(1f, buckets[index] / maximum);
		}
		return peaks;
	}

	/** The cached peaks for {@code audioFile}, or {@code null} when missing, stale or unreadable. */
	public static float @Nullable [] readCache(Path cacheFile, Path audioFile) {
		if (!Files.isRegularFile(cacheFile)) {
			return null;
		}
		try {
			byte[] bytes = Files.readAllBytes(cacheFile);
			if (bytes.length < HEADER_BYTES) {
				return null;
			}
			ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			for (byte magicByte : MAGIC) {
				if (header.get() != magicByte) {
					return null;
				}
			}
			if (header.getInt() != VERSION || header.getInt() != BUCKET_MILLIS_MS) {
				return null;
			}
			if (header.getLong() != Files.size(audioFile)
					|| header.getLong() != Files.getLastModifiedTime(audioFile).toMillis()) {
				return null;
			}
			int peakCount = header.getInt();
			if (peakCount < 0 || HEADER_BYTES + (long) peakCount * Float.BYTES != bytes.length) {
				return null;
			}
			float[] peaks = new float[peakCount];
			for (int index = 0; index < peakCount; index++) {
				peaks[index] = header.getFloat();
			}
			return peaks;
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** Writes the cache atomically; failures are swallowed (the next session re-decodes). */
	public static void writeCache(Path cacheFile, Path audioFile, float[] peaks) {
		try {
			ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + peaks.length * Float.BYTES)
					.order(ByteOrder.LITTLE_ENDIAN);
			buffer.put(MAGIC);
			buffer.putInt(VERSION);
			buffer.putInt(BUCKET_MILLIS_MS);
			buffer.putLong(Files.size(audioFile));
			buffer.putLong(Files.getLastModifiedTime(audioFile).toMillis());
			buffer.putInt(peaks.length);
			for (float peak : peaks) {
				buffer.putFloat(peak);
			}
			Path parent = cacheFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temporary = Files.createTempFile(parent, cacheFile.getFileName().toString(), ".tmp");
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				channel.write(buffer.flip());
				channel.force(true);
			}
			try {
				Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException error) {
			// A missing cache only costs a re-decode next session.
		}
	}
}
