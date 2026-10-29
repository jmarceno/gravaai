package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSequence;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.RecordingImportService;
import dev.gravaai.services.recording.RecordingImportService.RecordingWebcamSource;
import dev.gravaai.ui.editor.EditorViewActions;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;
import javafx.application.Platform;

/**
 * Ports {@code useRecordingLibrary.addToTimeline}: appends library recordings to the project's
 * source video one at a time (normalize → concat → mix → telemetry/webcam merge), ripples the
 * timeline regions like React's state setters and swaps the editor's source media at the end.
 *
 * <p>React semantics kept: a cancel between imports still commits the completed ones; a failure
 * commits nothing and deletes every pending output; zero completions is a silent return. The
 * regions ripple explicitly here and are written pre-rippled in one {@link EditorStore}
 * {@code applyImportBatch} command — the packed sequence makes the persistence normalizer treat
 * the sequence as unchanged, so it does not ripple a second time.
 */
public final class RecordingImportController {

	/** The recording service seams ({@code window.electronAPI} import calls). */
	public interface Library {

		/** {@code importRecording} with the cancellation token polled between ffmpeg steps. */
		RecordingImportService.ImportResult importRecording(String currentPath, String recordingPath,
				@Nullable RecordingWebcamSource currentWebcam, BooleanSupplier cancelled)
				throws IOException;

		/** {@code discardRecordingImport}: deletes every file one import produced. */
		void discardRecordingImport(String output) throws IOException;

		/** {@code getCursorTelemetry}: raw cursor samples of one video. */
		List<CursorTelemetryPoint> getCursorTelemetry(String videoPath);
	}

	/** The editor session seams the import commit needs. */
	public interface Session {

		/** The project's source video path ({@code project.videoSourcePath}). */
		String videoPath();

		/** The editor store (state reads, id counters, the batch command). */
		EditorStore store();

		/** The current project document (the commit's base). */
		ProjectDocument document();

		/** Swaps the source media and re-seeks to {@code seekSeconds} (off-FX safe). */
		void replaceSourceMedia(ProjectDocument document, double seekSeconds);

		/** {@code videoPlaybackRef.current?.pause()}. */
		void pausePlayback();

		/** FX-thread hook after the media swapped (preview path, cursor track, size cache). */
		void onMediaReplaced();
	}

	private final Library library;
	private final Session session;
	private final BooleanSupplier autoZoomsEnabled;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean cancelled = new AtomicBoolean();

	public RecordingImportController(Library library, Session session, BooleanSupplier autoZoomsEnabled) {
		this.library = library;
		this.session = session;
		this.autoZoomsEnabled = autoZoomsEnabled;
	}

	/** {@code cancelRecordingImport}: flips the token the running import polls. */
	public void cancel() {
		cancelled.set(true);
	}

	/**
	 * Runs the whole import on a virtual thread and reports exactly one result through
	 * {@code done} on the FX thread ({@code null} for React's silent returns).
	 */
	public void addToTimeline(List<String> paths, @Nullable Integer index,
			Consumer<EditorViewActions.ClipImportResult> done) {
		if (!running.compareAndSet(false, true)) {
			// Unreachable through the panel (it guards on the FX thread); React's lock return.
			return;
		}
		cancelled.set(false);
		Thread.ofVirtual().name("gravaai-recording-import").start(() -> {
			EditorViewActions.ClipImportResult result;
			try {
				result = run(paths, index);
			} catch (RuntimeException failure) {
				result = new EditorViewActions.ClipImportResult(false, 0,
						message(failure), cancelled.get());
			} finally {
				running.set(false);
			}
			EditorViewActions.ClipImportResult reported = result;
			Platform.runLater(() -> done.accept(reported));
		});
	}

	private EditorViewActions.@Nullable ClipImportResult run(List<String> paths,
			@Nullable Integer index) {
		String source = session.videoPath();
		if (source == null || source.isBlank()) {
			return null;
		}
		session.pausePlayback();

		Set<String> unique = new LinkedHashSet<>(paths);
		EditorStore store = session.store();
		var state = store.state();
		List<ClipRegion> before = Clips.sortClipRegions(state.clipRegions());
		List<ClipRegion> sequence = before;
		RecordingImportService.ImportResult media = null;
		WebcamOverlaySettings webcam = state.webcam();
		String sourcePath = source;
		int insertAt = Math.min(before.size(), Math.max(0, index == null ? before.size() : index));
		List<ZoomRegion> addedZooms = new ArrayList<>();
		List<String> clipIds = new ArrayList<>(before.stream().map(ClipRegion::id).toList());
		List<String> zoomIds = new ArrayList<>(state.zoomRegions().stream().map(ZoomRegion::id).toList());
		List<String> pending = new ArrayList<>();
		int completed = 0;
		String failure = null;

		try {
			for (String path : unique) {
				if (cancelled.get()) {
					break;
				}
				String webcamSourcePath = webcam == null ? null : webcam.sourcePath();
				RecordingWebcamSource webcamSource = webcamSourcePath == null ? null
						: new RecordingWebcamSource(webcamSourcePath, webcam.timeOffsetMs(),
								serviceRanges(webcam));
				RecordingImportService.ImportResult imported;
				try {
					imported = library.importRecording(sourcePath, path, webcamSource, cancelled::get);
				} catch (IOException error) {
					if (cancelled.get()) {
						break;
					}
					throw error;
				}
				completed++;
				if (!Objects.equals(session.videoPath(), source)) {
					throw new IllegalStateException(
							"The project changed while importing. Add the recordings again.");
				}
				media = imported;
				sourcePath = media.path();
				pending.add(media.path());
				if (media.webcam() != null) {
					webcam = composeWebcam(webcam, media.webcam());
				}
				String id = store.nextId("clip", clipIds);
				clipIds.add(id);
				List<ClipRegion> next = new ArrayList<>(sequence.size() + 1);
				for (ClipRegion clip : sequence) {
					next.add(new ClipRegion(clip.id(), clip.startMs(), clip.endMs(), clip.sourceStartMs(),
							clip.sourceMinMs() == null ? 0L : clip.sourceMinMs(),
							clip.sourceMaxMs() == null ? media.sourceStartMs() : clip.sourceMaxMs(),
							clip.speed(), clip.muted(), clip.showSourceAudio(), clip.cameraLayout(),
							clip.transitionOut()));
				}
				next.add(insertAt++, new ClipRegion(id, 0L, media.durationMs(), media.sourceStartMs(),
						media.sourceStartMs(), media.sourceStartMs() + media.durationMs(), 1.0,
						null, null, null, null));
				sequence = ClipSequence.packClipSequence(next);
				if (autoZoomsEnabled.getAsBoolean()) {
					addZoomSuggestions(media, id, sequence, zoomIds, addedZooms);
				}
			}
		} catch (IOException | RuntimeException error) {
			failure = message(error);
		}

		if (media == null && failure == null) {
			// Cancelled before the first import completed: React returns before any commit.
			return null;
		}
		if (failure != null) {
			discardAll(pending);
			return new EditorViewActions.ClipImportResult(false, completed, failure, cancelled.get());
		}
		if (media == null) {
			// Every path was cancelled before the first import committed (see above); nothing to do.
			return null;
		}

		String selectedClipId = clipIds.get(clipIds.size() - 1);
		long clipStartMs = sequence.stream()
				.filter(clip -> clip.id().equals(selectedClipId))
				.findFirst()
				.map(ClipRegion::startMs)
				.orElse(0L);

		// Ripple the retained regions onto the new sequence (React's setter wrappers) and write
		// everything as one batch; the packed sequence keeps the normalizer from re-rippling.
		List<ZoomRegion> zooms = new ArrayList<>(ClipSequence.rippleRegions(
				state.zoomRegions(), before, sequence, ClipSequence.ZOOM_SPAN));
		zooms.addAll(addedZooms);
		List<AnnotationRegion> annotations = ClipSequence.rippleRegions(
				state.annotationRegions(), before, sequence, ClipSequence.ANNOTATION_SPAN);
		List<AudioRegion> audios = ClipSequence.rippleRegionAnchors(
				state.audioRegions(), before, sequence, ClipSequence.AUDIO_SPAN);
		WebcamOverlaySettings committedWebcam = media.webcam() != null ? webcam : null;
		List<ClipRegion> committedSequence = sequence;

		store.applyImportBatch(json -> {
			json.set("clipRegions", ProjectDocument.writeClipRegions(committedSequence));
			json.set("zoomRegions", ProjectDocument.writeZoomRegions(zooms));
			json.set("annotationRegions", ProjectDocument.writeAnnotationRegions(annotations));
			json.set("audioRegions", ProjectDocument.writeAudioRegions(audios));
			json.putNull("autoFullTrackClipId");
			json.putNull("autoFullTrackClipEndMs");
			if (committedWebcam != null) {
				json.set("webcam", ProjectDocument.writeWebcam(committedWebcam));
			}
		}, selectedClipId);

		ProjectDocument commit = session.document()
				.withEditor(store.state())
				.withVideoPath(media.path());
		session.replaceSourceMedia(commit, clipStartMs / 1000.0);
		session.onMediaReplaced();

		pending.remove(media.path());
		discardAll(pending);
		return new EditorViewActions.ClipImportResult(true, completed, null, cancelled.get());
	}

	/** Cursor-based zoom suggestions for one imported recording (React's per-iteration block). */
	private void addZoomSuggestions(RecordingImportService.ImportResult media, String clipId,
			List<ClipRegion> sequence, List<String> zoomIds, List<ZoomRegion> addedZooms) {
		long start = media.sourceStartMs();
		long duration = media.durationMs();
		long offset = sequence.stream()
				.filter(clip -> clip.id().equals(clipId))
				.findFirst()
				.map(ClipRegion::startMs)
				.orElse(0L);
		List<ZoomSuggestionUtils.CursorTelemetryPoint> telemetry = new ArrayList<>();
		for (CursorTelemetryPoint point : library.getCursorTelemetry(media.path())) {
			if (point.timeMs() >= start && point.timeMs() < start + duration) {
				telemetry.add(new ZoomSuggestionUtils.CursorTelemetryPoint(
						point.timeMs() - start, point.cx(), point.cy(),
						point.interactionType() == null ? null : point.interactionType().wire(),
						point.cursorType() == null ? null : point.cursorType().wire()));
			}
		}
		List<ZoomSuggestionUtils.SuggestedZoomRegion> suggestions = ZoomSuggestionUtils
				.buildInteractionZoomSuggestions(new ZoomSuggestionUtils.SuggestionParams(
						telemetry, duration, Math.min(1000, duration), List.of()))
				.suggestions();
		for (ZoomSuggestionUtils.SuggestedZoomRegion suggestion : suggestions) {
			String id = session.store().nextId("zoom", zoomIds);
			zoomIds.add(id);
			addedZooms.add(new ZoomRegion(id,
					offset + Math.round(suggestion.startMs()),
					offset + Math.round(suggestion.endMs()),
					ZoomDepth.DEFAULT_AUTO,
					suggestion.focus().clampToDepth(ZoomDepth.DEFAULT_AUTO),
					ZoomMode.AUTO));
		}
	}

	/**
	 * {@code webcam = {...webcam, ...media.webcam, enabled: true}}: the current settings with the
	 * imported webcam source/time offset/visible ranges replaced and the overlay enabled.
	 */
	private static WebcamOverlaySettings composeWebcam(WebcamOverlaySettings current,
			RecordingWebcamSource imported) {
		WebcamOverlaySettings base = current == null ? WebcamOverlaySettings.defaults() : current;
		List<WebcamOverlaySettings.TimeRange> ranges = null;
		if (imported.visibleRanges() != null) {
			ranges = imported.visibleRanges().stream()
					.map(range -> new WebcamOverlaySettings.TimeRange(range.startMs(), range.endMs()))
					.toList();
		}
		return new WebcamOverlaySettings(ranges, true, imported.sourcePath(), imported.timeOffsetMs(),
				base.mirror(), base.cropRegion(), base.corner(), base.positionPreset(),
				base.positionX(), base.positionY(), base.size(), base.width(), base.height(),
				base.reactToZoom(), base.roundness(), base.cornerRadius(), base.shadow(),
				base.margin());
	}

	private static @Nullable List<RecordingImportService.VisibleRange> serviceRanges(
			WebcamOverlaySettings webcam) {
		if (webcam.visibleRanges() == null) {
			return null;
		}
		List<RecordingImportService.VisibleRange> ranges = new ArrayList<>();
		for (WebcamOverlaySettings.TimeRange range : webcam.visibleRanges()) {
			ranges.add(new RecordingImportService.VisibleRange(range.startMs(), range.endMs()));
		}
		return ranges;
	}

	/** Best-effort cleanup of superseded/failed import outputs ({@code finishRecordingImport}). */
	private void discardAll(List<String> pending) {
		for (String output : pending) {
			try {
				library.discardRecordingImport(output);
			} catch (IOException | RuntimeException error) {
				System.getLogger("gravaai.editor").log(System.Logger.Level.WARNING,
						"Could not clean up temporary imports: " + error);
			}
		}
		pending.clear();
	}

	private static String message(Throwable error) {
		String text = error.getMessage();
		return text == null || text.isBlank() ? error.toString() : text;
	}
}
