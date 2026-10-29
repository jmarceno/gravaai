package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.playback.DecoderFrameSourceProvider;
import dev.gravaai.media.playback.PlaybackEngine;
import dev.gravaai.media.playback.PlaybackFrame;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackState;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.PlaybackTime;
import dev.gravaai.media.playback.WebcamSync;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.ProjectPreview;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.RecordingPaths;
import dev.gravaai.ui.editor.preview.PreviewFrameMapper;
import dev.gravaai.ui.editor.preview.PreviewRenderer;
import dev.gravaai.ui.editor.preview.PreviewWallpaper;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * The dashboard project-card hover preview (port of {@code ProjectHoverPreview.tsx} plus the
 * preview-loading half of {@code ProjectThumbnail.tsx}): a muted five-second pass through the
 * saved timeline rendered by the editor compositor at 4:3 over the thumbnail, hidden until frames
 * flow and stopped by the 5 s cap, a pause after it started, a load error, the 15 s stall timeout
 * or the window going hidden.
 *
 * <p>The control owns its own {@link PlaybackEngine} (no audio track: the React preview is
 * volume 0) and its own small {@link PreviewRenderer} at a fixed 4:3 render target, so it never
 * touches the editor's engine. Everything heavy runs off the FX thread; the stop conditions hop
 * back through {@link Platform#runLater}. React's {@code prefers-reduced-motion} guard has no
 * Java equivalent (plan §7): the preview plays for reduced-motion users too, and React's
 * thumbnail {@code <video>} fallback for failed poster images has no Java counterpart.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class ProjectHoverPreview extends StackPane {

	private static final System.Logger LOG = System.getLogger("gravaai.ui.dashboard");

	/** React renders the preview surface at 4:3 covering the card ({@code aspectRatio="4:3"}). */
	private static final int RENDER_WIDTH = 320;
	private static final int RENDER_HEIGHT = 240;

	/** React {@code updateTime}: stop as soon as the pass reaches five seconds. */
	private static final double STOP_AFTER_SECONDS = 5;

	/** React stall guard: a blocked decoder must leave the static thumbnail usable. */
	private static final long STALL_TIMEOUT_MS = 15_000;

	private final @Nullable ProjectManager projects;
	private final @Nullable String projectPath;
	/** Set for a raw recording card (React {@code RawThumbnail}): no saved project to read. */
	private final @Nullable String rawVideoPath;
	private final Runnable onFinish;
	/** Serializes the lifecycle: the start thread holds it while attaching media and playing. */
	private final Object lifecycle = new Object();
	private final AtomicBoolean finished = new AtomicBoolean();
	private final AtomicBoolean firstFrame = new AtomicBoolean();
	private final AtomicBoolean playingStarted = new AtomicBoolean();
	private final AtomicReference<PreviewFrameMapper.PreviewScene> scene = new AtomicReference<>();
	private @Nullable ProjectPreview telemetryPreview;
	private final PreviewWallpaper wallpaper = new PreviewWallpaper();
	private final ImageView imageView = new ImageView();

	private volatile @Nullable PlaybackEngine engine;
	private volatile @Nullable PreviewRenderer renderer;
	private @Nullable PauseTransition stallTimeout;
	private volatile long lastHiddenCheckNanos;

	ProjectHoverPreview(ProjectManager projects, String projectPath, Runnable onFinish) {
		this.projects = projects;
		this.projectPath = projectPath;
		this.rawVideoPath = null;
		this.onFinish = onFinish;
		getStyleClass().add("project-hover-preview");
		setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		// React pointer-events-none: the card stays clickable underneath the preview.
		setMouseTransparent(true);
		imageView.setPreserveRatio(false);
		imageView.setSmooth(true);
		imageView.setManaged(false);
		imageView.fitWidthProperty().bind(widthProperty());
		imageView.fitHeightProperty().bind(heightProperty());
		getChildren().add(imageView);
		// React opacity-0 until playing.
		setOpacity(0);
	}

	/**
	 * Raw-recording variant (React {@code RawRecordings.tsx} {@code RawThumbnail}): plays the
	 * recording itself with the default editor scene, no project file and no cursor telemetry.
	 */
	ProjectHoverPreview(String rawVideoPath, Runnable onFinish) {
		this.projects = null;
		this.projectPath = null;
		this.rawVideoPath = rawVideoPath;
		this.onFinish = onFinish;
		getStyleClass().add("project-hover-preview");
		setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		setMouseTransparent(true);
		imageView.setPreserveRatio(false);
		imageView.setSmooth(true);
		imageView.setManaged(false);
		imageView.fitWidthProperty().bind(widthProperty());
		imageView.fitHeightProperty().bind(heightProperty());
		getChildren().add(imageView);
		setOpacity(0);
	}

	/** Loads the preview data and starts the muted pass; call once from the FX thread. */
	void start() {
		Thread.ofVirtual().name("project-hover-preview").start(this::run);
	}

	/** Stops the pass (5 s cap, mouse exit, error, ...); idempotent, any thread. */
	void stop() {
		finish();
	}

	private void run() {
		try {
			PlaybackMedia media;
			if (rawVideoPath != null) {
				// RawThumbnail: the recording itself, one full-range clip, default editor scene.
				ProjectEditorState defaults = ProjectPersistence.normalizeProjectEditor(
						dev.gravaai.core.json.Json.parseJsonWithByteOrderMark("{}"));
				media = loadRawMedia(defaults);
				scene.set(PreviewFrameMapper.sceneFor(defaults, PreviewFrameMapper.CursorTrack.NONE,
						null));
			} else {
				// ProjectThumbnail effect: load the preview data (the 300 ms delay lives on the card).
				ProjectPreview preview = projects.readProjectPreview(Path.of(projectPath));
				if (finished.get()) {
					return;
				}
				ProjectEditorState state = ProjectPersistence.normalizeProjectEditor(
						preview.project().get("editor"));
				media = loadMedia(preview, state);
				if (finished.get()) {
					return;
				}
				// React passes the editor scene without keystroke events (they come from a
				// separate sidecar hook the hover preview does not mount), so no keystroke path.
				scene.set(PreviewFrameMapper.sceneFor(state,
						PreviewFrameMapper.CursorTrack.NONE, null));
				telemetryPreview = preview;
			}
			if (finished.get()) {
				return;
			}

			PreviewRenderer started = PreviewRenderer.start(RENDER_WIDTH, RENDER_HEIGHT);
			boolean mount;
			synchronized (lifecycle) {
				mount = !finished.get();
				if (mount) {
					renderer = started;
				}
			}
			if (!mount) {
				started.close();
				return;
			}
			Platform.runLater(() -> {
				if (finished.get()) {
					return;
				}
				try {
					started.attach(imageView);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING,
							"hover preview sink attach failed: " + error);
				}
			});

			PlaybackEngine created = PlaybackEngine.createDefault(
					DecoderFrameSourceProvider.ffmpegBacked());
			synchronized (lifecycle) {
				if (finished.get()) {
					started.close();
					created.close();
					return;
				}
				engine = created;
				created.onFrame(this::onFrame);
				created.onTime(this::onTime);
				created.onStatus(this::onStatus);
				created.setMedia(media);
				created.play();
			}
			// React: the stall timeout starts when the preview mounts, i.e. once the media is live.
			Platform.runLater(this::startStallTimeout);
			// React: the cursor telemetry arrives async from getCursorTelemetry(project.videoPath).
			if (telemetryPreview != null) {
				loadTelemetry(telemetryPreview,
						ProjectPersistence.normalizeProjectEditor(
								telemetryPreview.project().get("editor")));
			}
		} catch (Exception error) {
			if (!finished.get()) {
				LOG.log(System.Logger.Level.WARNING, "hover preview failed: " + error);
				finish();
			}
		}
	}

	/**
	 * Builds the playback media: the preview's resolved recording paths, the saved clip sequence
	 * (React falls back to one full-range clip once the duration is known), and the webcam
	 * offset/ranges from the saved webcam settings.
	 */
	private PlaybackMedia loadMedia(ProjectPreview preview, ProjectEditorState state) {
		Path video = Path.of(preview.videoPath());
		Path webcam = preview.webcamPath() == null ? null : Path.of(preview.webcamPath());
		WebcamOverlaySettings settings = state.webcam();
		long offset = settings == null ? 0 : settings.timeOffsetMs();
		List<WebcamSync.WebcamRange> ranges = new ArrayList<>();
		if (settings != null && settings.visibleRanges() != null) {
			for (WebcamOverlaySettings.TimeRange range : settings.visibleRanges()) {
				ranges.add(new WebcamSync.WebcamRange(range.startMs(), range.endMs()));
			}
		}
		double durationSeconds;
		try {
			durationSeconds = MediaProbe.probe(video).durationSeconds();
		} catch (RuntimeException error) {
			throw new IllegalStateException("could not probe " + video + ": " + error, error);
		}
		long sourceDurationMs = Double.isFinite(durationSeconds) && durationSeconds > 0
				? Math.round(durationSeconds * 1000)
				: 0;
		List<ClipRegion> clips = state.clipRegions();
		if (clips.isEmpty() && sourceDurationMs > 0) {
			clips = List.of(new ClipRegion("hover-preview", 0, sourceDurationMs, 0L, null, null,
					1, null, null, null, null));
		}
		return new PlaybackMedia(video.toString(), webcam == null ? null : webcam.toString(),
				offset, clips, sourceDurationMs, ranges.isEmpty() ? null : ranges);
	}

	/** Raw-recording media: the file itself with one full-range clip (React RawThumbnail). */
	private PlaybackMedia loadRawMedia(ProjectEditorState defaults) {
		Path video = Path.of(rawVideoPath);
		double durationSeconds;
		try {
			durationSeconds = MediaProbe.probe(video).durationSeconds();
		} catch (RuntimeException error) {
			throw new IllegalStateException("could not probe " + video + ": " + error, error);
		}
		long sourceDurationMs = Double.isFinite(durationSeconds) && durationSeconds > 0
				? Math.round(durationSeconds * 1000)
				: 0;
		List<ClipRegion> clips = sourceDurationMs > 0
				? List.of(new ClipRegion("raw-hover-preview", 0, sourceDurationMs, 0L, null, null,
						1, null, null, null, null))
				: List.of();
		return new PlaybackMedia(video.toString(), null, 0, clips, sourceDurationMs, null);
	}

	/** Reads the recording's cursor sidecar next to the project's own video path (React parity). */
	private void loadTelemetry(ProjectPreview preview, ProjectEditorState state) {
		if (finished.get()) {
			return;
		}
		try {
			Path telemetry = Path.of(RecordingPaths.getTelemetryPathForVideo(
					preview.project().get("videoPath").asString()));
			if (!Files.isRegularFile(telemetry)) {
				return;
			}
			var parsed = dev.gravaai.core.json.Json.parseJsonWithByteOrderMark(
					Files.readString(telemetry, java.nio.charset.StandardCharsets.UTF_8));
			var samplesNode = parsed.isArray() ? parsed : parsed.get("samples");
			List<CursorTelemetry.CursorTelemetryPoint> points =
					CursorTelemetry.normalizeCursorTelemetrySamples(samplesNode);
			List<dev.gravaai.media.compose.CursorSample> samples = new ArrayList<>(points.size());
			for (CursorTelemetry.CursorTelemetryPoint point : points) {
				samples.add(new dev.gravaai.media.compose.CursorSample(
						point.timeMs() / 1000.0, point.cx(), point.cy()));
			}
			scene.set(PreviewFrameMapper.sceneFor(state,
					new PreviewFrameMapper.CursorTrack(samples, state.showCursor()), null));
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "hover preview cursor telemetry ignored: "
					+ error);
		}
	}

	private void onFrame(PlaybackFrame frame) {
		if (finished.get()) {
			return;
		}
		PreviewRenderer active = renderer;
		PreviewFrameMapper.PreviewScene current = scene.get();
		if (active == null || current == null) {
			return;
		}
		try {
			active.submit(PreviewFrameMapper.frameInput(frame, current,
					wallpaper.backgroundFor(current, frame.timelineSeconds())));
			if (firstFrame.compareAndSet(false, true)) {
				Platform.runLater(() -> {
					if (!finished.get()) {
						setOpacity(1);
					}
				});
			}
			stopWhenHidden();
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "hover preview render failed: " + error);
			finish();
		}
	}

	private void onTime(PlaybackTime time) {
		if (time.timelineSeconds() >= STOP_AFTER_SECONDS) {
			// React updateTime: pause the transport, then finish (idempotent).
			PlaybackEngine active = engine;
			if (active != null) {
				active.pause();
			}
			finish();
		}
	}

	private void onStatus(PlaybackStatus status) {
		// React updatePlaying: the first playing state starts the pass; any stop afterwards
		// (pause or the timeline end) finishes it.
		if (status.state() == PlaybackState.PLAYING) {
			playingStarted.set(true);
		} else if (playingStarted.get()) {
			finish();
		}
	}

	/** React document.hidden: a minimized window (or a detached card) stops the pass. */
	private void stopWhenHidden() {
		long now = System.nanoTime();
		if (now - lastHiddenCheckNanos < 500_000_000L) {
			return;
		}
		lastHiddenCheckNanos = now;
		Platform.runLater(() -> {
			if (finished.get()) {
				return;
			}
			var window = getScene() == null ? null : getScene().getWindow();
			if (window == null
					|| window instanceof javafx.stage.Stage stage && stage.isIconified()) {
				finish();
			}
		});
	}

	private void startStallTimeout() {
		if (finished.get()) {
			return;
		}
		stallTimeout = new PauseTransition(Duration.millis(STALL_TIMEOUT_MS));
		stallTimeout.setOnFinished(event -> finish());
		stallTimeout.play();
	}

	/**
	 * Idempotent stop: notifies the card (which unmounts the node) and releases the engine and
	 * the GL renderer off the FX thread. The heavy teardown runs on a virtual thread because the
	 * renderer close waits up to 500 ms for the GL context.
	 */
	private void finish() {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		PauseTransition timeout = stallTimeout;
		if (timeout != null) {
			timeout.stop();
		}
		Platform.runLater(() -> {
			onFinish.run();
			Thread.ofVirtual().name("project-hover-preview-close").start(this::release);
		});
	}

	private void release() {
		synchronized (lifecycle) {
			PlaybackEngine playback = engine;
			PreviewRenderer render = renderer;
			engine = null;
			renderer = null;
			if (playback != null) {
				playback.close();
			}
			if (render != null) {
				render.close();
			}
		}
		wallpaper.close();
	}

	@Override
	protected void layoutChildren() {
		imageView.resizeRelocate(0, 0, getWidth(), getHeight());
	}
}
