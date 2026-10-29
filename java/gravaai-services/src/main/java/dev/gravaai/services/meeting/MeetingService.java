package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import dev.gravaai.core.result.Result;
import dev.gravaai.core.result.Result.Unit;
import dev.gravaai.services.meeting.MeetingAssets.MeetingAsset;
import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.MeetingApiKeyStore;
import dev.gravaai.services.meeting.ports.MeetingAudioRecorder;
import dev.gravaai.services.meeting.ports.MeetingRecordingOptions;
import dev.gravaai.services.meeting.ports.MediaTools;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;
import dev.gravaai.services.meeting.ports.RecordingDirectories;
import dev.gravaai.services.meeting.ports.Reveal;
import dev.gravaai.services.meeting.ports.SettingsStore;
import dev.gravaai.services.meeting.ports.Trash;

/**
 * The meeting service: settings, library, pipeline jobs, assets and the open-request flow.
 * Replaces the IPC channel group in electron/ipc/register/meeting.ts (plan §5.3, Appendix A);
 * the Lead wires the concrete ports in AppContext, the UI subscribes to the events.
 */
public final class MeetingService {

	/** Which file {@code meeting-save-file} rewrites. */
	public enum FileKind {
		TRANSCRIPT("transcript"),
		NOTES("notes");

		private final String wire;

		FileKind(String wire) {
			this.wire = wire;
		}

		public @Nullable String wire() {
			return wire;
		}

		public static @Nullable FileKind fromWire(Object value) {
			if (value instanceof FileKind kind) {
				return kind;
			}
			if (!(value instanceof String text)) {
				return null;
			}
			for (FileKind kind : values()) {
				if (kind.wire.equals(text)) {
					return kind;
				}
			}
			return null;
		}
	}

	/**
	 * What the meeting view loads for one recording (meeting-get-files).
	 *
	 * @param transcript the transcript text, or null when the meeting was never transcribed
	 * @param notes the notes text, or null when the meeting was never summarized
	 */
	public record FilesView(MeetingFiles paths, @Nullable String transcript, @Nullable String notes,
			MeetingMeta meta, boolean interrupted) {
	}

	/** One row of the download manager (meeting-list-assets). */
	public record AssetStatus(String id, String label, long sizeBytes, boolean installed) {
	}

	/** A download progress event (meeting-asset-progress). */
	public record AssetProgress(String id, @Nullable Integer progress, boolean removed) {
	}

	private final MeetingAssets assets;
	private final AssetInstaller assetInstaller;
	private final MeetingSettingsService settings;
	private final MeetingMetaStore metaStore;
	private final MeetingJobs jobs;
	private final MeetingLibrary library;
	private final LlamaServer llama;
	private final Reveal reveal;
	private final MeetingAudioRecorder audioRecorder;
	private final ExecutorService asyncExecutor;
	private final ExecutorService jobExecutor;
	private final List<Runnable> openListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<AssetProgress>> assetListeners = new CopyOnWriteArrayList<>();
	private final Object openLock = new Object();
	private @Nullable Path requestedMeeting;

	private MeetingService(MeetingAssets assets, AssetInstaller assetInstaller,
			MeetingSettingsService settings, MeetingMetaStore metaStore, MeetingJobs jobs,
			MeetingLibrary library, LlamaServer llama, Reveal reveal,
			MeetingAudioRecorder audioRecorder, ExecutorService asyncExecutor,
			ExecutorService jobExecutor) {
		this.assets = assets;
		this.assetInstaller = assetInstaller;
		this.settings = settings;
		this.metaStore = metaStore;
		this.jobs = jobs;
		this.library = library;
		this.llama = llama;
		this.reveal = reveal;
		this.audioRecorder = audioRecorder;
		this.asyncExecutor = asyncExecutor;
		this.jobExecutor = jobExecutor;
	}

	/**
	 * Builds the service from the shared ports. {@code onMeetingNotesReady} is the automations
	 * hook for the "meeting-notes-ready" trigger; null disables it.
	 */
	public static MeetingService create(
			Path userDataDir,
			SettingsStore settingsStore,
			MeetingApiKeyStore apiKeyStore,
			ProcessRunnerPort processes,
			MediaTools mediaTools,
			FileDownloader downloader,
			RecordingDirectories recordings,
			Trash trash,
			Reveal reveal,
			MeetingAudioRecorder audioRecorder,
			@Nullable Consumer<Path> onMeetingNotesReady,
			Clock clock) {
		return create(userDataDir, settingsStore, apiKeyStore, processes, mediaTools, downloader,
				recordings, trash, reveal, audioRecorder, onMeetingNotesReady, clock,
				new HttpOpenAiClient());
	}

	static MeetingService create(
			Path userDataDir,
			SettingsStore settingsStore,
			MeetingApiKeyStore apiKeyStore,
			ProcessRunnerPort processes,
			MediaTools mediaTools,
			FileDownloader downloader,
			RecordingDirectories recordings,
			Trash trash,
			Reveal reveal,
			MeetingAudioRecorder audioRecorder,
			@Nullable Consumer<Path> onMeetingNotesReady,
			Clock clock,
			OpenAiClient client) {
		MeetingAssets assets = new MeetingAssets(userDataDir);
		AssetInstaller installer = new AssetInstaller(downloader, processes);
		MeetingSettingsService settingsService = new MeetingSettingsService(settingsStore,
				apiKeyStore, assets);
		MeetingMetaStore metaStore = new MeetingMetaStore();
		LlamaServer llama = new LlamaServer(assets, installer, processes);
		Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
		WhisperTranscriber whisper = new WhisperTranscriber(processes, mediaTools, tempDirectory);
		ApiTranscription apiTranscription = new ApiTranscription(client, processes, mediaTools,
				tempDirectory);
		MeetingPipeline pipeline = new MeetingPipeline(settingsService, assets, installer, whisper,
				apiTranscription, llama, client, metaStore, clock);
		ExecutorService jobExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "meeting-jobs");
			thread.setDaemon(true);
			return thread;
		});
		MeetingJobs jobs = new MeetingJobs(pipeline::run, settingsService::read, metaStore, clock,
				() -> UUID.randomUUID().toString(), jobExecutor, onMeetingNotesReady);
		MeetingLibrary library = new MeetingLibrary(recordings, metaStore, jobs, settingsService,
				trash, clock, () -> UUID.randomUUID().toString());
		ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
		return new MeetingService(assets, installer, settingsService, metaStore, jobs, library,
				llama, reveal, audioRecorder, asyncExecutor, jobExecutor);
	}

	// --- settings ---------------------------------------------------------------------------

	public MeetingSettings.View settings() {
		return settings.readView();
	}

	/** meeting-set-settings: applies one edit to the stored settings. */
	public Result<MeetingSettings.View> updateSettings(UnaryOperator<MeetingSettings> edit) {
		try {
			settings.update(edit);
			return Result.ok(settings.readView());
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/** Stores a new API key; a blank key clears it. */
	public Result<MeetingSettings.View> setApiKey(String apiKey) {
		try {
			settings.writeApiKey(apiKey);
			return Result.ok(settings.readView());
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Result<MeetingSettings.View> clearApiKey() {
		try {
			settings.clearApiKey();
			return Result.ok(settings.readView());
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	// --- library ----------------------------------------------------------------------------

	public Result<List<MeetingLibraryEntry>> listMeetings() {
		try {
			return Result.ok(library.listMeetings());
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Result<Path> importMeeting(Path sourcePath) {
		try {
			return Result.ok(library.importMeetingFile(sourcePath));
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Result<String> renameMeeting(Path mediaPath, String title) {
		try {
			return Result.ok(library.renameMeeting(mediaPath, title));
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/** meeting-delete: skips meetings that are still being processed. */
	public Result<List<Path>> deleteMeetings(List<Path> mediaPaths) {
		List<Path> deleted = new ArrayList<>();
		for (Path mediaPath : mediaPaths == null ? List.<Path>of() : mediaPaths) {
			try {
				if (jobs.findActive(mediaPath).isPresent()) {
					continue;
				}
				library.trashMeeting(mediaPath);
				deleted.add(mediaPath);
			} catch (RuntimeException error) {
				System.getLogger("gravaai.meeting").log(System.Logger.Level.WARNING,
						"Could not delete " + mediaPath, error);
			}
		}
		return Result.ok(List.copyOf(deleted));
	}

	public Result<Unit> revealMeeting(Path mediaPath) {
		try {
			reveal.revealInFolder(mediaPath);
			return Result.okUnit();
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	// --- files and jobs ---------------------------------------------------------------------

	public Result<FilesView> getFiles(Path mediaPath) {
		try {
			MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);
			return Result.ok(new FilesView(
					files,
					readOptional(files.transcript()),
					readOptional(files.notes()),
					metaStore.read(mediaPath),
					jobs.isInterrupted(mediaPath)));
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/**
	 * Registers a saved meeting recording (the audio-only capture that keeps per-speaker tracks) so
	 * the library lists it; the Electron app does this from the recording-saved hook.
	 */
	public Result<Unit> registerRecording(Path mediaPath, boolean hasVideo) {
		try {
			library.registerMeetingRecording(mediaPath, hasVideo);
			return Result.okUnit();
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Result<Unit> saveFile(Path mediaPath, FileKind kind, String content) {
		try {
			if (kind == null) {
				throw new IllegalStateException("Unknown file.");
			}
			if (content == null) {
				throw new IllegalStateException("Missing content.");
			}
			MeetingFiles files = MeetingFiles.meetingFilesFor(mediaPath);
			Path target = kind == FileKind.TRANSCRIPT ? files.transcript() : files.notes();
			Files.writeString(target, content);
			return Result.okUnit();
		} catch (IOException error) {
			return Result.err(error.getMessage() == null ? String.valueOf(error)
					: error.getMessage());
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Result<MeetingJob> runJob(Path mediaPath, MeetingJobMode mode) {
		try {
			if (mode == null) {
				throw new IllegalStateException("Unknown job mode.");
			}
			return Result.ok(jobs.enqueue(mediaPath, mode));
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public boolean cancelJob(String jobId) {
		return jobs.cancel(jobId);
	}

	public List<MeetingJob> listJobs() {
		return jobs.list();
	}

	public Subscription onJobUpdate(Consumer<MeetingJob> listener) {
		return jobs.onUpdate(listener);
	}

	// --- assets -----------------------------------------------------------------------------

	public List<AssetStatus> listAssets() {
		List<AssetStatus> statuses = new ArrayList<>();
		for (MeetingAsset asset : assets.all()) {
			statuses.add(new AssetStatus(asset.id(), asset.label(), asset.sizeBytes(),
					assetInstaller.isInstalled(asset)));
		}
		return List.copyOf(statuses);
	}

	/** meeting-download-asset: runs off the FX thread; progress arrives via the event. */
	public CompletableFuture<Result<Unit>> downloadAsset(String assetId) {
		Optional<MeetingAsset> asset = assets.find(assetId);
		if (asset.isEmpty()) {
			return CompletableFuture.completedFuture(Result.err("Unknown download."));
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				assetInstaller.ensure(asset.get(),
						percent -> publishAsset(new AssetProgress(assetId, percent, false)));
				return Result.okUnit();
			} catch (RuntimeException error) {
				return Result.<Unit>err(message(error));
			}
		}, asyncExecutor);
	}

	public Result<Unit> removeAsset(String assetId) {
		Optional<MeetingAsset> asset = assets.find(assetId);
		if (asset.isEmpty()) {
			return Result.err("Unknown download.");
		}
		try {
			assetInstaller.remove(asset.get());
			publishAsset(new AssetProgress(assetId, 0, true));
			return Result.okUnit();
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	public Subscription onAssetProgress(Consumer<AssetProgress> listener) {
		assetListeners.add(listener);
		return () -> assetListeners.remove(listener);
	}

	private void publishAsset(AssetProgress progress) {
		for (Consumer<AssetProgress> listener : assetListeners) {
			listener.accept(progress);
		}
	}

	// --- open request -----------------------------------------------------------------------

	/**
	 * The HUD and the editor window can be different windows, so the meeting to open after an
	 * audio-only recording waits here until the editor takes it.
	 */
	public void requestOpen(Path mediaPath) {
		synchronized (openLock) {
			requestedMeeting = mediaPath;
		}
		for (Runnable listener : openListeners) {
			listener.run();
		}
	}

	public Optional<Path> takeOpenRequest() {
		synchronized (openLock) {
			Path requested = requestedMeeting;
			requestedMeeting = null;
			return Optional.ofNullable(requested);
		}
	}

	public Subscription onOpenRequested(Runnable listener) {
		openListeners.add(listener);
		return () -> openListeners.remove(listener);
	}

	// --- recording and lifecycle ------------------------------------------------------------

	/** meeting-start-audio-recording: audio-only on Linux, keeping the per-speaker tracks. */
	public Result<Unit> startAudioRecording(MeetingRecordingOptions options) {
		try {
			audioRecorder.start(options);
			return Result.okUnit();
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/** before-quit: stop llama-server and the executors. */
	public void shutdown() {
		llama.stop();
		jobExecutor.shutdownNow();
		asyncExecutor.shutdownNow();
	}

	private static @Nullable String readOptional(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException error) {
			return null;
		}
	}

	private static String message(RuntimeException error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}
}
