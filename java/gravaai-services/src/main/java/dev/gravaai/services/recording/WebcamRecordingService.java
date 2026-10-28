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

	/** The running webcam recording handle. */
	public final class WebcamRecording implements AutoCloseable {

		private final Path path;
		private final Supplier<Long> timeOffsetMs;
		private final WebcamFormat format;
		private final WebcamCapture capture;
		private final WebcamVideoEncoder encoder;
		private final Thread thread;
		private final AtomicBoolean running;

		WebcamRecording(
				Path path,
				Supplier<Long> timeOffsetMs,
				WebcamFormat format,
				WebcamCapture capture,
				WebcamVideoEncoder encoder,
				Thread thread,
				AtomicBoolean running) {
			this.path = path;
			this.timeOffsetMs = timeOffsetMs;
			this.format = format;
			this.capture = capture;
			this.encoder = encoder;
			this.thread = thread;
			this.running = running;
		}

		public Path path() {
			return path;
		}

		/** Wall-clock offset of the first stored frame from the recording start. */
		public long timeOffsetMs() {
			return timeOffsetMs.get();
		}

		public WebcamFormat format() {
			return format;
		}

		/** Stops capture and finalises the MKV; returns an error message or null. */
		public String stop() {
			// The capture thread owns the device: it sees the flag after the current frame (one
			// frame period), closes the capture and exits. Closing it here instead freed the
			// FFmpeg contexts under a running av_read_frame.
			running.set(false);
			try {
				thread.join(2000);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			if (thread.isAlive()) {
				LOG.log(System.Logger.Level.WARNING, "Webcam capture thread did not stop within 2 s");
			}
			try {
				encoder.finish();
				return null;
			} catch (IOException error) {
				return String.valueOf(error);
			}
		}

		@Override
		public void close() {
			stop();
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

		long[] timeOffsetMs = {0};
		boolean[] wroteFrame = {false};
		long[] firstFrameMs = {0};
		long[] framesWritten = {0};
		AtomicBoolean running = new AtomicBoolean(true);
		Thread thread = Thread.ofPlatform().daemon().name("gravaai-webcam").start(() -> {
			ByteBuffer buffer = ByteBuffer.allocateDirect(format.width() * format.height() * 4);
			try {
				while (running.get()) {
					buffer.clear();
					boolean read = capture.readFrame(buffer);
					if (!read) {
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
					// The track starts on the recording timeline: frames before the
					// screen recording started are dropped, later ones set the offset.
					if (wallClockMs < recordingStartedAtMs && !wroteFrame[0]) {
						continue;
					}
					if (!wroteFrame[0]) {
						wroteFrame[0] = true;
						timeOffsetMs[0] = Math.max(0, wallClockMs - recordingStartedAtMs);
						firstFrameMs[0] = wallClockMs;
					}
					// The encoder reads a constant 30 fps stream, but cameras slow down in low
					// light (15 fps is common): place frames on the wall-clock grid, repeating
					// the latest frame when the camera is late and dropping it when early, so
					// the track lasts as long as the recording and stays in sync.
					long due = (wallClockMs - firstFrameMs[0]) * WEBCAM_FRAME_RATE / 1000 + 1;
					while (framesWritten[0] < due) {
						// readFrame writes with absolute puts and leaves the position at 0:
						// flip() here made every frame zero bytes long.
						buffer.rewind();
						encoder.writeFrame(buffer);
						framesWritten[0]++;
					}
				}
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "Webcam recording ended: " + error);
			} finally {
				running.set(false);
				capture.close();
			}
		});
		WebcamRecording recording = new WebcamRecording(
				path, () -> timeOffsetMs[0], format, capture, encoder, thread, running);
		// The offset is known once the first frame is stored; the app reads it after stop,
		// so keep the measured value on the service for the session manifest.
		this.lastTimeOffsetMs = () -> timeOffsetMs[0];
		this.lastRecording = recording;
		return new StartResult(true, null, recording);
	}

	private Supplier<Long> lastTimeOffsetMs = () -> 0L;
	private WebcamRecording lastRecording;

	/** The time offset measured for the most recent recording (after the first frame). */
	public long timeOffsetMs() {
		return lastTimeOffsetMs.get();
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
