package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.media.audio.AudioCleanupFilters;
import dev.gravaai.media.denoise.DenoisedAudio;
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
 *
 * <p>The edit-time cleanup and the source-track mix ride the same CLI: {@link #mixCommand} builds
 * one {@code -filter_complex} graph that cleans/gains each source track, mixes them with
 * {@code amix} and optionally normalizes the programme, so the preview cannot drift from the
 * export graph.
 */
public final class FfmpegCliPcmSource implements PcmSource {

	private static final System.Logger LOG = System.getLogger("gravaai.media.playback");

	/** The preview pipeline's fixed rate: the sink, the plan and the click tracks all assume it. */
	private static final int SAMPLE_RATE = 48_000;

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

	/**
	 * The ffmpeg argument list for one preview segment of a whole source-audio mix: every track is
	 * seeked/trimmed to the same source span, cleaned, gained, then {@code amix}ed and optionally
	 * loudness-normalized, so the preview hears the same stages the export graph applies.
	 *
	 * <p>The loudness stage is the known approximation: the export measures the whole programme
	 * first and normalizes with one linear gain ({@code ExportAudioLoudness}), while a preview
	 * segment can only normalize dynamically over its own span — the cleanup and gain stages are
	 * exact, the loudness trajectory is not, and a level step can be heard at segment boundaries
	 * on dynamic material. The click/key tracks are also layered on top of the normalized mix
	 * here, while the export normalizes them with the programme.
	 *
	 * <p>The output stays {@code pcm_s16le}: the bundled FFmpeg has no {@code f32le} muxer.
	 */
	public static List<String> mixCommand(
			Path ffmpeg,
			List<PreviewAudioTrack> tracks,
			double loudnessTargetLufs,
			double sourceStartSeconds,
			double durationSeconds,
			List<String> atempoFilters) {
		if (tracks == null || tracks.isEmpty()) {
			throw new IllegalArgumentException("at least one preview track is required");
		}
		List<String> command = new ArrayList<>();
		command.add(ffmpeg.toString());
		command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
		String peakModel = rnnoiseModelPath();
		List<String> chains = new ArrayList<>();
		List<String> mixInputs = new ArrayList<>();
		int inputCount = 0;
		for (int index = 0; index < tracks.size(); index++) {
			PreviewAudioTrack track = tracks.get(index);
			// Cleanup first, on the recording as it was captured: a denoiser fed sped-up speech
			// hears something it was never trained on. The speed change follows.
			List<String> filters = new ArrayList<>();
			StringBuilder chain = new StringBuilder();
			if (track.usesDenoisedCopy()) {
				// The rendered copy replaces the ffmpeg denoisers: no latency to give back, and
				// the level is the share of the original mixed back in.
				double dry = DenoisedAudio.dryGain(track.cleanup().noiseReduction());
				int denoisedInput = inputCount++;
				addInput(command, sourceStartSeconds, durationSeconds,
						java.util.Objects.requireNonNull(track.denoisedPath()));
				if (dry > 0) {
					int originalInput = inputCount++;
					addInput(command, sourceStartSeconds, durationSeconds, track.audioPath());
					chains.add("[" + originalInput + ":a]aresample=" + SAMPLE_RATE + ",volume="
							+ number(dry) + "[dry" + index + "]");
					chains.add("[" + denoisedInput + ":a]volume=" + number(1 - dry) + "[wet"
							+ index + "]");
					chain.append("[dry").append(index).append("][wet").append(index)
							.append("]amix=inputs=2:duration=longest:normalize=0,");
				} else {
					chain.append('[').append(denoisedInput).append(":a]");
				}
				if (track.cleanup().hasHighPass()) {
					filters.add("highpass=f=" + track.cleanup().highPassHz());
				}
			} else {
				int input = inputCount++;
				// A track on the fallback denoisers gives back their latency at the head (see
				// alignedTrackFilters), so it reads that much more input to cover the span.
				addInput(command, sourceStartSeconds, durationSeconds <= 0 ? 0 : durationSeconds
						+ AudioCleanupFilters.delaySeconds(track.cleanup(), peakModel),
						track.audioPath());
				chain.append('[').append(input).append(":a]");
				filters.addAll(AudioCleanupFilters.alignedTrackFilters(track.cleanup(), peakModel));
			}
			if (atempoFilters != null) {
				filters.addAll(atempoFilters);
			}
			if (Math.abs(track.gain() - 1) > 0.0005) {
				filters.add("volume=" + number(track.gain()));
			}
			if (!filters.isEmpty()) {
				chain.append(String.join(",", filters)).append(',');
			}
			chain.append("aresample=").append(SAMPLE_RATE)
					.append(",aformat=sample_fmts=fltp:channel_layouts=stereo")
					.append("[ain").append(index).append(']');
			chains.add(chain.toString());
			mixInputs.add("[ain" + index + "]");
		}
		StringBuilder graph = new StringBuilder();
		for (String chain : chains) {
			graph.append(chain).append(';');
		}
		graph.append(mixInputs.stream().reduce("", String::concat))
				.append("amix=inputs=").append(tracks.size())
				.append(":duration=longest:normalize=0");
		String loudnorm = AudioCleanupFilters.loudnormFilter(loudnessTargetLufs);
		if (loudnorm != null) {
			graph.append(',').append(loudnorm);
		}
		graph.append("[aout]");
		command.addAll(List.of("-filter_complex", graph.toString(), "-map", "[aout]", "-vn"));
		command.addAll(List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "pipe:1"));
		return List.copyOf(command);
	}

	/** Adds one seeked (and, with a positive duration, bounded) input to the command. */
	private static void addInput(List<String> command, double startSeconds, double durationSeconds,
			String path) {
		command.addAll(List.of("-ss", formatSeconds(startSeconds)));
		if (durationSeconds > 0) {
			command.addAll(List.of("-t", formatSeconds(durationSeconds)));
		}
		command.addAll(List.of("-i", path));
	}

	/** The RNNoise model of this build, or null when the payload lacks it. */
	private static @Nullable String rnnoiseModelPath() {
		java.nio.file.Path model = AudioCleanupFilters.rnnoiseModel();
		return model == null ? null : model.toString();
	}

	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	/** Opens the mixed preview source; call from the audio pump thread. */
	public static PcmSource openMix(
			Path ffmpeg,
			List<PreviewAudioTrack> tracks,
			double loudnessTargetLufs,
			double sourceStartSeconds,
			double durationSeconds,
			List<String> atempoFilters) {
		List<String> command = mixCommand(
				ffmpeg, tracks, loudnessTargetLufs, sourceStartSeconds, durationSeconds,
				atempoFilters);
		try {
			RunningProcess process = dev.gravaai.platform.process.ProcessRunner.of(command)
					.rawStdout()
					.onStderrLine(line -> LOG.log(System.Logger.Level.DEBUG, "audio ffmpeg: " + line))
					.start();
			return new FfmpegCliPcmSource(process);
		} catch (IOException error) {
			throw new IllegalStateException("could not start the preview audio mixer", error);
		}
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
