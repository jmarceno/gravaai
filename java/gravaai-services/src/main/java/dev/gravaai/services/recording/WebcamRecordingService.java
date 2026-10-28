package dev.gravaai.services.recording;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import dev.gravaai.media.capture.WebcamCapture;
import dev.gravaai.media.capture.WebcamDevice;
import dev.gravaai.media.capture.WebcamDevices;
import dev.gravaai.media.capture.WebcamFormat;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * Webcam recording next to a screen recording (plan §5.6.6, P1.9).
 *
 * <p>Frames come from the media layer's in-process libavdevice v4l2 capture
 * ({@link WebcamCapture}) and feed (a) the HUD/floating preview through the injected
 * {@link FrameListener} and (b) a software libx264 encoder writing
 * {@code recording-<ts>-webcam.mkv}. The first stored frame defines {@code timeOffsetMs}, recorded in
 * the {@code .gravaai-session.json} link so the editor aligns the track to the recording's
 * {@code startedAtMs}.
 *
 * <p>Substitution until media-native lands {@code WebcamRecorder}: the encoder is the bundled
 * {@code ffmpeg} CLI (software libx264) behind {@link WebcamVideoEncoder}; swapping in the
 * in-process encoder means providing another {@link EncoderFactory} (reported to the Lead).
 */
public final class WebcamRecordingService {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** Constraints from {@code src/hooks/useScreenRecorder.ts}. */
	public static final int WEBCAM_WIDTH = 1280;
	public static final int WEBCAM_HEIGHT = 720;
	public static final int WEBCAM_FRAME_RATE = 30;
	public static final String WEBCAM_SUFFIX = "-webcam";
	public static final String WEBCAM_EXTENSION = ".mkv";

	/** Receives every captured frame (HUD preview) on the capture thread. */
	public interface FrameListener {
		void onFrame(ByteBuffer rgbaTopFirst, WebcamFormat format, long wallClockMs);
	}

	/** In-process libx264 MKV encoder; replaceable once media-native ships {@code WebcamRecorder}. */
	public interface WebcamVideoEncoder extends AutoCloseable {
		void writeFrame(ByteBuffer rgbaTopFirst) throws IOException;

		/** Ends the stream and finalises the file; throws when the encoder failed. */
		void finish() throws IOException;
	}

	/** Creates an encoder for one output file and the negotiated format. */
	public interface EncoderFactory {
		WebcamVideoEncoder create(Path outputPath, WebcamFormat format);
	}

	public record StartResult(boolean success, String message, WebcamRecording recording) {

		static StartResult failed(String message) {
			return new StartResult(false, message, null);
		}
	}

	/**
	 * The running webcam recording. The camera is opened and warmed up before the screen
	 * recording starts; {@link #begin} then anchors a constant 30 fps track to the screen video's
	 * first frame, {@link #pause}/{@link #resume} follow the screen recorder, and
	 * {@link #finish} cuts or pads it to exactly the screen video's duration. The webcam track
	 * therefore always starts at offset 0 and lasts as long as the video.
	 */
	public final class WebcamRecording implements AutoCloseable {

		private final Path path;
		private final WebcamFormat format;
		private final WebcamCapture capture;
		private final WebcamVideoEncoder encoder;
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final Object state = new Object();
		private final ByteBuffer buffer;
		private Thread thread;
		/** Wall clock of the screen video's first frame; -1 until {@link #begin}. */
		private long anchorMs = -1;
		private long pausedAtMs = -1;
		private long pausedTotalMs;
		/** Wall clock of the screen recording's end; frames after it are not stored. */
		private long endMs = -1;
		private long framesWritten;
		private boolean haveFrame;
		private final java.util.concurrent.CountDownLatch firstFrame =
				new java.util.concurrent.CountDownLatch(1);

		WebcamRecording(Path path, WebcamFormat format, WebcamCapture capture,
				WebcamVideoEncoder encoder) {
			this.path = path;
			this.format = format;
			this.capture = capture;
			this.encoder = encoder;
			this.buffer = ByteBuffer.allocateDirect(format.width() * format.height() * 4);
		}

		public Path path() {
			return path;
		}

		/** Always 0: the track is anchored to the screen video's first frame. */
		public long timeOffsetMs() {
			return 0;
		}

		public WebcamFormat format() {
			return format;
		}

		/**
		 * Waits until the camera delivers its first frame (it can take hundreds of milliseconds
		 * after open), so the screen recording starts with the webcam already live.
		 */
		public boolean awaitFirstFrame(long timeoutMs) {
			try {
				return firstFrame.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/** Anchors the track to the screen video's first frame (wall clock). */
		public void begin(long screenStartedAtMs) {
			synchronized (state) {
				if (anchorMs < 0) {
					anchorMs = screenStartedAtMs;
				}
			}
		}

		/** The screen recording paused: no webcam frames are stored until {@link #resume}. */
		public void pause(long atMs) {
			synchronized (state) {
				if (pausedAtMs < 0) {
					pausedAtMs = atMs;
				}
			}
		}

		/** The screen recording resumed: the paused span is cut from the webcam track too. */
		public void resume(long atMs) {
			synchronized (state) {
				if (pausedAtMs >= 0) {
					pausedTotalMs += Math.max(0, atMs - pausedAtMs);
					pausedAtMs = -1;
				}
			}
		}

		/** The screen recording stopped at {@code atMs}; later camera frames are not stored. */
		public void endAt(long atMs) {
			synchronized (state) {
				if (endMs < 0) {
					endMs = atMs;
				}
			}
		}

		void start(FrameListener preview) {
			thread = Thread.ofPlatform().daemon().name("gravaai-webcam").start(() -> captureLoop(preview));
		}

		private void captureLoop(FrameListener preview) {
			try {
				while (running.get()) {
					buffer.clear();
					if (!capture.readFrame(buffer)) {
						break;
					}
					long wallClockMs = System.currentTimeMillis();
					if (preview != null) {
						try {
							preview.onFrame(buffer.asReadOnlyBuffer(), format, wallClockMs);
						} catch (RuntimeException error) {
							LOG.log(System.Logger.Level.WARNING, "Webcam preview frame failed: " + error);
						}
					}
					firstFrame.countDown();
					synchronized (state) {
						haveFrame = true;
						long due = dueFramesLocked(wallClockMs);
						// The encoder reads a constant 30 fps stream, but cameras slow down in low
						// light (15 fps is common): every slot on the screen's clock gets the newest
						// frame, repeating it when the camera is late and skipping when it is early.
						while (framesWritten < due) {
							writeLocked();
						}
					}
				}
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "Webcam recording ended: " + error);
			} finally {
				running.set(false);
				capture.close();
			}
		}

		/** Track frames that are due at {@code wallClockMs} on the screen recording's clock. */
		private long dueFramesLocked(long wallClockMs) {
			if (anchorMs < 0 || pausedAtMs >= 0) {
				return framesWritten;
			}
			long clock = endMs >= 0 ? Math.min(wallClockMs, endMs) : wallClockMs;
			long elapsed = clock - anchorMs - pausedTotalMs;
			if (elapsed < 0) {
				return framesWritten;
			}
			return elapsed * WEBCAM_FRAME_RATE / 1000 + 1;
		}

		private void writeLocked() throws IOException {
			// readFrame writes with absolute puts and leaves the position at 0: flip() here made
			// every frame zero bytes long.
			buffer.rewind();
			encoder.writeFrame(buffer);
			framesWritten++;
		}

		/** Stops capture and finalises the MKV as recorded; returns an error message or null. */
		public String stop() {
			return finish(-1);
		}

		/**
		 * Stops capture and finalises the MKV with exactly the frames of a screen video of
		 * {@code screenDurationMs} (the last camera frame repeats into a short tail; extra frames are
		 * cut); a negative duration keeps what was recorded. Returns an error message or null.
		 */
		public String finish(long screenDurationMs) {
			// The capture thread owns the device: it sees the flag after the current frame (one
			// frame period), closes the capture and exits. Closing it here instead freed the
			// FFmpeg contexts under a running av_read_frame.
			running.set(false);
			if (thread != null) {
				try {
					thread.join(2000);
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
				if (thread.isAlive()) {
					LOG.log(System.Logger.Level.WARNING, "Webcam capture thread did not stop within 2 s");
				}
			}
			long target = screenDurationMs < 0
					? -1
					: Math.max(1, Math.round(screenDurationMs * WEBCAM_FRAME_RATE / 1000.0));
			long written;
			try {
				synchronized (state) {
					if (target > 0 && haveFrame && (thread == null || !thread.isAlive())) {
						while (framesWritten < target) {
							writeLocked();
						}
					}
					written = framesWritten;
				}
				encoder.finish();
			} catch (IOException error) {
				return String.valueOf(error);
			}
			if (target > 0 && written > target) {
				String trimError = trimToFrames(path, target);
				if (trimError != null) {
					return trimError;
				}
			}
			LOG.log(System.Logger.Level.INFO, "webcam track: " + Math.max(written, target)
					+ " frames written, " + target + " for the screen video ("
					+ screenDurationMs + " ms)");
			return null;
		}

		@Override
		public void close() {
			stop();
		}
	}

	/**
	 * Cuts the webcam MKV to its first {@code frames} frames (a stream copy, no re-encode): the
	 * camera stores a few frames past the screen video's last one while the screen recorder
	 * shuts down. Returns an error message or null.
	 */
	private String trimToFrames(Path file, long frames) {
		if (processes == null || environment == null) {
			return null;
		}
		Path trimmed = file.resolveSibling(file.getFileName() + ".trim.mkv");
		List<String> command = List.of(environment.ffmpeg().toString(), "-hide_banner",
				"-loglevel", "error", "-y", "-i", file.toString(), "-map", "0:v:0", "-c", "copy",
				"-frames:v", String.valueOf(frames), trimmed.toString());
		try {
			SpawnedProcess process = processes.spawn(command, new SpawnOptions(false, false, false));
			int code = process.waitFor();
			if (code != 0 || !Files.isRegularFile(trimmed)) {
				Files.deleteIfExists(trimmed);
				return "could not trim the webcam track (ffmpeg exit " + code + ")";
			}
			Files.move(trimmed, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return null;
		} catch (IOException error) {
			return "could not trim the webcam track: " + error;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return "interrupted while trimming the webcam track";
		}
	}

	private final Supplier<Path> recordingsDir;
	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final EncoderFactory encoderFactory;
	private final Supplier<List<WebcamDevice>> devices;
	private final CaptureOpener opener;

	/** Opens a camera; {@link WebcamCapture#open} in the app, a fake in tests. */
	interface CaptureOpener {
		WebcamCapture open(String devicePath, int width, int height, int fps);
	}

	public WebcamRecordingService(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			EncoderFactory encoderFactory,
			Supplier<List<WebcamDevice>> devices) {
		this(recordingsDir, processes, environment, encoderFactory, devices, WebcamCapture::open);
	}

	WebcamRecordingService(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			EncoderFactory encoderFactory,
			Supplier<List<WebcamDevice>> devices,
			CaptureOpener opener) {
		this.recordingsDir = recordingsDir;
		this.processes = processes;
		this.environment = environment;
		this.encoderFactory = encoderFactory;
		this.devices = devices;
		this.opener = opener;
	}

	/** The installed cameras, for the HUD device list. */
	public List<WebcamDevice> listDevices() {
		return devices.get();
	}

	/** Encoder backed by the bundled ffmpeg CLI (software libx264); see the class comment. */
	public static EncoderFactory ffmpegCliEncoder(
			RecordingProcesses processes, RecordingEnvironment environment) {
		return (outputPath, format) -> new FfmpegCliWebcamEncoder(processes, environment, outputPath, format);
	}

	/** The webcam file for a recording timestamp: {@code recording-<ts>-webcam.mkv}. */
	public static String webcamFileName(long timestamp) {
		return "recording-" + timestamp + WEBCAM_SUFFIX + WEBCAM_EXTENSION;
	}

	/** The ffmpeg command for raw RGBA frames into a libx264 MKV. */
	static List<String> encoderCommand(Path ffmpeg, Path outputPath, WebcamFormat format) {
		return List.of(
				ffmpeg.toString(),
				"-hide_banner",
				"-loglevel", "error",
				"-y",
				"-f", "rawvideo",
				"-pix_fmt", "rgba",
				"-s", format.width() + "x" + format.height(),
				"-r", String.valueOf(WEBCAM_FRAME_RATE),
				"-i", "pipe:0",
				"-an",
				"-c:v", "libx264",
				"-preset", "ultrafast",
				"-crf", "18",
				"-pix_fmt", "yuv420p",
				outputPath.toString());
	}

	/**
	 * Starts recording the webcam. {@code devicePath} may be null to use the first camera.
	 *
	 * @param timestamp the recording timestamp used in the file name
	 * @param recordingStartedAtMs wall-clock start of the screen recording, the alignment reference
	 */
	public StartResult start(
			long timestamp,
			long recordingStartedAtMs,
			String devicePath,
			FrameListener preview) {
		StartResult result = prepare(timestamp, devicePath, preview);
		if (result.success()) {
			result.recording().begin(recordingStartedAtMs);
		}
		return result;
	}

	/**
	 * Opens and starts the camera without storing frames yet, so it is warmed up (first frames
	 * can take hundreds of milliseconds) before the screen recording starts. Call
	 * {@link WebcamRecording#begin} with the screen video's start once it is known.
	 */
	public StartResult prepare(long timestamp, String devicePath, FrameListener preview) {
		List<WebcamDevice> available = devices.get();
		WebcamDevice device = null;
		if (devicePath != null && !devicePath.isBlank()) {
			for (WebcamDevice candidate : available) {
				if (candidate.path().equals(devicePath)) {
					device = candidate;
					break;
				}
			}
			if (device == null) {
				return StartResult.failed("The selected camera is no longer available.");
			}
		} else if (!available.isEmpty()) {
			device = available.get(0);
		}
		if (device == null) {
			return StartResult.failed("No camera was found.");
		}
		WebcamCapture capture;
		try {
			capture = opener.open(device.path(), WEBCAM_WIDTH, WEBCAM_HEIGHT, WEBCAM_FRAME_RATE);
		} catch (RuntimeException error) {
			return StartResult.failed(String.valueOf(error));
		}
		WebcamFormat format = capture.format();
		Path path = recordingsDir.get().resolve(webcamFileName(timestamp));
		WebcamVideoEncoder encoder;
		try {
			encoder = encoderFactory.create(path, format);
		} catch (RuntimeException error) {
			capture.close();
			return StartResult.failed(String.valueOf(error));
		}

		WebcamRecording recording = new WebcamRecording(path, format, capture, encoder);
		recording.start(preview);
		this.lastRecording = recording;
		return new StartResult(true, null, recording);
	}

	private WebcamRecording lastRecording;

	/** Always 0: webcam tracks are anchored to the screen video's first frame. */
	public long timeOffsetMs() {
		return 0;
	}

	/** Pairs a finished recording with its webcam link; called after {@code stop}. */
	public RecordingSessionManifest.RecordingSessionData sessionFor(
			String videoPath, WebcamRecording recording) {
		return new RecordingSessionManifest.RecordingSessionData(
				videoPath, recording == null ? null : recording.path().toString(), timeOffsetMs());
	}

	/** Last started recording, for the app's stop path. */
	public WebcamRecording lastRecording() {
		return lastRecording;
	}

	/** The ffmpeg CLI implementation of {@link WebcamVideoEncoder}. */
	private static final class FfmpegCliWebcamEncoder implements WebcamVideoEncoder {

		private final SpawnedProcess process;
		private final OutputStream stdin;
		private boolean finished;

		FfmpegCliWebcamEncoder(
				RecordingProcesses processes,
				RecordingEnvironment environment,
				Path outputPath,
				WebcamFormat format) {
			try {
				this.process = processes.spawn(
						encoderCommand(environment.ffmpeg(), outputPath, format),
						new SpawnOptions(true, false, true));
			} catch (IOException error) {
				throw new IllegalStateException("Could not start the webcam encoder", error);
			}
			this.stdin = process.stdin();
			drain(process.stderr());
		}

		private static void drain(InputStream stream) {
			if (stream == null) {
				return;
			}
			Thread.ofPlatform().daemon().name("gravaai-webcam-encoder").start(() -> {
				try (var reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						LOG.log(System.Logger.Level.WARNING, "webcam ffmpeg: " + line.trim());
					}
				} catch (IOException error) {
					// Encoder exited.
				}
			});
		}

		@Override
		public void writeFrame(ByteBuffer rgbaTopFirst) throws IOException {
			byte[] chunk = new byte[Math.min(rgbaTopFirst.remaining(), 1 << 20)];
			while (rgbaTopFirst.hasRemaining()) {
				int length = Math.min(rgbaTopFirst.remaining(), chunk.length);
				rgbaTopFirst.get(chunk, 0, length);
				stdin.write(chunk, 0, length);
			}
		}

		@Override
		public void finish() throws IOException {
			if (finished) {
				return;
			}
			finished = true;
			stdin.close();
			int code;
			try {
				code = process.waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IOException("Webcam encoder was interrupted", error);
			}
			if (code != 0) {
				throw new IOException("The webcam encoder exited with code " + code + ".");
			}
		}

		@Override
		public void close() {
			try {
				finish();
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not finalise the webcam file: " + error);
			}
		}
	}
}
