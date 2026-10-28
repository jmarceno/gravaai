package dev.gravaai.media.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * MP4 encoder for the export job (plan §5.10): raw frames through ffmpeg's stdin, software
 * libx264 (+ optional aac in the same process from one filter graph), {@code +faststart}, ffmpeg
 * progress parsing and cooperative cancellation.
 *
 * <p>The frames arrive as I420 already converted on the GPU when the provider supports it (the
 * compositor does), otherwise as RGBA that swscale converts with the BT.709 matrix. The video
 * encoder itself is always {@code libx264}: no hardware encoder is used.
 */
public final class Mp4Encoder {

	private static final long ENCODE_TIMEOUT_MS = 30L * 60_000L;
	private static final int MIN_BITRATE = 1_500_000;
	private static final String AAC_BITRATE = "192k";

	private Mp4Encoder() {}

	/** Encodes the request into {@link Mp4ExportRequest#outputPath()}. */
	public static ExportResult encode(Mp4ExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(frames, "frames");
		Objects.requireNonNull(listener, "listener");
		Objects.requireNonNull(cancellation, "cancellation");
		if (frames.width() != request.width() || frames.height() != request.height()
				|| frames.frameRate() != request.frameRate()) {
			throw new IllegalArgumentException("frame provider does not match the export dimensions"
					+ " / frame rate (" + frames.width() + "x" + frames.height() + "@"
					+ frames.frameRate() + ")");
		}
		Path outputPath = request.outputPath().toAbsolutePath();
		try {
			if (outputPath.getParent() != null) {
				Files.createDirectories(outputPath.getParent());
			}
		} catch (IOException error) {
			throw new ExportException("could not create the export directory", error);
		}
		long totalFrames = request.totalFrames();
		// GPU-converted I420 when the provider can (no swscale, 1.5 bytes per pixel through the pipe).
		boolean yuvInput = frames.supportsYuv420p();
		String encoderName = yuvInput
				? "libx264 (CPU) · GPU compositing + YUV"
				: "libx264 (software, CPU)";
		List<String> arguments = buildArguments(request, yuvInput);
		StringBuilder stderr = new StringBuilder();
		AtomicLong encodedTimeUs = new AtomicLong(-1);
		RunningProcess process;
		try {
			process = ProcessRunner.of(arguments).rawStdout().rawStderr().start();
		} catch (IOException error) {
			throw new ExportException("could not start ffmpeg", error);
		}
		Thread stderrDrain = FfmpegFramePipe.drain(process.stderr(),
				line -> stderr.append(line).append('\n'));
		Thread stdoutDrain = FfmpegFramePipe.drain(process.stdout(), line -> {
			if (line.startsWith("out_time_us=")) {
				try {
					encodedTimeUs.set(Long.parseLong(line.substring("out_time_us=".length()).trim()));
				} catch (NumberFormatException ignored) {
					// ffmpeg prints -1 before the first frame.
				}
			}
		});
		ExportSpeedMeter speed = new ExportSpeedMeter(totalFrames);
		try {
			FfmpegFramePipe.writeFrames(process, frames, totalFrames, cancellation,
					(frameIndex, ignoredTotal) -> {
						ExportSpeedMeter.Reading reading = speed.frameWritten(frameIndex);
						if (reading == null) {
							return;
						}
						double percentage = 95.0 * reading.framesDone() / totalFrames;
						listener.onProgress(ExportProgress.of((int) reading.framesDone(),
								(int) totalFrames, percentage, reading.secondsRemaining())
								.withRenderFps(reading.fps())
								.withBackends(null, ExportTuning.EncodeBackend.FFMPEG)
								.withEncoderName(encoderName));
					}, yuvInput);
			listener.onProgress(ExportProgress.of((int) totalFrames, (int) totalFrames, 95, 0)
					.withPhase(ExportProgress.Phase.FINALIZING));
			int exitCode = FfmpegFramePipe.awaitExit(process, ENCODE_TIMEOUT_MS, cancellation);
			stderrDrain.join(2_000);
			stdoutDrain.join(2_000);
			if (exitCode != 0) {
				FfmpegFramePipe.abort(process, outputPath);
				throw new ExportException("ffmpeg MP4 encode failed (exit " + exitCode + "): "
						+ FfmpegFramePipe.tail(stderr, 4_000));
			}
			long bytes = Files.size(outputPath);
			listener.onProgress(ExportProgress.of((int) totalFrames, (int) totalFrames, 100, 0));
			return new ExportResult(outputPath, bytes, (int) totalFrames, request.durationSeconds());
		} catch (ExportCancelledException cancelled) {
			FfmpegFramePipe.abort(process, outputPath);
			throw cancelled;
		} catch (ExportException error) {
			FfmpegFramePipe.abort(process, outputPath);
			throw error;
		} catch (Exception error) {
			FfmpegFramePipe.abort(process, outputPath);
			throw new ExportException("MP4 export failed: " + error.getMessage(), error);
		}
	}

	/** The full ffmpeg argument list for the MP4 encode (exposed for tests/diagnostics). */
	public static List<String> buildArguments(Mp4ExportRequest request) {
		return buildArguments(request, false);
	}

	/**
	 * The ffmpeg arguments for RGBA input (converted by swscale with the BT.709 matrix) or for I420
	 * input already converted on the GPU ({@code yuv420pInput}), which goes to libx264 unchanged.
	 */
	public static List<String> buildArguments(Mp4ExportRequest request, boolean yuv420pInput) {
		List<String> arguments = new ArrayList<>();
		arguments.add(FfmpegCli.binary().toString());
		arguments.addAll(List.of("-y", "-hide_banner", "-loglevel", "error", "-stats_period", "0.5",
				"-progress", "pipe:1", "-nostats"));
		arguments.addAll(List.of("-f", "rawvideo", "-pix_fmt", yuv420pInput ? "yuv420p" : "rgba",
				"-s:v", request.width() + "x" + request.height(), "-framerate",
				String.valueOf(request.frameRate()), "-i", "pipe:0"));
		if (request.audioPlan().hasAudio()) {
			arguments.addAll(request.audioPlan().inputArguments());
			arguments.addAll(List.of("-filter_complex", request.audioPlan().filterComplex(),
					"-map", "0:v:0", "-map", "[aout]"));
		} else {
			arguments.addAll(List.of("-map", "0:v:0", "-an"));
		}
		if (!yuv420pInput) {
			// swscale converts RGB with the BT.601 matrix unless told otherwise; the stream is tagged
			// BT.709 below, so convert with BT.709 too.
			arguments.addAll(List.of("-vf", "scale=out_color_matrix=bt709:out_range=tv"));
		}
		arguments.addAll(List.of("-c:v", "libx264"));
		arguments.addAll(libx264ModeArguments(request.encodingMode()));
		arguments.addAll(List.of("-g", String.valueOf(Math.max(1,
				Math.round(request.frameRate() * 5.0f)))));
		arguments.addAll(bitrateArguments(request.bitrate()));
		arguments.addAll(List.of("-pix_fmt", "yuv420p", "-colorspace", "bt709",
				"-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"));
		if (request.audioPlan().hasAudio()) {
			arguments.addAll(List.of("-c:a", "aac", "-b:a", AAC_BITRATE));
		}
		// Bound the mux to the output timeline so a longer audio input cannot stretch the container.
		arguments.addAll(List.of("-t", String.format(java.util.Locale.ROOT, "%.6f",
				request.durationSeconds())));
		arguments.addAll(List.of("-movflags", "+faststart", request.outputPath().toAbsolutePath().toString()));
		return List.copyOf(arguments);
	}

	/** {@code getLibx264ModeArgs} of {@code electron/ipc/nativeVideoExport.ts}. */
	public static List<String> libx264ModeArguments(ExportEncodingMode encodingMode) {
		return switch (encodingMode == null ? ExportEncodingMode.BALANCED : encodingMode) {
			case FAST -> List.of("-preset", "ultrafast", "-tune", "zerolatency");
			case QUALITY -> List.of("-preset", "slow");
			case BALANCED -> List.of("-preset", "medium");
		};
	}

	/** {@code getBitrateArgs} of {@code electron/ipc/nativeVideoExport.ts}. */
	public static List<String> bitrateArguments(int bitrate) {
		long effectiveBitrate = Math.max(MIN_BITRATE, Math.round((double) bitrate));
		long maxRate = Math.max(effectiveBitrate, Math.round(effectiveBitrate * 1.2));
		long bufferSize = Math.max(maxRate * 2, effectiveBitrate * 2);
		return List.of("-b:v", String.valueOf(effectiveBitrate), "-maxrate", String.valueOf(maxRate),
				"-bufsize", String.valueOf(bufferSize));
	}
}