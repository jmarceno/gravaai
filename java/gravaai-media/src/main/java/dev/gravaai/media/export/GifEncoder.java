package dev.gravaai.media.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * GIF encoder for the export job (plan §5.10): two-pass palettegen/paletteuse through the bundled
 * ffmpeg CLI. The provider renders the clip twice (deterministic providers required); both passes
 * stream raw RGBA through stdin, so no intermediate frame storage is needed.
 */
public final class GifEncoder {

	private static final long ENCODE_TIMEOUT_MS = 30L * 60_000L;

	private GifEncoder() {}

	/** Encodes the request into {@link GifExportRequest#outputPath()}. */
	public static ExportResult encode(GifExportRequest request, ExportFrameProvider frames,
			ExportProgressListener listener, ExportCancellation cancellation) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(frames, "frames");
		Objects.requireNonNull(listener, "listener");
		Objects.requireNonNull(cancellation, "cancellation");
		if (frames.width() != request.width() || frames.height() != request.height()
				|| frames.frameRate() != request.frameRate()) {
			throw new IllegalArgumentException("frame provider does not match the GIF dimensions"
					+ " / frame rate");
		}
		Path outputPath = request.outputPath().toAbsolutePath();
		Path palettePath = outputPath.resolveSibling(outputPath.getFileName() + ".palette.png");
		Path paletteDirectory = palettePath.getParent();
		try {
			if (paletteDirectory != null) {
				Files.createDirectories(paletteDirectory);
			}
			Files.deleteIfExists(palettePath);
			Files.deleteIfExists(outputPath);
		} catch (IOException error) {
			throw new ExportException("could not prepare the GIF export directory", error);
		}
		long totalFrames = request.totalFrames();
		try {
			encodePalettePass(request, frames, palettePath, totalFrames, listener, cancellation);
			encodeOutputPass(request, frames, palettePath, totalFrames, listener, cancellation);
			long bytes;
			try {
				bytes = Files.size(outputPath);
			} catch (IOException error) {
				throw new ExportException("could not read the GIF export size", error);
			}
			listener.onProgress(ExportProgress.of((int) totalFrames, (int) totalFrames, 100, 0));
			return new ExportResult(outputPath, bytes, (int) totalFrames, request.durationSeconds());
		} finally {
			try {
				Files.deleteIfExists(palettePath);
			} catch (IOException ignored) {
				// Best effort.
			}
		}
	}

	private static void encodePalettePass(GifExportRequest request, ExportFrameProvider frames,
			Path palettePath, long totalFrames, ExportProgressListener listener,
			ExportCancellation cancellation) {
		resetProvider(frames);
		List<String> arguments = rawvideoInputArguments(request);
		arguments.addAll(List.of("-vf", "palettegen=stats_mode=full", "-frames:v", "1",
				palettePath.toString()));
		runPass(arguments, request, frames, totalFrames, listener, cancellation, 0, 50.0,
				"GIF palette pass");
	}

	private static void encodeOutputPass(GifExportRequest request, ExportFrameProvider frames,
			Path palettePath, long totalFrames, ExportProgressListener listener,
			ExportCancellation cancellation) {
		resetProvider(frames);
		List<String> arguments = rawvideoInputArguments(request);
		arguments.addAll(List.of("-i", palettePath.toString(), "-lavfi",
				"[0:v][1:v]paletteuse=dither=floyd_steinberg", "-loop",
				String.valueOf(request.loop() ? 0 : -1), request.outputPath().toAbsolutePath().toString()));
		runPass(arguments, request, frames, totalFrames, listener, cancellation, 50.0, 100.0,
				"GIF output pass");
	}

	private static void resetProvider(ExportFrameProvider frames) {
		try {
			frames.reset();
		} catch (Exception error) {
			throw new ExportException("could not reset the frame provider for the GIF pass", error);
		}
	}

	private static List<String> rawvideoInputArguments(GifExportRequest request) {
		List<String> arguments = new ArrayList<>();
		arguments.addAll(List.of("-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo",
				"-pix_fmt", "rgba", "-s:v", request.width() + "x" + request.height(), "-framerate",
				String.valueOf(request.frameRate()), "-i", "pipe:0"));
		return arguments;
	}

	private static void runPass(List<String> arguments, GifExportRequest request,
			ExportFrameProvider frames, long totalFrames, ExportProgressListener listener,
			ExportCancellation cancellation, double fromPercentage, double toPercentage,
			String label) {
		StringBuilder stderr = new StringBuilder();
		List<String> command = new ArrayList<>();
		command.add(FfmpegCli.binary().toString());
		command.addAll(arguments);
		RunningProcess process;
		try {
			process = ProcessRunner.of(command).rawStdout().rawStderr().start();
		} catch (IOException error) {
			throw new ExportException("could not start ffmpeg (" + label + ")", error);
		}
		FfmpegFramePipe.drain(process.stderr(), line -> stderr.append(line).append('\n'));
		FfmpegFramePipe.drain(process.stdout(), line -> {});
		ExportSpeedMeter speed = new ExportSpeedMeter(totalFrames);
		try {
			FfmpegFramePipe.writeFrames(process, frames, totalFrames, cancellation,
					(frameIndex, ignoredTotal) -> {
						ExportSpeedMeter.Reading reading = speed.frameWritten(frameIndex);
						if (reading == null) {
							return;
						}
					double percentage = fromPercentage
							+ (toPercentage - fromPercentage) * reading.framesDone() / totalFrames;
					listener.onProgress(ExportProgress.of((int) reading.framesDone(),
							(int) totalFrames, percentage, reading.secondsRemaining())
							.withRenderFps(reading.fps())
							.withBackends(null, ExportTuning.EncodeBackend.FFMPEG)
							.withEncoderName("GIF (software, CPU)"));
					});
			int exitCode = FfmpegFramePipe.awaitExit(process, ENCODE_TIMEOUT_MS, cancellation);
			if (exitCode != 0) {
				throw new ExportException(label + " failed (exit " + exitCode + "): "
						+ FfmpegFramePipe.tail(stderr, 4_000));
			}
		} catch (ExportCancelledException cancelled) {
			FfmpegFramePipe.abort(process);
			throw cancelled;
		} catch (ExportException error) {
			FfmpegFramePipe.abort(process);
			throw error;
		} catch (Exception error) {
			FfmpegFramePipe.abort(process);
			throw new ExportException(label + " failed: " + error.getMessage(), error);
		}
	}
}