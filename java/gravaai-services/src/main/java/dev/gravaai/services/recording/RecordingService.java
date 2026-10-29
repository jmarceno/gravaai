package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.services.recording.CosmicRecorder.RecordingOptions;
import dev.gravaai.services.recording.CosmicRecorder.StartResult;
import dev.gravaai.services.recording.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.MeetingInfo;
import dev.gravaai.services.recording.CosmicSession.RecoverResult;
import dev.gravaai.services.recording.CosmicSession.RecoverableRecording;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceArea;
import tools.jackson.databind.JsonNode;

/**
 * The recording service (Appendix A {@code RecordingService}), ported from the Linux part of
 * {@code electron/ipc/register/recording.ts} and the recording/cursor/keyboard modules (plan §5.6).
 *
 * <p>It owns the recording state machine ({@code recording-state-changed},
 * {@code recording-session-changed}, {@code recording-interrupted},
 * {@code recording-stop-requested}), crash recovery, the recordings library and the telemetry
 * sidecars. UI work (HUD hiding, area border, cursor restore) is injected through
 * {@link HudHooks}; the app wires the JavaFX implementation.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class RecordingService {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** UI hooks the recording flow needs; implemented by {@code dev.gravaai.ui} and the app. */
	public interface HudHooks {

		/** Called before the capture helper grabs the screen, so the HUD is not recorded. */
		void hideForCapture();

		/** Restores the HUD when a recording fails to start. */
		void restoreAfterCapture();

		/** Shows the click-through border around an area recording. */
		void showAreaRecordingBorder(CaptureArea area);

		void hideAreaRecordingBorder();

		/** Shows the real cursor again when recording stops (cursorHider.ts). */
		void showCursor();
	}

	private final Supplier<Path> recordingsDir;
	private final AppSettingsStore settings;
	private final CosmicRecorder cosmic;
	private final RecordingLibrary library;
	private final CursorCaptureSession cursorSession;
	private final RecordingEvents events;
	private final HudHooks hudHooks;
	private final Supplier<SelectedSource> selectedSource;
	private final Consumer<StopResult> recordingSaved;

	private final List<Runnable> stopFromTrayListeners = new CopyOnWriteArrayList<>();

	private volatile @Nullable String currentVideoPath;
	private volatile RecordingEvents.@Nullable RecordingSession currentRecordingSession;
	private @Nullable RecordingImportService importService;
	private @Nullable RecordingThumbnails thumbnails;

	public RecordingService(
			Supplier<Path> recordingsDir,
			AppSettingsStore settings,
			CosmicRecorder cosmic,
			RecordingLibrary library,
			CursorCaptureSession cursorSession,
			RecordingEvents events,
			HudHooks hudHooks,
			Supplier<SelectedSource> selectedSource,
			Consumer<StopResult> recordingSaved) {
		this.recordingsDir = recordingsDir;
		this.settings = settings;
		this.cosmic = cosmic;
		this.library = library;
		this.cursorSession = cursorSession;
		this.events = events;
		this.hudHooks = hudHooks;
		this.selectedSource = selectedSource;
		this.recordingSaved = recordingSaved;
	}

	// ── Capture lifecycle ───────────────────────────────────────────────────

	/** {@code start-native-screen-recording}. */
	public StartResult startNativeScreenRecording(SelectedSource source, RecordingOptions options) {
		// Protect the HUD at the capture boundary, before the renderer publishes recording state.
		hudHooks.hideForCapture();
		StartResult result = cosmic.startCosmicRecording(source, options);
		if (!result.success()) {
			hudHooks.restoreAfterCapture();
		}
		return result;
	}

	/** Wall clock of the active screen video's first frame, or null when nothing records. */
	public Long activeScreenStartedAtMs() {
		return cosmic.getActiveCosmicStartedAtMs();
	}

	/** {@code start-native-*}: audio-only meeting recording (used by the meeting service). */
	public StartResult startAudioRecording(RecordingOptions options) {
		return cosmic.startCosmicAudioRecording(options);
	}

	/** {@code stop-native-screen-recording}. */
	public StopResult stopNativeScreenRecording() {
		StopResult result = cosmic.stopCosmicRecording();
		if (result.success() && result.path() != null) {
			recordingSaved.accept(result);
		}
		return result;
	}

	/** {@code pause-native-screen-recording}. */
	public StartResult pauseNativeScreenRecording() {
		return cosmic.pauseCosmicRecording();
	}

	/** {@code resume-native-screen-recording}. */
	public StartResult resumeNativeScreenRecording() {
		return cosmic.resumeCosmicRecording();
	}

	// ── Crash recovery (Appendix A list-recoverable-recordings, recover, discard) ──

	public List<RecoverableRecording> listRecoverableRecordings() {
		// Nothing is offered while recording: its own files are in the same folder.
		if (cosmic.isCosmicRecordingActive()) {
			return List.of();
		}
		return CosmicSession.listRecoverableCosmicRecordings(
				recordingsDir.get(), cosmic.getActiveCosmicTimestamp());
	}

	public RecoverResult recoverRecording(String id) {
		Path manifestPath = CosmicSession.recoverableManifestPath(recordingsDir.get(), id);
		if (manifestPath == null) {
			return RecoverResult.err("Unknown recording.");
		}
		Long activeTimestamp = cosmic.getActiveCosmicTimestamp();
		if (activeTimestamp != null && String.valueOf(activeTimestamp).equals(id)) {
			return RecoverResult.err("That recording is still in progress.");
		}
		RecoverResult result = CosmicSession.recoverCosmicRecording(manifestPath, cosmic);
		// A recovered recording is a saved recording, so the same automations run.
		if (result.success() && result.path() != null) {
			recordingSaved.accept(new StopResult(
					true,
					result.path(),
					null,
					result.meeting() != null ? result.meeting() : null));
		}
		return result;
	}

	public void discardRecoverableRecording(String id) {
		Path manifestPath = CosmicSession.recoverableManifestPath(recordingsDir.get(), id);
		if (manifestPath == null) {
			return;
		}
		Long activeTimestamp = cosmic.getActiveCosmicTimestamp();
		if (activeTimestamp != null && String.valueOf(activeTimestamp).equals(id)) {
			return;
		}
		CosmicSession.discardRecoverableCosmicRecording(manifestPath);
	}

	// ── Recording state machine (set-recording-state, recording-state-changed) ──

	/**
	 * Port of {@code set-recording-state}: starts or stops cursor/key/position sampling and shows
	 * the area border, then publishes the state change to the UI.
	 */
	public void setRecordingState(boolean recording) {
		if (recording) {
			cursorSession.start();
		} else {
			cursorSession.stop();
			hudHooks.showCursor();
			// setHudOverlayRecordingActive(false): the HUD hidden for the capture comes back.
			hudHooks.restoreAfterCapture();
		}
		SelectedSource source = selectedSource.get();
		CaptureArea recordingArea = recording && source != null ? SourceArea.getSourceArea(source) : null;
		if (recordingArea != null) {
			hudHooks.showAreaRecordingBorder(recordingArea);
		} else {
			hudHooks.hideAreaRecordingBorder();
		}
		String sourceName = source == null ? "Screen" : source.name();
		events.emitRecordingStateChanged(recording, sourceName);
	}

	/** {@code pause-cursor-capture}: drops samples after the renderer's pause boundary. */
	public void pauseCursorCapture(long pausedAtMs) {
		cursorSession.pause(pausedAtMs);
	}

	/** {@code resume-cursor-capture}: resumes the cursor clock and takes one sample. */
	public void resumeCursorCapture(long resumedAtMs) {
		cursorSession.resume(resumedAtMs);
	}

	// ── Telemetry sidecars ─────────────────────────────────────────────────

	/** {@code get-cursor-telemetry}; a recording without a sidecar has no samples. */
	public List<CursorTelemetryPoint> getCursorTelemetry(String videoPath) {
		String target = videoPath != null ? videoPath : currentVideoPath;
		if (target == null) {
			return List.of();
		}
		try {
			String content = Files.readString(
					Path.of(RecordingPaths.getTelemetryPathForVideo(target)), StandardCharsets.UTF_8);
			return CursorTelemetry.normalizeCursorTelemetrySamples(Json.parseJsonWithByteOrderMark(content));
		} catch (IOException error) {
			return List.of();
		}
	}

	/** {@code set-cursor-telemetry}; an empty list removes the sidecar. */
	public List<CursorTelemetryPoint> setCursorTelemetry(String videoPath, List<CursorTelemetryPoint> samples)
			throws IOException {
		String target = videoPath != null ? videoPath : currentVideoPath;
		if (target == null) {
			throw new IllegalStateException("No video path available for cursor telemetry");
		}
		return CursorTelemetry.writeCursorTelemetry(target, samples);
	}

	/** {@code get-keystroke-telemetry}; a recording without presses returns an empty list. */
	public List<KeystrokeEvent> getKeystrokeTelemetry(String videoPath) {
		String target = videoPath != null ? videoPath : currentVideoPath;
		if (target == null) {
			return List.of();
		}
		try {
			return KeystrokeTelemetry.readKeystrokeTelemetry(target);
		} catch (IOException error) {
			return List.of();
		}
	}

	// ── Settings ───────────────────────────────────────────────────────────

	/** {@code get-mic-noise-reduction}. */
	public MicNoiseReduction getMicNoiseReduction() {
		JsonNode value = settings.readSetting(MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING);
		return MicNoiseReductionFilters.normalizeMicNoiseReduction(
				value != null && value.isString() ? value.asString() : null);
	}

	/** {@code set-mic-noise-reduction}. */
	public void setMicNoiseReduction(MicNoiseReduction level) throws IOException {
		settings.writeSetting(
				MicNoiseReductionFilters.MIC_NOISE_REDUCTION_SETTING,
				Json.nodeFactory().stringNode((level == null ? MicNoiseReduction.DEFAULT : level).wire()));
	}

	/** {@code get-keystroke-capture-mode}. */
	public KeystrokeCaptureMode getKeystrokeCaptureMode() {
		JsonNode value = settings.readSetting(KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING);
		return KeystrokeCapture.normalizeKeystrokeCaptureMode(
				value != null && value.isString() ? value.asString() : null);
	}

	/** {@code set-keystroke-capture-mode}. */
	public void setKeystrokeCaptureMode(KeystrokeCaptureMode mode) throws IOException {
		settings.writeSetting(
				KeystrokeCapture.KEYSTROKE_CAPTURE_MODE_SETTING,
				Json.nodeFactory().stringNode((mode == null ? KeystrokeCaptureMode.OFF : mode).wire()));
	}

	// ── Video/session state (Appendix A get/set-current-video-path, get/set-current-recording-session) ──

	public String getCurrentVideoPath() {
		return currentVideoPath;
	}

	public void setCurrentVideoPath(String videoPath) {
		currentVideoPath = videoPath;
	}

	public void clearCurrentVideoPath() {
		currentVideoPath = null;
	}

	public RecordingEvents.@Nullable RecordingSession getCurrentRecordingSession() {
		return currentRecordingSession;
	}

	public void setCurrentRecordingSession(RecordingEvents.RecordingSession session) {
		currentRecordingSession = session;
		events.emitRecordingSessionChanged(session);
	}

	/** {@code get-recorded-video-path}: newest non-empty screen recording in the recordings folder. */
	public @Nullable String getRecordedVideoPath() {
		Path dir = recordingsDir.get();
		List<Path> candidates = new ArrayList<>();
		try (var entries = Files.list(dir)) {
			entries.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString()
							.matches("^recording-\\d+\\.(webm|mov|mp4)$"))
					.filter(path -> {
						try {
							return Files.size(path) > 0;
						} catch (IOException error) {
							return false;
						}
					})
					.forEach(candidates::add);
		} catch (IOException error) {
			return null;
		}
		return candidates.stream()
				.max(java.util.Comparator.comparingLong(path -> {
					try {
						return Files.getLastModifiedTime(path).toMillis();
					} catch (IOException error) {
						return 0L;
					}
				}))
				.map(Path::toString)
				.orElse(null);
	}

	public List<RecordingLibrary.RecordingLibraryEntry> listRecordings() throws IOException {
		return listRecordings(false);
	}

	/** {@code list-recordings}: app recordings, or the raw library including camera/audio sources. */
	public List<RecordingLibrary.RecordingLibraryEntry> listRecordings(boolean includeSources) throws IOException {
		return library.listRecordings(includeSources);
	}

	/** {@code set-recordings-removed}: stages removals for Undo and sends them to Trash. */
	public void setRecordingsRemoved(List<String> paths, boolean removed) throws IOException {
		library.setRecordingsRemoved(paths, removed);
	}

	/** {@code get-recording-thumbnail}: decoded lazily through {@link RecordingThumbnails}. */
	public java.util.concurrent.CompletableFuture<String> getRecordingThumbnail(String videoPath) {
		if (thumbnails == null) {
			return java.util.concurrent.CompletableFuture.failedFuture(
					new IllegalStateException("Recording thumbnails are not wired"));
		}
		return thumbnails.getRecordingThumbnail(videoPath);
	}

	/** Wiring point for {@link RecordingImportService} (constructed by {@code AppContext}). */
	public void setImportService(RecordingImportService importService) {
		this.importService = importService;
	}

	/** Wiring point for {@link RecordingThumbnails} (constructed by {@code AppContext}). */
	public void setThumbnails(RecordingThumbnails thumbnails) {
		this.thumbnails = thumbnails;
	}

	/** {@code import-recording}: appends one library recording to the current video. */
	public RecordingImportService.ImportResult importRecording(
			String currentPath, String recordingPath,
			RecordingImportService.RecordingWebcamSource currentWebcam) throws IOException {
		return importRecording(currentPath, recordingPath, currentWebcam, null);
	}

	/** {@code import-recording} with a cancellation token (the Clips panel's Cancel button). */
	public RecordingImportService.ImportResult importRecording(
			String currentPath, String recordingPath,
			RecordingImportService.RecordingWebcamSource currentWebcam,
			BooleanSupplier cancelled) throws IOException {
		if (importService == null) {
			throw new IllegalStateException("Recording import is not wired");
		}
		return importService.importRecording(currentPath, recordingPath, currentWebcam, cancelled);
	}

	/** {@code cancel-recording-import}: removes a partial import's outputs. */
	public void discardRecordingImport(String output) throws IOException {
		if (importService == null) {
			throw new IllegalStateException("Recording import is not wired");
		}
		importService.discardRecordingImport(output);
	}

	// ── Tray ───────────────────────────────────────────────────────────────

	/** The tray's "Stop recording" item; listeners save the recording the normal way. */
	public AutoCloseable onStopRecordingFromTray(Runnable listener) {
		stopFromTrayListeners.add(listener);
		return () -> stopFromTrayListeners.remove(listener);
	}

	/** Port of the {@code stop-recording-from-tray} push channel. */
	public void emitStopRecordingFromTray() {
		for (Runnable listener : stopFromTrayListeners) {
			listener.run();
		}
	}

	/** Stops the active recording from the tray, if any. */
	public StopResult stopRecordingFromTray() {
		emitStopRecordingFromTray();
		return stopNativeScreenRecording();
	}

	// ── Crash/interruption events for other services ───────────────────────

	public RecordingEvents getEvents() {
		return events;
	}

	/** True while a recording (screen or audio-only) is running; for HUD/overlay wiring. */
	public boolean isRecordingActive() {
		return cosmic.isCosmicRecordingActive();
	}

	public CosmicRecorder getCosmicRecorder() {
		return cosmic;
	}

	public CursorCaptureSession getCursorSession() {
		return cursorSession;
	}
}
