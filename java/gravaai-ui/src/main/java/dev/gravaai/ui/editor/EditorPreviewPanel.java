package dev.gravaai.ui.editor;

import java.net.URL;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.AspectRatios;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.ui.editor.preview.AnnotationOverlayController;
import dev.gravaai.ui.editor.preview.EditorPreviewView;
import dev.gravaai.ui.editor.preview.PreviewFrameMapper;
import dev.gravaai.ui.editor.timeline.PlaybackSeek;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Skeleton;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Preview column (port of {@code layout/EditorPreviewPanel.tsx}): the aspect/ratio + crop toolbar,
 * the aspect-fitted preview frame fed by the compositor, and the wallpaper/plate fallback used
 * before a playback engine is bound.
 *
 * <p>P2.5 (task-17): {@link #setPlaybackController(PlaybackController)} mounts the
 * {@link EditorPreviewView} (compositor {@code PixelBuffer} surface) inside the preview frame and
 * turns the fallback off; {@link #scrubTo(double)}/{@link #seekTo(double)} are the seam the timeline
 * and global shortcuts use for scrubbing. The panel itself never decodes or composites: that is
 * the playback engine plus {@code dev.gravaai.ui.editor.preview}.
 */
public final class EditorPreviewPanel extends VBox {

	private static final String PREVIEW_STYLESHEET = "/ui/editor/preview.css";
	private static final double STAGE_PADDING_X = 16;
	private static final double STAGE_PADDING_Y = 12;

	private final EditorStore store;
	private final EditorViewActions actions;
	private final Label aspectLabel = new Label("16:9");
	private final StackPane stage = new StackPane();
	private final ImageView wallpaper = new ImageView();
	private final Region plate = new Region();
	private final Circle cropDot = new Circle(3, Color.web("#2563EB"));
	private final StackPane plateHost = new StackPane();
	private final StackPane previewFrame = new StackPane();
	private final EditorPreviewView previewView = new EditorPreviewView();
	/** Interactive annotation overlay (selection outline, drag/resize) over the preview. */
	private final AnnotationOverlayController annotationOverlay;
	private final Skeleton loadingSkeleton = new Skeleton();
	private PlaybackController playback;
	/** Last status pushed by the engine; the panel never takes the engine lock on FX. */
	private volatile PlaybackStatus lastStatus;
	private AutoCloseable statusSubscription;
	private PreviewFrameMapper.CursorTrack cursorTrack = PreviewFrameMapper.CursorTrack.NONE;
	private java.nio.file.Path mediaPath;
	private Node previewContent;
	/** Uncommitted overlay drag/resize; the composited annotation follows it live. */
	private AnnotationOverlayController.LiveBox liveAnnotationBox;

	public EditorPreviewPanel(EditorStore store, EditorViewActions actions) {
		this.store = store;
		this.actions = actions;
		getStyleClass().add("editor-preview");
		VBox.setVgrow(this, javafx.scene.layout.Priority.ALWAYS);
		annotationOverlay = new AnnotationOverlayController(store, previewView::geometry,
				this::currentTimelineSeconds);
		previewView.setFramePresentedListener(annotationOverlay::sync);
		annotationOverlay.setLiveBoxListener(box -> {
			liveAnnotationBox = box;
			// null arrives right before the commit, whose store change repaints the final box.
			if (box != null && playback != null) {
				previewView.setScene(currentScene());
				previewView.requestRepaint();
			}
		});

		// ---- toolbar: aspect ratio + crop -------------------------------------
		HBox toolbar = new HBox(12);
		toolbar.getStyleClass().add("editor-preview__toolbar");
		toolbar.setAlignment(Pos.CENTER);

		aspectLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 500; -fx-text-fill: rgba(255,255,255,0.8);");
		Button aspectButton = new Button("", Button.Variant.GHOST, Button.Size.SM);
		HBox aspectGraphic = new HBox(4, aspectLabel, Icon.of(Icons.CaretDown, 10));
		aspectGraphic.setAlignment(Pos.CENTER);
		aspectButton.setGraphic(aspectGraphic);
		DropdownMenu aspectMenu = new DropdownMenu(aspectButton);
		for (String ratio : AspectRatios.PRESETS) {
			aspectMenu.item(aspectLabelFor(ratio), () -> applyAspectRatio(ratio));
		}

		Region divider = new Region();
		divider.getStyleClass().add("editor-preview__divider");
		divider.setMinHeight(16);
		divider.setPrefHeight(16);

		Button crop = new Button("Crop Video", Button.Variant.GHOST, Button.Size.SM);
		crop.icon(Icons.Crop);
		crop.setGraphicTextGap(6);
		crop.setContentDisplay(ContentDisplay.LEFT);
		crop.setOnAction(event -> actions.openCropEditor());
		cropDot.managedProperty().bind(cropDot.visibleProperty());
		HBox cropGraphic = new HBox(6, Icon.of(Icons.Crop, 14), new Label("Crop Video"), cropDot);
		cropGraphic.setAlignment(Pos.CENTER);
		crop.setGraphic(cropGraphic);
		crop.setText("");

		toolbar.getChildren().addAll(aspectButton, divider, crop);

		// ---- stage ------------------------------------------------------------
		stage.getStyleClass().add("editor-preview__stage");
		VBox.setVgrow(stage, javafx.scene.layout.Priority.ALWAYS);
		stage.setMinSize(0, 0);
		wallpaper.setPreserveRatio(false);
		wallpaper.setSmooth(true);
		wallpaper.fitWidthProperty().bind(stage.widthProperty());
		wallpaper.fitHeightProperty().bind(stage.heightProperty());
		wallpaper.setManaged(false);
		plate.getStyleClass().add("editor-preview__plate");
		plateHost.getChildren().add(plate);
		plateHost.setManaged(false);

		// The compositor surface lives in an aspect-fitted frame (React: width
		// min(100cqw, 100cqh * ratio), height min(100cqh, 100cqw / ratio)).
		previewFrame.getStyleClass().add("editor-preview__frame");
		previewView.setManaged(false);
		annotationOverlay.layer().setManaged(false);
		annotationOverlay.layer().setPickOnBounds(false);
		previewFrame.setManaged(false);
		previewFrame.setVisible(false);
		previewFrame.getChildren().addAll(previewView, annotationOverlay.layer());
		Rectangle frameClip = new Rectangle();
		frameClip.widthProperty().bind(previewFrame.widthProperty());
		frameClip.heightProperty().bind(previewFrame.heightProperty());
		frameClip.setArcWidth(20);
		frameClip.setArcHeight(20);
		previewFrame.setClip(frameClip);

		loadingSkeleton.getStyleClass().add("editor-preview__skeleton");
		loadingSkeleton.setManaged(false);
		loadingSkeleton.setVisible(false);
		stage.getChildren().addAll(wallpaper, plateHost, previewFrame, loadingSkeleton);
		StackPane.setAlignment(plateHost, Pos.CENTER);
		stage.widthProperty().addListener((observable, oldValue, newValue) -> layoutStage());
		stage.heightProperty().addListener((observable, oldValue, newValue) -> layoutStage());
		stage.sceneProperty().addListener((observable, oldScene, newScene) -> installStyles(newScene));

		VBox previewColumn = new VBox(toolbar, stage);
		VBox.setVgrow(stage, javafx.scene.layout.Priority.ALWAYS);
		getChildren().add(previewColumn);
		VBox.setVgrow(previewColumn, javafx.scene.layout.Priority.ALWAYS);
		refresh();
	}

	private static void installStyles(Scene scene) {
		if (scene == null) {
			return;
		}
		URL resource = EditorPreviewPanel.class.getResource(PREVIEW_STYLESHEET);
		if (resource == null) {
			return;
		}
		String url = resource.toExternalForm();
		if (!scene.getStylesheets().contains(url)) {
			scene.getStylesheets().add(url);
		}
	}

	private void applyAspectRatio(String ratio) {
		EditorMutations.update(store, json -> json.put("aspectRatio", ratio));
		refresh();
	}

	/**
	 * Binds the playback engine (P2.5): mounts the compositor surface and switches the stage from
	 * the wallpaper/plate fallback to the live preview. Safe before or after the scene is shown.
	 */
	public void setPlaybackController(PlaybackController controller) {
		if (playback != null && playback != controller) {
			previewView.dispose();
		}
		closeStatusSubscription();
		lastStatus = null;
		this.playback = controller;
		if (controller != null) {
			statusSubscription = controller.onStatus(status -> lastStatus = status);
		}
		boolean live = controller != null;
		previewFrame.setVisible(live);
		wallpaper.setVisible(!live);
		plateHost.setVisible(!live);
		if (live) {
			previewView.setScene(currentScene());
			previewView.bind(controller);
			previewView.renderIdle();
			annotationOverlay.refresh();
			// First-frame skeleton (usePreviewVideoReady): visible until the first decoded frame is
			// composited, then it leaves permanently for this media.
			loadingSkeleton.visibleProperty().unbind();
			loadingSkeleton.visibleProperty().bind(previewView.readyProperty().not());
		} else {
			previewView.dispose();
			loadingSkeleton.visibleProperty().unbind();
			loadingSkeleton.setVisible(false);
		}
		layoutStage();
	}

	/** The bound playback engine, or null for the fallback preview. */
	public PlaybackController playbackController() {
		return playback;
	}

	/** The compositor surface control (tests/snapshots). */
	public EditorPreviewView previewView() {
		return previewView;
	}

	/** Forwards the extension render hooks to the compositor surface (P3.3). */
	public void setOverlayHooks(dev.gravaai.media.compose.FrameOverlayHooks hooks) {
		previewView.setOverlayHooks(hooks);
	}

	/** The interactive annotation overlay (tests/snapshots). */
	public AnnotationOverlayController annotationOverlay() {
		return annotationOverlay;
	}

	/** True once the first decoded frame reached the preview surface. */
	public ReadOnlyBooleanProperty previewReadyProperty() {
		return previewView.readyProperty();
	}

	public boolean isPreviewReady() {
		return previewView.isReady();
	}

	/**
	 * Seek seam for the timeline (P2.7): the React timeline seek pauses the transport, so a click
	 * on the timeline pauses then seeks. Call on the FX thread.
	 */
	public PlaybackSeek timelineSeek() {
		return seconds -> seekTo(seconds, true);
	}

	/**
	 * Scrub seam for playhead drags: pauses like a timeline seek, then hands the position to the
	 * engine's latest-wins scrub, which decodes off the FX thread.
	 */
	public PlaybackSeek timelineScrub() {
		return seconds -> {
			if (playback == null) {
				return;
			}
			if (playback.isPlaying()) {
				playback.pause();
			}
			playback.scrub(clampToTimeline(seconds));
		};
	}

	/**
	 * Cursor telemetry for the compositor (recording sidecar → {@code CursorTrack}); pass
	 * {@link PreviewFrameMapper.CursorTrack#NONE} when the recording has no telemetry.
	 */
	public void setCursorTrack(PreviewFrameMapper.CursorTrack track) {
		this.cursorTrack = track == null ? PreviewFrameMapper.CursorTrack.NONE : track;
		if (playback != null) {
			previewView.setScene(currentScene());
			previewView.requestRepaint();
		}
	}

	/** The recording behind the preview, or {@code null} before one is loaded. */
	public java.nio.file.Path mediaPath() {
		return mediaPath;
	}

	/** The recording behind the preview; its {@code .keys.json} feeds the keystroke overlay. */
	public void setMediaPath(java.nio.file.Path video) {
		this.mediaPath = video;
		if (playback != null) {
			previewView.setScene(currentScene());
			previewView.requestRepaint();
		}
	}

	/** The cursor telemetry currently fed to the compositor. */
	public PreviewFrameMapper.CursorTrack cursorTrack() {
		return cursorTrack;
	}

	// ── scrubbing seam (timeline / shortcuts call these on the FX thread) ────

	/** Latest-wins scrub: asks the engine to show the frame at {@code seconds}. */
	public void scrubTo(double seconds) {
		if (playback != null) {
			playback.scrub(clampToTimeline(seconds));
		}
	}

	/** Precise seek (pauses first when {@code pause} is true, like the React timeline seek). */
	public void seekTo(double seconds, boolean pause) {
		if (playback == null) {
			return;
		}
		if (pause) {
			playback.pause();
		}
		playback.seek(clampToTimeline(seconds));
	}

	/** Precise seek without pausing. */
	public void seekTo(double seconds) {
		seekTo(seconds, false);
	}

	/** Skips by a delta (transport shortcut seam). */
	public void skip(double deltaSeconds) {
		if (playback != null) {
			playback.seek(clampToTimeline(currentTimelineSeconds() + deltaSeconds));
		}
	}

	private double currentTimelineSeconds() {
		PlaybackStatus status = lastStatus;
		return status == null ? 0 : status.timelineSeconds();
	}

	private double clampToTimeline(double seconds) {
		PlaybackStatus status = lastStatus;
		double duration = status == null ? 0 : status.durationSeconds();
		double upper = duration > 0 ? duration : Math.max(0, seconds);
		return Math.max(0, Math.min(upper, seconds));
	}

	private void closeStatusSubscription() {
		if (statusSubscription != null) {
			try {
				statusSubscription.close();
			} catch (Exception ignored) {
				// listener handle is best-effort
			}
			statusSubscription = null;
		}
	}

	/** Legacy seam: mounts an arbitrary preview node inside the video plate (fallback path). */
	public void setPreviewContent(Node content) {
		if (previewContent != null) {
			plateHost.getChildren().remove(previewContent);
		}
		previewContent = content;
		if (content != null) {
			plateHost.getChildren().add(content);
		}
	}

	/** Re-renders aspect label, wallpaper background, frame size and crop indicator. */
	public void refresh() {
		var state = store.state();
		aspectLabel.setText(aspectLabelFor(state.aspectRatio()));
		cropDot.setVisible(!isDefaultCrop(state.cropRegion()));

		if (playback != null) {
			previewView.setScene(currentScene());
			previewView.requestRepaint();
			annotationOverlay.refresh();
		} else {
			refreshFallback(state.wallpaper());
		}
		layoutStage();
	}

	/** The compositor scene for the current store state plus any live overlay gesture. */
	private PreviewFrameMapper.PreviewScene currentScene() {
		PreviewFrameMapper.PreviewScene scene = PreviewFrameMapper.sceneFor(store.state(),
				cursorTrack, mediaPath);
		AnnotationOverlayController.LiveBox box = liveAnnotationBox;
		if (box == null || box.position() == null || box.size() == null) {
			return scene;
		}
		return scene.withAnnotationBox(box.id(), box.position().x(), box.position().y(),
				box.size().width(), box.size().height());
	}

	private void refreshFallback(String value) {
		Image image = EditorWallpapers.preview(value);
		if (image != null) {
			wallpaper.setImage(image);
			wallpaper.setVisible(true);
			stage.setBackground(null);
		} else {
			wallpaper.setImage(null);
			wallpaper.setVisible(false);
			stage.setBackground(new Background(new BackgroundFill(
					EditorWallpapers.paintFor(value, Color.web("#121418")),
					CornerRadii.EMPTY, Insets.EMPTY)));
		}
	}

	private void layoutStage() {
		double availableWidth = Math.max(0, stage.getWidth() - 2 * STAGE_PADDING_X);
		double availableHeight = Math.max(0, stage.getHeight() - 2 * STAGE_PADDING_Y);
		double ratio = aspectValue(store.state().aspectRatio());
		double width = Math.min(availableWidth, availableHeight * ratio);
		double height = width / ratio;
		if (width <= 0 || height <= 0) {
			return;
		}
		plate.setPrefSize(width, height);
		plate.setMinSize(width, height);
		plate.setMaxSize(width, height);
		double x = (stage.getWidth() - width) / 2;
		double y = (stage.getHeight() - height) / 2;
		plateHost.resizeRelocate(x, y, width, height);
		previewFrame.resizeRelocate(x, y, width, height);
		// Both nodes are unmanaged (the StackPane stage does not lay them out), so drive their
		// size here; this is what triggers the preview renderer for the new target size.
		previewView.resizeRelocate(0, 0, width, height);
		annotationOverlay.layer().resizeRelocate(0, 0, width, height);
		loadingSkeleton.resizeRelocate(x, y, width, height);
	}

	private static boolean isDefaultCrop(CropRegion crop) {
		return crop.x() <= 0 && crop.y() <= 0 && crop.width() >= 1 && crop.height() >= 1;
	}

	/** {@code getAspectRatioLabel}. */
	public static String aspectLabelFor(String ratio) {
		if (ratio == null) {
			return "16:9";
		}
		return "native".equals(ratio) ? "Native" : ratio;
	}

	/** {@code getAspectRatioValue}. */
	public static double aspectValue(String ratio) {
		if (ratio == null || "native".equals(ratio)) {
			return 16.0 / 9.0;
		}
		String[] parts = ratio.split(":");
		if (parts.length == 2) {
			try {
				double width = Double.parseDouble(parts[0]);
				double height = Double.parseDouble(parts[1]);
				if (width > 0 && height > 0) {
					return width / height;
				}
			} catch (NumberFormatException ignored) {
				// fall through to the default ratio
			}
		}
		return switch (ratio) {
			case "9:16" -> 9.0 / 16.0;
			case "1:1" -> 1;
			case "4:3" -> 4.0 / 3.0;
			case "4:5" -> 4.0 / 5.0;
			case "16:10" -> 16.0 / 10.0;
			case "10:16" -> 10.0 / 16.0;
			default -> 16.0 / 9.0;
		};
	}

	/** Stops the preview renderer (shell disposal). */
	public void dispose() {
		closeStatusSubscription();
		lastStatus = null;
		previewView.dispose();
		loadingSkeleton.stop();
	}
}