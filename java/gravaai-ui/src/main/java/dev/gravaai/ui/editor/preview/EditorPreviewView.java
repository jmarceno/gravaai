package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.CameraGeometry;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectEditorInputs;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackFrame;
import dev.gravaai.media.playback.PlaybackState;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.PreviewVideoReadiness;
import dev.gravaai.media.segmentation.MaskTuning;
import dev.gravaai.media.segmentation.WebcamMaskQuality;
import dev.gravaai.media.segmentation.WebcamSegmenter;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;

/**
 * The compositor-backed preview surface inside the editor preview frame (plan §5.9, P2.5).
 *
 * <p>It owns one {@link PreviewRenderer} for the current panel pixel size and forwards the
 * engine's decoded frames to it non-blocking: {@link PreviewRenderer#submit} copies the frame into
 * the render queue and returns, so {@code setMedia}/{@code onFrame} never wait for the compositor
 * (P2.11 first-preview). Frames arrive off the FX thread; only the scene-graph work and the
 * renderer start/restart hops to it.
 *
 * <p>When the panel is resized, the renderer is recreated for the new size after a short settle
 * (the GL context and compositor are size-bound). While no renderer is ready the control shows the
 * previous image (or nothing), and the first compositor frame replaces it.
 */
public final class EditorPreviewView extends Region {

	private static final System.Logger LOG = System.getLogger("gravaai.ui.preview");
	private static final int MIN_HEIGHT = 180;
	private static final int MAX_WIDTH = 1280;
	private static final int MAX_HEIGHT = 720;

	private final ImageView imageView = new ImageView();
	private final AtomicReference<PreviewFrameMapper.@Nullable PreviewScene> scene =
			new AtomicReference<>(null);
	private final ReadOnlyBooleanWrapper ready = new ReadOnlyBooleanWrapper(false);
	private final PreviewVideoReadiness readiness = new PreviewVideoReadiness(this::publishReady);

	private @Nullable PlaybackController controller;
	private @Nullable AutoCloseable frameSubscription;
	private @Nullable AutoCloseable statusSubscription;
	private @Nullable AutoCloseable timeSubscription;
	private volatile @Nullable PreviewRenderer renderer;
	/** Last status pushed by the engine; used for repaints without locking the engine on FX. */
	private volatile @Nullable PlaybackStatus lastStatus;
	/** Stable copy of the last decoded frame, replayed without seeking the engine. */
	private volatile @Nullable PreviewFrameSnapshot lastFrame;
	private volatile @Nullable Runnable framePresentedListener;
	/** Extension render hooks handed to every renderer this view starts (P3.3). */
	private volatile FrameOverlayHooks overlayHooks = FrameOverlayHooks.NONE;
	private int targetWidth = -1;
	private int targetHeight = -1;
	private boolean starting;
	private boolean disposed;

	public EditorPreviewView() {
		getStyleClass().add("editor-preview__surface");
		imageView.setPreserveRatio(false);
		imageView.setSmooth(true);
		imageView.setManaged(false);
		getChildren().add(imageView);
		widthProperty().addListener((observable, oldValue, newValue) -> requestRendererSize());
		heightProperty().addListener((observable, oldValue, newValue) -> requestRendererSize());
	}

	/**
	 * Registers the FX-thread callback that runs after each presented preview frame; the
	 * annotation overlay uses it to follow the animated camera transform.
	 */
	public void setFramePresentedListener(Runnable listener) {
		this.framePresentedListener = listener;
		PreviewRenderer active = renderer;
		if (active != null) {
			active.setPresentedListener(listener);
		}
	}

	/**
	 * Video-layer geometry of the last presented frame: where the recording rect sits and the
	 * camera transform applied to it. The annotation overlay maps percent positions through it.
	 */
	public CameraGeometry geometry() {
		PreviewRenderer active = renderer;
		if (active == null) {
			int width = (int) Math.max(1, Math.round(getWidth()));
			int height = (int) Math.max(1, Math.round(getHeight()));
			return CameraGeometry.fullFrame(width, height);
		}
		return active.geometry();
	}

	/** Sets the extension render hooks for the current and any later renderer (P3.3). */
	public void setOverlayHooks(FrameOverlayHooks hooks) {
		this.overlayHooks = hooks == null ? FrameOverlayHooks.NONE : hooks;
		PreviewRenderer active = renderer;
		if (active != null) {
			active.setOverlayHooks(this.overlayHooks);
		}
	}

	/**
	 * Current editor appearance snapshot; immutable so the decode thread can read it. A scene change
	 * also re-evaluates the segmentation path, because the Advanced high-quality switch changes it
	 * while the playback state stays the same.
	 */
	public void setScene(PreviewFrameMapper.PreviewScene nextScene) {
		scene.set(nextScene);
		syncMaskQuality();
	}

	/** Subscribes to a playback engine; may be called once (null clears it). */
	public void bind(PlaybackController playback) {
		unbind();
		controller = playback;
		if (playback == null) {
			return;
		}
		// First-frame readiness (usePreviewVideoReady semantics): false until a frame with known
		// dimensions is drawn; transient seek drops keep the previous frame/ready state.
		readiness.attach(new PreviewVideoReadiness.MediaState(0, 0, 0));
		frameSubscription = playback.onFrame(this::onFrame);
		statusSubscription = playback.onStatus(this::onStatus);
		timeSubscription = playback.onTime(time -> { /* the controls own the playhead display */ });
		requestRendererSize();
	}

	private void unbind() {
		closeQuietly(frameSubscription);
		closeQuietly(statusSubscription);
		closeQuietly(timeSubscription);
		frameSubscription = null;
		statusSubscription = null;
		timeSubscription = null;
		controller = null;
		lastStatus = null;
		lastFrame = null;
		readiness.detach();
		ready.set(false);
	}

	// Video wallpaper decoder: used on the engine thread, closed on dispose.
	private final PreviewWallpaper wallpaper = new PreviewWallpaper();

	// Webcam background effect: the app layer wires the segmenter (null keeps the effect off). The
	// mask cache is guarded by the frame snapshot monitor (every submitSnapshot caller holds it).
	private @Nullable WebcamSegmenter webcamSegmenter;
	private @Nullable RgbaFrame lastMaskedWebcamFrame;
	private @Nullable RgbaFrame lastWebcamMask;
	/** Edge settings the cached mask was shaped with; a change invalidates the cache. */
	private MaskTuning lastMaskedTuning = MaskTuning.DEFAULT;
	/** Segmentation path the cached mask came from; a fast/slow switch invalidates the cache. */
	private @Nullable WebcamMaskQuality lastMaskQuality;
	/**
	 * Path last asked of the segmenter, so the status callback does not re-send an unchanged switch
	 * (and re-trigger a repaint) on every status tick.
	 */
	private @Nullable WebcamMaskQuality lastRequestedQuality;
	/**
	 * Set while this view is repainting because a mask finished on the segmenter's worker thread:
	 * that repaint must consume the mask already in hand rather than queue another inference.
	 * Guarded by the frame snapshot monitor.
	 */
	private boolean maskRepaintPending;

	/** Supplies the person segmenter backing the webcam background effect; null keeps it off. */
	public void setWebcamSegmenter(@Nullable WebcamSegmenter segmenter) {
		WebcamSegmenter previous = this.webcamSegmenter;
		if (previous != segmenter && previous != null) {
			previous.setMaskReadyListener(null);
		}
		this.webcamSegmenter = segmenter;
		lastMaskedWebcamFrame = null;
		lastWebcamMask = null;
		lastMaskedTuning = MaskTuning.DEFAULT;
		lastMaskQuality = null;
		lastRequestedQuality = null;
		if (segmenter != null) {
			// The mask lands on the segmenter's worker thread, long after the frame that asked for
			// it was drawn. Repainting then is what makes the edge controls visible while paused:
			// the render loop only draws frames the engine submits, and a paused engine submits
			// none. Drop the cached mask first so the repaint asks for a fresh one.
			segmenter.setMaskReadyListener(this::onMaskReady);
		}
	}

	/**
	 * A mask finished on the segmenter's worker thread: repaint the retained frame so the new edge
	 * shape reaches the screen. The cache is guarded by the frame snapshot monitor, which a repaint
	 * takes, so the flag must be set under it too.
	 *
	 * <p>Only a paused preview needs this: while playing, the engine's own frames arrive at the
	 * frame rate and pick the mask up anyway, and repainting on every delivered mask would double
	 * the render work for no visible gain.
	 */
	private void onMaskReady() {
		PreviewFrameSnapshot snapshot = lastFrame;
		PlaybackStatus status = lastStatus;
		// The cached status, not engine.isPlaying(): this runs on the mask worker and must not
		// take the engine lock (the frame callback already holds it while decoding).
		if (snapshot == null || disposed || status == null || status.state() == PlaybackState.PLAYING) {
			return;
		}
		synchronized (snapshot) {
			maskRepaintPending = true;
			// The delivered mask may pair with a buffer this view cached as "no mask yet"; drop
			// that answer so the repaint picks the completed mask up.
			lastMaskedWebcamFrame = null;
			lastWebcamMask = null;
		}
		Platform.runLater(this::requestRepaint);
	}

	private void onFrame(PlaybackFrame frame) {
		if (disposed) {
			return;
		}
		// Retain the decoder pixels before the engine reuses its buffers. This is also what lets a
		// renderer that is still starting (or is recreated on a layout change) present the current
		// frame without a seek. The cache is deliberately independent of the renderer and scene.
		PreviewFrameSnapshot snapshot = lastFrame;
		if (snapshot == null) {
			snapshot = new PreviewFrameSnapshot();
			snapshot.update(frame);
			lastFrame = snapshot;
		} else {
			snapshot.update(frame);
		}
		submitSnapshot(snapshot, renderer, scene.get(), true);
	}

	/**
	 * Composites a retained frame with the current scene. Returns false when there is nothing to
	 * draw or the renderer cannot accept it. This path never touches the playback controller: an
	 * appearance repaint must not seek, because {@code PlaybackEngine.seek} flushes/restarts the
	 * audio track and audibly cracks while playing.
	 *
	 * @param current the scene to composite with; null skips the submit (no scene yet)
	 */
	private boolean submitSnapshot(@Nullable PreviewFrameSnapshot snapshot, @Nullable PreviewRenderer target,
			PreviewFrameMapper.@Nullable PreviewScene current, boolean updateReadiness) {
		if (snapshot == null || target == null || current == null) {
			return false;
		}
		try {
			Background background = wallpaper.backgroundFor(current, snapshot.timelineSeconds());
			// The snapshot reuses its pixel buffers. Keep its monitor until PreviewFrameQueue has
			// copied the mapped input, otherwise the next decoded frame could overwrite the bytes
			// while the queue is still copying them.
			synchronized (snapshot) {
				PlaybackFrame frame = snapshot.playbackFrame();
				boolean accepted = target.submit(PreviewFrameMapper.frameInput(frame, current,
						background, webcamMask(frame, current, maskRepaintPending)));
				// One repaint per delivered mask: the next one must ask the segmenter again.
				maskRepaintPending = false;
				if (accepted && updateReadiness) {
					markReadiness(frame);
				}
				return accepted;
			}
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "preview frame render failed: " + error);
			if (updateReadiness) {
				readiness.onError();
			}
			return false;
		}
	}

	/**
	 * The person mask for the decoded webcam frame when the scene enables a background effect, else
	 * null. The decoded buffers rotate in turn, so identical frames return the cached mask; only a
	 * buffer flip, an edge-control change or a fast/slow path switch recomputes it. The segmenter
	 * may be asynchronous (the preview path keeps inference off this thread), so the mask it returns
	 * is the most recent completed one, not necessarily this frame's; caching it per buffer keeps
	 * that stable across repaints.
	 *
	 * <p>On the repaint this view requests for a finished mask ({@link #onMaskReady}), the mask is
	 * already in hand: reading {@link WebcamSegmenter#completedMask()} instead of asking again is
	 * what stops that repaint from queueing another inference and looping forever while paused.
	 * Callers hold the frame snapshot monitor.
	 */
	private @Nullable RgbaFrame webcamMask(PlaybackFrame frame, PreviewFrameMapper.PreviewScene scene,
			boolean maskAlreadyDelivered) {
		RgbaFrame webcamFrame = frame.webcamFrame();
		if (webcamFrame == null || !scene.webcam().segmentsPerson()) {
			return null;
		}
		if (webcamSegmenter == null) {
			return null;
		}
		MaskTuning tuning = ProjectEditorInputs.maskTuning(scene.editor());
		WebcamMaskQuality quality = currentMaskQuality(scene);
		if (webcamFrame == lastMaskedWebcamFrame && tuning.equals(lastMaskedTuning)
				&& quality == lastMaskQuality) {
			// Same decoded buffer, edge settings and path: answer from the cache. A null entry is a
			// real answer (the segmenter had no mask yet), not a cache miss.
			return lastWebcamMask;
		}
		lastMaskedWebcamFrame = webcamFrame;
		lastMaskedTuning = tuning;
		lastMaskQuality = quality;
		lastWebcamMask = maskAlreadyDelivered
				? webcamSegmenter.completedMask()
				: webcamSegmenter.maskFor(webcamFrame, tuning);
		return lastWebcamMask;
	}

	/**
	 * The segmentation path this frame wants: the precise model unless the timeline is playing and
	 * the project did not ask for the high-quality mask during playback.
	 *
	 * <p>Scrubbing counts as a stopped state (the engine reports {@code SEEKING} for it), so a scrub
	 * shows the same clean edge the paused frame will. {@code ENDED} is stopped too. Before the
	 * engine reports anything, the fast path is assumed: it is the one that must never cost a frame
	 * budget, and the first status arrives with the first frame.
	 */
	private WebcamMaskQuality currentMaskQuality(PreviewFrameMapper.PreviewScene scene) {
		PlaybackStatus status = lastStatus;
		boolean playing = status != null && status.state() == PlaybackState.PLAYING;
		if (!playing || highQualityWhilePlaying(scene)) {
			return WebcamMaskQuality.PRECISE;
		}
		return WebcamMaskQuality.FAST;
	}

	/** The project's Advanced "high-quality mask while playing" switch. */
	private static boolean highQualityWhilePlaying(PreviewFrameMapper.PreviewScene scene) {
		var webcam = scene.editor() == null ? null : scene.editor().webcam();
		return webcam != null && webcam.highQualityMaskWhilePlaying();
	}

	/**
	 * Points the segmenter at the path the current state wants. Called on every status change so a
	 * pause starts the precise inference immediately rather than on the next decoded frame, and a
	 * play resumes the cheap one.
	 *
	 * <p>Goes through the {@link WebcamSegmenter} port rather than downcasting: the app wraps the
	 * dual-path selector in {@link AsyncWebcamSegmenter}, which forwards the switch to its delegate
	 * and drops a queued request that belongs to the path being left. A segmenter with a single
	 * model (export, still frames) ignores the call, which is why there is no type check here.</p>
	 */
	private void syncMaskQuality() {
		WebcamSegmenter segmenter = webcamSegmenter;
		if (segmenter == null) {
			return;
		}
		PreviewFrameMapper.PreviewScene scene = this.scene.get();
		if (scene == null) {
			return;
		}
		WebcamMaskQuality wanted = currentMaskQuality(scene);
		if (wanted == lastRequestedQuality) {
			return;
		}
		lastRequestedQuality = wanted;
		segmenter.setQuality(wanted);
		// The cached mask belongs to the previous path; drop it so the repaint runs the new model.
		PreviewFrameSnapshot snapshot = lastFrame;
		if (snapshot != null) {
			synchronized (snapshot) {
				lastMaskedWebcamFrame = null;
				lastWebcamMask = null;
			}
		}
		// A pause delivers no new frame of its own, so ask for the precise mask right away: the
		// repaint after it lands is what swaps the fast edge for the clean one.
		if (wanted == WebcamMaskQuality.PRECISE) {
			Platform.runLater(this::requestRepaint);
		}
	}

	private void markReadiness(PlaybackFrame frame) {
		var source = frame.sourceFrame();
		if (source != null) {
			readiness.onMediaState(new PreviewVideoReadiness.MediaState(
					PreviewVideoReadiness.HAVE_CURRENT_DATA, source.width(), source.height()));
			readiness.onLoadedData();
		} else {
			// A timeline gap has no frame data; do not reset readiness for transient gaps.
			readiness.onMediaState(new PreviewVideoReadiness.MediaState(0, 0, 0));
		}
	}

	private void onStatus(PlaybackStatus status) {
		// Cache the status so repaint/seek paths never take the engine lock on the FX thread. A
		// seek keeps the last presented frame (the React hook ignores the transient drop).
		PlaybackState previousState = lastStatus == null ? null : lastStatus.state();
		lastStatus = status;
		if (status.state() != previousState) {
			// Play ⇄ pause decides which model segments the next frame; scrubbing (SEEKING) and end
			// of playback both want the precise one.
			syncMaskQuality();
		}
		if (status.state() == dev.gravaai.media.playback.PlaybackState.SEEKING) {
			readiness.onSeeked();
		}
	}

	/** True once a decoded frame with known dimensions reached the compositor. */
	public ReadOnlyBooleanProperty readyProperty() {
		return ready.getReadOnlyProperty();
	}

	public boolean isReady() {
		return ready.get();
	}

	private void publishReady(boolean value) {
		if (Platform.isFxApplicationThread()) {
			ready.set(value);
		} else {
			Platform.runLater(() -> ready.set(value));
		}
	}

	/**
	 * Re-renders the last retained frame with the current {@link PreviewScene}, e.g. after the
	 * editor appearance changed. Never calls {@link PlaybackController#seek}: the next decoded
	 * frame also picks up the new scene, and seeking would flush the audio sink while playing.
	 */
	public void requestRepaint() {
		if (disposed) {
			return;
		}
		submitSnapshot(lastFrame, renderer, scene.get(), true);
	}

	/** Renders the wallpaper-only idle frame once a renderer exists. */
	public void renderIdle() {
		PreviewRenderer preview = renderer;
		PreviewFrameMapper.PreviewScene current = scene.get();
		if (preview == null || current == null) {
			return;
		}
		preview.renderIdle(PreviewFrameMapper.idleInput(0, 0, current));
	}

	/** Last retained decoded frame (diagnostics/tests); null before the first frame arrives. */
	@Nullable PreviewFrameSnapshot lastFrameSnapshot() {
		return lastFrame;
	}

	private void requestRendererSize() {
		if (disposed || starting) {
			return;
		}
		int width = (int) Math.round(getWidth());
		int height = (int) Math.round(getHeight());
		if (width <= 0 || height <= 0) {
			return;
		}
		int[] target = targetSize(width, height);
		if (renderer != null && target[0] == targetWidth && target[1] == targetHeight) {
			return;
		}
		if (target[0] == targetWidth && target[1] == targetHeight) {
			return;
		}
		startRenderer(target[0], target[1]);
	}

	/**
	 * Render target for a panel size: the panel's aspect (the image view stretches the frame to
	 * the panel, so any other aspect distorts it), at most 1280x720-ish, at least 180 px on the
	 * short side, even pixels. Scaling is uniform: clamping each side alone squashed a narrow
	 * 9:16 preview to a 320 px wide frame.
	 */
	static int[] targetSize(int panelWidth, int panelHeight) {
		double width = Math.max(1, panelWidth);
		double height = Math.max(1, panelHeight);
		double scale = Math.min(1.0, Math.min(MAX_WIDTH / width, MAX_HEIGHT / height));
		if (Math.min(width, height) * scale < MIN_HEIGHT) {
			scale = MIN_HEIGHT / Math.min(width, height);
		}
		int targetWidth = Math.max(2, (int) Math.round(width * scale));
		int targetHeight = Math.max(2, (int) Math.round(height * scale));
		targetWidth -= targetWidth & 1;
		targetHeight -= targetHeight & 1;
		return new int[] {targetWidth, targetHeight};
	}

	private void startRenderer(int width, int height) {
		if (disposed) {
			return;
		}
		starting = true;
		targetWidth = width;
		targetHeight = height;
		PreviewRenderer previous = renderer;
		renderer = null;
		Thread.ofVirtual().name("preview-renderer-start").start(() -> {
			if (previous != null) {
				previous.close();
			}
			PreviewRenderer created;
			try {
				created = PreviewRenderer.start(width, height);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "preview renderer unavailable: " + error);
				Platform.runLater(() -> starting = false);
				return;
			}
			Platform.runLater(() -> {
				if (disposed) {
					created.close();
					return;
				}
				renderer = created;
				Runnable presentedListener = framePresentedListener;
				created.setPresentedListener(presentedListener);
				created.setOverlayHooks(overlayHooks);
				try {
					created.attach(imageView);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "preview sink attach failed: " + error);
				}
				starting = false;
				// A new renderer starts with an empty framebuffer. Re-submit the retained frame
				// first (paused + layout/tab changes), or the wallpaper idle frame before any media.
				if (!submitSnapshot(lastFrame, created, scene.get(), true)) {
					renderIdle();
				}
				// Size changes during the start were ignored; catch up with the final layout.
				requestRendererSize();
			});
		});
	}

	@Override
	protected void layoutChildren() {
		imageView.resizeRelocate(0, 0, getWidth(), getHeight());
	}

	/** Frames copied off the engine thread into the non-blocking queue. */
	public long framesSubmitted() {
		PreviewRenderer active = renderer;
		return active == null ? 0 : active.framesSubmitted();
	}

	/** Frames the GL thread drew from the queue. */
	public long framesDrawn() {
		PreviewRenderer active = renderer;
		return active == null ? 0 : active.framesDrawn();
	}

	/** Frames the compositor handed to the FX sink (diagnostics/snapshot harness). */
	public long framesReceived() {
		PreviewRenderer active = renderer;
		return active == null ? 0 : active.framesReceived();
	}

	/** Frames dropped by the sink because FX had not presented the previous one. */
	public long framesDropped() {
		PreviewRenderer active = renderer;
		return active == null ? 0 : active.framesDropped();
	}

	/** Render target width, or -1 while no renderer is ready. */
	public int rendererWidth() {
		PreviewRenderer active = renderer;
		return active == null ? -1 : active.width();
	}

	/** Render target height, or -1 while no renderer is ready. */
	public int rendererHeight() {
		PreviewRenderer active = renderer;
		return active == null ? -1 : active.height();
	}

	/** Renderer failure, or null. */
	public @Nullable Throwable rendererFailure() {
		PreviewRenderer active = renderer;
		return active == null ? null : active.failure();
	}

	/** Stops the renderer and unsubscribes; call from the panel teardown. */
	public void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;
		WebcamSegmenter segmenter = webcamSegmenter;
		if (segmenter != null) {
			// The worker outlives this view (the app closes it): stop it calling back into a
			// disposed preview.
			segmenter.setMaskReadyListener(null);
		}
		readiness.detach();
		ready.set(false);
		unbind();
		wallpaper.close();
		PreviewRenderer active = renderer;
		renderer = null;
		if (active != null) {
			active.close();
		}
	}

	private static void closeQuietly(@Nullable AutoCloseable handle) {
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		} catch (Exception ignored) {
			// listener handles are best-effort
		}
	}
}