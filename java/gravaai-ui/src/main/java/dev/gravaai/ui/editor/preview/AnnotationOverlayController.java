package dev.gravaai.ui.editor.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.media.compose.CameraGeometry;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Interactive annotation layer over the compositor preview (port of {@code AnnotationOverlay.tsx},
 * plan P3.4 gap 3).
 *
 * <p>The preview frame already has the annotations baked in by the compositor, so this layer never
 * draws their content: it draws the selection outline and the four corner resize handles, and turns
 * mouse gestures into the same commands the settings panel uses ({@link AnnotationCommands}) so the
 * change is undoable and saved with the project.
 *
 * <p>Interaction matches React: only the selected annotation can be dragged, its handles resize it,
 * clicking an unselected annotation selects it, and clicking the selected one again cycles through
 * the annotations visible at that time. Edits commit on gesture end (React commits in
 * {@code onDragStop}/{@code onResizeStop}), never per mouse move. While a gesture is live the
 * {@link #setLiveBoxListener live box} is published on every move so the compositor redraws the
 * annotation inside the moving box (React moves the DOM content with the box).
 */
public final class AnnotationOverlayController {

	/** Uncommitted box of the annotation being dragged/resized, in percent of the recording rect. */
	public record LiveBox(String id, AnnotationPosition position, AnnotationSize size) {
	}

	private static final double HANDLE_SIZE = 12;
	private static final double MIN_SIZE = 8;
	private static final Color ACCENT = Color.web("#2563EB");
	private static final Color SELECTION_FILL = Color.web("#2563EB", 0.10);

	private final EditorStore store;
	private final Supplier<CameraGeometry> geometry;
	private final Supplier<Double> timeSeconds;
	private final Pane layer = new Pane();
	/** Node per visible annotation, keyed by id (children are in z-order, selected last). */
	private final java.util.Map<String, Pane> nodes = new LinkedHashMap<>();
	private final List<AnnotationRegion> visible = new ArrayList<>();
	private List<String> renderedIds = List.of();
	private String renderedSelected;
	private Gesture gesture;
	private java.util.function.Consumer<LiveBox> liveBoxListener = box -> { };

	/**
	 * Receives the live box on every gesture move, and {@code null} right before the gesture
	 * commits (or ends without a move), so the listener can drop its override.
	 */
	public void setLiveBoxListener(java.util.function.Consumer<LiveBox> listener) {
		this.liveBoxListener = listener == null ? box -> { } : listener;
	}

	public AnnotationOverlayController(EditorStore store, Supplier<CameraGeometry> geometry,
			Supplier<Double> timeSeconds) {
		this.store = store;
		this.geometry = geometry;
		this.timeSeconds = timeSeconds;
		layer.getStyleClass().add("editor-preview__annotation-layer");
		layer.setPickOnBounds(false);
		layer.setMouseTransparent(false);
	}

	/** The layer to add on top of the preview surface (sized like the preview frame). */
	public Pane layer() {
		return layer;
	}

	/** The annotations currently drawn by the overlay (tests/diagnostics). */
	public List<AnnotationRegion> visibleAnnotations() {
		return List.copyOf(visible);
	}

	/** Rebuilds the overlay from the store (store change, seek, resize). FX thread. */
	public void refresh() {
		renderedIds = List.of();
		renderedSelected = null;
		sync();
	}

	/**
	 * Follows the animated camera transform: called after each presented preview frame. Only
	 * rebuilds when the visible set or the selection changed; otherwise it just repositions.
	 */
	public void sync() {
		double width = layer.getWidth();
		double height = layer.getHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		List<AnnotationRegion> next = activeAnnotations();
		List<String> ids = next.stream().map(AnnotationRegion::id).toList();
		String selected = store.selectedAnnotationId();
		if (!ids.equals(renderedIds) || !eq(selected, renderedSelected)) {
			rebuild(next, selected);
			return;
		}
		if (gesture != null) {
			// A live gesture owns the node geometry until it commits.
			return;
		}
		for (AnnotationRegion annotation : next) {
			Pane node = nodes.get(annotation.id());
			if (node != null) {
				AnnotationOverlayMath.Rect rect = rectFor(annotation, width, height);
				node.resizeRelocate(rect.x(), rect.y(), rect.width(), rect.height());
				relocateHandles(node, rect);
			}
		}
	}

	/** Annotation active at the playhead, sorted by z-index (React z-order). */
	private List<AnnotationRegion> activeAnnotations() {
		double timeMs = Math.round(timeSeconds.get() * 1000);
		List<AnnotationRegion> result = new ArrayList<>();
		for (AnnotationRegion annotation : store.state().annotationRegions()) {
			if (timeMs >= annotation.startMs() && timeMs <= annotation.endMs()) {
				result.add(annotation);
			}
		}
		result.sort(Comparator.comparingInt(AnnotationRegion::zIndex));
		return result;
	}

	private void rebuild(List<AnnotationRegion> next, String selected) {
		visible.clear();
		visible.addAll(next);
		renderedIds = next.stream().map(AnnotationRegion::id).toList();
		renderedSelected = selected;
		nodes.clear();
		layer.getChildren().clear();
		double width = layer.getWidth();
		double height = layer.getHeight();
		for (AnnotationRegion annotation : next) {
			boolean isSelected = annotation.id().equals(selected);
			Pane node = buildNode(annotation, isSelected);
			AnnotationOverlayMath.Rect rect = rectFor(annotation, width, height);
			node.resizeRelocate(rect.x(), rect.y(), rect.width(), rect.height());
			nodes.put(annotation.id(), node);
			// Selected annotation last (React boosts the selected z-index above the rest).
			if (isSelected) {
				continue;
			}
			layer.getChildren().add(node);
		}
		Pane selectedNode = nodes.get(selected);
		if (selectedNode != null) {
			layer.getChildren().add(selectedNode);
		}
	}

	private Pane buildNode(AnnotationRegion annotation, boolean isSelected) {
		Pane node = new Pane();
		node.getStyleClass().add("editor-preview__annotation");
		node.setManaged(false);
		node.setPickOnBounds(true);
		if (isSelected) {
			node.setStyle("-fx-background-color: rgba(37,99,235,0.10);"
					+ " -fx-border-color: rgba(37,99,235,0.8); -fx-border-width: 2;");
			node.setCursor(Cursor.MOVE);
			node.setOnMousePressed(event -> {
				event.consume();
				beginDrag(annotation, node, event.getSceneX(), event.getSceneY(), false, 0);
			});
			node.setOnMouseDragged(event -> {
				event.consume();
				updateDrag(event.getSceneX(), event.getSceneY());
			});
			node.setOnMouseReleased(event -> {
				event.consume();
				endGesture();
			});
			for (int corner = 0; corner < 4; corner++) {
				node.getChildren().add(buildHandle(node, annotation, corner));
			}
		} else {
			node.setStyle("-fx-background-color: transparent;");
			node.setCursor(Cursor.HAND);
			node.setOnMousePressed(event -> {
				event.consume();
				store.apply(new AnnotationCommands.Select(annotation.id()));
			});
		}
		return node;
	}

	private Circle buildHandle(Pane node, AnnotationRegion annotation, int corner) {
		Circle handle = new Circle(HANDLE_SIZE / 2, Color.WHITE);
		handle.setStroke(ACCENT);
		handle.setStrokeWidth(2);
		handle.setManaged(false);
		handle.setCursor(corner == 0 || corner == 3 ? Cursor.NW_RESIZE : Cursor.NE_RESIZE);
		handle.setMouseTransparent(false);
		handle.setOnMousePressed(event -> {
			event.consume();
			beginDrag(annotation, node, event.getSceneX(), event.getSceneY(), true, corner);
		});
		handle.setOnMouseDragged(event -> {
			event.consume();
			updateDrag(event.getSceneX(), event.getSceneY());
		});
		handle.setOnMouseReleased(event -> {
			event.consume();
			endGesture();
		});
		return handle;
	}

	/** Places the four handles on the node corners (React: -6px offsets, 12px circles). */
	private static void relocateHandles(Pane node, AnnotationOverlayMath.Rect rect) {
		List<javafx.scene.Node> children = node.getChildrenUnmodifiable();
		if (children.size() < 4) {
			return;
		}
		double half = HANDLE_SIZE / 2;
		double[] xs = {0 - half, rect.width() - half, 0 - half, rect.width() - half};
		double[] ys = {0 - half, 0 - half, rect.height() - half, rect.height() - half};
		for (int i = 0; i < 4 && i < children.size(); i++) {
			javafx.scene.Node child = children.get(i);
			child.setLayoutX(xs[i]);
			child.setLayoutY(ys[i]);
		}
	}

	private AnnotationOverlayMath.Rect rectFor(AnnotationRegion annotation, double width,
			double height) {
		return AnnotationOverlayMath.overlayRect(annotation.position(), annotation.size(),
				geometry.get(), width, height);
	}

	// ── gestures ─────────────────────────────────────────────────────────────

	/** Mutable in-progress drag/resize. */
	private static final class Gesture {
		final AnnotationRegion annotation;
		final Pane node;
		final boolean resize;
		final int corner;
		final AnnotationOverlayMath.Rect startRect;
		final double pressX;
		final double pressY;
		boolean moved;
		AnnotationOverlayMath.Rect current;

		Gesture(AnnotationRegion annotation, Pane node, boolean resize, int corner,
				AnnotationOverlayMath.Rect startRect, double pressX, double pressY) {
			this.annotation = annotation;
			this.node = node;
			this.resize = resize;
			this.corner = corner;
			this.startRect = startRect;
			this.pressX = pressX;
			this.pressY = pressY;
			this.current = startRect;
		}
	}

	/** Starts a move (or a corner resize) from a press at scene coordinates. */
	void beginDrag(AnnotationRegion annotation, Pane node, double sceneX, double sceneY,
			boolean resize, int corner) {
		if (gesture != null) {
			return;
		}
		AnnotationOverlayMath.Rect start = new AnnotationOverlayMath.Rect(node.getLayoutX(),
				node.getLayoutY(), node.getWidth(), node.getHeight());
		gesture = new Gesture(annotation, node, resize, corner, start, sceneX, sceneY);
	}

	/** Applies the pointer delta of a drag to the node geometry (live preview, no commit). */
	void updateDrag(double sceneX, double sceneY) {
		Gesture active = gesture;
		if (active == null) {
			return;
		}
		double dx = sceneX - active.pressX;
		double dy = sceneY - active.pressY;
		if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
			active.moved = true;
		}
		AnnotationOverlayMath.Rect next;
		if (active.resize) {
			next = resizeRect(active.startRect, dx, dy, active.corner);
		} else {
			next = new AnnotationOverlayMath.Rect(active.startRect.x() + dx,
					active.startRect.y() + dy, active.startRect.width(),
					active.startRect.height());
		}
		active.current = next;
		active.node.resizeRelocate(next.x(), next.y(), next.width(), next.height());
		relocateHandles(active.node, next);
		if (active.moved) {
			AnnotationSize size = active.resize
					? AnnotationOverlayMath.sizeFor(next, geometry.get(), layer.getWidth(),
							layer.getHeight())
					: active.annotation.size();
			liveBoxListener.accept(new LiveBox(active.annotation.id(),
					AnnotationOverlayMath.positionFor(next, geometry.get(), layer.getWidth(),
							layer.getHeight()),
					size));
		}
	}

	/** Ends the gesture: a click cycles the selection, a drag commits one undoable edit. */
	void endGesture() {
		Gesture active = gesture;
		if (active == null) {
			return;
		}
		gesture = null;
		liveBoxListener.accept(null);
		if (!active.moved) {
			cycleSelection(active);
			return;
		}
		commit(active);
	}

	/** React {@code onDragStop}/{@code onResizeStop}: one undoable command per edit. */
	private void commit(Gesture active) {
		AnnotationOverlayMath.Rect rect = active.current;
		AnnotationPosition position = AnnotationOverlayMath.positionFor(rect, geometry.get(),
				layer.getWidth(), layer.getHeight());
		store.apply(new AnnotationCommands.ChangePosition(active.annotation.id(), position));
		if (active.resize) {
			AnnotationSize size = AnnotationOverlayMath.sizeFor(rect, geometry.get(),
					layer.getWidth(), layer.getHeight());
			store.apply(new AnnotationCommands.ChangeSize(active.annotation.id(), size));
		}
	}

	/** Clicking the selected annotation cycles through the annotations visible at that time. */
	private void cycleSelection(Gesture active) {
		if (!active.annotation.id().equals(store.selectedAnnotationId()) || visible.size() < 2) {
			return;
		}
		int current = -1;
		for (int i = 0; i < visible.size(); i++) {
			if (visible.get(i).id().equals(active.annotation.id())) {
				current = i;
				break;
			}
		}
		if (current < 0) {
			return;
		}
		String next = visible.get((current + 1) % visible.size()).id();
		store.apply(new AnnotationCommands.Select(next));
	}

	/** Resize from one corner keeping the opposite corner fixed (React Rnd semantics). */
	static AnnotationOverlayMath.Rect resizeRect(AnnotationOverlayMath.Rect start, double dx,
			double dy, int corner) {
		double x = start.x();
		double y = start.y();
		double width = start.width();
		double height = start.height();
		boolean west = corner == 0 || corner == 2;
		boolean north = corner == 0 || corner == 1;
		if (west) {
			x = start.x() + dx;
			width = start.width() - dx;
		} else {
			width = start.width() + dx;
		}
		if (north) {
			y = start.y() + dy;
			height = start.height() - dy;
		} else {
			height = start.height() + dy;
		}
		// Never collapse or invert: clamp to the minimum size, keeping the anchored edge.
		if (width < MIN_SIZE) {
			if (west) {
				x = start.x() + start.width() - MIN_SIZE;
			}
			width = MIN_SIZE;
		}
		if (height < MIN_SIZE) {
			if (north) {
				y = start.y() + start.height() - MIN_SIZE;
			}
			height = MIN_SIZE;
		}
		return new AnnotationOverlayMath.Rect(x, y, width, height);
	}

	/** Current gesture, for tests. */
	boolean isDragging() {
		return gesture != null;
	}

	/** Node for an annotation id, for tests. */
	Pane nodeFor(String id) {
		return nodes.get(id);
	}

	/** Handle circles of a node, for tests. */
	List<Circle> handlesFor(String id) {
		Pane node = nodes.get(id);
		if (node == null) {
			return List.of();
		}
		List<Circle> handles = new ArrayList<>();
		for (javafx.scene.Node child : node.getChildrenUnmodifiable()) {
			if (child instanceof Circle circle) {
				handles.add(circle);
			}
		}
		return handles;
	}

	/** Test seam: clears any in-progress gesture. */
	void cancelGesture() {
		gesture = null;
	}

	private static boolean eq(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}
}
