package dev.gravaai.media.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * MP4 encoder for the export job (plan §5.10): raw frames through ffmpeg's stdin, an H.264 encoder
 * (hardware when the bundled ffmpeg and this machine can run one, libx264 otherwise), {@code +faststart},
 * ffmpeg progress parsing and cooperative cancellation.
 *
 * <p>The frames arrive as I420 already converted on the GPU when the provider supports it (the
 * compositor does), otherwise as RGBA that swscale converts with the BT.709 matrix. The encoder is
 * chosen by {@link VideoEncoderProbe} once per job; a GPU encoder that exits with an error is
 * retried on libx264 before the export is reported as failed.
 */
public final class Mp4Encoder {

	private static final long ENCODE_TIMEOUT_MS = 30L * 60_000L;
	private static final int MIN_BITRATE = 1_500_000;
	private static final String AAC_BITRATE = "192k";

	/** The CRF the export uses for the constant-quality paths (recording uses its own levels). */
	private static final int EXPORT_CRF = 20;

	private Mp4Encoder() {}

	/** Encodes the request into {@link Mp4ExportRequest#outputPath()} with the bundled probe. */
	public static ExportResult encode(Mp4ExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation) {
		return encode(request, frames, listener, cancellation, VideoEncoderProbe.bundled());
	}

	/** Encodes the request, choosing the encoder through {@code probe}. */
	public static ExportResult encode(Mp4ExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation,
			VideoEncoderProbe probe) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(frames, "frames");
		Objects.requireNonNull(listener, "listener");
		Objects.requireNonNull(cancellation, "cancellation");
		Objects.requireNonNull(probe, "probe");
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
		// GPU-converted I420 when the provider can (no swscale, 1.5 bytes per pixel through the pipe).
		boolean yuvInput = frames.supportsYuv420p();
		// Exports below the measured break-even size stay on libx264: on the weakest supported GPU
		// the hardware encoder is slower there (see VideoEncoderProbe#EXPORT_HARDWARE_MIN_PIXELS).
		VideoEncoderSpec spec = probe.selectForExport(request.width(), request.height());
		RenderFailure renderFailure = new RenderFailure();
		ExportFrameProvider guarded = guardRenderFailures(frames, renderFailure);
		try {
			return encodeWith(request, guarded, listener, cancellation, spec, yuvInput, outputPath);
		} catch (ExportCancelledException cancelled) {
			throw cancelled;
		} catch (RuntimeException failure) {
			// A failure of the frame provider itself is not the encoder's fault, and a cancelled
			// export is not either: only a hardware encode that actually failed is retried.
			if (!spec.hardware() || renderFailure.happened() || cancellation.isCancelled()) {
				throw failure;
			}
			// A hardware session that fails on this machine must never cost the user the export: the
			// same job runs again on libx264, and the label then states what actually ran.
			try {
				guarded.reset();
			} catch (Exception resetError) {
				throw new ExportException("could not restart the export on libx264", resetError);
			}
			listener.onProgress(ExportProgress.of(0, (int) request.totalFrames(), 0, 0)
					.withPhase(ExportProgress.Phase.PREPARING)
					.withEncoderName(VideoEncoderSpec.SOFTWARE.label()));
			return encodeWith(request, guarded, listener, cancellation, VideoEncoderSpec.SOFTWARE,
					yuvInput, outputPath);
		}
	}

	/** Records exceptions thrown by the frame provider so the fallback can tell them from encoder failures. */
	private static ExportFrameProvider guardRenderFailures(ExportFrameProvider frames,
			RenderFailure failure) {
		return new ExportFrameProvider() {
			@Override
			public int width() {
				return frames.width();
			}

			@Override
			public int height() {
				return frames.height();
			}

			@Override
			public int frameRate() {
				return frames.frameRate();
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, ByteBuffer rgba)
					throws Exception {
				try {
					frames.renderFrame(frameIndex, timeSeconds, rgba);
				} catch (Exception | Error error) {
					failure.record(error);
					throw error;
				}
			}

			@Override
			public boolean supportsYuv420p() {
				return frames.supportsYuv420p();
			}

			@Override
			public void renderFrameYuv420p(long frameIndex, double timeSeconds, ByteBuffer yuv)
					throws Exception {
				try {
					frames.renderFrameYuv420p(frameIndex, timeSeconds, yuv);
				} catch (Exception | Error error) {
					failure.record(error);
					throw error;
				}
			}

			@Override
			public void reset() throws Exception {
				frames.reset();
			}

			@Override
			public void close() {
				frames.close();
			}
		};
	}

	/** One-shot record of the first exception the frame provider threw (rendering is serial). */
	private static final class RenderFailure {

		private @Nullable Throwable first;

		void record(Throwable error) {
			if (first == null) {
				first = error;
			}
		}

		boolean happened() {
			return first != null;
		}
	}

	/** One encode attempt with a fixed encoder. */
	private static ExportResult encodeWith(Mp4ExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation, VideoEncoderSpec spec,
			boolean yuvInput, Path outputPath) {
		long totalFrames = request.totalFrames();
		String encoderName = VideoEncoderArguments.label(spec, yuvInput);
		// ffmpeg reads the chapters from an ffmetadata input document; the bundled build has the
		// demuxer, so the MP4 stays a single encode and faststart is applied once.
		Path metadataFile;
		try {
			metadataFile = ChapterSidecars.writeMetadataFile(outputPath, request.chapters(),
					Math.round(request.durationSeconds() * 1000));
		} catch (IOException error) {
			throw new ExportException("could not write the export chapter metadata", error);
		}
		try {
			return runEncode(request, frames, listener, cancellation, spec, yuvInput, outputPath,
					encoderName, metadataFile);
		} finally {
			if (metadataFile != null) {
				try {
					Files.deleteIfExists(metadataFile);
				} catch (IOException ignored) {
					// Best effort; the export itself already succeeded or failed on its own.
				}
			}
		}
	}

	private static ExportResult runEncode(Mp4ExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation, VideoEncoderSpec spec,
			boolean yuvInput, Path outputPath, String encoderName,
			@Nullable Path metadataFile) {
		long totalFrames = request.totalFrames();
		List<String> arguments = buildArguments(request, yuvInput, spec, metadataFile);
		StringBuilder stderr = new StringBuilder();
		AtomicLong encodedTimeUs = new AtomicLong(-1);
		RunningProcess process;
		try {
			process = ProcessRunner.of(arguments).rawStdout().rawStderr().start();
		} catch (IOException error) {
			throw new ExportException("could not start ffmpeg (" + spec.codec() + ")", error);
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
				throw new ExportException("ffmpeg MP4 encode failed on " + spec.codec()
						+ " (exit " + exitCode + "): " + FfmpegFramePipe.tail(stderr, 4_000));
			}
			long bytes = Files.size(outputPath);
			listener.onProgress(ExportProgress.of((int) totalFrames, (int) totalFrames, 100, 0)
					.withEncoderName(encoderName));
			return new ExportResult(outputPath, bytes, (int) totalFrames, request.durationSeconds());
		} catch (ExportCancelledException cancelled) {
			FfmpegFramePipe.abort(process, outputPath);
			throw cancelled;
		} catch (ExportException error) {
			FfmpegFramePipe.abort(process, outputPath);
			throw error;
		} catch (Exception error) {
			FfmpegFramePipe.abort(process, outputPath);
			throw new ExportException("MP4 export failed on " + spec.codec() + ": "
					+ error.getMessage(), error);
		}
	}

	/** The full ffmpeg argument list for the MP4 encode (exposed for tests/diagnostics). */
	public static List<String> buildArguments(Mp4ExportRequest request) {
		return buildArguments(request, false, VideoEncoderSpec.SOFTWARE);
	}

	/** The ffmpeg arguments for RGBA input, for tests/diagnostics. */
	public static List<String> buildArguments(Mp4ExportRequest request, boolean yuv420pInput) {
		return buildArguments(request, yuv420pInput, VideoEncoderSpec.SOFTWARE);
	}

	/**
	 * The ffmpeg arguments for RGBA input (converted by swscale with the BT.709 matrix) or for I420
	 * input already converted on the GPU ({@code yuv420pInput}), encoded with {@code spec}.
	 */
	public static List<String> buildArguments(Mp4ExportRequest request, boolean yuv420pInput,
			VideoEncoderSpec spec) {
		return buildArguments(request, yuv420pInput, spec, null);
	}

	/**
	 * The ffmpeg arguments, embedding the chapters of {@code metadataFile} when it is not null.
	 * The document is an extra {@code ffmetadata} input; {@code -map_chapters 1} attaches its
	 * chapters to the output, and {@code -map 0} keeps the rest of the streams out of it.
	 */
	public static List<String> buildArguments(Mp4ExportRequest request, boolean yuv420pInput,
			VideoEncoderSpec spec, @Nullable Path metadataFile) {
		List<String> arguments = new ArrayList<>();
		arguments.add(FfmpegCli.binary().toString());
		arguments.addAll(List.of("-y", "-hide_banner", "-loglevel", "error", "-stats_period", "0.5",
				"-progress", "pipe:1", "-nostats"));
		arguments.addAll(spec.deviceArguments());
		arguments.addAll(List.of("-f", "rawvideo", "-pix_fmt", yuv420pInput ? "yuv420p" : "rgba",
				"-s:v", request.width() + "x" + request.height(), "-framerate",
				String.valueOf(request.frameRate()), "-i", "pipe:0"));
		if (request.audioPlan().hasAudio()) {
			arguments.addAll(request.audioPlan().inputArguments());
		}
		boolean chapters = metadataFile != null;
		if (chapters) {
			arguments.addAll(List.of("-f", "ffmetadata", "-i",
					java.util.Objects.requireNonNull(metadataFile).toString()));
		}
		if (request.audioPlan().hasAudio()) {
			arguments.addAll(List.of("-filter_complex", request.audioPlan().filterComplex(),
					"-map", "0:v:0", "-map", "[aout]"));
		} else {
			arguments.addAll(List.of("-map", "0:v:0", "-an"));
		}
		if (chapters) {
			// The metadata input is the last one: one video input + one audio input per file.
			int metadataIndex = request.audioPlan().inputs().size() + 1;
			arguments.addAll(List.of("-map_chapters", String.valueOf(metadataIndex)));
		}
		List<String> filters = new ArrayList<>();
		if (!yuv420pInput) {
			// swscale converts RGB with the BT.601 matrix unless told otherwise; the stream is tagged
			// BT.709 below, so convert with BT.709 too.
			filters.add("scale=out_color_matrix=bt709:out_range=tv");
		}
		List<String> withUpload = VideoEncoderArguments.appendUpload(filters, spec);
		if (!withUpload.isEmpty()) {
			arguments.addAll(List.of("-vf", String.join(",", withUpload)));
		}
		arguments.addAll(List.of("-c:v", spec.codec()));
		arguments.addAll(VideoEncoderArguments.rateControl(spec, request.encodingMode(),
				request.bitrate(), EXPORT_CRF, false));
		arguments.addAll(List.of("-g", String.valueOf(Math.max(1,
				Math.round(request.frameRate() * 5.0f)))));
		if (!VideoEncoderArguments.needsUpload(spec)) {
			arguments.addAll(List.of("-pix_fmt", "yuv420p"));
		}
		arguments.addAll(List.of("-colorspace", "bt709",
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