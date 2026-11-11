package dev.gravaai.media.denoise;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * The denoised copy of one audio source: the {@link SpeechDenoiser} run over a whole file once,
 * kept as a WAV the preview and export graphs read like any other input.
 *
 * <p>The ffmpeg filter graphs cannot host the network, so a cleaned track is rendered ahead of
 * time. The render is always full strength; the editor's Light / Balanced / Strong levels are a
 * mix of the original and this file inside the graph ({@link #dryGain}), so changing the level
 * never needs a new render.
 *
 * <p>The file starts at the source's container time zero and has the source's length, at 48 kHz
 * with the source's channel count (mono or stereo), so it can replace or be mixed with the
 * original sample for sample. It is keyed by the source's path, size and modification time and
 * the engine version: an edited recording or a newer engine renders again instead of reusing a
 * stale file.
 */
public final class DenoisedAudio {

	/** The model directory inside the native payload. */
	public static final String MODEL_DIRECTORY = "models/denoise";

	private static final System.Logger LOG = System.getLogger("gravaai.media.denoise");

	private static final int CHUNK_FRAMES = 4800;

	private DenoisedAudio() {}

	/** The bundled model directory, present or not. */
	public static Path modelDir() {
		return NativeHelpers.nativeDir().resolve(MODEL_DIRECTORY);
	}

	/** True when this build ships the denoiser model. */
	public static boolean isAvailable() {
		try {
			return SpeechDenoiser.isInstalled(modelDir());
		} catch (RuntimeException error) {
			return false;
		}
	}

	/**
	 * How much of the original stays in the mix at a noise-reduction level: the attenuation limit
	 * of the level as a linear gain. Light caps the reduction at 12 dB (the room stays audible,
	 * nothing is ever gated), Balanced at 24 dB, Strong removes everything the network removes.
	 * The denoised file gets {@code 1 - dryGain}.
	 */
	public static double dryGain(AudioCleanup.NoiseReduction level) {
		return switch (level) {
			case OFF -> 1;
			case LIGHT -> Math.pow(10, -12 / 20.0);
			case BALANCED -> Math.pow(10, -24 / 20.0);
			case STRONG -> 0;
		};
	}

	/**
	 * Where the denoised copy of {@code source} lives in {@code cacheDir}. The name changes when
	 * the source file or the engine does.
	 */
	public static Path cacheFile(Path cacheDir, Path source) throws IOException {
		Path absolute = source.toAbsolutePath().normalize();
		String key = absolute + "|" + Files.size(absolute) + "|"
				+ Files.getLastModifiedTime(absolute).toMillis() + "|"
				+ SpeechDenoiser.ENGINE_VERSION;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(key.getBytes(StandardCharsets.UTF_8));
			return cacheDir.resolve(HexFormat.of().formatHex(digest, 0, 16) + ".wav");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	/** The rendered copy of {@code source}, when it is already in the cache. */
	public static Optional<Path> cached(Path cacheDir, Path source) {
		try {
			Path file = cacheFile(cacheDir, source);
			return Files.isRegularFile(file) && Files.size(file) > WAV_HEADER_BYTES
					? Optional.of(file) : Optional.empty();
		} catch (IOException | RuntimeException error) {
			return Optional.empty();
		}
	}

	/**
	 * Renders the denoised copy of {@code source} into the cache and returns it; an existing
	 * render is returned as is. The work is written to a temporary file and moved into place, so a
	 * cancelled or crashed render never leaves a half file under the final name.
	 *
	 * @param ffmpeg the ffmpeg binary that decodes the source
	 * @param progress 0..1 as the file is processed
	 * @param cancelled polled between chunks; a cancelled render throws
	 * @throws IOException when the source cannot be decoded or the cache cannot be written
	 * @throws java.util.concurrent.CancellationException when {@code cancelled} turned true
	 */
	public static Path render(Path ffmpeg, Path source, Path cacheDir, DoubleConsumer progress,
			BooleanSupplier cancelled) throws IOException {
		Path target = cacheFile(cacheDir, source);
		if (Files.isRegularFile(target) && Files.size(target) > WAV_HEADER_BYTES) {
			progress.accept(1);
			return target;
		}
		Files.createDirectories(cacheDir);
		MediaInfo info = MediaProbe.probe(source);
		if (info.audioStreams().isEmpty()) {
			throw new IOException("no audio stream in " + source);
		}
		int channels = Math.max(1, Math.min(2, info.audioStreams().get(0).channels()));
		double durationSeconds = Math.max(info.audioStreams().get(0).durationSeconds(),
				info.durationSeconds());
		long expectedFrames = Double.isFinite(durationSeconds) && durationSeconds > 0
				? Math.round(durationSeconds * DenoiseDsp.SAMPLE_RATE) : 0;
		Path partial = Files.createTempFile(cacheDir, "render-", ".part");
		long started = System.nanoTime();
		try {
			long frames = renderTo(ffmpeg, source, partial, channels, expectedFrames, progress,
					cancelled);
			try {
				Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
			}
			double audioSeconds = frames / (double) DenoiseDsp.SAMPLE_RATE;
			double seconds = (System.nanoTime() - started) / 1e9;
			LOG.log(System.Logger.Level.INFO, () -> String.format(java.util.Locale.ROOT,
					"denoised %s: %.1f s of audio in %.1f s (%.0fx realtime)",
					source.getFileName(), audioSeconds, seconds,
					seconds > 0 ? audioSeconds / seconds : 0));
			progress.accept(1);
			return target;
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	/**
	 * Denoises a raw capture (48 kHz signed 16-bit interleaved PCM, the recorder's own track
	 * format) into another raw file of the same length, with the level baked in: the result is
	 * {@code dryGain} of the original plus the rest of the denoised signal, sample for sample.
	 * This is the record-time path, where the cleaned microphone goes straight into the mux.
	 *
	 * <p>A stereo capture whose two channels are identical (a mono microphone opened as stereo,
	 * the usual case) is denoised once and written to both channels, which halves the time.
	 *
	 * @param channels 1 or 2
	 * @param dryGain 0..1, see {@link #dryGain}
	 * @param cancelled polled between chunks; a cancelled run throws
	 * @return how many frames were written
	 */
	public static long denoiseRaw(Path rawInput, Path rawOutput, int channels, double dryGain,
			BooleanSupplier cancelled) throws IOException {
		if (channels < 1 || channels > 2) {
			throw new IllegalArgumentException("channels must be 1 or 2: " + channels);
		}
		float dry = (float) Math.clamp(dryGain, 0, 1);
		float wet = 1 - dry;
		boolean dualMono = channels == 2 && isDualMono(rawInput);
		int engineChannels = dualMono ? 1 : channels;
		long started = System.nanoTime();
		long frames = 0;
		try (SpeechDenoiser denoiser = SpeechDenoiser.open(modelDir());
				InputStream in = new java.io.BufferedInputStream(Files.newInputStream(rawInput),
						1 << 16);
				java.io.OutputStream out = new java.io.BufferedOutputStream(
						Files.newOutputStream(rawOutput), 1 << 16)) {
			SpeechDenoiser.Stream[] streams = new SpeechDenoiser.Stream[engineChannels];
			for (int channel = 0; channel < engineChannels; channel++) {
				streams[channel] = denoiser.newStream();
			}
			// The original samples wait here until their denoised counterparts come out, so the
			// two can be mixed at the same positions.
			java.util.ArrayDeque<float[][]> pendingDry = new java.util.ArrayDeque<>();
			int pendingOffset = 0;
			byte[] bytes = new byte[CHUNK_FRAMES * channels * Short.BYTES];
			boolean ended = false;
			while (!ended) {
				if (cancelled.getAsBoolean()) {
					throw new java.util.concurrent.CancellationException("denoise cancelled");
				}
				int filled = in.readNBytes(bytes, 0, bytes.length);
				ended = filled < bytes.length;
				int chunk = filled / (channels * Short.BYTES);
				if (chunk > 0) {
					float[][] planes = new float[engineChannels][chunk];
					for (int frame = 0; frame < chunk; frame++) {
						for (int channel = 0; channel < engineChannels; channel++) {
							int at = (frame * channels + channel) * Short.BYTES;
							planes[channel][frame] =
									(short) ((bytes[at] & 0xFF) | (bytes[at + 1] << 8)) / 32768f;
						}
					}
					pendingDry.add(planes);
					for (int channel = 0; channel < engineChannels; channel++) {
						streams[channel].write(planes[channel], 0, chunk);
					}
				}
				if (ended) {
					for (SpeechDenoiser.Stream stream : streams) {
						stream.finish();
					}
				}
				int ready = Integer.MAX_VALUE;
				for (SpeechDenoiser.Stream stream : streams) {
					ready = Math.min(ready, stream.available());
				}
				if (ready <= 0) {
					continue;
				}
				float[][] cleaned = new float[engineChannels][ready];
				for (int channel = 0; channel < engineChannels; channel++) {
					streams[channel].read(cleaned[channel], 0, ready);
				}
				ByteBuffer mixed = ByteBuffer.allocate(ready * channels * Short.BYTES)
						.order(ByteOrder.LITTLE_ENDIAN);
				for (int frame = 0; frame < ready; frame++) {
					float[][] original = java.util.Objects.requireNonNull(pendingDry.peek());
					for (int channel = 0; channel < channels; channel++) {
						int source = dualMono ? 0 : channel;
						float value = dry * original[source][pendingOffset]
								+ wet * cleaned[source][frame];
						mixed.putShort((short) Math.round(Math.clamp(value, -1f, 1f) * 32767f));
					}
					if (++pendingOffset == original[0].length) {
						pendingDry.remove();
						pendingOffset = 0;
					}
				}
				out.write(mixed.array());
				frames += ready;
			}
		}
		long written = frames;
		double audioSeconds = written / (double) DenoiseDsp.SAMPLE_RATE;
		double seconds = (System.nanoTime() - started) / 1e9;
		LOG.log(System.Logger.Level.INFO, () -> String.format(java.util.Locale.ROOT,
				"denoised %s: %.1f s of audio in %.1f s (%.0fx realtime%s)",
				rawInput.getFileName(), audioSeconds, seconds,
				seconds > 0 ? audioSeconds / seconds : 0, dualMono ? ", dual mono" : ""));
		return written;
	}

	/** True when every frame of a raw stereo capture has the same sample in both channels. */
	public static boolean isDualMono(Path rawStereo) throws IOException {
		try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(rawStereo),
				1 << 16)) {
			byte[] bytes = new byte[1 << 16];
			while (true) {
				int filled = in.readNBytes(bytes, 0, bytes.length);
				for (int at = 0; at + 3 < filled; at += 4) {
					if (bytes[at] != bytes[at + 2] || bytes[at + 1] != bytes[at + 3]) {
						return false;
					}
				}
				if (filled < bytes.length) {
					return true;
				}
			}
		}
	}

	/** The decode command: the first audio stream as 48 kHz PCM from container time zero. */
	static List<String> decodeCommand(Path ffmpeg, Path source, int channels) {
		return List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-i", source.toString(), "-vn", "-map", "0:a:0",
				// A stream that starts late in its container is padded from zero, one that starts
				// early is trimmed: the copy must share the original's timeline.
				"-af", "aresample=" + DenoiseDsp.SAMPLE_RATE + ":first_pts=0",
				"-f", "s16le", "-ar", String.valueOf(DenoiseDsp.SAMPLE_RATE),
				"-ac", String.valueOf(channels), "pipe:1");
	}

	private static long renderTo(Path ffmpeg, Path source, Path output, int channels,
			long expectedFrames, DoubleConsumer progress, BooleanSupplier cancelled)
			throws IOException {
		RunningProcess process = ProcessRunner.of(decodeCommand(ffmpeg, source, channels))
				.rawStdout()
				.onStderrLine(line -> LOG.log(System.Logger.Level.DEBUG, "denoise ffmpeg: " + line))
				.start();
		long frames = 0;
		try (SpeechDenoiser denoiser = SpeechDenoiser.open(modelDir());
				RandomAccessFile wav = new RandomAccessFile(output.toFile(), "rw")) {
			wav.setLength(0);
			wav.write(wavHeader(channels, 0));
			SpeechDenoiser.Stream[] streams = new SpeechDenoiser.Stream[channels];
			for (int channel = 0; channel < channels; channel++) {
				streams[channel] = denoiser.newStream();
			}
			InputStream stdout = process.stdout();
			byte[] bytes = new byte[CHUNK_FRAMES * channels * Short.BYTES];
			float[][] planes = new float[channels][CHUNK_FRAMES];
			int carry = 0;
			while (true) {
				if (cancelled.getAsBoolean()) {
					throw new java.util.concurrent.CancellationException("denoise cancelled");
				}
				int read = stdout.read(bytes, carry, bytes.length - carry);
				if (read < 0) {
					break;
				}
				int available = carry + read;
				int chunk = available / (channels * Short.BYTES);
				for (int frame = 0; frame < chunk; frame++) {
					for (int channel = 0; channel < channels; channel++) {
						int at = (frame * channels + channel) * Short.BYTES;
						short value = (short) ((bytes[at] & 0xFF) | (bytes[at + 1] << 8));
						planes[channel][frame] = value / 32768f;
					}
				}
				carry = available - chunk * channels * Short.BYTES;
				System.arraycopy(bytes, available - carry, bytes, 0, carry);
				for (int channel = 0; channel < channels; channel++) {
					streams[channel].write(planes[channel], 0, chunk);
				}
				frames += drain(streams, wav);
				if (expectedFrames > 0) {
					progress.accept(Math.min(0.99, frames / (double) expectedFrames));
				}
			}
			for (SpeechDenoiser.Stream stream : streams) {
				stream.finish();
			}
			frames += drain(streams, wav);
			wav.seek(0);
			wav.write(wavHeader(channels, frames));
		} catch (RuntimeException | IOException error) {
			process.cancel();
			throw error;
		}
		try {
			var result = process.waitForExit();
			if (!result.succeeded() && frames == 0) {
				throw new IOException("could not decode " + source + ": " + result.stderr());
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("denoise interrupted", interrupted);
		}
		if (frames == 0) {
			throw new IOException("no audio decoded from " + source);
		}
		return frames;
	}

	/** Writes every frame all channels have ready; returns how many frames were written. */
	private static long drain(SpeechDenoiser.Stream[] streams, RandomAccessFile wav)
			throws IOException {
		int channels = streams.length;
		int ready = Integer.MAX_VALUE;
		for (SpeechDenoiser.Stream stream : streams) {
			ready = Math.min(ready, stream.available());
		}
		if (ready <= 0) {
			return 0;
		}
		float[][] planes = new float[channels][ready];
		for (int channel = 0; channel < channels; channel++) {
			streams[channel].read(planes[channel], 0, ready);
		}
		ByteBuffer out = ByteBuffer.allocate(ready * channels * Short.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN);
		for (int frame = 0; frame < ready; frame++) {
			for (int channel = 0; channel < channels; channel++) {
				float value = Math.clamp(planes[channel][frame], -1f, 1f);
				out.putShort((short) Math.round(value * 32767f));
			}
		}
		wav.write(out.array());
		return ready;
	}

	private static final int WAV_HEADER_BYTES = 44;

	/** A canonical 16-bit PCM WAV header; sizes past 4 GB are written as "until end of file". */
	static byte[] wavHeader(int channels, long frames) {
		long dataBytes = frames * channels * Short.BYTES;
		long clamped = Math.min(dataBytes, 0xFFFFFFFFL - 36);
		ByteBuffer header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
		header.putInt((int) (clamped + 36));
		header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
		header.putInt(16);
		header.putShort((short) 1);
		header.putShort((short) channels);
		header.putInt(DenoiseDsp.SAMPLE_RATE);
		header.putInt(DenoiseDsp.SAMPLE_RATE * channels * Short.BYTES);
		header.putShort((short) (channels * Short.BYTES));
		header.putShort((short) 16);
		header.put("data".getBytes(StandardCharsets.US_ASCII));
		header.putInt((int) clamped);
		return header.array();
	}
}
