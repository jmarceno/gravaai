package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import dev.gravaai.platform.process.RunningProcess;

/**
 * Shared plumbing for the ffmpeg-CLI encoders (rawvideo frames through stdin, progress lines from
 * stdout, cooperative cancellation with temp-file cleanup; plan §5.10).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class FfmpegFramePipe {

	/** Callback after a frame was handed to ffmpeg. */
	@FunctionalInterface
	interface FrameSink {
		void onFrameWritten(long frameIndex, long totalFrames);
	}

	private FfmpegFramePipe() {}

	/** Reads UTF-8 lines from a process stream on a daemon thread. */
	static Thread drain(InputStream stream, Consumer<String> onLine) {
		Thread thread = new Thread(() -> {
			try (var reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					onLine.accept(line);
				}
			} catch (IOException ignored) {
				// The process closed the stream; nothing to do.
			}
		}, "export-ffmpeg-drain");
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** Writes every frame of the timeline as RGBA into the process stdin and closes it. */
	static void writeFrames(RunningProcess process, ExportFrameProvider frames, long totalFrames,
			ExportCancellation cancellation, FrameSink sink) throws Exception {
		writeFrames(process, frames, totalFrames, cancellation, sink, false);
	}

	/**
	 * Writes every frame of the timeline into the process stdin and closes it: RGBA, or I420 from
	 * {@link ExportFrameProvider#renderFrameYuv420p} when {@code yuv420p}.
	 *
	 * <p>Rendering and piping overlap: this thread renders frame N+1 while a writer thread hands
	 * frame N to ffmpeg (which encodes in its own process), with {@link #IN_FLIGHT} buffers
	 * between them. {@code sink} is called on the writer thread, in frame order.</p>
	 */
	static void writeFrames(RunningProcess process, ExportFrameProvider frames, long totalFrames,
			ExportCancellation cancellation, FrameSink sink, boolean yuv420p) throws Exception {
		OutputStream stdin = process.stdin();
		long pixels = (long) frames.width() * frames.height();
		int frameBytes = Math.toIntExact(yuv420p ? pixels * 3 / 2 : pixels * 4);
		java.util.concurrent.BlockingQueue<byte[]> free =
				new java.util.concurrent.ArrayBlockingQueue<>(IN_FLIGHT);
		for (int i = 0; i < IN_FLIGHT; i++) {
			free.add(new byte[frameBytes]);
		}
		record Filled(long frameIndex, @Nullable byte[] bytes) {}
		Filled end = new Filled(-1, null);
		java.util.concurrent.BlockingQueue<Filled> filled =
				new java.util.concurrent.ArrayBlockingQueue<>(IN_FLIGHT + 1);
		java.util.concurrent.atomic.AtomicReference<Throwable> writeFailure =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread writer = Thread.ofPlatform().name("export-ffmpeg-writer").daemon().start(() -> {
			try {
				while (true) {
					Filled next = filled.take();
					if (next == end) {
						break;
					}
					stdin.write(next.bytes(), 0, frameBytes);
					free.add(next.bytes());
					sink.onFrameWritten(next.frameIndex(), totalFrames);
				}
				stdin.flush();
				stdin.close();
			} catch (Throwable error) {
				writeFailure.set(error);
				// Unblock the renderer waiting for a free buffer.
				free.offer(new byte[0]);
			}
		});
		try {
			for (long frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
				if (cancellation.isCancelled()) {
					throw new ExportCancelledException();
				}
				byte[] bytes = free.take();
				rethrowWriteFailure(writeFailure);
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				double timeSeconds = frameIndex / (double) frames.frameRate();
				if (yuv420p) {
					frames.renderFrameYuv420p(frameIndex, timeSeconds, buffer);
				} else {
					frames.renderFrame(frameIndex, timeSeconds, buffer);
				}
				filled.put(new Filled(frameIndex, bytes));
			}
			filled.put(end);
			writer.join();
			rethrowWriteFailure(writeFailure);
		} finally {
			if (writer.isAlive()) {
				writer.interrupt();
			}
		}
	}

	/** Frames rendered ahead of ffmpeg's stdin (memory: 3 x 12 MB at 1080p RGBA). */
	static final int IN_FLIGHT = 3;

	private static void rethrowWriteFailure(
			java.util.concurrent.atomic.AtomicReference<Throwable> failure) throws Exception {
		Throwable error = failure.get();
		if (error == null) {
			return;
		}
		if (error instanceof Exception exception) {
			throw exception;
		}
		throw new ExportException("writing frames to ffmpeg failed", error);
	}

	/** Waits for the process, killing it when the export is cancelled or the timeout expires. */
	static int awaitExit(RunningProcess process, long timeoutMillis, ExportCancellation cancellation)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		Process child = process.process();
		while (true) {
			if (cancellation.isCancelled()) {
				process.cancel();
				throw new ExportCancelledException();
			}
			if (child.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				return child.exitValue();
			}
			if (System.nanoTime() > deadline) {
				process.cancel();
				throw new ExportException("ffmpeg timed out after " + timeoutMillis + " ms");
			}
		}
	}

	/** Kills the process and removes an output/temp file, ignoring secondary failures. */
	static void abort(RunningProcess process, java.nio.file.Path... files) {
		if (process != null) {
			try {
				process.cancel();
			} catch (RuntimeException ignored) {
				// Already gone.
			}
		}
		for (java.nio.file.Path file : files) {
			if (file == null) {
				continue;
			}
			try {
				java.nio.file.Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// Best effort.
			}
		}
	}

	/** Last {@code maxChars} of the collected stderr for error messages. */
	static String tail(StringBuilder stderr, int maxChars) {
		String text = stderr.toString().trim();
		return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
	}
}
