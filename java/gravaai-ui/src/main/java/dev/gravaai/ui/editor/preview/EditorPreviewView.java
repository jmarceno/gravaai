package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.CameraGeometry;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackFrame;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.PreviewVideoReadiness;
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

	/** Current editor appearance snapshot; immutable so the decode thread can read it. */
	public void setScene(PreviewFrameMapper.PreviewScene nextScene) {
		scene.set(nextScene);
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
				boolean accepted = target.submit(
						PreviewFrameMapper.frameInput(frame, current, background));
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
		lastStatus = status;
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