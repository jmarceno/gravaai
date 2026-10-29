package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AudioCommands;
import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.result.Result;
import dev.gravaai.media.playback.AudioMediaClock;
import dev.gravaai.media.playback.DecoderFrameSourceProvider;
import dev.gravaai.media.playback.PlaybackAudioProducer;
import dev.gravaai.media.playback.PlaybackAudioTrack;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.media.playback.PlaybackEngine;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackMediaLoader;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.ProjectMediaSources;
import dev.gravaai.ui.editor.preview.PlaybackControllerPort;
import dev.gravaai.ui.editor.timeline.PlaybackSeek;
import dev.gravaai.ui.editor.timeline.StoreTimelineCommands;
import dev.gravaai.ui.editor.timeline.StoreTimelineDataSource;
import dev.gravaai.ui.editor.timeline.TimelineCommands;
import dev.gravaai.ui.editor.timeline.TimelineDataSource;
import dev.gravaai.ui.editor.timeline.TimelineSelection;
import dev.gravaai.ui.editor.timeline.WaveformProvider;
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

	/** The project file; {@code null} for an unsaved recording until it is saved. */
	private @Nullable Path projectPath;
	private ProjectDocument document;
	private ProjectDocument mediaSource;
	private final EditorStore store;
	private final PlaybackEngine engine;
	private final PlaybackControllerPort playback;
	private final AtomicBoolean dirty = new AtomicBoolean();
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
	private final TimelineCommands timelineCommands;
	private volatile @Nullable PlaybackAudioTrack audioTrack;
	private volatile boolean closed;
	/** The clips playback was last given; null until media is attached. */
	private volatile @Nullable List<ClipRegion> playbackClips;
	private final java.util.concurrent.ExecutorService clipSync =
			java.util.concurrent.Executors.newSingleThreadExecutor(
					Thread.ofVirtual().name("gravaai-playback-clips").factory());

	private EditorSession(@Nullable Path projectPath, ProjectDocument document, ProjectDocument mediaSource) {
		this.projectPath = projectPath;
		this.document = document;
		this.mediaSource = mediaSource;
		this.store = new EditorStore(document.editor());
		// Cheap audio-master clock; the media/audio attach happens in attachMedia() after the window
		// is visible (P2.11 startup budget).
		this.engine = new PlaybackEngine(PlaybackEngine.Dependencies.production(
				DecoderFrameSourceProvider.ffmpegBacked(), new AudioMediaClock(), true));
		this.playback = new PlaybackControllerPort(engine);
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
			syncPlaybackClips();
		});
		this.dataSource = new StoreTimelineDataSource(store, this::timelineDurationMs,
				() -> (double) playheadMs.get(), WaveformProvider.empty());
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

	/** Heavy media attach (probe/audio producer/decode), called off the FX thread after the window is visible. */
	public void attachMedia() {
		LOG.log(System.Logger.Level.INFO, "editor media attach start: " + mediaSource.videoPath());
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
		LOG.log(System.Logger.Level.INFO, "editor engine setMedia start");
		engine.setMedia(media);
		playbackClips = media.clips();
		// setMedia emits the initial frame for the preview; no seek here (it forces a ring fill
		// and can block on slow decoders - the preview can play/seek once the user asks).
		LOG.log(System.Logger.Level.INFO, "editor engine media set");
		LOG.log(System.Logger.Level.INFO,
				"editor media attached, duration=" + media.sourceDurationMs() + " ms");
		try {
			LOG.log(System.Logger.Level.INFO, "editor audio attach start");
			PlaybackAudioTrack audio = PlaybackAudioProducer.openWithPulse(media.videoPath(),
					media.clips(),
					NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg"));
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
		PlaybackMedia media = loadMedia(mediaSource);
		if (media == null) {
			LOG.log(System.Logger.Level.WARNING,
					"editor media replace: no resolvable media for " + mediaSource.videoPath());
			return;
		}
		engine.setMedia(media);
		playbackClips = media.clips();
		PlaybackAudioTrack previous = audioTrack;
		PlaybackAudioTrack audio = null;
		try {
			audio = PlaybackAudioProducer.openWithPulse(media.videoPath(),
					media.clips(),
					NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg"));
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
				PlaybackAudioTrack audio = PlaybackAudioProducer.openWithPulse(video, clips,
						NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg"));
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

	/** Loads a project through the canonical persistence path and starts a playback session. */
	public static EditorSession open(Path projectPath) throws IOException {
		ProjectDocument document = ProjectDocument.load(projectPath);
		return new EditorSession(projectPath, document, document);
	}

	/**
	 * A recording as a new, unsaved project ({@code useInitialEditorSource}: the recording session
	 * loads with {@code currentProjectPath = null}; the first save asks where to put the project).
	 * The webcam track of the session is enabled with its measured offset.
	 */
	public static EditorSession openRecording(Path videoPath, @Nullable String webcamPath,
			long webcamTimeOffsetMs) {
		tools.jackson.databind.node.ObjectNode editor = dev.gravaai.core.json.Json.nodeFactory().objectNode();
		if (webcamPath != null && !webcamPath.isBlank()) {
			tools.jackson.databind.node.ObjectNode webcam = editor.putObject("webcam");
			webcam.put("enabled", true);
			webcam.put("sourcePath", webcamPath);
			webcam.put("timeOffsetMs", webcamTimeOffsetMs);
		}
		// useTimelineProjection: a source without clips starts as one full-length clip. Seeded here,
		// before the store exists, so the first undo step cannot return to an empty timeline.
		long durationMs = probeDurationMs(videoPath);
		if (durationMs > 0) {
			editor.set("clipRegions", ProjectDocument.writeClipRegions(
					dev.gravaai.core.project.Clips.trimsToClips(List.of(), durationMs)));
		}
		ProjectDocument document = ProjectDocument.create(
				videoPath.toAbsolutePath().normalize().toString(),
				ProjectPersistence.normalizeProjectEditor(editor));
		return new EditorSession(null, document, document);
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
						NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg"));
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

	/** The project file, or {@code null} for an unsaved recording. */
	public @Nullable Path projectPath() {
		return projectPath;
	}

	public EditorStore store() {
		return store;
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
		if (projectPath == null) {
			throw new IllegalStateException("The project has not been saved yet; use Save As.");
		}
		ProjectDocument next = document
				.withEditor(ProjectPersistence.normalizeProjectEditor(store.editorJson()));
		next.write(projectPath);
		document = next;
		dirty.set(false);
	}

	/** Save As: writes the same project data to a new file and continues there. */
	public void saveAs(Path target) throws IOException {
		ProjectDocument next = document
				.withEditor(ProjectPersistence.normalizeProjectEditor(store.editorJson()));
		next.write(target);
		document = next;
		projectPath = target;
		dirty.set(false);
	}

	/** Late-bound timeline scrub for playhead drags (non-blocking, latest wins). */
	public void setTimelineScrub(PlaybackSeek scrub) {
		timelineScrub.set(scrub);
	}

	public void setTimelineSeek(PlaybackSeek seek) {
	timelineSeek.set(seek);
}

	/** The project media path (null for a project without a video). */
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
		clipSync.shutdownNow();
		closeQuietly(timeSubscription);
		closeQuietly(changeSubscription);
		closeQuietly(playback);
		engine.close();
	}

	private static void closeQuietly(AutoCloseable handle) {
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		} catch (Exception ignored) {
			// Closing a session must not throw.
		}
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
