package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.app.AppBinaries;
import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AudioCommands;
import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AtomicProjectSave;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClickSoundSettings;
import dev.gravaai.core.project.KeySoundSettings;
import dev.gravaai.core.project.KeystrokeElement;
import dev.gravaai.core.project.KeystrokeGrouping;
import dev.gravaai.core.project.KeystrokeOverlaySettings;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectFileNaming;
import dev.gravaai.core.project.SourceAudioSettings;
import dev.gravaai.core.project.SourceAudioTrackSettings;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.result.Result;
import dev.gravaai.media.audio.AudioPeaks;
import dev.gravaai.media.audio.ClickSoundHits;
import dev.gravaai.media.audio.KeySoundHits;
import dev.gravaai.media.audio.ClickSounds;
import dev.gravaai.media.playback.AudioMediaClock;
import dev.gravaai.media.playback.ClickSoundTrack;
import dev.gravaai.media.playback.DecoderFrameSourceProvider;
import dev.gravaai.media.playback.PlaybackAudioProducer;
import dev.gravaai.media.playback.PlaybackAudioTrack;
import dev.gravaai.media.playback.PreviewAudioTrack;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.media.playback.PlaybackEngine;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackMediaLoader;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.ProjectMediaSources;
import dev.gravaai.services.recording.InkTelemetry;
import dev.gravaai.services.recording.PushToTalkTelemetry;
import dev.gravaai.services.recording.KeystrokePresses;
import dev.gravaai.services.recording.KeystrokeTelemetry;
import dev.gravaai.services.recording.KeystrokeTypes;
import dev.gravaai.ui.editor.preview.PlaybackControllerPort;
import dev.gravaai.ui.editor.timeline.InkMark;
import dev.gravaai.ui.editor.timeline.MicMuteMark;
import dev.gravaai.ui.editor.timeline.PlaybackSeek;
import dev.gravaai.ui.editor.timeline.CachingWaveformProvider;
import dev.gravaai.ui.editor.timeline.StoreTimelineCommands;
import dev.gravaai.ui.editor.timeline.StoreTimelineDataSource;
import dev.gravaai.ui.editor.timeline.TimelineCommands;
import dev.gravaai.ui.editor.timeline.TimelineDataSource;
import dev.gravaai.ui.editor.timeline.TimelineSelection;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTracks;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils;
import dev.gravaai.ui.editor.timeline.core.Span;
import tools.jackson.databind.JsonNode;

/**
 * One open project in the Java editor (plan P2.10, §5.11/§5.12).
 *
 * <p>The session owns everything the editor window needs and nothing that is UI: the
 * {@link ProjectDocument} (raw tree + editor state), the {@link EditorStore} (undo/redo, commands,
 * selections), the playback engine and the two timeline adapters. It is JavaFX-free so it can be
 * tested directly; {@code EditorWindow} builds the view on top.
 */
public final class EditorSession implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.editor");

	/**
	 * The debounce window between an edit and the autosave write (plan §5.11, sweep gap A2). Two
	 * seconds is far below what the user notices and keeps a project file — which can carry a whole
	 * transcript — from being rewritten on every drag. Nothing is written while the editor is idle,
	 * and leaving the editor flushes, so a crash only loses the last two seconds of edits.
	 */
	public static final long AUTOSAVE_DEBOUNCE_MS = 2_000L;

	/** The project file; {@code null} for an unsaved recording until it is saved. */
	private @Nullable Path projectPath;
	private ProjectDocument document;
	private ProjectDocument mediaSource;
	private final EditorStore store;
	private final dev.gravaai.services.elements.ElementDiagnostics elementDiagnostics =
			new dev.gravaai.services.elements.ElementDiagnostics();
	private final java.util.List<AutoCloseable> ownedResources =
			new java.util.concurrent.CopyOnWriteArrayList<>();
	private final PlaybackEngine engine;
	private final PlaybackControllerPort playback;
	private final AtomicBoolean dirty = new AtomicBoolean();
	/** Edits counted since the session opened, so a write only clears dirtiness it actually wrote. */
	private final AtomicLong changeSequence = new AtomicLong();
	/** Installed by the window to turn debounced autosave on; {@code null} keeps it off. */
	private volatile @Nullable AutosaveHooks autosaveHooks;
	/** Debounce window; the app uses {@link #AUTOSAVE_DEBOUNCE_MS}, tests shorten it. */
	private volatile long autosaveDelayMs = AUTOSAVE_DEBOUNCE_MS;
	/** The single daemon thread the debounce timer and the project writes run on. */
	private volatile @Nullable ScheduledExecutorService autosaveScheduler;
	private volatile @Nullable ScheduledFuture<?> autosavePending;
	/** One write in flight; the state update lands on the UI thread once it finishes. */
	private final AtomicBoolean autosaveWriting = new AtomicBoolean();
	/** Why the last autosave failed, kept until a write succeeds; drives a persistent warning. */
	private volatile @Nullable String autosaveFailure;
	private final AtomicLong playheadMs = new AtomicLong();
	/** Playhead listeners for the timeline indicator (engine thread; the window marshals to FX). */
	private final java.util.List<java.util.function.Consumer<Double>> playheadListeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();
	private final AtomicLong mediaGeneration = new AtomicLong();
	private final AtomicReference<PlaybackSeek> timelineSeek = new AtomicReference<>();
	private final AtomicReference<PlaybackSeek> timelineScrub = new AtomicReference<>();
	private final AutoCloseable changeSubscription;
	private final AutoCloseable timeSubscription;
	private final StoreTimelineDataSource dataSource;
	/** The smart-framing dodge plan and the state it was walked for (Camera-track markers). */
	private @Nullable ProjectEditorState smartFramingPlanState;
	private dev.gravaai.media.compose.SmartFraming.@Nullable Inputs smartFramingPlanInputs;
	/** The media generation the cached plan was walked for (a replaced source re-plans). */
	private long smartFramingPlanGeneration = -1;
	private dev.gravaai.media.compose.SmartFraming.@Nullable Plan smartFramingPlan;
	private final TimelineCommands timelineCommands;
	/** Serializes the background peak decodes; closed with the session. */
	private final ExecutorService waveformExecutor;
	/** The timeline's waveform source (decode + project-beside cache). */
	private final CachingWaveformProvider waveforms;
	private volatile @Nullable PlaybackAudioTrack audioTrack;
	private volatile boolean closed;
	/** The clips playback was last given; null until media is attached. */
	private volatile @Nullable List<ClipRegion> playbackClips;
	/** The click-sound settings the audio track was last given (change detection). */
	private volatile @Nullable ClickSoundSettings appliedClickSound;
	/** The key-sound settings the audio track was last given (change detection). */
	private volatile @Nullable KeySoundSettings appliedKeySound;
	/** The source-audio cleanup/mix settings the audio track was last opened with. */
	private volatile @Nullable String appliedAudioCleanup;
	/** Renders and serves the denoised copies of the cleaned source tracks. */
	private final dev.gravaai.services.denoise.DenoiseCache denoiseCache =
			dev.gravaai.services.denoise.DenoiseCache.shared();
	private final java.util.concurrent.ExecutorService clipSync =
			java.util.concurrent.Executors.newSingleThreadExecutor(
					Thread.ofVirtual().name("gravaai-playback-clips").factory());
	/**
	 * Delays a cleanup-driven audio reopen until the edits settle: dragging a control changes the
	 * defaults many times per second, and every change would otherwise reopen the whole preview
	 * pipeline (one ffmpeg process per segment). Only the last change in a burst is reopened.
	 */
	private final java.util.concurrent.ScheduledExecutorService cleanupSync =
			java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
					Thread.ofVirtual().name("gravaai-playback-cleanup").factory());
	/** The pending debounced cleanup reopen, cancelled by a newer change of the same settings. */
	private volatile java.util.concurrent.@Nullable ScheduledFuture<?> pendingCleanupSync;

	private EditorSession(@Nullable Path projectPath, ProjectDocument document, ProjectDocument mediaSource) {
		this.projectPath = projectPath;
		this.document = document;
		this.mediaSource = mediaSource;
		this.store = new EditorStore(document.editor());
		this.eyeContactState = store.state();
		// Cheap audio-master clock; the media/audio attach happens in attachMedia() after the window
		// is visible (P2.11 startup budget).
		this.engine = new PlaybackEngine(PlaybackEngine.Dependencies.production(
				DecoderFrameSourceProvider.ffmpegBacked().withDecoratedDecoders(this::decorateDecoder),
				new AudioMediaClock(), true));
		this.playback = new PlaybackControllerPort(engine);
		// Decode the bundled clicks off-thread: the first audition and the first playback are warm.
		ClickSounds.preload();
		this.timeSubscription = engine.onTime(time -> {
			long ms = Math.round(time.timelineSeconds() * 1000);
			playheadMs.set(ms);
			for (java.util.function.Consumer<Double> listener : playheadListeners) {
				try {
					listener.accept((double) ms);
				} catch (RuntimeException error) {
					// A UI listener must never break the engine's time callback.
				}
			}
		});
		this.changeSubscription = store.onChange(changed -> {
			dirty.set(true);
			changeSequence.incrementAndGet();
			scheduleAutosave();
			syncPlaybackClips();
			syncClickSound();
			syncKeySound();
			syncAudioCleanup();
			syncEyeContact(false);
		});
		this.waveformExecutor = Threads.daemon("gravaai-waveforms");
		this.waveforms = new CachingWaveformProvider(this::waveformCachePath,
				AudioPeaks::loadPeaks, AudioPeaks.PEAKS_PER_SECOND, waveformExecutor);
		this.dataSource = new StoreTimelineDataSource(store, this::timelineDurationMs,
				() -> (double) playheadMs.get(), waveforms);
		this.timelineCommands = new StoreTimelineCommands(store, seconds -> {
			PlaybackSeek lateBound = timelineSeek.get();
			if (lateBound != null) {
				lateBound.seek(seconds);
			} else {
				engine.seek(seconds);
			}
		}, seconds -> {
			PlaybackSeek lateBound = timelineScrub.get();
			if (lateBound != null) {
				lateBound.seek(seconds);
			} else {
				engine.scrub(seconds);
			}
		});
		// Suggest-zooms telemetry, read from the current source video on demand (React keeps
		// normalizedCursorTelemetry for project.videoSourcePath; imports swap the source).
		this.dataSource.cursorTelemetry(this::cursorTelemetryPoints);
		// Smart-framing dodge markers on the Camera layout track: the same plan the preview and
		// the export render with, cached per project state.
		this.dataSource.smartFramingPlan(this::smartFramingPlan);
		// The cleanup badge of a lane shows where its denoised copy stands.
		this.dataSource.cleanupRenderStatus(this::cleanupRenderStatus);
		// The eye-contact strip of the Camera layout track follows the webcam analysis.
		this.dataSource.eyeContactStrip(this::eyeContactStrip);
		// The pen bands of the clip row: where the presenter drew on screen while recording.
		this.dataSource.inkMarks(this::inkMarks);
		// The mic lane's muted bands: where push-to-talk kept the microphone muted.
		this.dataSource.micMuteMarks(this::micMuteMarks);
		// Suggest-zooms frames each draw session once instead of zooming on every pen press.
		this.dataSource.inkFocus(this::inkFocus);
		ownedResources.add(gazeCache.addListener(this::onGazeChanged));
		// A cleaned track's rendered copy lands some time after the user switches noise
		// reduction on: the badge follows the render, and the preview reopens its audio on the
		// copy when it is there.
		ownedResources.add(denoiseCache.addListener(() -> {
			if (!closed) {
				syncAudioCleanup();
				this.dataSource.notifyChanged();
			}
		}));
	}

	// ── eye contact ──────────────────────────────────────────────────────────

	/** The webcam analysis behind eye contact, shared with the exports. */
	private final dev.gravaai.services.gaze.GazeCache gazeCache =
			dev.gravaai.services.gaze.GazeCache.shared();
	/** The media the engine plays; the decode worker reads it to recognise the webcam. */
	private volatile Optional<PlaybackMedia> previewMedia = Optional.empty();
	/** The state the decode worker corrects with (the store itself is FX-thread state). */
	private volatile ProjectEditorState eyeContactState;
	/** The eye-contact settings last applied to the preview, to refresh only on a real change. */
	private String appliedEyeContact = "";
	/** The analysis phase last seen for the preview's webcam. */
	private volatile dev.gravaai.services.gaze.GazeCache.Phase lastGazePhase =
			dev.gravaai.services.gaze.GazeCache.Phase.IDLE;
	/** Bumped on every analysis change: the strip cache keys on it. */
	private final java.util.concurrent.atomic.AtomicLong gazeVersion =
			new java.util.concurrent.atomic.AtomicLong();
	private volatile Optional<EyeStripCache> eyeStripCache = Optional.empty();

	private record EyeStripCache(ProjectEditorState state, long version,
			Optional<dev.gravaai.ui.editor.timeline.EyeContactStrip> strip) {}

	/**
	 * Wraps the preview's webcam decoder so each frame is corrected on the decode worker, the
	 * frame-exact result the export computes. Other media decode untouched.
	 */
	private dev.gravaai.media.playback.VideoFrameDecoder decorateDecoder(String path,
			dev.gravaai.media.playback.VideoFrameDecoder decoder) {
		Optional<PlaybackMedia> media = previewMedia;
		if (media.isEmpty() || !path.equals(media.get().webcamPath())
				|| !gazeCache.isAvailable()) {
			return decoder;
		}
		Path webcam = Path.of(path);
		double offsetSeconds = media.get().webcamTimeOffsetMs() / 1000.0;
		return new dev.gravaai.media.playback.FilteredVideoFrameDecoder(decoder,
				new dev.gravaai.media.gaze.EyeContactFilter(() -> gazeCache.ready(webcam),
						seconds -> dev.gravaai.media.compose.ProjectEditorInputs.eyeContactAngleAt(
								eyeContactState, seconds * 1000),
						dev.gravaai.media.onnx.OnnxWebGpuPlugin::probe),
				// The preview maps the webcam straight off the timeline (minus its start offset).
				seconds -> seconds + offsetSeconds);
	}

	/** The preview's webcam recording, when the playing media has one. */
	private Optional<Path> previewWebcam() {
		return previewMedia.flatMap(media -> Optional.ofNullable(media.webcamPath())).map(Path::of);
	}

	/**
	 * Follows the eye-contact settings: starts the webcam analysis the first time eye contact is
	 * asked for and re-decodes the preview's webcam when the correction changes.
	 *
	 * @param force refresh even when the settings did not change (new media)
	 */
	private void syncEyeContact(boolean force) {
		ProjectEditorState state = store.state();
		eyeContactState = state;
		String signature = eyeContactSignature(state);
		if (!force && signature.equals(appliedEyeContact)) {
			return;
		}
		appliedEyeContact = signature;
		Optional<Path> webcam = previewWebcam();
		if (webcam.isPresent() && gazeCache.isAvailable()
				&& dev.gravaai.media.compose.ProjectEditorInputs.eyeContactWanted(state)) {
			gazeCache.ensure(webcam.get());
		}
		gazeVersion.incrementAndGet();
		engine.refreshWebcam();
	}

	/** Everything the correction reads from the project, as one comparable string. */
	private static String eyeContactSignature(ProjectEditorState state) {
		var webcam = state.webcam();
		StringBuilder signature = new StringBuilder();
		if (webcam != null) {
			signature.append(webcam.enabled()).append('|').append(webcam.eyeContact()).append('|')
					.append(webcam.eyeContactAngle());
		}
		for (var segment : state.cameraLayoutSegments()) {
			segment.style().eyeContact().ifPresent(angle -> signature.append('|')
					.append(segment.startMs()).append('-').append(segment.endMs()).append(':')
					.append(angle));
		}
		return signature.toString();
	}

	/** The analysis moved: repaint the strip, and re-decode the preview once it is ready. */
	private void onGazeChanged() {
		if (closed) {
			return;
		}
		gazeVersion.incrementAndGet();
		Optional<Path> webcam = previewWebcam();
		if (webcam.isPresent()) {
			var phase = gazeCache.status(webcam.get()).phase();
			var previous = lastGazePhase;
			lastGazePhase = phase;
			if (phase == dev.gravaai.services.gaze.GazeCache.Phase.READY
					&& previous != dev.gravaai.services.gaze.GazeCache.Phase.READY) {
				engine.refreshWebcam();
			}
		}
		dataSource.notifyChanged();
	}

	/** The ink sidecar of one source video, read once. */
	private record InkSource(String videoPath, InkTelemetry.InkRecording ink, List<InkMark.Session> sessions) {
	}

	/** The ink sidecar of the current source video, read once per video. */
	private Optional<InkSource> inkSource() {
		String video = videoPath();
		if (video == null || video.isBlank()) {
			return Optional.empty();
		}
		Optional<InkSource> known = inkSource.filter(source -> source.videoPath().equals(video));
		if (known.isPresent()) {
			return known;
		}
		InkTelemetry.InkRecording ink = InkTelemetry.read(video);
		List<InkMark.Session> sessions = ink.sessions().stream()
				.map(session -> new InkMark.Session(session.startMs(), session.endMs(), ink.strokeCount(session)))
				.toList();
		Optional<InkSource> read = Optional.of(new InkSource(video, ink, sessions));
		inkSource = read;
		return read;
	}

	/** Draw sessions for suggest-zooms, in source time like the cursor telemetry it gets. */
	private List<ZoomSuggestionUtils.InkFocus> inkFocus() {
		return inkSource()
				.map(source -> ZoomSuggestionUtils.inkFocus(source.ink(), 0, Long.MAX_VALUE / 2))
				.orElse(List.of());
	}

	/** The pen bands for one project state. */
	private record InkMarksCache(ProjectEditorState state, String videoPath, List<InkMark> marks) {
	}

	private volatile Optional<InkSource> inkSource = Optional.empty();
	private volatile Optional<InkMarksCache> inkMarksCache = Optional.empty();

	/**
	 * The clip row's pen bands, cached per project state: the sidecar is read once per source
	 * video and projected through the clips (cuts and speed) whenever the edit changes.
	 */
	private List<InkMark> inkMarks() {
		String video = videoPath();
		if (video == null || video.isBlank()) {
			return List.of();
		}
		ProjectEditorState state = store.state();
		Optional<InkMarksCache> cached = inkMarksCache;
		if (cached.isPresent() && cached.get().state() == state && cached.get().videoPath().equals(video)) {
			return cached.get().marks();
		}
		Optional<InkSource> source = inkSource();
		if (source.isEmpty()) {
			return List.of();
		}
		List<InkMark> marks = InkMark.project(source.get().sessions(), state.clipRegions());
		inkMarksCache = Optional.of(new InkMarksCache(state, video, marks));
		return marks;
	}

	/** The push-to-talk sidecar of one source video, read once. */
	private record MicMuteSource(String videoPath, List<MicMuteMark.Session> spans) {
	}

	/** The push-to-talk sidecar of the current source video, read once per video. */
	private Optional<MicMuteSource> micMuteSource() {
		String video = videoPath();
		if (video == null || video.isBlank()) {
			return Optional.empty();
		}
		Optional<MicMuteSource> known = micMuteSource.filter(source -> source.videoPath().equals(video));
		if (known.isPresent()) {
			return known;
		}
		PushToTalkTelemetry.Recording ptt = PushToTalkTelemetry.read(video);
		List<MicMuteMark.Session> spans = ptt.spans().stream()
				.map(span -> new MicMuteMark.Session(span.startMs(), span.endMs()))
				.toList();
		Optional<MicMuteSource> read = Optional.of(new MicMuteSource(video, spans));
		micMuteSource = read;
		return read;
	}

	/** The mic lane's muted bands for one project state. */
	private record MicMuteMarksCache(ProjectEditorState state, String videoPath,
			List<MicMuteMark> marks) {
	}

	private volatile Optional<MicMuteSource> micMuteSource = Optional.empty();
	private volatile Optional<MicMuteMarksCache> micMuteMarksCache = Optional.empty();

	/**
	 * The mic lane's muted bands, cached per project state: the sidecar is read once per source
	 * video and projected through the clips (cuts and speed) whenever the edit changes.
	 */
	private List<MicMuteMark> micMuteMarks() {
		String video = videoPath();
		if (video == null || video.isBlank()) {
			return List.of();
		}
		ProjectEditorState state = store.state();
		Optional<MicMuteMarksCache> cached = micMuteMarksCache;
		if (cached.isPresent() && cached.get().state() == state
				&& cached.get().videoPath().equals(video)) {
			return cached.get().marks();
		}
		Optional<MicMuteSource> source = micMuteSource();
		if (source.isEmpty()) {
			return List.of();
		}
		List<MicMuteMark> marks = MicMuteMark.project(source.get().spans(), state.clipRegions());
		micMuteMarksCache = Optional.of(new MicMuteMarksCache(state, video, marks));
		return marks;
	}

	/**
	 * The Camera-track eye-contact strip, cached per project state and analysis change (the
	 * timeline repaints far more often than either moves).
	 */
	private Optional<dev.gravaai.ui.editor.timeline.EyeContactStrip> eyeContactStrip() {
		ProjectEditorState state = store.state();
		long version = gazeVersion.get();
		Optional<EyeStripCache> cached = eyeStripCache;
		if (cached.isPresent() && cached.get().state() == state
				&& cached.get().version() == version) {
			return cached.get().strip();
		}
		Optional<dev.gravaai.ui.editor.timeline.EyeContactStrip> strip = buildEyeContactStrip(state);
		eyeStripCache = Optional.of(new EyeStripCache(state, version, strip));
		return strip;
	}

	private Optional<dev.gravaai.ui.editor.timeline.EyeContactStrip> buildEyeContactStrip(
			ProjectEditorState state) {
		Optional<Path> webcam = previewWebcam();
		if (webcam.isEmpty() || !gazeCache.isAvailable()
				|| !dev.gravaai.media.compose.ProjectEditorInputs.eyeContactWanted(state)) {
			return Optional.empty();
		}
		var status = gazeCache.status(webcam.get());
		switch (status.phase()) {
			case FAILED -> {
				return Optional.of(new dev.gravaai.ui.editor.timeline.EyeContactStrip(
						dev.gravaai.ui.editor.timeline.EyeContactStrip.State.FAILED, 0, List.of()));
			}
			case IDLE, ANALYSING -> {
				return Optional.of(new dev.gravaai.ui.editor.timeline.EyeContactStrip(
						dev.gravaai.ui.editor.timeline.EyeContactStrip.State.ANALYSING,
						status.progress(), List.of()));
			}
			case READY -> {
				// Falls through to the spans below.
			}
		}
		Optional<dev.gravaai.media.gaze.GazeTrack> track = gazeCache.ready(webcam.get());
		if (track.isEmpty()) {
			return Optional.empty();
		}
		double offsetSeconds = previewMedia.map(PlaybackMedia::webcamTimeOffsetMs).orElse(0L)
				/ 1000.0;
		long total = timelineDurationMs();
		long step = 100;
		List<dev.gravaai.ui.editor.timeline.EyeContactStrip.Span> spans = new ArrayList<>();
		int runKind = 0;
		long runStart = 0;
		for (long t = 0; t <= total; t += step) {
			int kindHere = 0;
			if (dev.gravaai.media.compose.ProjectEditorInputs.eyeContactAngleAt(state, t) > 0) {
				double weight = track.get().weightAt(t / 1000.0 - offsetSeconds);
				kindHere = weight >= 0.7 ? 2 : weight >= 0.05 ? 1 : 0;
			}
			if (kindHere != runKind) {
				if (runKind != 0) {
					spans.add(new dev.gravaai.ui.editor.timeline.EyeContactStrip.Span(runStart, t,
							runKind == 2));
				}
				runKind = kindHere;
				runStart = t;
			}
		}
		if (runKind != 0) {
			spans.add(new dev.gravaai.ui.editor.timeline.EyeContactStrip.Span(runStart, total,
					runKind == 2));
		}
		return Optional.of(new dev.gravaai.ui.editor.timeline.EyeContactStrip(
				dev.gravaai.ui.editor.timeline.EyeContactStrip.State.READY, 1, spans));
	}

	/**
	 * The project's smart-framing dodge schedule for the Camera-track indicators, cached until the
	 * project state changes (the walk is per state, not per paint). Null when smart framing is off.
	 */
	public dev.gravaai.media.compose.SmartFraming.@Nullable Plan smartFramingPlan() {
		ProjectEditorState state = store.state();
		var webcam = state.webcam();
		if (webcam == null || !webcam.smartFraming()) {
			smartFramingPlanState = state;
			return null;
		}
		if (smartFramingPlanState != state) {
			smartFramingPlanState = state;
			// Most edits do not touch what the plan is made from: compare its inputs before
			// reloading the telemetry and walking the whole timeline on the FX thread.
			var inputs = dev.gravaai.media.compose.SmartFraming.Inputs.of(state);
			if (inputs.equals(smartFramingPlanInputs)
					&& mediaGeneration.get() == smartFramingPlanGeneration) {
				return smartFramingPlan;
			}
			smartFramingPlanInputs = inputs;
			smartFramingPlanGeneration = mediaGeneration.get();
			Path video = Path.of(videoPath());
			smartFramingPlan = dev.gravaai.ui.editor.SmartFramingPlans.forState(state,
					CursorTelemetryLoader.load(videoPath()), video, webcamPath(),
					timelineDurationMs() / 1000.0,
					dev.gravaai.media.compose.WebcamOverlay.REFERENCE_VIEWPORT_WIDTH,
					dev.gravaai.media.compose.WebcamOverlay.referenceViewportHeight());
		}
		return smartFramingPlan;
	}

	/** The current document (raw tree + editor state), including post-import replacements. */
	public ProjectDocument document() {
		return document;
	}

	/** Cursor telemetry of the current source video, in the timeline's suggestion units. */
	public List<ZoomSuggestionUtils.CursorTelemetryPoint> cursorTelemetryPoints() {
		List<CursorSample> samples = CursorTelemetryLoader.load(videoPath());
		List<ZoomSuggestionUtils.CursorTelemetryPoint> points = new ArrayList<>(samples.size());
		for (CursorSample sample : samples) {
			points.add(new ZoomSuggestionUtils.CursorTelemetryPoint(
					sample.timeSeconds() * 1000.0, sample.x(), sample.y(),
					sample.interactionType(), sample.cursorType()));
		}
		return points;
	}

	/**
	 * Resolves the recording's source-audio tracks (mic/system sidecars, embedded audio) off the
	 * FX thread and pushes them into the timeline data source, so the source-audio lanes render
	 * per clip with their own waveforms. Late-arriving by design: the store change listeners
	 * repaint the timeline when the tracks land.
	 */
	private void refreshSourceAudioTracks() {
		String videoPath = mediaSource.videoPath();
		List<TimelineSourceAudioTrack> tracks;
		if (videoPath == null || videoPath.isBlank()
				|| !Files.isRegularFile(Path.of(videoPath))) {
			tracks = List.of();
		} else {
			Path video = Path.of(videoPath);
			boolean hasEmbeddedAudio = false;
			try {
				hasEmbeddedAudio = !MediaProbe.probe(video).audioStreams().isEmpty();
			} catch (RuntimeException | LinkageError error) {
				LOG.log(System.Logger.Level.WARNING,
						"audio probe failed for " + video.getFileName() + ": " + error);
			}
			String mic = firstExistingSidecar(video, "mic");
			String system = firstExistingSidecar(video, "system");
			if (dev.gravaai.services.recording.RecordingAudioManifest.read(video).isPresent()) {
				// The recording kept its capture tracks: the lanes are the untouched microphone
				// and the system audio, and the recording's own audio (the same two, mixed, with
				// the record-time noise reduction baked in) must not play on top of them.
				hasEmbeddedAudio = false;
			}
			// The has*Sidecar flags gate the paths; the absent ones carry an unused empty string.
			tracks = TimelineSourceAudioTracks.buildTracks(hasEmbeddedAudio, videoPath,
					mic != null, mic == null ? "" : mic,
					system != null, system == null ? "" : system,
					TimelineSourceAudioTracks.Labels.DEFAULT);
		}
		dataSource.setSourceAudioTracks(tracks);
	}

	/** The first existing {@code <recording>.<suffix>.wav|m4a|webm} sidecar, or null. */
	private static @Nullable String firstExistingSidecar(Path video, String suffix) {
		for (String candidate : TimelineSourceAudioTracks
				.buildSidecarPathCandidates(video.toString(), suffix)) {
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path)) {
				return path.toString();
			}
		}
		return null;
	}

	/**
	 * The persistent peaks cache for an audio file: beside the recording when the file lives in
	 * the session directory the app already owns (next to its {@code .cursor.json}/
	 * {@code .keys.json} sidecars), otherwise the per-user cache — an imported audio file from
	 * anywhere on disk must not grow siblings. Both locations survive reopening a project, so the
	 * decode happens once per media file.
	 */
	private Path waveformCachePath(String audioPath) {
		Path audio = Path.of(audioPath).toAbsolutePath().normalize();
		String video = document.videoPath();
		Path sessionDirectory = video == null || video.isBlank() ? null
				: Path.of(video).toAbsolutePath().normalize().getParent();
		if (sessionDirectory != null && sessionDirectory.equals(audio.getParent())) {
			return audio.resolveSibling(audio.getFileName().toString() + ".peaks");
		}
		return UserData.resolve().dir().resolve("peaks")
				.resolve(sha256Hex(audio.toString()) + ".peaks");
	}

	/** Lower-case hex SHA-256 of {@code value} (the user-cache key for an absolute path). */
	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	/** Heavy media attach (probe/audio producer/decode), called off the FX thread after the window is visible. */
	public void attachMedia() {
		LOG.log(System.Logger.Level.INFO, "editor media attach start: " + mediaSource.videoPath());
		refreshSourceAudioTracks();
		long generation = mediaGeneration.get();
		PlaybackMedia media = loadMedia(mediaSource);
		if (media == null) {
			LOG.log(System.Logger.Level.WARNING,
					"editor media attach: no resolvable media for " + mediaSource.videoPath());
			return;
		}
		if (mediaGeneration.get() != generation) {
			// A source replacement landed while probing; it owns the engine now.
			LOG.log(System.Logger.Level.INFO, "editor media attach superseded by a source replacement");
			return;
		}
		LOG.log(System.Logger.Level.INFO, "editor media resolved: " + media.videoPath());
		// Video first: the preview only needs the decoded frame; audio is attached afterwards.
		previewMedia = Optional.of(media);
		LOG.log(System.Logger.Level.INFO, "editor engine setMedia start");
		engine.setMedia(media);
		syncEyeContact(true);
		playbackClips = media.clips();
		// setMedia emits the initial frame for the preview; no seek here (it forces a ring fill
		// and can block on slow decoders - the preview can play/seek once the user asks).
		LOG.log(System.Logger.Level.INFO, "editor engine media set");
		LOG.log(System.Logger.Level.INFO,
				"editor media attached, duration=" + media.sourceDurationMs() + " ms");
		try {
			LOG.log(System.Logger.Level.INFO, "editor audio attach start");
			ClickSoundSettings clickSound = store.state().clickSound();
			KeySoundSettings keySound = store.state().keySound();
			PlaybackAudioTrack audio = openPreviewAudio(media.videoPath(), media.clips(),
					clickSound, keySound);
			appliedClickSound = clickSound;
			appliedKeySound = keySound;
			appliedAudioCleanup = audioCleanupSignature();
			if (mediaGeneration.get() != generation) {
				// Superseded while the audio producer opened: keep the replacement's track.
				closeQuietly(audio);
				return;
			}
			if (audio != null) {
				engine.setAudioTrack(audio);
				audioTrack = audio;
			}
			LOG.log(System.Logger.Level.INFO, "editor audio attached");
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "audio playback unavailable: " + error);
		}
	}

	/**
	 * Subscribes to engine playhead changes in timeline milliseconds ({@code onTime}); the callback
	 * runs on the engine thread, so UI code marshals it to the FX thread. Returns a closer.
	 */
	public dev.gravaai.core.event.Subscription onPlayhead(
			java.util.function.Consumer<Double> listener) {
		if (listener == null) {
			return () -> { };
		}
		playheadListeners.add(listener);
		return () -> playheadListeners.remove(listener);
	}

	/**
	 * Swaps the project's source media after a recording import (React replaces
	 * {@code videoSourcePath}/{@code videoPath} and bumps the preview version). The document and
	 * media source are updated first and unconditionally, then the engine media and audio track
	 * are swapped and the old track closed; the generation guard keeps a late
	 * {@link #attachMedia()} from clobbering the replacement.
	 */
	public void replaceSourceMedia(ProjectDocument next, double seekSeconds) {
		document = next;
		mediaSource = next;
		mediaGeneration.incrementAndGet();
		refreshSourceAudioTracks();
		PlaybackMedia media = loadMedia(mediaSource);
		if (media == null) {
			LOG.log(System.Logger.Level.WARNING,
					"editor media replace: no resolvable media for " + mediaSource.videoPath());
			return;
		}
		previewMedia = Optional.of(media);
		engine.setMedia(media);
		syncEyeContact(true);
		playbackClips = media.clips();
		PlaybackAudioTrack previous = audioTrack;
		PlaybackAudioTrack audio = null;
		try {
			ClickSoundSettings clickSound = store.state().clickSound();
			KeySoundSettings keySound = store.state().keySound();
			audio = openPreviewAudio(media.videoPath(), media.clips(), clickSound, keySound);
			appliedClickSound = clickSound;
			appliedKeySound = keySound;
			appliedAudioCleanup = audioCleanupSignature();
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "audio playback unavailable after replace: " + error);
		}
		if (audio != null) {
			engine.setAudioTrack(audio);
			audioTrack = audio;
		}
		if (previous != null && previous != audio) {
			closeQuietly(previous);
		}
		if (seekSeconds > 0) {
			engine.seek(seekSeconds);
		}
		LOG.log(System.Logger.Level.INFO,
				"editor media replaced: " + media.videoPath() + ", duration=" + media.sourceDurationMs() + " ms");
	}

	/**
	 * True when an editor change actually changed the playback clip sequence. Appearance-only
	 * edits (wallpaper, blur, cursor, webcam settings, …) produce an equal list and must not reach
	 * {@link #syncPlaybackClips()} audio reopen/update path.
	 */
	static boolean clipSequenceChanged(@Nullable List<ClipRegion> current, List<ClipRegion> next) {
		return current == null || !current.equals(next);
	}

	/**
	 * Pushes the store's clip sequence into playback after an edit (cut, split, delete, mute, clip
	 * speed, transitions). Before this the preview kept the clips it was opened with: a cut range
	 * still played and a muted clip still sounded. Runs off the FX thread, in edit order.
	 */
	private void syncPlaybackClips() {
		List<ClipRegion> clips = store.state().clipRegions();
		if (!clipSequenceChanged(playbackClips, clips)) {
			return;
		}
		playbackClips = clips;
		long generation = mediaGeneration.get();
		clipSync.execute(() -> {
			if (closed || mediaGeneration.get() != generation) {
				return;
			}
			engine.updateClips(clips);
			String video = mediaSource.videoPath();
			if (video == null || audioTrack == null) {
				return;
			}
			PlaybackAudioTrack previous = audioTrack;
			try {
				ClickSoundSettings clickSound = store.state().clickSound();
				KeySoundSettings keySound = store.state().keySound();
				PlaybackAudioTrack audio = openPreviewAudio(video, clips, clickSound, keySound);
				appliedClickSound = clickSound;
				appliedKeySound = keySound;
				appliedAudioCleanup = audioCleanupSignature();
				if (audio != null) {
					// setAudioTrack seeks the new track to the playhead; the engine tick pumps it.
					engine.setAudioTrack(audio);
					audioTrack = audio;
					closeQuietly(previous);
				}
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "audio playback not updated after an edit: " + error);
			}
		});
	}

	/**
	 * Swaps the click-sound track when the setting changed: the producer stays open, only the
	 * track is replaced. The rebuild runs on the clip-sync executor so the sidecar read never
	 * touches the FX thread and lands after any clip reopen.
	 */
	private void syncClickSound() {
		ClickSoundSettings clickSound = store.state().clickSound();
		if (clickSound.equals(appliedClickSound)) {
			return;
		}
		appliedClickSound = clickSound;
		clipSync.execute(() -> {
			if (closed) {
				return;
			}
			PlaybackAudioTrack track = audioTrack;
			String video = mediaSource.videoPath();
			if (track == null || video == null || video.isBlank()) {
				return;
			}
			track.setClickTrack(clickTrackFor(clickSound, video, store.state().clipRegions()));
		});
	}

	/** Swaps the key-press sound track when its setting changed (see {@link #syncClickSound}). */
	private void syncKeySound() {
		KeySoundSettings keySound = store.state().keySound();
		if (keySound.equals(appliedKeySound)) {
			return;
		}
		appliedKeySound = keySound;
		clipSync.execute(() -> {
			if (closed) {
				return;
			}
			PlaybackAudioTrack track = audioTrack;
			String video = mediaSource.videoPath();
			if (track == null || video == null || video.isBlank()) {
				return;
			}
			track.setKeyTrack(keyTrackFor(keySound, video, store.state().clipRegions()));
		});
	}

	/**
	 * Opens the preview audio for one media/clip snapshot: every source-audio track of the project
	 * with its gain and edit-time cleanup, so the preview hears what the export will write.
	 */
	private PlaybackAudioTrack openPreviewAudio(String video, List<ClipRegion> clips,
			ClickSoundSettings clickSound, KeySoundSettings keySound) {
		return PlaybackAudioProducer.openWithPulse(previewTracks(video), programmeLoudnessTarget(),
				clips, AppBinaries.ffmpeg(), clickTrackFor(clickSound, video, clips),
				keyTrackFor(keySound, video, clips));
	}

	/**
	 * The source tracks the preview plays. The timeline resolves sidecars/embedded audio (and the
	 * inspector's Audio section lists the same lanes); before they land the recording itself is the
	 * only track, which is exactly the pre-cleanup behaviour.
	 */
	private List<PreviewAudioTrack> previewTracks(@Nullable String videoPath) {
		SourceAudioTrackSettings defaults = store.state().defaultSourceAudioTrackSettingsTyped();
		List<PreviewAudioTrack> tracks = new ArrayList<>();
		for (dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack track
				: dataSource.sourceAudioTracks()) {
			if (track.audioPath() == null || track.audioPath().isBlank()) {
				continue;
			}
			dev.gravaai.core.project.AudioCleanup cleanup =
					SourceAudioSettings.resolveCleanup(defaults, track.id());
			tracks.add(new PreviewAudioTrack(track.audioPath(),
					SourceAudioSettings.resolveGain(defaults, track.id()), cleanup,
					denoisedCopy(track.audioPath(), cleanup)));
		}
		if (tracks.isEmpty() && videoPath != null && !videoPath.isBlank()) {
			tracks.add(PreviewAudioTrack.plain(videoPath));
		}
		return List.copyOf(tracks);
	}

	/**
	 * The programme loudness target the preview normalizes to, or 0 when off. Loudness belongs to
	 * the mix, so the first enabled lane in the exporter's priority order (mic, system, mixed) wins
	 * — but only for lanes that are actually in the preview.
	 */
	private double programmeLoudnessTarget() {
		SourceAudioTrackSettings defaults = store.state().defaultSourceAudioTrackSettingsTyped();
		List<dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack> sourceTracks =
				dataSource.sourceAudioTracks();
		List<String> present = new ArrayList<>();
		for (String trackId : List.of(SourceAudioTrackSettings.MIC,
				SourceAudioTrackSettings.SYSTEM, SourceAudioTrackSettings.MIXED)) {
			for (var track : sourceTracks) {
				if (track.id().equals(trackId)) {
					present.add(trackId);
					break;
				}
			}
		}
		return SourceAudioSettings.resolveProgrammeLoudnessTarget(defaults, present);
	}

	/**
	 * The rendered denoised copy of a track with noise reduction on, or null while there is none.
	 * A missing copy is requested here: the render runs in the background, the track plays the
	 * fallback chain meanwhile, and the cache listener reopens the audio when the copy lands.
	 */
	private @Nullable String denoisedCopy(String audioPath,
			dev.gravaai.core.project.AudioCleanup cleanup) {
		if (cleanup.noiseReduction() == dev.gravaai.core.project.AudioCleanup.NoiseReduction.OFF
				|| !denoiseCache.isAvailable()) {
			return null;
		}
		Path source = Path.of(audioPath);
		Optional<Path> ready = denoiseCache.ready(source);
		if (ready.isPresent()) {
			return ready.get().toString();
		}
		// Asked again every time the preview audio is rebuilt (a settings or clip change), so a
		// render that failed is retried by the next edit; a failure alone rebuilds nothing, so
		// this cannot loop.
		denoiseCache.ensure(source);
		return null;
	}

	/**
	 * What a source-audio lane's cleanup badge adds: the render percentage of its denoised copy,
	 * {@code "!"} when the render failed (the basic denoiser plays instead), nothing when the copy
	 * is ready or the lane has no noise reduction.
	 */
	private String cleanupRenderStatus(String sourceTrackId) {
		SourceAudioTrackSettings defaults = store.state().defaultSourceAudioTrackSettingsTyped();
		if (SourceAudioSettings.resolveCleanup(defaults, sourceTrackId).noiseReduction()
				== dev.gravaai.core.project.AudioCleanup.NoiseReduction.OFF) {
			return "";
		}
		for (var track : dataSource.sourceAudioTracks()) {
			if (!track.id().equals(sourceTrackId) || track.audioPath() == null
					|| track.audioPath().isBlank()) {
				continue;
			}
			var status = denoiseCache.status(Path.of(track.audioPath()));
			return switch (status.phase()) {
				case RENDERING -> (int) Math.floor(status.progress() * 100) + "%";
				case FAILED -> "!";
				case READY, IDLE -> "";
			};
		}
		return "";
	}

	/**
	 * Signature of the cleanup/mix settings the running audio track was built from, including
	 * which cleaned tracks have their rendered copy: the audio reopens when a copy lands.
	 */
	private String audioCleanupSignature() {
		tools.jackson.databind.node.ObjectNode settings =
				store.state().defaultSourceAudioTrackSettings();
		StringBuilder signature = new StringBuilder(settings == null ? "" : settings.toString());
		SourceAudioTrackSettings defaults = store.state().defaultSourceAudioTrackSettingsTyped();
		for (var track : dataSource.sourceAudioTracks()) {
			if (track.audioPath() == null || track.audioPath().isBlank()
					|| SourceAudioSettings.resolveCleanup(defaults, track.id()).noiseReduction()
							== dev.gravaai.core.project.AudioCleanup.NoiseReduction.OFF) {
				continue;
			}
			signature.append('|').append(track.id()).append(
					denoiseCache.ready(Path.of(track.audioPath())).isPresent() ? "=ready" : "=wait");
		}
		return signature.toString();
	}

	/** How long a cleanup change waits for the next one before the preview audio reopens. */
	private static final long CLEANUP_SYNC_DELAY_MS = 300;

	/**
	 * Rebuilds the preview audio when the per-track cleanup changes: the filters live in the
	 * decoder command, so only a fresh producer picks them up. Bursts of changes are coalesced
	 * into one reopen ({@link #CLEANUP_SYNC_DELAY_MS} after the last change), which then runs on
	 * the clip-sync executor like the other audio reopen paths; {@code setAudioTrack} seeks the
	 * new track to the playhead.
	 */
	private void syncAudioCleanup() {
		String signature = audioCleanupSignature();
		if (signature.equals(appliedAudioCleanup)) {
			return;
		}
		java.util.concurrent.ScheduledFuture<?> pending = pendingCleanupSync;
		if (pending != null) {
			pending.cancel(false);
		}
		long generation = mediaGeneration.get();
		pendingCleanupSync = cleanupSync.schedule(() -> {
			pendingCleanupSync = null;
			if (closed) {
				return;
			}
			// The reopen itself must stay ordered with the other audio reopens (attach, replace):
			// they all run on the clip-sync executor.
			clipSync.execute(() -> reopenAudioForCleanup(generation));
		}, CLEANUP_SYNC_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	/** The debounced reopen body: swap the audio track, or keep the old one on any failure. */
	private void reopenAudioForCleanup(long generation) {
		if (closed || mediaGeneration.get() != generation) {
			// Superseded (a replace or a newer change owns the track now); the replace path
			// opens with the current settings anyway.
			return;
		}
		String video = mediaSource.videoPath();
		if (video == null || video.isBlank() || audioTrack == null) {
			return;
		}
		PlaybackAudioTrack previous = audioTrack;
		PlaybackAudioTrack audio = null;
		try {
			ClickSoundSettings clickSound = store.state().clickSound();
			KeySoundSettings keySound = store.state().keySound();
			audio = openPreviewAudio(video, store.state().clipRegions(), clickSound, keySound);
			appliedClickSound = clickSound;
			appliedKeySound = keySound;
			if (audio != null) {
				try {
					engine.setAudioTrack(audio);
				} catch (RuntimeException error) {
					// The engine never took the new track: release it and keep playing the old one.
					closeQuietly(audio);
					audio = null;
					throw error;
				}
				audioTrack = audio;
				closeQuietly(previous);
			}
			// Applied only once the reopen succeeded: a failed open must retry on the next
			// settings change instead of leaving the preview silently on the old chain.
			appliedAudioCleanup = audioCleanupSignature();
		} catch (RuntimeException error) {
			closeQuietly(audio);
			LOG.log(System.Logger.Level.WARNING,
					"audio playback not updated after a cleanup change: " + error);
		}
	}

	/** The preview key-sound track, or {@code null} when off / nothing audible was typed. */
	private @Nullable ClickSoundTrack keyTrackFor(
			KeySoundSettings keySound, String videoPath, List<ClipRegion> clips) {
		if (!keySound.enabled()) {
			return null;
		}
		long[] timesMs = KeySoundHits.timesMs(keySound, KeystrokePresses.load(videoPath), clips);
		if (timesMs.length == 0) {
			return null;
		}
		return ClickSoundTrack.forKeys(keySound.sound(), keySound.volume(), timesMs);
	}

	/** The preview click track for these settings, or {@code null} when off / no clicks on screen. */
	private @Nullable ClickSoundTrack clickTrackFor(
			ClickSoundSettings clickSound, String videoPath, List<ClipRegion> clips) {
		if (!clickSound.enabled()) {
			return null;
		}
		long[] timesMs = ClickSoundHits.timesMs(
				clickSound, CursorTelemetryLoader.load(videoPath), clips);
		if (timesMs.length == 0) {
			return null;
		}
		return new ClickSoundTrack(clickSound.sound(), clickSound.volume(), timesMs);
	}

	/** Loads a project through the canonical persistence path and starts a playback session. */
	public static EditorSession open(Path projectPath) throws IOException {
		ProjectDocument document = ProjectDocument.load(projectPath);
		document = seedKeystrokeElements(document);
		return new EditorSession(projectPath, document, document);
	}

	/**
	 * Loads a project without opening an editor window or starting the playback engine: the
	 * headless load used by the batch export queue's renderer. The Keystrokes track is seeded from
	 * the recording sidecar exactly like {@link #open(Path)}, so a queued export renders the same
	 * elements as the editor would.
	 *
	 * @param projectPath the project file to load
	 * @return the project document with the seeded Keystrokes elements
	 * @throws IOException when the project file cannot be read
	 */
	public static ProjectDocument loadDocumentForExport(Path projectPath) throws IOException {
		return seedKeystrokeElements(ProjectDocument.load(projectPath));
	}

	/**
	 * A recording as a new, unsaved project ({@code useInitialEditorSource}: the recording session
	 * loads with {@code currentProjectPath = null}; the first save asks where to put the project).
	 * The webcam track of the session is enabled with its measured offset.
	 */
	public static EditorSession openRecording(Path videoPath, @Nullable String webcamPath,
			long webcamTimeOffsetMs) {
		ProjectDocument document = recordingDocument(videoPath, Optional.ofNullable(webcamPath),
				webcamTimeOffsetMs);
		return new EditorSession(null, document, document);
	}

	/**
	 * The document of a raw recording as a new project, without an editor session around it.
	 *
	 * <p>This is the one definition of "what a recording looks like before the user edits it": one
	 * full-length clip, the Keystrokes track seeded from the sidecar, and the record-time
	 * microphone cleanup carried over. {@link #openRecording} wraps it in a session for the editor
	 * and the headless CLI exports it, so both see the same timeline.
	 *
	 * @param webcamPath the linked webcam recording, or empty when the take has none
	 */
	public static ProjectDocument recordingDocument(Path videoPath, Optional<String> webcamPath,
			long webcamTimeOffsetMs) {
		tools.jackson.databind.node.ObjectNode editor = dev.gravaai.core.json.Json.nodeFactory().objectNode();
		webcamPath.filter(path -> !path.isBlank()).ifPresent(path -> {
			tools.jackson.databind.node.ObjectNode webcam = editor.putObject("webcam");
			webcam.put("enabled", true);
			webcam.put("sourcePath", path);
			webcam.put("timeOffsetMs", webcamTimeOffsetMs);
		});
		// useTimelineProjection: a source without clips starts as one full-length clip. Seeded here,
		// before the store exists, so the first undo step cannot return to an empty timeline.
		long durationMs = probeDurationMs(videoPath);
		if (durationMs > 0) {
			editor.set("clipRegions", ProjectDocument.writeClipRegions(
					dev.gravaai.core.project.Clips.trimsToClips(List.of(), durationMs)));
		}
		// The Keystrokes track: recorded presses become editable elements before the store exists,
		// so the first undo step already contains them.
		addKeystrokeElementsFromSidecar(editor, videoPath, KeystrokeOverlaySettings.DEFAULT.lingerMs());
		// A recording made with microphone noise reduction kept its untouched microphone: the
		// editor plays that capture, so the new project starts with the same level on the mic
		// lane. It sounds like the recording did, and the user can now turn it down or off.
		dev.gravaai.services.recording.RecordingAudioManifest.read(videoPath)
				.filter(manifest -> manifest.mic(videoPath).isPresent())
				.map(manifest -> dev.gravaai.core.project.AudioCleanup.NoiseReduction
						.fromText(manifest.micNoiseReduction()))
				.filter(level -> level != dev.gravaai.core.project.AudioCleanup.NoiseReduction.OFF)
				.ifPresent(level -> editor.set("defaultSourceAudioTrackSettings",
						SourceAudioSettings.mergeDefaultCleanup(null, SourceAudioTrackSettings.MIC,
								new dev.gravaai.core.project.AudioCleanup(level, 0, 0))));
		return ProjectDocument.create(videoPath.toAbsolutePath().normalize().toString(),
				ProjectPersistence.normalizeProjectEditor(editor));
	}

	/**
	 * The Keystrokes track for a loaded project: when the state carries no elements yet, seed them
	 * from the recording's {@code .keys.json} sidecar so its presses become editable blocks. Once
	 * seeded (or once the user deletes all of them), the elements are the source of truth and the
	 * sidecar is never re-read.
	 */
	private static ProjectDocument seedKeystrokeElements(ProjectDocument document) {
		String videoPath = document.videoPath();
		if (videoPath == null || videoPath.isBlank()
				|| !document.editor().keystrokeElements().isEmpty()) {
			return document;
		}
		Path video = Path.of(videoPath);
		if (!Files.isRegularFile(video)) {
			return document;
		}
		long lingerMs = document.editor().keystrokeOverlay() == null
				? KeystrokeOverlaySettings.DEFAULT.lingerMs()
				: document.editor().keystrokeOverlay().lingerMs();
		tools.jackson.databind.node.ObjectNode editor =
				ProjectDocument.writeEditor(document.editor(), dev.gravaai.core.json.Json.nodeFactory().objectNode());
		if (!addKeystrokeElementsFromSidecar(editor, video, lingerMs)) {
			return document;
		}
		return document.withEditor(ProjectPersistence.normalizeProjectEditor(editor));
	}

	/**
	 * Writes the sidecar's presses into {@code editor} as {@code keystrokeElements}, grouped into
	 * word elements by {@link KeystrokeGrouping} (fast typing becomes one element per word;
	 * modifier combinations and special keys stay individual); a no-op (and {@code false}) when
	 * the recording has no readable sidecar or no presses.
	 */
	private static boolean addKeystrokeElementsFromSidecar(
			tools.jackson.databind.node.ObjectNode editor, Path videoPath, long lingerMs) {
		List<KeystrokeTypes.KeystrokeEvent> events;
		try {
			events = KeystrokeTelemetry.readKeystrokeTelemetry(videoPath.toString());
		} catch (IOException error) {
			return false; // No sidecar (or unreadable): the recording simply has no Keystrokes track.
		}
		if (events.isEmpty()) {
			return false;
		}
		List<KeystrokeGrouping.Event> presses = new ArrayList<>(events.size());
		for (KeystrokeTypes.KeystrokeEvent event : events) {
			presses.add(new KeystrokeGrouping.Event(event.timeMs(), event.key(),
					event.modifiers().stream().map(KeystrokeTypes.KeyModifier::wire).toList()));
		}
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(presses, lingerMs);
		if (groups.isEmpty()) {
			return false;
		}
		tools.jackson.databind.node.ArrayNode elements = editor.putArray("keystrokeElements");
		int index = 1;
		for (KeystrokeGrouping.Group group : groups) {
			tools.jackson.databind.node.ObjectNode node = elements.addObject();
			node.put("id", "key-" + index++);
			node.put("startMs", group.startMs());
			node.put("endMs", group.endMs());
			node.put("key", group.text());
			tools.jackson.databind.node.ArrayNode modifiers = node.putArray("modifiers");
			for (String modifier : group.modifiers()) {
				modifiers.add(modifier);
			}
		}
		return true;
	}

	private static long probeDurationMs(Path videoPath) {
		try {
			double seconds = dev.gravaai.media.ffmpeg.MediaProbe.probe(videoPath).durationSeconds();
			return Double.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : 0;
		} catch (RuntimeException | LinkageError error) {
			LOG.log(System.Logger.Level.WARNING, "could not probe " + videoPath + ": " + error);
			return 0;
		}
	}

	/** True until the project has a file (a recording opened straight from the recorder). */
	public boolean isUnsaved() {
		return projectPath == null;
	}

	/** Audio-out engine when PulseAudio + bundled ffmpeg are available, else the silent default. */
	private static @Nullable PlaybackEngine createEngine(PlaybackMedia media) {
		if (media != null && media.videoPath() != null && !media.videoPath().isBlank()) {
			try {
				PlaybackAudioTrack audio = PlaybackAudioProducer.openWithPulse(media.videoPath(),
						media.clips(),
						AppBinaries.ffmpeg());
				AudioMediaClock clock = new AudioMediaClock();
				PlaybackEngine engine = new PlaybackEngine(PlaybackEngine.Dependencies.production(
						DecoderFrameSourceProvider.ffmpegBacked(), clock, true));
				engine.setAudioTrack(audio);
				return engine;
			} catch (RuntimeException error) {
				// Headless / no PulseAudio: keep the silent monotonic-clock engine.
			}
		}
		return PlaybackEngine.createDefault(DecoderFrameSourceProvider.ffmpegBacked());
	}

	private static @Nullable PlaybackMedia loadMedia(ProjectDocument document) {
		String videoPath = document.videoPath();
		if (videoPath == null || videoPath.isBlank()) {
			return null;
		}
		Path video = Path.of(videoPath);
		if (!Files.isRegularFile(video)) {
			// Missing source media: keep the editor open with the state only, no playback.
			return null;
		}
		Path webcam = resolveWebcamPath(document);
		// The webcam track starts timeOffsetMs after the screen recording (session manifest).
		long webcamOffsetMs = webcam == null || document.editor().webcam() == null ? 0L
				: document.editor().webcam().timeOffsetMs();
		return PlaybackMediaLoader.load(video, webcam, webcamOffsetMs, document.editor().clipRegions(), null);
	}

	/** The project's webcam recording, or {@code null} when it has none or it is missing. */
	private static @Nullable Path resolveWebcamPath(ProjectDocument document) {
		try {
			Result<ProjectMediaSources> sources = ProjectManager
					.resolveProjectMediaSources(document.toJson());
			if (sources instanceof Result.Ok<ProjectMediaSources> ok
					&& ok.value().webcamPath() != null) {
				return Path.of(ok.value().webcamPath());
			}
		} catch (RuntimeException ignored) {
			// A project without a resolvable webcam still plays its main video.
		}
		return null;
	}

	/** The current document's webcam recording (export input), or {@code null} when it has none. */
	public @Nullable Path webcamPath() {
		return resolveWebcamPath(document);
	}

	/**
	 * The project's webcam recording resolved through its media sources, or empty when the project
	 * has no (resolvable) webcam track. Headless counterpart of {@link #webcamPath()}, for the batch
	 * export queue's renderer.
	 *
	 * @param document the project document to inspect
	 * @return the webcam recording path, or empty
	 */
	public static Optional<Path> webcamPathFor(ProjectDocument document) {
		return Optional.ofNullable(resolveWebcamPath(document));
	}

	/** The project file, or {@code null} for an unsaved recording. */
	public @Nullable Path projectPath() {
		return projectPath;
	}

	public EditorStore store() {
		return store;
	}

	/** Element errors of this session, reported by every render path (preview, frames, export). */
	public dev.gravaai.services.elements.ElementDiagnostics elementDiagnostics() {
		return elementDiagnostics;
	}

	/** Closes {@code resource} with the session (e.g. the preview's element runtime). */
	public void own(AutoCloseable resource) {
		ownedResources.add(resource);
	}

	public PlaybackControllerPort playbackPort() {
		return playback;
	}

	public PlaybackEngine engine() {
		return engine;
	}

	public TimelineDataSource timelineDataSource() {
		return dataSource;
	}

	public TimelineCommands timelineCommands() {
		return timelineCommands;
	}

	public boolean dirty() {
		return dirty.get();
	}

	/** Project display name: the file name without the extension. */
	public String displayName() {
		if (projectPath == null) {
			// Unsaved recording: named after the recording file until it is saved.
			String video = document.videoPath() == null ? "" : Path.of(document.videoPath()).getFileName().toString();
			int dot = video.lastIndexOf('.');
			return dot > 0 ? video.substring(0, dot) : video.isEmpty() ? "Untitled" : video;
		}
		String name = projectPath.getFileName().toString();
		return name.endsWith(".gravaai") ? name.substring(0, name.length() - ".gravaai".length()) : name;
	}

	/** Saves the working editor state into the project file (raw unknown keys are preserved). */
	public void save() throws IOException {
		Path target = projectPath;
		if (target == null) {
			throw new IllegalStateException("The project has not been saved yet; use Save As.");
		}
		// An autosave that was in flight took its snapshot before this save: its result must not
		// overwrite the document this save installs (see applyAutosaveResult).
		changeSequence.incrementAndGet();
		ProjectDocument next = document
				.withEditor(ProjectPersistence.normalizeProjectEditor(store.editorJson()));
		writeProjectAtomically(target, next);
		document = next;
		dirty.set(false);
		autosaveFailure = null;
	}

	/** Save As: writes the same project data to a new file and continues there. */
	public void saveAs(Path target) throws IOException {
		changeSequence.incrementAndGet();
		ProjectDocument next = document
				.withEditor(ProjectPersistence.normalizeProjectEditor(store.editorJson()));
		writeProjectAtomically(target, next);
		document = next;
		projectPath = target;
		dirty.set(false);
		autosaveFailure = null;
	}

	/**
	 * The one project-file write behind every save path: {@link AtomicProjectSave} writes a synced
	 * temporary file and renames it over the target, keeping the previous generation as
	 * {@code <project>.bak}, so a crash mid-save cannot leave a truncated project behind.
	 */
	private static void writeProjectAtomically(Path target, ProjectDocument next) throws IOException {
		writeProjectJson(target, Json.stringify(next.toJson()));
	}

	/** The same write with the JSON already serialized (the autosave thread never touches the store). */
	private static void writeProjectJson(Path target, String json) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try {
			AtomicProjectSave.writeProjectFileAtomically(target, json).join();
		} catch (CompletionException | UncheckedIOException error) {
			// The atomic saver completes the future exceptionally with an UncheckedIOException;
			// callers of an explicit save keep seeing the plain IOException they already handle.
			IOException io = firstIoCause(error);
			if (io != null) {
				throw io;
			}
			throw error;
		}
	}

	/** The {@code IOException} behind a wrapped write failure, if there is one. */
	private static @Nullable IOException firstIoCause(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof IOException io) {
				return io;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return null;
	}

	/**
	 * Turns debounced autosave on. The window installs it (the session has no JavaFX, no project
	 * folder and no idea whether an export is running); a session without hooks — every headless
	 * caller — keeps the explicit-save behaviour.
	 *
	 * @param hooks the wiring, installed once the window can answer for the UI thread
	 */
	public void enableAutosave(AutosaveHooks hooks) {
		autosaveHooks = hooks;
		if (autosaveScheduler == null && !closed) {
			autosaveScheduler = Threads.daemonScheduler("gravaai-autosave");
		}
	}

	/** Replaces the debounce window (tests shorten it; the app runs on the default). */
	public void setAutosaveDelayMs(long delayMs) {
		autosaveDelayMs = Math.max(0L, delayMs);
	}

	/** Why the last autosave failed, or {@code null} once a write has succeeded. */
	public @Nullable String autosaveFailure() {
		return autosaveFailure;
	}

	/**
	 * Writes pending changes now, on the calling thread — the window calls it on the FX thread when
	 * the user asks to leave, before its close prompt decides whether there is unsaved work. A
	 * failure keeps the session dirty so the prompt still protects it. Deliberately <em>not</em>
	 * called from {@link #close()}: the "Discard" path closes the session and must not write. No-op
	 * for a session with no autosave installed.
	 */
	public void flushAutosave() {
		ScheduledExecutorService scheduler = autosaveScheduler;
		if (scheduler != null) {
			cancelPendingAutosave();
		}
		AutosaveHooks hooks = autosaveHooks;
		if (closed || hooks == null || !dirty.get() || hooks.saveBlocked().getAsBoolean()) {
			return;
		}
		if (!autosaveWriting.compareAndSet(false, true)) {
			// A debounced write is in flight; it applies its own result and keeps the session dirty
			// when it fails, which is exactly what the close prompt then asks about.
			return;
		}
		try {
			AutosaveSnapshot snapshot = buildAutosaveSnapshot();
			if (snapshot == null) {
				return;
			}
			applyAutosaveResult(snapshot, writeFailure(snapshot));
		} finally {
			autosaveWriting.set(false);
		}
	}

	/**
	 * The autosave wiring installed by {@link #enableAutosave(AutosaveHooks)}.
	 *
	 * @param untitledDirectory where an unsaved project's first file goes (the Projects folder), or
	 *     {@code null} when the app cannot name one — an untitled project is then simply not autosaved
	 * @param saveBlocked true while a write must not run (an export rendering the same state)
	 * @param uiExecutor the thread the store belongs to (the FX thread, inline in tests): every
	 *     store read and every state update happens there, never on the write thread
	 * @param autosaved called on the UI executor after a write reached the disk
	 * @param saveFailed called on the UI executor with the reason a write did not reach the disk
	 */
	public record AutosaveHooks(
			Supplier<@Nullable Path> untitledDirectory,
			BooleanSupplier saveBlocked,
			Executor uiExecutor,
			Consumer<Autosaved> autosaved,
			Consumer<String> saveFailed) {}

	/** One autosave that reached the disk; {@code createdFile} marks the project's first file. */
	public record Autosaved(Path projectPath, boolean createdFile) {}

	/** A serialized project ready to hit the disk, with the state it was taken from. */
	private record AutosaveSnapshot(
			Path target, ProjectDocument document, String json, long sequence, boolean targetIsNew) {}

	/** Re-arms the debounce timer; the write itself happens when it fires. */
	private void scheduleAutosave() {
		ScheduledExecutorService scheduler = autosaveScheduler;
		if (scheduler == null || autosaveHooks == null || closed) {
			return;
		}
		cancelPendingAutosave();
		try {
			autosavePending = scheduler.schedule(
					this::runDebouncedAutosave, autosaveDelayMs, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException stopped) {
			autosavePending = null;
		}
	}

	private void cancelPendingAutosave() {
		ScheduledFuture<?> pending = autosavePending;
		autosavePending = null;
		if (pending != null) {
			pending.cancel(false);
		}
	}

	/** The timer: hand the snapshot to the UI thread, then the write back to this one. */
	private void runDebouncedAutosave() {
		autosavePending = null;
		AutosaveHooks hooks = autosaveHooks;
		ScheduledExecutorService scheduler = autosaveScheduler;
		if (hooks == null || scheduler == null || closed || autosaveWriting.get()) {
			return;
		}
		if (hooks.saveBlocked().getAsBoolean()) {
			// An export is rendering: keep the changes dirty and try again after the next window.
			scheduleAutosave();
			return;
		}
		hooks.uiExecutor().execute(() -> {
			if (closed || autosaveWriting.get()) {
				return;
			}
			AutosaveSnapshot snapshot = buildAutosaveSnapshot();
			if (snapshot == null || !autosaveWriting.compareAndSet(false, true)) {
				return;
			}
			try {
				scheduler.execute(() -> {
					try {
						writeAutosaveSnapshot(snapshot);
					} finally {
						autosaveWriting.set(false);
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException stopped) {
				autosaveWriting.set(false);
			}
		});
	}

	/**
	 * Reads the store and serializes the project. Runs on the UI thread because the store belongs to
	 * it; the write that follows is the only part that leaves it.
	 */
	private @Nullable AutosaveSnapshot buildAutosaveSnapshot() {
		if (closed) {
			return null;
		}
		Path target = projectPath;
		boolean isNew = false;
		if (target == null) {
			AutosaveHooks hooks = autosaveHooks;
			Path directory = hooks == null ? null : hooks.untitledDirectory().get();
			if (directory == null) {
				// No Projects folder to create the file in: the work stays dirty, as before autosave.
				return null;
			}
			target = ProjectFileNaming.uniqueProjectPath(directory, displayName());
			isNew = true;
		}
		ProjectDocument next = document
				.withEditor(ProjectPersistence.normalizeProjectEditor(store.editorJson()));
		return new AutosaveSnapshot(target, next, Json.stringify(next.toJson()),
				changeSequence.get(), isNew);
	}

	/** The write, on the autosave thread; the result is applied back on the UI thread. */
	private void writeAutosaveSnapshot(AutosaveSnapshot snapshot) {
		if (closed) {
			return;
		}
		String failure = writeFailure(snapshot);
		AutosaveHooks hooks = autosaveHooks;
		if (hooks == null || closed) {
			return;
		}
		hooks.uiExecutor().execute(() -> applyAutosaveResult(snapshot, failure));
	}

	/** Performs one snapshot write; {@code null} means success. Never throws. */
	private @Nullable String writeFailure(AutosaveSnapshot snapshot) {
		try {
			writeProjectJson(snapshot.target(), snapshot.json());
			return null;
		} catch (IOException | RuntimeException error) {
			String reason = describeWriteFailure(error);
			LOG.log(System.Logger.Level.WARNING,
					"autosave failed for " + snapshot.target() + ": " + reason, error);
			return reason;
		}
	}

	/** Applies one write result on the UI thread: the file, the project path and the dirty flag. */
	private void applyAutosaveResult(AutosaveSnapshot snapshot, @Nullable String failure) {
		AutosaveHooks hooks = autosaveHooks;
		if (closed || hooks == null) {
			return;
		}
		if (failure != null) {
			// Disk full, read-only folder, removed volume: the work stays dirty and the editor says
			// so persistently — never a "saved" toast that did not happen.
			autosaveFailure = failure;
			hooks.saveFailed().accept(failure);
			return;
		}
		Path current = projectPath;
		if (snapshot.targetIsNew() && current != null && !current.equals(snapshot.target())) {
			// The user saved the project somewhere else while this first autosave was writing:
			// the file it created is nobody's project. Remove it (and its backup generation)
			// instead of announcing a second copy to the dashboard.
			deleteStrayAutosave(snapshot.target());
			if (dirty.get()) {
				scheduleAutosave();
			}
			return;
		}
		boolean unchanged = changeSequence.get() == snapshot.sequence();
		autosaveFailure = null;
		if (unchanged) {
			document = snapshot.document();
			dirty.set(false);
		}
		if (projectPath == null) {
			// The write created the project's first file: later edits continue in the same file.
			projectPath = snapshot.target();
		}
		hooks.autosaved().accept(new Autosaved(snapshot.target(), snapshot.targetIsNew()));
		if (!unchanged && dirty.get()) {
			// Edited (or explicitly saved) while the file was being written: that state is not on
			// disk yet.
			scheduleAutosave();
		}
	}

	/** Removes an autosave file that lost the race to an explicit Save As. Best effort. */
	private static void deleteStrayAutosave(Path file) {
		for (Path stray : List.of(file, file.resolveSibling(file.getFileName() + ".bak"))) {
			try {
				Files.deleteIfExists(stray);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not remove the superseded autosave " + stray + ": " + error);
			}
		}
	}

	/** The reason a write failed, for the persistent warning: "No space left on device", … */
	private static String describeWriteFailure(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	/** Late-bound timeline scrub for playhead drags (non-blocking, latest wins). */
	public void setTimelineScrub(PlaybackSeek scrub) {
		timelineScrub.set(scrub);
	}

	public void setTimelineSeek(PlaybackSeek seek) {
	timelineSeek.set(seek);
}

	/** The project media path. Never null; the file itself may be missing from disk. */
	public String videoPath() {
	return document.videoPath();
}

	/** Points the session at the renamed project file (the file was moved by the app service). */
	public void relocate(Path newProjectPath) {
		projectPath = newProjectPath;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		cancelPendingAutosave();
		cleanupSync.shutdownNow();
		clipSync.shutdownNow();
		waveforms.close();
		waveformExecutor.shutdownNow();
		closeQuietly(timeSubscription);
		closeQuietly(changeSubscription);
		closeQuietly(playback);
		engine.close();
		for (AutoCloseable resource : ownedResources) {
			closeQuietly(resource);
		}
		ScheduledExecutorService autosave = autosaveScheduler;
		if (autosave != null) {
			autosave.shutdownNow();
		}
	}

	private static void closeQuietly(@Nullable AutoCloseable handle) {
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		} catch (Exception ignored) {
			// Closing a session must not throw.
		}
	}

	/** Timeline duration in seconds (the auto-mask pass needs it as a double). */
	public double durationSeconds() {
		return timelineDurationMs() / 1000.0;
	}

	/** Timeline duration from the clip regions, falling back to the media duration. */
	private long timelineDurationMs() {
		long end = 0L;
		for (ClipRegion clip : store.state().clipRegions()) {
			end = Math.max(end, clip.endMs());
		}
		if (end > 0L) {
			return end;
		}
		try {
			return Math.round(engine.status().durationSeconds() * 1000);
		} catch (RuntimeException ignored) {
			return 0L;
		}
	}

	/** Repaints the timeline when the store changes. */
	public dev.gravaai.core.event.Subscription onTimelineChange(Runnable listener) {
		return dataSource.onChange(listener);
	}
}
