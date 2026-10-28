package dev.gravaai.media.playback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.platform.process.RunningProcess;

/**
 * A {@link PcmSource} backed by the bundled ffmpeg CLI: {@code -ss/-t} around one
 * {@link PlaybackAudioPlan.AudioSegment} plus its {@code atempo} chain, emitting signed 16-bit
 * little-endian 48 kHz stereo on stdout (converted to float here).
 *
 * <p>The bundled FFmpeg is built with {@code --disable-everything} and only enables the
 * {@code pcm_s16le} PCM muxer, not {@code f32le}: asking for {@code -f f32le} fails with
 * "Requested output format 'f32le' is not known", the decoder writes nothing and the preview plays
 * silence (the packaged-app "no audio" bug). 16-bit is the preview path only; the export graph
 * keeps float internally.
 *
 * <p>The pipeline is software-only (libavfilter {@code atempo}); the CLI is used so the preview
 * audio can land before the in-process AVFilterGraph wrapper (plan §5.7 remainder) exists. The
 * command builder is pure and tested; the live decode is covered by a fixture-backed test.
 */
public final class FfmpegCliPcmSource implements PcmSource {

	private static final System.Logger LOG = System.getLogger("gravaai.media.playback");

	private final InputStream stdout;
	private final RunningProcess process;
	/** Reusable stdout buffer, grown to whole frames (1920 stereo frames = 7680 bytes). */
	private byte[] bytes = new byte[8192];
	private boolean closed;

	private FfmpegCliPcmSource(RunningProcess process) {
		this.process = process;
		this.stdout = process.stdout();
	}

	/** The ffmpeg argument list for one segment ({@code -af} omitted at normal speed). */
	public static List<String> pcmCommand(
			Path ffmpeg,
			String mediaPath,
			double sourceStartSeconds,
			double durationSeconds,
			List<String> atempoFilters) {
		List<String> command = new ArrayList<>();
		command.add(ffmpeg.toString());
		command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
		command.addAll(List.of("-ss", formatSeconds(sourceStartSeconds)));
		if (durationSeconds > 0) {
			command.addAll(List.of("-t", formatSeconds(durationSeconds)));
		}
		command.addAll(List.of("-i", mediaPath, "-vn", "-f", "s16le", "-ar", "48000", "-ac", "2"));
		if (atempoFilters != null && !atempoFilters.isEmpty()) {
			command.add("-af");
			command.add(String.join(",", atempoFilters));
		}
		command.add("pipe:1");
		return List.copyOf(command);
	}

	private static String formatSeconds(double seconds) {
		double safe = Double.isFinite(seconds) && seconds > 0 ? seconds : 0;
		return String.format(java.util.Locale.ROOT, "%.6f", safe);
	}

	/** Opens the CLI and starts decoding; call from the audio pump thread. */
	public static PcmSource open(
			Path ffmpeg,
			String mediaPath,
			double sourceStartSeconds,
			double durationSeconds,
			List<String> atempoFilters) {
		List<String> command = pcmCommand(
				ffmpeg, mediaPath, sourceStartSeconds, durationSeconds, atempoFilters);
		try {
			RunningProcess process = dev.gravaai.platform.process.ProcessRunner.of(command)
					.rawStdout()
					.onStderrLine(line -> LOG.log(System.Logger.Level.DEBUG, "audio ffmpeg: " + line))
					.start();
			return new FfmpegCliPcmSource(process);
		} catch (IOException error) {
			throw new IllegalStateException("could not start the preview audio decoder", error);
		}
	}

	@Override
	public int read(float[] interleavedStereo, int frameCount) {
		if (closed) {
			return -1;
		}
		int channels = 2;
		int requiredBytes = frameCount * channels * Short.BYTES;
		if (bytes.length < requiredBytes) {
			bytes = new byte[requiredBytes];
		}
		int filled = 0;
		try {
			while (filled < requiredBytes) {
				int read = stdout.read(bytes, filled, requiredBytes - filled);
				if (read == -1) {
					if (filled == 0) {
						return -1;
					}
					break;
				}
				filled += read;
			}
		} catch (IOException error) {
			throw new IllegalStateException("preview audio decode failed", error);
		}
		// Little-endian int16 -> float [-1, 1); the producer works on interleaved stereo floats.
		int samples = filled / Short.BYTES;
		for (int index = 0; index < samples; index++) {
			int offset = index * Short.BYTES;
			short value = (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
			interleavedStereo[index] = value / 32768.0f;
		}
		return samples / channels;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			stdout.close();
		} catch (IOException ignored) {
			// Process already gone.
		}
		// cancel() waits up to the kill grace for ffmpeg to exit; pause/seek callers (the FX
		// thread) must never wait for that, so the shutdown runs in the background.
		Thread.ofVirtual().name("gravaai-audio-decoder-stop").start(process::cancel);
	}
}
