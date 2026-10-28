package dev.gravaai.services.recording;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioInput;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxResult;
import dev.gravaai.services.recording.CosmicSession.CosmicSessionManifest;
import dev.gravaai.services.recording.CosmicSession.MeetingInfo;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.SelectedSource;
import tools.jackson.databind.JsonNode;

/**
 * Native screen recording for Wayland compositors with ext-image-copy-capture (COSMIC), a
 * line-by-line port of {@code electron/ipc/recording/cosmic.ts} (plan §5.6).
 *
 * <p>The xdg-desktop-portal screencast shows stale browser content after tab switches and stalls
 * on rotated outputs, so video comes straight from the compositor and audio from PulseAudio
 * (parec), then everything is muxed.
 */
public final class CosmicRecorder implements CosmicSession.CosmicMux {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** Audio captured with a recording: one parec per role. */
	public record RecordingOptions(
			boolean capturesSystemAudio,
			boolean capturesMicrophone,
			String microphoneDeviceId,
			String microphoneLabel,
			boolean keepSpeakerTracks) {
	}

	/** {@code { success, message? }} of the start requests. */
	public record StartResult(boolean success, String message) {

		public static StartResult ok() {
			return new StartResult(true, null);
		}

		public static StartResult failed(String message) {
			return new StartResult(false, message);
		}
	}

	/** The stop result; {@code meeting} is set for meeting recordings (speaker tracks kept). */
	public record StopResult(boolean success, String path, String error, MeetingInfo meeting) {

		public static StopResult ok(String path, MeetingInfo meeting) {
			return new StopResult(true, path, null, meeting);
		}

		public static StopResult failed(String error) {
			return new StopResult(false, null, error, null);
		}
	}

	/** A uniformly captured frame size and pixel layout reported by the helper. */
	record CaptureFormat(int width, int height, String pixelFormat, int transform) {
	}

	private record PauseState(AtomicBoolean paused) {
	}

	private static final class AudioTrack {

		private final SpawnedProcess process;
		private final CosmicAudioRole role;
		private final Path path;
		private final OutputStream file;
		private final CompletableFuture<Void> firstData = new CompletableFuture<>();
		private volatile long startedAtMs = -1;

		AudioTrack(SpawnedProcess process, CosmicAudioRole role, Path path, OutputStream file) {
			this.process = process;
			this.role = role;
			this.path = path;
			this.file = file;
		}
	}

	private record VideoCapture(
			SpawnedProcess capture,
			SpawnedProcess encoder,
			Path videoPath,
			CompletableFuture<Integer> encoderExit) {
	}

	private static final class ActiveRecording {

		private VideoCapture video;
		private List<AudioTrack> audio;
		private Path outputPath;
		private long startedAtMs;
		private boolean keepSpeakerTracks;
		private PauseState pauseState;
		private MicNoiseReduction micNoiseReduction;
		private long timestamp;
		private Path manifestPath;
		private ScheduledFuture<?> diskCheck;
	}

	private static final long MIN_FREE_BYTES_TO_START = 2L * 1024 * 1024 * 1024;
	private static final long MIN_FREE_BYTES_WHILE_RECORDING = 512L * 1024 * 1024;
	private static final long DISK_CHECK_INTERVAL_MS = 15_000;
	private static final int AUDIO_RATE = 48_000;
	// parec writes s16le stereo at 48 kHz: 192 bytes per millisecond.
	private static final double AUDIO_BYTES_PER_MS = (AUDIO_RATE * 2 * 2) / 1000.0;
	private static final Pattern FORMAT_LINE = Pattern.compile("^FORMAT (\\d+) (\\d+) (\\w+) (\\d+)$");
	private static final Pattern POSITION_LINE = Pattern.compile("^POS:(-?\\d+):(-?\\d+)$");
	private static final int COPY_BUFFER_BYTES = 4 * 1024 * 1024;

	private final Supplier<Path> recordingsDir;
	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final RecordingEvents events;
	private final CursorTelemetrySource cursorTelemetry;
	private final KeystrokeSource keystrokes;
	private final Supplier<MicNoiseReduction> micNoiseReductionSetting;
	private final ScheduledExecutorService diskChecker;

	private ActiveRecording active;

	public CosmicRecorder(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			RecordingEvents events,
			CursorTelemetrySource cursorTelemetry,
			KeystrokeSource keystrokes,
			Supplier<MicNoiseReduction> micNoiseReductionSetting) {
		this.recordingsDir = recordingsDir;
		this.processes = processes;
		this.environment = environment;
		this.events = events;
		this.cursorTelemetry = cursorTelemetry;
		this.keystrokes = keystrokes;
		this.micNoiseReductionSetting = micNoiseReductionSetting;
		this.diskChecker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "gravaai-disk-check");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Timestamp of the recording in progress, so recovery leaves its files alone. */
	public synchronized Long getActiveCosmicTimestamp() {
		return active == null ? null : active.timestamp;
	}

	/**
	 * Wall clock of the screen video's first frame for the recording in progress (the webcam
	 * track anchors to it), or null when nothing is recording.
	 */
	public synchronized Long getActiveCosmicStartedAtMs() {
		return active == null ? null : active.startedAtMs;
	}

	public synchronized boolean isCosmicRecordingActive() {
		return active != null;
	}

	/** Free bytes available to this user in dir, or null when unknown. */
	public static Long getFreeBytes(Path dir) {
		try {
			FileStore store = Files.getFileStore(dir);
			return store.getUsableSpace();
		} catch (IOException error) {
			return null;
		}
	}

	private static String checkFreeSpaceToStart(Path dir) {
		Long free = getFreeBytes(dir);
		return free != null && free < MIN_FREE_BYTES_TO_START
				? "Not enough free disk space to record (less than 2 GB free in " + dir + ")."
				: null;
	}

	/** Asks the renderer to stop (and save) once, when the disk is nearly full. */
	private void startDiskCheck(ActiveRecording recording, Path dir) {
		recording.diskCheck = diskChecker.scheduleWithFixedDelay(() -> {
			Long free = getFreeBytes(dir);
			synchronized (this) {
				if (active != recording || free == null || free >= MIN_FREE_BYTES_WHILE_RECORDING) {
					return;
				}
				stopDiskCheck(recording);
			}
			LOG.log(System.Logger.Level.WARNING,
					"Only " + free + " bytes free in " + dir + "; stopping");
			events.emitRecordingStopRequested(
					"low-disk", "Disk almost full — the recording was stopped and saved.");
		}, DISK_CHECK_INTERVAL_MS, DISK_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	private static void stopDiskCheck(ActiveRecording recording) {
		if (recording.diskCheck != null) {
			recording.diskCheck.cancel(false);
			recording.diskCheck = null;
		}
	}

	private static CosmicSessionManifest buildManifest(ActiveRecording recording) {
		List<CosmicSession.CosmicAudioTrack> audio = new ArrayList<>();
		for (AudioTrack track : recording.audio) {
			audio.add(new CosmicSession.CosmicAudioTrack(
					track.role,
					track.path.toString(),
					track.startedAtMs < 0 ? null : track.startedAtMs));
		}
		return new CosmicSessionManifest(
				1,
				recording.timestamp,
				ProcessHandle.current().pid(),
				java.time.Instant.ofEpochMilli(recording.timestamp).toString(),
				recording.outputPath.toString(),
				recording.video == null ? null : recording.video.videoPath().toString(),
				recording.startedAtMs,
				recording.keepSpeakerTracks,
				recording.micNoiseReduction,
				List.copyOf(audio));
	}

	/**
	 * Writes the manifest, logging instead of failing: a recording without one still works.
	 * Writes are serialized so two never share the temp file.
	 */
	private synchronized void saveManifest(ActiveRecording recording) {
		try {
			CosmicSession.writeSessionManifest(recording.manifestPath, buildManifest(recording));
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not write the recording manifest: " + error);
		}
	}

	/** Sets up the manifest and disk check once {@code active} holds the recording. */
	private void beginRecordingSession(ActiveRecording recording) {
		recording.manifestPath = CosmicSession.sessionManifestPath(
				recordingsDir.get(), recording.timestamp);
		synchronized (this) {
			active = recording;
		}
		// A track that had no sound yet updates the manifest on its first chunk.
		for (AudioTrack track : recording.audio) {
			if (track.startedAtMs >= 0) {
				continue;
			}
			track.firstData.thenRun(() -> {
				synchronized (this) {
					if (active != recording || track.startedAtMs < 0) {
						return;
					}
				}
				saveManifest(recording);
			});
		}
		saveManifest(recording);
		startDiskCheck(recording, recordingsDir.get());
	}

	/** The PulseAudio source whose description matches Chromium's device label. */
	String resolvePulseMicrophone(String label) {
		if (label == null || label.isBlank() || "default".equalsIgnoreCase(label)) {
			return "@DEFAULT_SOURCE@";
		}
		try {
			byte[] stdout = runAndCapture(List.of(
					environment.command("pactl").toString(), "-f", "json", "list", "sources"));
			JsonNode sources = Json.parseJsonWithByteOrderMark(new String(stdout, StandardCharsets.UTF_8));
			String name = pickPulseMicrophone(sources, label);
			return name != null ? name : "@DEFAULT_SOURCE@";
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not list PulseAudio sources: " + error);
			return "@DEFAULT_SOURCE@";
		}
	}

	/** The matching source name from {@code pactl -f json list sources} output, or null. */
	static String pickPulseMicrophone(JsonNode sources, String label) {
		if (sources == null || !sources.isArray()) {
			return null;
		}
		for (JsonNode source : sources) {
			JsonNode name = source.get("name");
			JsonNode description = source.get("description");
			if (name == null || !name.isString() || description == null || !description.isString()) {
				continue;
			}
			String descriptionText = description.asString();
			if (descriptionText.equals(label)
					|| label.contains(descriptionText)
					|| descriptionText.contains(label)) {
				return name.asString();
			}
		}
		return null;
	}

	private static String transposeFilter(int transform) {
		String flip = transform >= 4 ? "hflip," : "";
		return switch (transform % 4) {
			case 1 -> flip + "transpose=1";
			case 2 -> flip + "hflip,vflip";
			case 3 -> flip + "transpose=2";
			default -> flip.isEmpty() ? null : "hflip";
		};
	}

	/** Resolves with the helper's FORMAT line, or throws with its error. */
	private CaptureFormat readCaptureFormat(SpawnedProcess capture) throws IOException {
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(capture.stderr(), StandardCharsets.UTF_8));
		StringBuilder stderr = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			stderr.append(line).append('\n');
			Matcher match = FORMAT_LINE.matcher(line);
			if (match.matches()) {
				Thread.ofPlatform().daemon().name("gravaai-capture-log").start(() -> drainLines(reader));
				return new CaptureFormat(
						Integer.parseInt(match.group(1)),
						Integer.parseInt(match.group(2)),
						match.group(3),
						Integer.parseInt(match.group(4)));
			}
		}
		String text = stderr.toString().trim();
		throw new IOException(text.isEmpty() ? "The screen capture helper exited early." : text);
	}

	private static void drainLines(BufferedReader reader) {
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				LOG.log(System.Logger.Level.WARNING, "[cosmic-capture] helper: " + line.trim());
			}
		} catch (IOException error) {
			// The helper exited; nothing to drain.
		}
	}

	/**
	 * Starts one parec per requested device. Audio starts before any video; each track remembers
	 * when its first sample was captured so the mux can align it.
	 */
	private List<AudioTrack> startAudioTracks(
			Path recordingsDir, long timestamp, RecordingOptions options, PauseState pauseState) {
		List<AudioTrack> audio = new ArrayList<>();
		if (options.capturesSystemAudio()) {
			// The monitor's own volume scales what capture apps get, not what you hear;
			// a low value there records near-silence.
			try {
				runAndCapture(List.of(
						environment.command("pactl").toString(),
						"set-source-volume", "@DEFAULT_MONITOR@", "100%"));
			} catch (IOException | RuntimeException error) {
				// Best effort, like the TS .catch(() => undefined).
			}
			audio.add(startAudioTrack(recordingsDir, timestamp, "@DEFAULT_MONITOR@", CosmicAudioRole.SYSTEM, pauseState));
		}
		if (options.capturesMicrophone()) {
			String device = resolvePulseMicrophone(options.microphoneLabel());
			audio.add(startAudioTrack(recordingsDir, timestamp, device, CosmicAudioRole.MIC, pauseState));
		}
		CompletableFuture<Void> allFirstData = CompletableFuture.allOf(
				audio.stream().map(track -> track.firstData).toArray(CompletableFuture[]::new));
		try {
			allFirstData.get(1500, TimeUnit.MILLISECONDS);
		} catch (TimeoutException error) {
			// A silent device may not produce data yet; the track keeps waiting in the background.
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException error) {
			// firstData only completes normally.
		}
		return audio;
	}

	private AudioTrack startAudioTrack(
			Path recordingsDir,
			long timestamp,
			String device,
			CosmicAudioRole role,
			PauseState pauseState) {
		Path file = recordingsDir.resolve(".recording-" + timestamp + "." + role.wire() + ".pcm");
		SpawnedProcess process;
		OutputStream output;
		try {
			output = Files.newOutputStream(file);
		} catch (IOException error) {
			throw new IllegalStateException("Could not create " + file, error);
		}
		try {
			process = processes.spawn(
					List.of(
							environment.command("parec").toString(),
							"--device=" + device,
							"--raw",
							"--format=s16le",
							"--rate=" + AUDIO_RATE,
							"--channels=2",
							"--latency-msec=20"),
					new SpawnOptions(false, true, false));
		} catch (IOException error) {
			closeQuietly(output);
			throw new IllegalStateException("Could not start parec for " + device, error);
		}
		AudioTrack track = new AudioTrack(process, role, file, output);
		Thread.ofPlatform().daemon().name("gravaai-audio-" + role.wire()).start(() -> {
			byte[] buffer = new byte[8192];
			try (InputStream in = process.stdout()) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					if (track.startedAtMs < 0) {
						track.startedAtMs = System.currentTimeMillis()
								- (long) (read / AUDIO_BYTES_PER_MS);
						track.firstData.complete(null);
					}
					// Paused audio is dropped, matching the video, which stops emitting frames.
					if (!pauseState.paused().get()) {
						track.file.write(buffer, 0, read);
					}
				}
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Audio capture ended: " + error);
			} finally {
				if (!track.firstData.isDone()) {
					track.firstData.complete(null);
				}
			}
		});
		processExit(process).thenRun(() -> track.firstData.complete(null));
		LOG.log(System.Logger.Level.INFO, "Recording audio from " + device);
		return track;
	}

	private static CompletableFuture<Void> processExit(SpawnedProcess process) {
		return CompletableFuture.runAsync(() -> {
			try {
				process.waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void killAudioTracks(List<AudioTrack> audio) {
		for (AudioTrack track : audio) {
			track.process.destroy();
			closeQuietly(track.file);
		}
	}

	private static void closeQuietly(OutputStream output) {
		try {
			output.close();
		} catch (IOException error) {
			// Already closed.
		}
	}

	/** Starts a screen recording of one COSMIC output. */
	public synchronized StartResult startCosmicRecording(
			SelectedSource source, RecordingOptions options) {
		if (active != null) {
			return StartResult.failed("A screen recording is already active.");
		}
		String outputName = CosmicSources.outputNameForSourceId(source == null ? null : source.id());
		Path recordingsDir = this.recordingsDir.get();
		String lowDisk = checkFreeSpaceToStart(recordingsDir);
		if (lowDisk != null) {
			return StartResult.failed(lowDisk);
		}
		long timestamp = System.currentTimeMillis();
		Path outputPath = recordingsDir.resolve("recording-" + timestamp + ".mp4");
		Path videoPath = recordingsDir.resolve(".recording-" + timestamp + ".video.mkv");

		PauseState pauseState = new PauseState(new AtomicBoolean(false));
		List<AudioTrack> audio = startAudioTracks(recordingsDir, timestamp, options, pauseState);
		SpawnedProcess capture;
		try {
			capture = processes.spawn(
					List.of(environment.captureHelper().toString(), outputName, String.valueOf(CosmicSources.CAPTURE_FPS)),
					new SpawnOptions(true, true, true));
		} catch (IOException error) {
			killAudioTracks(audio);
			return StartResult.failed(String.valueOf(error));
		}
		CaptureFormat format;
		try {
			format = readCaptureFormat(capture);
		} catch (IOException error) {
			capture.destroy();
			killAudioTracks(audio);
			return StartResult.failed(error.getMessage());
		}
		// The helper prints FORMAT right after grabbing the first frame and its
		// frame clock starts there.
		long videoStartedAtMs = System.currentTimeMillis();

		List<String> filters = new ArrayList<>();
		String transpose = transposeFilter(format.transform());
		if (transpose != null) {
			filters.add(transpose);
		}
		filters.add("format=yuv420p");
		SpawnedProcess encoder;
		try {
			encoder = processes.spawn(
					encoderCommand(format, String.join(",", filters), videoPath),
					new SpawnOptions(true, false, true));
		} catch (IOException error) {
			capture.destroy();
			killAudioTracks(audio);
			return StartResult.failed(String.valueOf(error));
		}
		drainWarnings(encoder.stderr(), "[cosmic-capture] ffmpeg:");
		drainWarnings(capture.stderr(), "[cosmic-capture] helper:");
		Thread.ofPlatform().daemon().name("gravaai-av-copy").start(() -> copyFrames(capture, encoder));

		ActiveRecording recording = new ActiveRecording();
		recording.video = new VideoCapture(capture, encoder, videoPath, exitCode(encoder));
		recording.audio = audio;
		recording.outputPath = outputPath;
		recording.startedAtMs = videoStartedAtMs;
		recording.keepSpeakerTracks = options.keepSpeakerTracks();
		recording.pauseState = pauseState;
		recording.micNoiseReduction = currentMicNoiseReduction();
		recording.timestamp = timestamp;
		beginRecordingSession(recording);
		LOG.log(System.Logger.Level.INFO, "Recording " + outputName + " " + format.width() + "x"
				+ format.height() + " transform " + format.transform() + " to " + outputPath);
		return StartResult.ok();
	}

	private List<String> encoderCommand(CaptureFormat format, String filters, Path videoPath) {
		return List.of(
				environment.ffmpeg().toString(),
				"-hide_banner",
				"-loglevel",
				"error",
				"-y",
				"-f",
				"rawvideo",
				"-pix_fmt",
				format.pixelFormat(),
				"-s",
				format.width() + "x" + format.height(),
				"-r",
				String.valueOf(CosmicSources.CAPTURE_FPS),
				"-i",
				"pipe:0",
				"-vf",
				filters,
				"-c:v",
				"libx264",
				"-preset",
				"ultrafast",
				"-crf",
				"18",
				"-g",
				String.valueOf(CosmicSources.CAPTURE_FPS * 2),
				// Short clusters: a crash or power loss loses at most ~2 s of video.
				"-cluster_time_limit",
				"2000",
				videoPath.toString());
	}

	private static void copyFrames(SpawnedProcess capture, SpawnedProcess encoder) {
		try (ReadableByteChannel input = Channels.newChannel(capture.stdout());
				WritableByteChannel output = Channels.newChannel(encoder.stdin())) {
			ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
			while (input.read(buffer) != -1) {
				buffer.flip();
				while (buffer.hasRemaining()) {
					output.write(buffer);
				}
				buffer.clear();
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Frame copy ended: " + error);
		}
	}

	private static CompletableFuture<Integer> exitCode(SpawnedProcess process) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return process.waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return -1;
			}
		});
	}

	private static void drainWarnings(InputStream stream, String prefix) {
		if (stream == null) {
			return;
		}
		Thread.ofPlatform().daemon().name("gravaai-drain").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOG.log(System.Logger.Level.WARNING, prefix + " " + line.trim());
				}
			} catch (IOException error) {
				// Process exited.
			}
		});
	}

	/** Records only audio (a meeting without the screen) into an .m4a. */
	public synchronized StartResult startCosmicAudioRecording(RecordingOptions options) {
		if (active != null) {
			return StartResult.failed("A recording is already active.");
		}
		if (!options.capturesMicrophone() && !options.capturesSystemAudio()) {
			return StartResult.failed("Choose a microphone or system audio to record.");
		}
		Path recordingsDir = this.recordingsDir.get();
		String lowDisk = checkFreeSpaceToStart(recordingsDir);
		if (lowDisk != null) {
			return StartResult.failed(lowDisk);
		}
		long timestamp = System.currentTimeMillis();
		PauseState pauseState = new PauseState(new AtomicBoolean(false));
		List<AudioTrack> audio = startAudioTracks(recordingsDir, timestamp, options, pauseState);
		List<Long> starts = new ArrayList<>();
		for (AudioTrack track : audio) {
			if (track.startedAtMs >= 0) {
				starts.add(track.startedAtMs);
			}
		}
		if (starts.isEmpty()) {
			killAudioTracks(audio);
			return StartResult.failed("No audio device produced any sound data.");
		}
		ActiveRecording recording = new ActiveRecording();
		recording.video = null;
		recording.audio = audio;
		recording.outputPath = recordingsDir.resolve("recording-" + timestamp + ".m4a");
		recording.startedAtMs = starts.stream().min(Long::compare).orElse(timestamp);
		recording.keepSpeakerTracks = options.keepSpeakerTracks();
		recording.pauseState = pauseState;
		recording.micNoiseReduction = currentMicNoiseReduction();
		recording.timestamp = timestamp;
		beginRecordingSession(recording);
		LOG.log(System.Logger.Level.INFO, "Recording audio only to " + recording.outputPath);
		return StartResult.ok();
	}

	public synchronized StartResult pauseCosmicRecording() {
		ActiveRecording recording = active;
		if (recording == null) {
			return StartResult.failed("No screen recording is active.");
		}
		if (!recording.pauseState.paused().get()) {
			if (recording.video != null) {
				writeCommand(recording.video.capture(), "pause");
			}
			recording.pauseState.paused().set(true);
		}
		return StartResult.ok();
	}

	public synchronized StartResult resumeCosmicRecording() {
		ActiveRecording recording = active;
		if (recording == null) {
			return StartResult.failed("No screen recording is active.");
		}
		if (recording.pauseState.paused().get()) {
			if (recording.video != null) {
				writeCommand(recording.video.capture(), "resume");
			}
			recording.pauseState.paused().set(false);
		}
		return StartResult.ok();
	}

	private static void writeCommand(SpawnedProcess process, String command) {
		try {
			OutputStream stdin = process.stdin();
			if (stdin != null) {
				stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
				stdin.flush();
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not send " + command + " to the capture helper: " + error);
		}
	}

	private static boolean hasAudioData(Path file) {
		try {
			// A WAV header alone is 44 bytes.
			return Files.size(file) > 1024;
		} catch (IOException error) {
			return false;
		}
	}

	/**
	 * Saves the cursor telemetry next to the screen video, on the video's clock. The cursor clock
	 * starts when the renderer reports the recording as started, a little after the first frame.
	 */
	private void persistCosmicCursorTelemetry(ActiveRecording recording) {
		long shiftMs = cursorTelemetry.cursorCaptureStartTimeMs() - recording.startedAtMs;
		long appliedShiftMs = shiftMs > 0 && shiftMs < 5000 ? shiftMs : 0;
		List<CursorTelemetryPoint> samples = new ArrayList<>();
		for (CursorTelemetryPoint sample : cursorTelemetry.snapshotAndTakePendingSamples()) {
			samples.add(new CursorTelemetryPoint(
					sample.timeMs() + appliedShiftMs,
					sample.cx(),
					sample.cy(),
					sample.interactionType(),
					sample.cursorType()));
		}
		try {
			CursorTelemetry.writeCursorTelemetry(recording.outputPath.toString(), samples);
			LOG.log(System.Logger.Level.INFO,
					"Saved " + samples.size() + " cursor samples (shifted " + appliedShiftMs + "ms)");
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Failed to save cursor telemetry: " + error);
		}
		// Key presses ride the same shift so they line up with the first frame.
		// Only the count is logged: the presses themselves are never logged.
		try {
			List<KeystrokeEvent> shifted = new ArrayList<>();
			for (KeystrokeEvent event : keystrokes.takeRecordedKeystrokes()) {
				shifted.add(new KeystrokeEvent(event.timeMs() + appliedShiftMs, event.key(), event.modifiers()));
			}
			KeystrokeTelemetry.writeKeystrokeTelemetry(recording.outputPath.toString(), shifted);
			LOG.log(System.Logger.Level.INFO, "Saved " + shifted.size() + " key presses");
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Failed to save key presses: " + error);
		}
	}

	/** ffmpeg filter that lines a raw track up with the recording's reference start. */
	public static String alignTrackFilter(long trackStartedAtMs, long referenceMs) {
		long offsetMs = Math.round((double) (trackStartedAtMs - referenceMs));
		return offsetMs > 0
				? "adelay=" + offsetMs + ":all=1"
				: "atrim=start=" + String.format(Locale.ROOT, "%.3f", (-offsetMs) / 1000.0)
						+ ",asetpts=PTS-STARTPTS";
	}

	/** One track as the filter builders see it. */
	public record TrackRef(CosmicAudioRole role, long startedAtMs) {
	}

	/** Filters for one track, in order: alignment first so timing stays exact, then mic denoise. */
	public static List<String> trackFilterChain(TrackRef track, long referenceMs, List<String> micFilters) {
		List<String> filters = new ArrayList<>();
		filters.add(alignTrackFilter(track.startedAtMs(), referenceMs));
		if (track.role() == CosmicAudioRole.MIC) {
			filters.addAll(micFilters);
		}
		return List.copyOf(filters);
	}

	/** The -filter_complex that lines every audio track up and mixes them into [a]. */
	public static String buildCosmicAudioMixFilter(
			List<TrackRef> tracks, long referenceMs, int firstInputIndex, List<String> micFilters) {
		List<String> chains = new ArrayList<>();
		for (int index = 0; index < tracks.size(); index++) {
			TrackRef track = tracks.get(index);
			chains.add("[" + (index + firstInputIndex) + ":a]"
					+ String.join(",", trackFilterChain(track, referenceMs, micFilters))
					+ "[a" + index + "]");
		}
		if (tracks.size() == 1) {
			return chains.get(0).replace("[a0]", "[a]");
		}
		StringBuilder labels = new StringBuilder();
		for (int index = 0; index < tracks.size(); index++) {
			labels.append("[a").append(index).append("]");
		}
		return String.join(";", chains)
				+ ";" + labels + "amix=inputs=" + tracks.size() + ":duration=longest:normalize=0[a]";
	}

	/** Path of the per-speaker meeting track kept next to a recording. */
	public static String speakerTrackPath(String outputPath, CosmicAudioRole role) {
		int slash = Math.max(outputPath.lastIndexOf('/'), outputPath.lastIndexOf('\\'));
		int dot = outputPath.lastIndexOf('.');
		String stem = dot > slash ? outputPath.substring(0, dot) : outputPath;
		return stem + ".meeting-" + role.wire() + ".opus";
	}

	/** Mic denoise chain for this recording's level; logs why it fell back when the model is missing. */
	private List<String> prepareMicNoiseFilters(MicNoiseReduction level) {
		Path modelPath = level == MicNoiseReduction.OFF ? null : environment.rnnoiseModel();
		if ((level == MicNoiseReduction.BALANCED || level == MicNoiseReduction.STRONG) && modelPath == null) {
			LOG.log(System.Logger.Level.WARNING,
					"RNNoise model not found; using the light mic filter.");
		}
		LOG.log(System.Logger.Level.INFO, "mic noise reduction: " + level.wire());
		return MicNoiseReductionFilters.buildMicNoiseFilters(
				level, modelPath == null ? null : modelPath.toString());
	}

	private record FfmpegRun(SpawnedProcess process, CompletableFuture<String> stderr) {
	}

	/** Runs the bundled ffmpeg CLI with {@code args} (the arguments only, without the program). */
	private FfmpegRun runFfmpeg(List<String> args) {
		List<String> command = new ArrayList<>(args.size() + 1);
		command.add(environment.ffmpeg().toString());
		command.addAll(args);
		try {
			SpawnedProcess process = processes.spawn(command, new SpawnOptions(false, false, true));
			CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
				try (InputStream in = process.stderr()) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				} catch (IOException error) {
					return "";
				}
			});
			return new FfmpegRun(process, stderr);
		} catch (IOException error) {
			throw new IllegalStateException("Could not start ffmpeg", error);
		}
	}

	private static int waitFor(FfmpegRun run) {
		try {
			return run.process().waitFor();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	private static List<String> rawTrackInput(Path file) {
		return List.of(
				"-f", "s16le",
				"-ar", String.valueOf(AUDIO_RATE),
				"-ac", "2",
				"-i", file.toString());
	}

	/**
	 * Muxes the temp files into the final recording, plus the speaker tracks for meetings. Used by
	 * a normal stop and by crash recovery.
	 */
	@Override
	public CosmicMuxResult mux(CosmicMuxInput input) {
		List<String> micFilters = prepareMicNoiseFilters(input.micNoiseReduction());
		List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
		if (input.videoPath() != null) {
			// A video that was never finalised may end in a truncated cluster.
			if (input.recovering()) {
				args.addAll(List.of("-fflags", "+genpts+discardcorrupt", "-err_detect", "ignore_err"));
			}
			args.addAll(List.of("-i", input.videoPath()));
		}
		int firstAudioInput = input.videoPath() != null ? 1 : 0;
		for (CosmicAudioInput track : input.audio()) {
			args.addAll(rawTrackInput(Path.of(track.path())));
		}
		if (input.videoPath() != null) {
			args.addAll(List.of("-map", "0:v"));
		}
		if (!input.audio().isEmpty()) {
			for (CosmicAudioInput track : input.audio()) {
				LOG.log(System.Logger.Level.INFO,
						Path.of(track.path()).getFileName() + " align "
								+ alignTrackFilter(track.startedAtMs(), input.startedAtMs()));
			}
			List<TrackRef> tracks = new ArrayList<>();
			for (CosmicAudioInput track : input.audio()) {
				tracks.add(new TrackRef(track.role(), track.startedAtMs()));
			}
			args.addAll(List.of("-filter_complex",
					buildCosmicAudioMixFilter(tracks, input.startedAtMs(), firstAudioInput, micFilters),
					"-map", "[a]"));
		}
		if (input.videoPath() != null) {
			args.addAll(List.of("-c:v", "copy"));
		}
		if (!input.audio().isEmpty()) {
			args.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
		}
		args.add("-movflags");
		args.add("+faststart");
		if (input.videoPath() != null) {
			args.add("-shortest");
		}
		args.add(input.outputPath());

		// Speaker tracks are transcription input only: mono, aligned like the mix,
		// never played back (listeners always get the mixed track above).
		List<FfmpegRun> speakerRuns = new ArrayList<>();
		if (input.keepSpeakerTracks()) {
			for (CosmicAudioInput track : input.audio()) {
				List<String> speakerArgs = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
				speakerArgs.addAll(rawTrackInput(Path.of(track.path())));
				speakerArgs.addAll(List.of(
						"-af",
						String.join(",", trackFilterChain(
								new TrackRef(track.role(), track.startedAtMs()),
								input.startedAtMs(),
								micFilters)),
						"-ac", "1",
						"-c:a", "libopus",
						"-b:a", "48k",
						speakerTrackPath(input.outputPath(), track.role())));
				speakerRuns.add(runFfmpeg(speakerArgs));
			}
		}

		FfmpegRun mux = runFfmpeg(args);
		int muxCode = waitFor(mux);
		String muxStderr = mux.stderr().join();
		for (FfmpegRun speaker : speakerRuns) {
			int code = waitFor(speaker);
			if (code != 0) {
				LOG.log(System.Logger.Level.WARNING, "Speaker track failed: " + speaker.stderr().join());
			}
		}
		if (muxCode != 0) {
			return CosmicMuxResult.err(muxStderr.isEmpty()
					? "Muxing failed with code " + muxCode + "."
					: muxStderr);
		}
		return CosmicMuxResult.ok();
	}

	public synchronized StopResult stopCosmicRecording() {
		ActiveRecording recording = active;
		if (recording == null) {
			return StopResult.failed("No screen recording is active.");
		}
		active = null;
		stopDiskCheck(recording);

		VideoCapture video = recording.video;
		if (video != null) {
			writeCommand(video.capture(), "stop");
			closeStdin(video.capture());
		}
		List<CompletableFuture<Void>> audioExits = new ArrayList<>();
		for (AudioTrack track : recording.audio) {
			track.process.destroy();
			audioExits.add(processExit(track.process).thenRun(() -> closeQuietly(track.file)));
		}
		if (video != null) {
			try {
				video.capture().waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			persistCosmicCursorTelemetry(recording);
		}
		int encoderCode = 0;
		if (video != null) {
			try {
				encoderCode = video.encoderExit().join();
			} catch (RuntimeException error) {
				encoderCode = -1;
			}
		}
		CompletableFuture.allOf(audioExits.toArray(CompletableFuture[]::new)).join();
		if (encoderCode != 0) {
			return StopResult.failed("The video encoder exited with code " + encoderCode + ".");
		}

		List<CosmicAudioInput> audioTracks = new ArrayList<>();
		for (AudioTrack track : recording.audio) {
			if (track.startedAtMs >= 0 && hasAudioData(track.path)) {
				audioTracks.add(new CosmicAudioInput(track.role, track.path.toString(), track.startedAtMs));
			} else {
				LOG.log(System.Logger.Level.WARNING, "No audio captured in " + track.path);
			}
		}
		if (video == null && audioTracks.isEmpty()) {
			removeCosmicTempFiles(recording);
			return StopResult.failed("No audio was captured.");
		}

		// On failure the temp files and manifest stay, so the next launch offers recovery.
		CosmicMuxResult mux = mux(new CosmicMuxInput(
				video == null ? null : video.videoPath().toString(),
				List.copyOf(audioTracks),
				recording.startedAtMs,
				recording.outputPath.toString(),
				recording.keepSpeakerTracks,
				recording.micNoiseReduction,
				false));
		if (!mux.success()) {
			return StopResult.failed(mux.error());
		}

		removeCosmicTempFiles(recording);
		LOG.log(System.Logger.Level.INFO, "Saved " + recording.outputPath);
		return StopResult.ok(
				recording.outputPath.toString(),
				recording.keepSpeakerTracks ? new MeetingInfo(video != null) : null);
	}

	private static void closeStdin(SpawnedProcess process) {
		try {
			OutputStream stdin = process.stdin();
			if (stdin != null) {
				stdin.close();
			}
		} catch (IOException error) {
			// Already closed.
		}
	}

	private static void removeCosmicTempFiles(ActiveRecording recording) {
		List<Path> files = new ArrayList<>();
		for (AudioTrack track : recording.audio) {
			files.add(track.path);
		}
		if (recording.video != null) {
			files.add(recording.video.videoPath());
		}
		files.add(recording.manifestPath);
		for (Path file : files) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not remove temp file " + file + ": " + error);
			}
		}
	}

	/** Stops the processes without producing a file. */
	public synchronized void discardCosmicRecording() {
		ActiveRecording recording = active;
		if (recording == null) {
			return;
		}
		active = null;
		stopDiskCheck(recording);
		if (recording.video != null) {
			recording.video.capture().destroy();
			recording.video.encoder().destroy();
		}
		killAudioTracks(recording.audio);
		if (recording.video != null) {
			try {
				recording.video.capture().waitFor();
				recording.video.encoderExit().join();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException error) {
				// Encoder already gone.
			}
		}
		removeCosmicTempFiles(recording);
	}

	/** Waits for the next mouse click anywhere and resolves with the output under it. */
	public CompletableFuture<CosmicOutput> waitForCosmicOutputClick(long timeoutMs) {
		CompletableFuture<CosmicOutput> result = new CompletableFuture<>();
		SpawnedProcess helper;
		try {
			helper = processes.spawn(
					List.of(environment.cursorHelper().toString()),
					new SpawnOptions(true, true, false));
		} catch (IOException error) {
			return CompletableFuture.completedFuture(null);
		}
		AtomicBoolean settled = new AtomicBoolean(false);
		java.util.concurrent.atomic.AtomicReference<int[]> point = new java.util.concurrent.atomic.AtomicReference<>();
		ScheduledFuture<?> timer = diskChecker.schedule(
				() -> finishOutputPick(result, settled, helper, null), timeoutMs, TimeUnit.MILLISECONDS);
		Thread.ofPlatform().daemon().name("gravaai-output-pick").start(() -> {
			int[] picked = null;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(helper.stdout(), StandardCharsets.UTF_8))) {
				String line;
				while (!settled.get() && (line = reader.readLine()) != null) {
					Matcher position = POSITION_LINE.matcher(line);
					if (position.matches()) {
						point.set(new int[] {
								Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2))});
						continue;
					}
					if (line.startsWith("INTERACTION:mousedown") && point.get() != null) {
						picked = point.get();
						break;
					}
				}
			} catch (IOException error) {
				// Helper exited.
			}
			timer.cancel(false);
			finishOutputPick(result, settled, helper, picked);
		});
		return result;
	}

	private void finishOutputPick(
			CompletableFuture<CosmicOutput> result,
			AtomicBoolean settled,
			SpawnedProcess helper,
			int[] picked) {
		if (!settled.compareAndSet(false, true)) {
			return;
		}
		writeCommand(helper, "");
		helper.destroy();
		if (picked == null) {
			result.complete(null);
			return;
		}
		Thread.ofVirtual().start(() -> {
			try {
				result.complete(CosmicSources.outputAt(listCosmicOutputs(), picked[0], picked[1]));
			} catch (IOException | RuntimeException error) {
				result.complete(null);
			}
		});
	}

	/** Lists the COSMIC outputs by running {@code gravaai-wayland-capture --list}. */
	public List<CosmicOutput> listCosmicOutputs() throws IOException {
		byte[] stdout = runAndCapture(List.of(
				environment.captureHelper().toString(), "--list"));
		return new ArrayList<>(CosmicSources.parseOutputs(new String(stdout, StandardCharsets.UTF_8)));
	}

	/** Runs a command, returns stdout, throws when it exits non-zero (like {@code execFile}). */
	private byte[] runAndCapture(List<String> command) throws IOException {
		SpawnedProcess process = processes.spawn(command, new SpawnOptions(false, true, true));
		CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
			try (InputStream in = process.stdout()) {
				return in.readAllBytes();
			} catch (IOException error) {
				return new byte[0];
			}
		});
		CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> {
			try (InputStream in = process.stderr()) {
				return in.readAllBytes();
			} catch (IOException error) {
				return new byte[0];
			}
		});
		int code;
		try {
			code = process.waitFor();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", error);
		}
		byte[] output = stdout.join();
		if (code != 0) {
			throw new IOException(new String(stderr.join(), StandardCharsets.UTF_8).trim()
					.isEmpty() ? "Command exited with code " + code + "." : new String(stderr.join(), StandardCharsets.UTF_8).trim());
		}
		return output;
	}

	private MicNoiseReduction currentMicNoiseReduction() {
		MicNoiseReduction level = micNoiseReductionSetting.get();
		return level == null ? MicNoiseReduction.DEFAULT : level;
	}
}
