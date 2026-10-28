package dev.gravaai.ui.launch;

import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * Floating recording webcam preview (plan §7.1 P1.7), port of
 * {@code src/components/launch/hooks/useWebcamPreviewOverlay.ts} +
 * {@code LaunchWindow.module.css .recordingWebcamPreview}.
 *
 * <p>Rounded 24 px frame with a live mirrored camera image, draggable and resizable from the
 * bottom-right handle. Positions are clamped by the HUD window, which sizes the stage to include
 * this frame (tight window instead of Electron's full-screen overlay, plan §5.4).
 */
public final class HudWebcamPreview extends StackPane {

	/** Default size from useWebcamPreviewOverlay.ts (4:3). */
	private static final double DEFAULT_WIDTH = 320;
	private static final double DEFAULT_HEIGHT = 240;
	private static final double MIN_WIDTH = 160;
	private static final double MIN_HEIGHT = 120;
	private static final double RESIZE_HANDLE = 20;

	private final WebcamPreviewView preview = new WebcamPreviewView();
	private final Region resizeHandle = new Region();
	private double frameWidth = DEFAULT_WIDTH;
	private double frameHeight = DEFAULT_HEIGHT;
	private double dragStartX;
	private double dragStartY;
	private double dragOriginX;
	private double dragOriginY;
	private boolean dragging;
	private boolean resizing;

	public HudWebcamPreview() {
		getStyleClass().add("hud-webcam-preview");
		setPickOnBounds(true);
		getChildren().addAll(preview, resizeHandle);
		resizeHandle.getStyleClass().add("hud-webcam-resize-handle");
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		clip.setArcWidth(48);
		clip.setArcHeight(48);
		setClip(clip);

		setOnMousePressed(event -> {
			dragging = true;
			dragStartX = event.getScreenX();
			dragStartY = event.getScreenY();
			dragOriginX = getLayoutX();
			dragOriginY = getLayoutY();
			event.consume();
		});
		setOnMouseDragged(event -> {
			if (dragging) {
				double dx = event.getScreenX() - dragStartX;
				double dy = event.getScreenY() - dragStartY;
				setLayoutX(dragOriginX + dx);
				setLayoutY(dragOriginY + dy);
				requestParentLayout();
				event.consume();
			}
		});
		setOnMouseReleased(event -> dragging = false);

		resizeHandle.setOnMousePressed(event -> {
			resizing = true;
			dragStartX = event.getScreenX();
			dragStartY = event.getScreenY();
			dragOriginX = frameWidth;
			dragOriginY = frameHeight;
			event.consume();
		});
		resizeHandle.setOnMouseDragged(event -> {
			if (resizing) {
				double width = Math.max(MIN_WIDTH, Math.min(640, dragOriginX + event.getScreenX() - dragStartX));
				double height = Math.max(MIN_HEIGHT, Math.min(480, dragOriginY + event.getScreenY() - dragStartY));
				setFrameSize(width, height);
				requestParentLayout();
				event.consume();
			}
		});
		resizeHandle.setOnMouseReleased(event -> resizing = false);
	}

	/** Starts the camera preview for the device path; a blank path clears it. */
	public void start(String devicePath) {
		preview.start(devicePath);
	}

	/** Stops the camera. */
	public void stop() {
		preview.stop();
	}

	public boolean isRunning() {
		return preview.isRunning();
	}

	/** Current preview width in px. */
	public double frameWidth() {
		return frameWidth;
	}

	/** Current preview height in px. */
	public double frameHeight() {
		return frameHeight;
	}

	public void setFrameSize(double width, double height) {
		frameWidth = Math.max(MIN_WIDTH, width);
		frameHeight = Math.max(MIN_HEIGHT, height);
		setMinSize(frameWidth, frameHeight);
		setPrefSize(frameWidth, frameHeight);
		setMaxSize(frameWidth, frameHeight);
		requestLayout();
	}

	@Override
	protected void layoutChildren() {
		preview.resizeRelocate(0, 0, frameWidth, frameHeight);
		resizeHandle.resizeRelocate(frameWidth - RESIZE_HANDLE, frameHeight - RESIZE_HANDLE, RESIZE_HANDLE,
				RESIZE_HANDLE);
	}
}
