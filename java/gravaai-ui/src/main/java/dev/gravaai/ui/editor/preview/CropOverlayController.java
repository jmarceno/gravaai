package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dev.gravaai.core.editor.CropCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CameraGeometry;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Interactive crop layer over the compositor preview: the crop-mode counterpart of
 * {@link AnnotationOverlayController}. The compositor displays the cropped recording, so the
 * crop's edges are the recording rect's edges — this layer draws handles on them and turns mouse
 * gestures into {@link CropCommands.Set} so the change is undoable and saved with the project.
 *
 * <p>While a gesture is live the store state is replaced (not published) on every move, so the
 * compositor re-renders the moving crop without flooding the undo stack or the other panels; the
 * commit happens once on release through the canonical command path, which is the one undo step
 * that restores the previous crop. The aspect lock captures the crop's pixel ratio when it turns
 * on ({@link CropOverlayMath#pixelRatio}) and holds it while dragging.
 */
public final class CropOverlayController {

	/** Edge handle: a 3px blue bar centred in a wider transparent hit strip (crop dialog). */
	private static final double EDGE_STRIP = 11;
	/** Corner handle: a small square. */
	private static final double CORNER_SIZE = 10;
	private static final Color ACCENT = Color.web("#2563EB");

	private final EditorStore store;
	private final Supplier<CameraGeometry> geometry;
	private final IntSupplier sourceWidth;
	private final IntSupplier sourceHeight;
	/** Repaints the compositor after a live drag move (scene + requestRepaint on the panel). */
	private final Runnable repaint;
	private final Pane layer = new Pane();
	private final Rectangle outline = new Rectangle();
	private final Pane[] handles = new Pane[CropOverlayMath.Handle.values().length];
	private boolean active;
	private boolean aspectLocked;
	private double lockedRatio = 1;
	private @Nullable Gesture gesture;

	/** Mutable in-progress drag. */
	private static final class Gesture {
		final CropOverlayMath.Handle handle;
		final ProjectEditorState startState;
		final CropRegion startCrop;
		final CameraGeometry startGeometry;
		/** The region the gesture dragged to; the commit uses this, not the store's live state. */
		@Nullable CropRegion current;

		Gesture(CropOverlayMath.Handle handle, ProjectEditorState startState, CropRegion startCrop,
				CameraGeometry startGeometry) {
			this.handle = handle;
			this.startState = startState;
			this.startCrop = startCrop;
			this.startGeometry = startGeometry;
		}
	}

	public CropOverlayController(EditorStore store, Supplier<CameraGeometry> geometry,
			IntSupplier sourceWidth, IntSupplier sourceHeight, Runnable repaint) {
		this.store = store;
		this.geometry = geometry;
		this.sourceWidth = sourceWidth;
		this.sourceHeight = sourceHeight;
		this.repaint = repaint;
		layer.getStyleClass().add("editor-preview__crop-layer");
		layer.setPickOnBounds(false);
		outline.setMouseTransparent(true);
		outline.setStroke(ACCENT);
		outline.setStrokeWidth(1.5);
		outline.setFill(Color.TRANSPARENT);
		layer.getChildren().add(outline);
		for (CropOverlayMath.Handle handle : CropOverlayMath.Handle.values()) {
			Pane handleNode = buildHandle(handle);
			handles[handle.ordinal()] = handleNode;
			layer.getChildren().add(handleNode);
		}
		layer.setVisible(false);
	}

	/** The layer to add on top of the preview surface (sized like the preview frame). */
	public Pane layer() {
		return layer;
	}

	/** Whether crop mode is on (the layer is visible and owns the preview gestures). */
	public boolean isActive() {
		return active;
	}

	/** Whether new drags hold the captured aspect ratio. */
	public boolean isAspectLocked() {
		return aspectLocked;
	}

	/** Enters or leaves crop mode; entering lays the handles out for the current crop. */
	public void setActive(boolean nextActive) {
		active = nextActive;
		layer.setVisible(active);
		if (active) {
			sync();
		} else {
			// Leaving mid-gesture rolls the live drag states back; nothing was committed.
			Gesture drag = gesture;
			gesture = null;
			if (drag != null) {
				store.replaceState(drag.startState);
				repaint.run();
			}
		}
	}

	/**
	 * Toggles the aspect lock; turning it on captures the current crop's pixel ratio (from the
	 * probed source size, falling back to a square source), turning it off lets drags run free
	 * again.
	 */
	public void setAspectLocked(boolean nextLocked) {
		aspectLocked = nextLocked;
		if (aspectLocked) {
			lockedRatio = CropOverlayMath.pixelRatio(store.state().cropRegion(),
					sourceWidth.getAsInt(), sourceHeight.getAsInt());
		}
	}

	/** Resets the crop to the full frame as one undoable step; the aspect lock releases. */
	public void reset() {
		rollbackGesture();
		aspectLocked = false;
		store.apply(new CropCommands.Set(CropRegion.DEFAULT));
	}

	/** Rebuilds the handle layout from the store crop and the compositor geometry. FX thread. */
	public void sync() {
		if (!active) {
			return;
		}
		double width = layer.getWidth();
		double height = layer.getHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		AnnotationOverlayMath.Rect rect = CropOverlayMath.rectFor(geometry.get(), width, height);
		outline.resizeRelocate(rect.x(), rect.y(), rect.width(), rect.height());
		double halfStrip = EDGE_STRIP / 2;
		double halfCorner = CORNER_SIZE / 2;
		double cx = rect.x();
		double cy = rect.y();
		double rightEdge = rect.x() + rect.width();
		double bottomEdge = rect.y() + rect.height();
		handles[CropOverlayMath.Handle.TOP.ordinal()].resizeRelocate(cx, cy - halfStrip,
				rect.width(), EDGE_STRIP);
		handles[CropOverlayMath.Handle.BOTTOM.ordinal()].resizeRelocate(cx, bottomEdge - halfStrip,
				rect.width(), EDGE_STRIP);
		handles[CropOverlayMath.Handle.LEFT.ordinal()].resizeRelocate(cx - halfStrip, cy,
				EDGE_STRIP, rect.height());
		handles[CropOverlayMath.Handle.RIGHT.ordinal()].resizeRelocate(rightEdge - halfStrip, cy,
				EDGE_STRIP, rect.height());
		resizeCorner(handles[CropOverlayMath.Handle.NW.ordinal()], cx - halfCorner,
				cy - halfCorner);
		resizeCorner(handles[CropOverlayMath.Handle.NE.ordinal()], rightEdge - halfCorner,
				cy - halfCorner);
		resizeCorner(handles[CropOverlayMath.Handle.SW.ordinal()], cx - halfCorner,
				bottomEdge - halfCorner);
		resizeCorner(handles[CropOverlayMath.Handle.SE.ordinal()], rightEdge - halfCorner,
				bottomEdge - halfCorner);
	}

	private static void resizeCorner(Node handle, double x, double y) {
		handle.resizeRelocate(x, y, CORNER_SIZE, CORNER_SIZE);
	}

	private Pane buildHandle(CropOverlayMath.Handle handle) {
		Pane handleNode = new Pane();
		boolean edge = handle == CropOverlayMath.Handle.TOP
				|| handle == CropOverlayMath.Handle.BOTTOM
				|| handle == CropOverlayMath.Handle.LEFT
				|| handle == CropOverlayMath.Handle.RIGHT;
		handleNode.setManaged(false);
		if (edge) {
			// 3px blue bar centred in a wider transparent hit strip, like the crop dialog.
			boolean horizontalBar = handle == CropOverlayMath.Handle.TOP
					|| handle == CropOverlayMath.Handle.BOTTOM;
			handleNode.setStyle("-fx-background-color: transparent, #2563EB; "
					+ (horizontalBar ? "-fx-background-insets: 0, 4 0 4 0;"
							: "-fx-background-insets: 0, 0 4 0 4;"));
			handleNode.setCursor(horizontalBar ? Cursor.V_RESIZE : Cursor.H_RESIZE);
		} else {
			handleNode.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #2563EB;"
					+ " -fx-border-width: 2;");
			handleNode.setCursor(switch (handle) {
				case NW -> Cursor.NW_RESIZE;
				case NE -> Cursor.NE_RESIZE;
				case SW -> Cursor.SW_RESIZE;
				default -> Cursor.SE_RESIZE;
			});
		}
		handleNode.setOnMousePressed(event -> begin(handle, event));
		handleNode.setOnMouseDragged(this::move);
		handleNode.setOnMouseReleased(event -> {
			end();
			event.consume();
		});
		return handleNode;
	}

	private void begin(CropOverlayMath.Handle handle, MouseEvent event) {
		if (gesture != null || layer.getWidth() <= 0 || layer.getHeight() <= 0) {
			return;
		}
		ProjectEditorState state = store.state();
		gesture = new Gesture(handle, state, state.cropRegion(), geometry.get());
		event.consume();
	}

	private void move(MouseEvent event) {
		Gesture drag = gesture;
		if (drag == null) {
			return;
		}
		javafx.geometry.Point2D local = layer.sceneToLocal(event.getSceneX(), event.getSceneY());
		CropOverlayMath.Point source = CropOverlayMath.sourcePointFor(local.getX(), local.getY(),
				drag.startCrop, drag.startGeometry, layer.getWidth(), layer.getHeight());
		CropRegion dragged = CropOverlayMath.drag(drag.startCrop, drag.handle, source.x(),
				source.y());
		if (aspectLocked) {
			dragged = CropOverlayMath.applyAspect(dragged, drag.startCrop, drag.handle,
					lockedRatio, sourceWidth.getAsInt(), sourceHeight.getAsInt());
		}
		drag.current = dragged;
		// Live preview without a history entry: the compositor reads the replaced state on the
		// next render; the undoable commit happens once, on release.
		store.replaceState(drag.startState.withCropRegion(dragged));
		repaint.run();
		event.consume();
	}

	private void end() {
		Gesture drag = gesture;
		if (drag == null) {
			return;
		}
		gesture = null;
		// Roll the live states back so the canonical command records one undo step that restores
		// the pre-gesture crop (the changed-check compares against it). The commit uses the
		// region the gesture dragged, not the store's live state: an undo or other store change
		// mid-gesture must not change what the drag commits.
		store.replaceState(drag.startState);
		CropRegion next = drag.current == null ? drag.startCrop : drag.current;
		if (!next.equals(drag.startCrop)) {
			store.apply(new CropCommands.Set(next));
		}
	}

	/** Rolls a live gesture back to its starting state without committing it. */
	private void rollbackGesture() {
		Gesture drag = gesture;
		gesture = null;
		if (drag != null) {
			store.replaceState(drag.startState);
		}
	}
}
