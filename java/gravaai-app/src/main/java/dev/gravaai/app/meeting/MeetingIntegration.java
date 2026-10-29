package dev.gravaai.app.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.services.automations.AutomationService;
import dev.gravaai.services.meeting.HttpFileDownloader;
import dev.gravaai.services.meeting.MeetingFiles;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.MediaTools;
import dev.gravaai.services.meeting.ports.MeetingApiKeyStore;
import dev.gravaai.services.meeting.ports.MeetingAudioRecorder;
import dev.gravaai.services.meeting.ports.MeetingRecordingOptions;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;
import dev.gravaai.services.meeting.ports.RecordingDirectories;
import dev.gravaai.services.meeting.ports.Reveal;
import dev.gravaai.services.meeting.ports.SettingsStore;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.recording.CosmicRecorder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the meeting service (plan §5.3, P1.11) to the concrete platform/services ports.
 *
 * <p>The meeting service owns settings, the library, download jobs and the pipeline; the
 * application owns the ports. Everything here is deliberately thin: the process runner, trash,
 * file manager, recordings directory and the recording service already exist.
 *
 * <p>The API-key store is the plan §5.2 keyring gap: Electron encrypts the key through
 * {@code safeStorage}; the Java build has no libsecret backend yet, so reading a plain string kept
 * by the Electron app works, while writing reports the gap instead of persisting plaintext.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class MeetingIntegration implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.app.meeting");
	private static final JsonMapper JSON = JsonMapper.shared();

	private final MeetingService service;

	private MeetingIntegration(MeetingService service) {
		this.service = service;
	}

	public static MeetingIntegration create(UserData userData, AppSettingsStore settingsStore,
			dev.gravaai.app.recording.RecordingWiring recordingWiring, FileManager fileManager,
			AutomationService automationService) {
		MeetingService service = MeetingService.create(
				userData.dir(),
				settingsAdapter(settingsStore),
				apiKeyAdapter(settingsStore),
				new ProcessRunnerAdapter(),
				mediaTools(),
				new HttpFileDownloader(),
				recordingsDirectory(userData),
				trashAdapter(),
				revealAdapter(fileManager),
				audioRecorder(recordingWiring),
				mediaPath -> automationService.fireMeetingNotesReady(mediaPath.toString(),
						MeetingFiles.meetingFilesFor(mediaPath).notes().toString()),
				Clock.systemUTC());
		LOG.log(System.Logger.Level.INFO, "meeting service ready");
		return new MeetingIntegration(service);
	}

	public MeetingService service() {
		return service;
	}

	/** The ffmpeg CLI bundled by java/native/build-ffmpeg.sh. */
	public static Path ffmpegPath() {
		return NativeHelpers.nativeDir().resolve("ffmpeg/bin/ffmpeg");
	}

	/** A process runner over the platform ProcessRunner, shared with auto-captions. */
	public static ProcessRunnerPort processRunner() {
		return new ProcessRunnerAdapter();
	}

	/** whisper-cli bundled by java/native/build-whisper.sh. */
	public static Path whisperCliPath() {
		return NativeHelpers.nativeDir().resolve("whisper/whisper-cli");
	}

	@Override
	public void close() {
		service.shutdown();
	}

	// ── ports ────────────────────────────────────────────────────────────────────────────────

	private static SettingsStore settingsAdapter(AppSettingsStore settings) {
		return new SettingsStore() {
			@Override
			public @Nullable Object get(String key) {
				JsonNode node = settings.readSetting(key);
				return node == null || node.isNull() ? null : JSON.convertValue(node, Object.class);
			}

			@Override
			public void set(String key, Object value) {
				try {
					settings.writeSetting(key, JSON.valueToTree(value));
				} catch (IOException error) {
					throw new UncheckedIOException(error);
				}
			}

			@Override
			public void save() {
				// writeSetting() already persists atomically; saving is the store's own concern.
			}
		};
	}

	private static MeetingApiKeyStore apiKeyAdapter(AppSettingsStore settings) {
		return new MeetingApiKeyStore() {
			@Override
			public Optional<String> read() {
				JsonNode node = settings.readSetting(MeetingServiceKeys.API_KEY);
				if (node == null || !node.isString()) {
					return Optional.empty();
				}
				String text = node.asString("").strip();
				return text.isEmpty() ? Optional.empty() : Optional.of(text);
			}

			@Override
			public void write(String key) {
				// The plan documents this gap: Electron encrypts through safeStorage; this build
				// must not persist API keys in plaintext until a libsecret store lands.
				throw new IllegalStateException(
						"Meeting API keys need the OS keyring (safeStorage); not available in this build yet. "
								+ "Use the local Whisper pipeline or set the key through the Electron app.");
			}

			@Override
			public void clear() {
				try {
					settings.writeSetting(MeetingServiceKeys.API_KEY, JSON.valueToTree(""));
				} catch (IOException error) {
					throw new UncheckedIOException(error);
				}
			}
		};
	}

	private static MediaTools mediaTools() {
		Path ffmpeg = ffmpegPath();
		Path whisper = whisperCliPath();
		return new MediaTools() {
			@Override
			public Path ffmpeg() {
				return ffmpeg;
			}

			@Override
			public Path whisperCli() {
				return whisper;
			}
		};
	}

	private static RecordingDirectories recordingsDirectory(UserData userData) {
		RecordingsDirectory directory = new RecordingsDirectory(userData);
		return () -> {
			try {
				return directory.getRecordingsDir();
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		};
	}

	private static dev.gravaai.services.meeting.ports.Trash trashAdapter() {
		return path -> {
			Trash trash = Trash.defaultTrash();
			if (trash == null) {
				throw new UncheckedIOException(new IOException("No home trash directory is available."));
			}
			try {
				trash.trash(path);
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		};
	}

	private static Reveal revealAdapter(FileManager fileManager) {
		return path -> {
			if (fileManager != null) {
				fileManager.reveal(path);
			}
		};
	}

	private static MeetingAudioRecorder audioRecorder(
			dev.gravaai.app.recording.RecordingWiring recordingWiring) {
		return (MeetingRecordingOptions options) -> {
			CosmicRecorder.RecordingOptions nativeOptions = new CosmicRecorder.RecordingOptions(
					Boolean.TRUE.equals(options.capturesSystemAudio()),
					Boolean.TRUE.equals(options.capturesMicrophone()),
					options.microphoneDeviceId(),
					options.microphoneLabel(),
					true);
			CosmicRecorder.StartResult result = recordingWiring.recording()
					.startAudioRecording(nativeOptions);
			if (!result.success()) {
				throw new IllegalStateException(result.message() == null ? "Could not start the meeting recording."
						: result.message());
			}
		};
	}

	/** Settings keys shared with the meeting service (kept literal, like Electron). */
	private static final class MeetingServiceKeys {
		static final String API_KEY = "meetingApiKey";

		private MeetingServiceKeys() {
		}
	}

	// ── process runner port ─────────────────────────────────────────────────────────────────

	private static final class ProcessRunnerAdapter implements ProcessRunnerPort {

		@Override
		public ProcessResult run(List<String> command, Duration timeout, Map<String, String> environment) {
			ProcessRunner runner = ProcessRunner.of(command).timeout(timeout);
			if (environment != null) {
				environment.forEach(runner::environment);
			}
			try {
				dev.gravaai.platform.process.ProcessResult result = runner.run();
				return new ProcessResult(result.exitCode(), result.stdout(), result.stderr());
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while running " + command.get(0), error);
			}
		}

		@Override
		public ProcessRunnerPort.RunningProcess spawn(List<String> command,
				Map<String, String> environment, Consumer<String> stderrLines) {
			StringBuilder tail = new StringBuilder();
			Consumer<String> consumer = line -> {
				synchronized (tail) {
					tail.append(line).append('\n');
					if (tail.length() > 65_536) {
						tail.delete(0, tail.length() - 65_536);
					}
				}
				if (stderrLines != null) {
					stderrLines.accept(line);
				}
			};
			ProcessRunner runner = ProcessRunner.of(command).onStderrLine(consumer);
			if (environment != null) {
				environment.forEach(runner::environment);
			}
			try {
				return new SpawnedProcess(runner.start(), tail);
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
		}
	}

	private static final class SpawnedProcess implements ProcessRunnerPort.RunningProcess {
		private final RunningProcess process;
		private final StringBuilder tail;
		private volatile OptionalInt exit = OptionalInt.empty();

		SpawnedProcess(RunningProcess process, StringBuilder tail) {
			this.process = process;
			this.tail = tail;
		}

		@Override
		public boolean isAlive() {
			return process.isAlive();
		}

		@Override
		public OptionalInt exitCode() {
			if (process.isAlive()) {
				return OptionalInt.empty();
			}
			if (exit.isEmpty()) {
				try {
					exit = OptionalInt.of(process.waitForExit().exitCode());
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					return OptionalInt.empty();
				}
			}
			return exit;
		}

		@Override
		public boolean waitFor(Duration timeout) throws InterruptedException {
			long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
			while (process.isAlive()) {
				if (System.nanoTime() >= deadline) {
					return false;
				}
				Thread.sleep(15L);
			}
			return true;
		}

		@Override
		public void terminate(Duration grace) throws InterruptedException {
			if (!process.isAlive()) {
				return;
			}
			process.process().destroy();
			if (!waitFor(grace)) {
				process.cancel();
				process.waitForExit();
			}
		}

		@Override
		public void kill() throws InterruptedException {
			process.cancel();
			process.waitForExit();
		}

		@Override
		public String stderrTail() {
			synchronized (tail) {
				return tail.toString();
			}
		}
	}
}
