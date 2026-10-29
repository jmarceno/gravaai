package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.WebcamCropMath;
import dev.gravaai.core.project.WebcamCropMath.Handle;
import javafx.scene.Cursor;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;

/**
 * Webcam crop control of the inspector's Webcam &gt; Advanced section (port of
 * {@code WebcamCropControl.tsx}, plan P3.4 gap 4).
 *
 * <p>The crop region is a fraction of the webcam source: this control draws the source plate, the
 * dimmed outside area, the crop rectangle with its thirds guides and the four corner handles, and
 * turns moves/resizes (mouse and keyboard) into a normalised {@link CropRegion}. A mirrored webcam
 * is edited flipped horizontally and the committed region is flipped back, so the stored crop
 * stays in source space (React {@code flipCropHorizontally}).
 *
 * <p>Edits commit on gesture end (React {@code flushPendingCommit}), never per mouse-move.
 */
public final class WebcamCropControl extends Region {

	/** Visual size of a corner handle (React: h-3.5 w-3.5 = 14 px). */
	private static final double HANDLE_SIZE = 14;
	private static final Color ACCENT = Color.web("#2563EB");
	private static final Color DIM = Color.web("#000000", 0.58);

	private final Pane cropBox = new Pane();
	private final Pane mask = new Pane();
	private final Path dimPath = new Path();
	private final Rectangle hole = new Rectangle();
	private final Circle[] handles = new Circle[4];
	private final Handle[] handleKinds = {Handle.NW, Handle.NE, Handle.SE, Handle.SW};
	private final Rectangle[] guides = new Rectangle[4];

	private CropRegion crop = CropRegion.DEFAULT;
	private boolean mirrored;
	private Consumer<CropRegion> onCropChange = region -> { };
	private @Nullable Handle activeHandle;
	private @Nullable Drag drag;

	/** One in-progress pointer gesture; coordinates are scene coordinates. */
	private static final class Drag {
		final Handle handle;
		final double startSceneX;
		final double startSceneY;
		final CropRegion initial;

		Drag(Handle handle, double startSceneX, double startSceneY, CropRegion initial) {
			this.handle = handle;
			this.startSceneX = startSceneX;
			this.startSceneY = startSceneY;
			this.initial = initial;
		}
	}

	public WebcamCropControl() {
		getStyleClass().add("editor-webcam-crop");
		setFocusTraversable(true);
		setPrefSize(224, 224);
		setMaxSize(224, 224);

		dimPath.setFillRule(FillRule.EVEN_ODD);
		dimPath.setFill(DIM);
		hole.setFill(Color.TRANSPARENT);
		hole.setStroke(Color.WHITE);
		hole.setStrokeWidth(1);
		mask.setMouseTransparent(true);
		mask.getChildren().addAll(dimPath, hole);

		cropBox.getStyleClass().add("editor-webcam-crop__box");
		cropBox.setCursor(Cursor.MOVE);
		cropBox.setPickOnBounds(true);
		cropBox.setOnMousePressed(event -> {
			requestFocus();
			beginDrag(Handle.MOVE, event.getSceneX(), event.getSceneY());
		});
		cropBox.setOnMouseDragged(event -> updateDrag(event.getSceneX(), event.getSceneY()));
		cropBox.setOnMouseReleased(event -> endDrag());

		for (int index = 0; index < guides.length; index++) {
			guides[index] = new Rectangle();
			guides[index].setFill(Color.web("#ffffff", 0.45));
			guides[index].setMouseTransparent(true);
			cropBox.getChildren().add(guides[index]);
		}
		for (int index = 0; index < handles.length; index++) {
			int corner = index;
			Circle handle = new Circle(HANDLE_SIZE / 2, ACCENT);
			handle.setStroke(Color.WHITE);
			handle.setStrokeWidth(2);
			handle.setCursor(handleKinds[index] == Handle.NW || handleKinds[index] == Handle.SE
					? Cursor.NW_RESIZE
					: Cursor.NE_RESIZE);
			handle.setOnMousePressed(event -> {
				event.consume();
				requestFocus();
				beginDrag(handleKinds[corner], event.getSceneX(), event.getSceneY());
			});
			handle.setOnMouseDragged(event -> {
				event.consume();
				updateDrag(event.getSceneX(), event.getSceneY());
			});
			handle.setOnMouseReleased(event -> {
				event.consume();
				endDrag();
			});
			handles[index] = handle;
		}
		cropBox.getChildren().addAll(handles);
		getChildren().addAll(mask, cropBox);
		setOnKeyPressed(this::handleKey);
	}

	/** The crop region shown/edited (stored source space, not mirrored). */
	public @Nullable CropRegion cropRegion() {
		return crop;
	}

	/** {@code setCropRegion}: shows a crop from the store. */
	public void setCropRegion(CropRegion region) {
		this.crop = WebcamCropMath.normalize(region);
		requestLayout();
	}

	/** Whether the webcam is mirrored (the crop is displayed flipped). */
	public boolean isMirrored() {
		return mirrored;
	}

	public void setMirrored(boolean value) {
		this.mirrored = value;
		requestLayout();
	}

	/** Commit callback; receives a normalised source-space crop on every edit. */
	public void setOnCropChange(Consumer<CropRegion> listener) {
		this.onCropChange = listener == null ? region -> { } : listener;
	}

	/** Visual crop: flipped when the webcam is mirrored. */
	CropRegion visualCrop() {
		return mirrored ? WebcamCropMath.flipHorizontally(crop) : crop;
	}

	private void beginDrag(Handle handle, double sceneX, double sceneY) {
		if (drag != null) {
			return;
		}
		activeHandle = handle;
		drag = new Drag(handle, sceneX, sceneY, visualCrop());
		cropBox.setCursor(handle == Handle.MOVE ? Cursor.CLOSED_HAND : cropBox.getCursor());
		layoutChildren();
	}

	private void updateDrag(double sceneX, double sceneY) {
		Drag active = drag;
		if (active == null) {
			return;
		}
		double width = Math.max(1, getWidth());
		double height = Math.max(1, getHeight());
		double deltaX = (sceneX - active.startSceneX) / width;
		double deltaY = (sceneY - active.startSceneY) / height;
		CropRegion nextVisual = WebcamCropMath.resize(active.initial, active.handle, deltaX,
				deltaY);
		crop = mirrored ? WebcamCropMath.flipHorizontally(nextVisual) : nextVisual;
		layoutChildren();
	}

	private void endDrag() {
		if (drag == null) {
			return;
		}
		drag = null;
		activeHandle = null;
		cropBox.setCursor(Cursor.MOVE);
		crop = WebcamCropMath.normalize(crop);
		layoutChildren();
		onCropChange.accept(crop);
	}

	@Override
	protected void layoutChildren() {
		double width = getWidth() > 0 ? getWidth() : getPrefWidth();
		double height = getHeight() > 0 ? getHeight() : getPrefHeight();
		CropRegion visual = visualCrop();
		double x = visual.x() * width;
		double y = visual.y() * height;
		double cropWidth = Math.max(1, visual.width() * width);
		double cropHeight = Math.max(1, visual.height() * height);

		// Dim everything outside the crop: one even-odd path (outer frame + crop hole).
		dimPath.getElements().clear();
		dimPath.getElements().addAll(outerRect(0, 0, width, height));
		dimPath.getElements().addAll(outerRect(x, y, cropWidth, cropHeight));
		hole.setX(x);
		hole.setY(y);
		hole.setWidth(cropWidth);
		hole.setHeight(cropHeight);

		cropBox.resizeRelocate(x, y, cropWidth, cropHeight);
		cropBox.toFront();

		double[] fractions = {1.0 / 3.0, 2.0 / 3.0};
		for (int index = 0; index < 2; index++) {
			guides[index].setX(cropWidth * fractions[index] - 0.5);
			guides[index].setY(0);
			guides[index].setWidth(1);
			guides[index].setHeight(cropHeight);
			guides[index + 2].setX(0);
			guides[index + 2].setY(cropHeight * fractions[index] - 0.5);
			guides[index + 2].setWidth(cropWidth);
			guides[index + 2].setHeight(1);
		}

		double[] xs = {0, cropWidth, cropWidth, 0};
		double[] ys = {0, 0, cropHeight, cropHeight};
		for (int index = 0; index < handles.length; index++) {
			handles[index].setCenterX(xs[index]);
			handles[index].setCenterY(ys[index]);
			handles[index].setScaleX(activeHandle == handleKinds[index] ? 1.1 : 1);
			handles[index].setScaleY(activeHandle == handleKinds[index] ? 1.1 : 1);
		}
	}

	/** Four path elements forming a closed rectangle. */
	private static PathElement[] outerRect(double x, double y, double width, double height) {
		return new PathElement[] {
				new javafx.scene.shape.MoveTo(x, y),
				new javafx.scene.shape.LineTo(x + width, y),
				new javafx.scene.shape.LineTo(x + width, y + height),
				new javafx.scene.shape.LineTo(x, y + height),
				new javafx.scene.shape.ClosePath()};
	}

	/** Keyboard editing (React {@code handleKeyboardAdjust}): arrows move, shift is faster. */
	void handleKey(KeyEvent event) {
		double[] delta = WebcamCropMath.keyboardDelta(event.getCode().getName(), event.isShiftDown());
		if (delta == null) {
			return;
		}
		event.consume();
		CropRegion visual = visualCrop();
		CropRegion nextVisual = WebcamCropMath.resize(visual, Handle.MOVE, delta[0], delta[1]);
		crop = mirrored ? WebcamCropMath.flipHorizontally(nextVisual) : nextVisual;
		layoutChildren();
		onCropChange.accept(crop);
	}

	/** Test seams. */
	Pane cropBoxForTest() {
		return cropBox;
	}

	Circle[] handlesForTest() {
		return handles;
	}

	boolean isDragging() {
		return drag != null;
	}

	/** Simulates a full gesture in scene coordinates (tests skip JavaFX mouse synthesis). */
	void dragForTest(Handle handle, double startX, double startY, double endX, double endY) {
		beginDrag(handle, startX, startY);
		updateDrag(endX, endY);
		endDrag();
	}
}
