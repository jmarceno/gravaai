package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.core.project.KeySoundStyle;
import dev.gravaai.media.audio.ClickSounds;

/**
 * Pre-renders the click bus: one mono 48 kHz 16-bit RIFF WAV with every click placed at its
 * timeline time, so the export adds a single {@link ExportAudioGraph.RegionInput} instead of one
 * input per click (no {@code amix} explosion on long recordings).
 *
 * <p>The file is created with {@code setLength} and each click window is written with its own
 * {@code seek}: silent stretches stay holes on ext4/btrfs/xfs, keeping the physical size near
 * the sum of the click tails. Data is streamed in 1 s blocks, so memory stays constant, and the
 * render is capped at the last hit plus the sound length — never the full video.</p>
 */
public final class ClickSoundWav {

	private static final System.Logger LOG = System.getLogger(ClickSoundWav.class.getName());
	private static final int SAMPLE_RATE = 48_000;
	/** 1 s of mono 16-bit blocks: the streaming granularity. */
	private static final int BLOCK_FRAMES = SAMPLE_RATE;

	private ClickSoundWav() {}

	/**
	 * Writes the click bus to {@code destination} and returns it, or {@code null} when there is
	 * nothing to write (no hits, sound unavailable, I/O failure).
	 */
	public static @Nullable Path write(Path destination, ClickSoundStyle style, double volume,
			long[] timesMs) {
		return write(destination, ClickSounds.pcm(style), volume, timesMs);
	}

	/** Same bus for key-press sounds. */
	public static @Nullable Path write(Path destination, KeySoundStyle style, double volume,
			long[] timesMs) {
		return write(destination, ClickSounds.pcm(style), volume, timesMs);
	}

	private static @Nullable Path write(Path destination, float @Nullable [] pcm, double volume,
			long[] timesMs) {
		if (timesMs == null || timesMs.length == 0) {
			return null;
		}
		if (pcm == null || pcm.length == 0) {
			return null;
		}
		int soundFrames = pcm.length / 2;
		long lastHitEndFrames = timesMs[timesMs.length - 1] * SAMPLE_RATE / 1000 + soundFrames;
		long dataBytes = lastHitEndFrames * 2;
		double gain = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
		try {
			Files.createDirectories(destination.toAbsolutePath().getParent());
			try (RandomAccessFile file = new RandomAccessFile(destination.toFile(), "rw")) {
				file.setLength(44 + dataBytes);
				byte[] blockBytes = new byte[BLOCK_FRAMES * 2];
				for (long hitMs : timesMs) {
					long startFrame = hitMs * SAMPLE_RATE / 1000;
					for (int offset = 0; offset < soundFrames; offset += BLOCK_FRAMES) {
						int count = Math.min(BLOCK_FRAMES, soundFrames - offset);
						for (int i = 0; i < count; i++) {
							int source = (offset + i) * 2;
							float mono = (pcm[source] + pcm[source + 1]) * 0.5f * (float) gain;
							float clamped = mono > 1 ? 1 : (mono < -1 ? -1 : mono);
							int sample = Math.round(clamped * 32767f) & 0xFFFF;
							blockBytes[i * 2] = (byte) sample;
							blockBytes[i * 2 + 1] = (byte) (sample >> 8);
						}
						file.seek(44 + (startFrame + offset) * 2);
						file.write(blockBytes, 0, count * 2);
					}
				}
				writeHeader(file, dataBytes);
			}
			destination.toFile().deleteOnExit();
			return destination;
		} catch (IOException failure) {
			LOG.log(System.Logger.Level.WARNING, "click bus WAV write failed: " + failure);
			return null;
		}
	}

	/** RIFF/WAVE header for mono 16-bit 48 kHz PCM of {@code dataBytes} bytes. */
	private static void writeHeader(RandomAccessFile file, long dataBytes) throws IOException {
		file.seek(0);
		file.writeBytes("RIFF");
		writeLE32(file, (int) Math.min(Integer.MAX_VALUE, 36 + dataBytes));
		file.writeBytes("WAVE");
		file.writeBytes("fmt ");
		writeLE32(file, 16);
		writeLE16(file, 1);
		writeLE16(file, 1);
		writeLE32(file, SAMPLE_RATE);
		writeLE32(file, SAMPLE_RATE * 2);
		writeLE16(file, 2);
		writeLE16(file, 16);
		file.writeBytes("data");
		writeLE32(file, (int) Math.min(Integer.MAX_VALUE, dataBytes));
	}

	private static void writeLE32(RandomAccessFile file, int value) throws IOException {
		file.write(new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16),
				(byte) (value >> 24)});
	}

	private static void writeLE16(RandomAccessFile file, int value) throws IOException {
		file.write(new byte[] {(byte) value, (byte) (value >> 8)});
	}
}
