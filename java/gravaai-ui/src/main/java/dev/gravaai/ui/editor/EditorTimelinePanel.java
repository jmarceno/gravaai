package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import javafx.beans.binding.DoubleExpression;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

/**
 * Timeline strip below the preview (port of {@code layout/EditorTimelinePanel.tsx}): 22% of the
 * window height until the user drags the handle on its top edge, which then fixes the height in
 * pixels so the strip can show more lanes at once. The dashboard teammate's
 * {@code dev.gravaai.ui.editor.timeline.TimelineView} is mounted through {@link #setContent};
 * until it is wired, the empty-timeline visual from the reference screenshot is rendered.
 */
public final class EditorTimelinePanel extends VBox {

	/** Shortest the strip may get: the toolbar, the ruler and one lane stay visible. */
	public static final double MIN_HEIGHT = 180;
	/** Share of the editor height used before the user overrides it with the handle (mock ~23%). */
	private static final double DEFAULT_HEIGHT_RATIO = 0.23;
	/**
	 * Default-height ceiling (the pre-resize cap), raised so the toolbar, ruler and four 44px lanes
	 * fit without scrolling (Instrument mock).
	 */
	private static final double DEFAULT_MAX_HEIGHT = 400;
	/** Height the preview column keeps (image plus the playback bar) when the strip is dragged up. */
	private static final double PREVIEW_RESERVE = 260;
	/** Grab strip along the top edge; generous enough to hit without pixel hunting. */
	private static final double RESIZE_HANDLE_HEIGHT = 8;
	/** Distance the pointer moves before a press counts as a drag instead of a click. */
	private static final double DRAG_THRESHOLD_PX = 2;

	private final Pane host = new Pane();
	private final StackPane resizeHandle = new StackPane();
	private final Region resizeGrip = new Region();
	private @Nullable Node content;
	private @Nullable DoubleExpression editorHeight;
	private @Nullable ChangeListener<Number> heightListener;
	/** Whether the user dragged the handle; until then the strip follows the editor height. */
	private boolean userSized;
	/** Height the user dragged to, before clamping. */
	private double userHeight;
	private boolean resizing;
	private boolean resizeMoved;
	private double resizeStartSceneY;
	private double resizeStartHeight;

	public EditorTimelinePanel() {
		getStyleClass().add("editor-timeline");
		setMinHeight(MIN_HEIGHT);
		VBox.setVgrow(host, javafx.scene.layout.Priority.ALWAYS);
		host.setMinHeight(0);
		// The timeline scrolls its own rows; nothing may paint past the panel (React overflow-hidden).
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
		clip.widthProperty().bind(host.widthProperty());
		clip.heightProperty().bind(host.heightProperty());
		host.setClip(clip);
		installResizeHandle();
		getChildren().addAll(resizeHandle, host);
		setEmptyTimeline();
	}

	/** Follows the editor height (22%, capped at 280) until the user drags the handle. */
	public void bindDefaultHeight(DoubleExpression editorHeight) {
		if (this.editorHeight != null && heightListener != null) {
			this.editorHeight.removeListener(heightListener);
		}
		this.editorHeight = editorHeight;
		heightListener = (observable, oldValue, newValue) -> {
			if (!userSized) {
				requestLayout();
			}
		};
		editorHeight.addListener(heightListener);
		if (!userSized) {
			applyDefaultHeight();
		}
	}

	/**
	 * Fixes the strip height in pixels, clamped so the panel stays usable and the preview column
	 * keeps room; called by the drag handle.
	 */
	public void setTimelineHeight(double height) {
		userSized = true;
		userHeight = height;
		applyUserHeight();
	}

	/** Double-clicking the handle goes back to the 22% default. */
	public void resetTimelineHeight() {
		userSized = false;
		applyDefaultHeight();
	}

	private void applyDefaultHeight() {
		// A dragged height is the user's call; only the preview column's room caps it.
		setMaxHeight(DEFAULT_MAX_HEIGHT);
		requestLayout();
	}

	private void applyUserHeight() {
		setMaxHeight(Double.MAX_VALUE);
		requestLayout();
	}

	/**
	 * The strip height: the default share of the editor, or the dragged height clamped so the
	 * preview column keeps {@link #PREVIEW_RESERVE}. Computing it here (instead of caching a pixel
	 * size) means a window resize re-clamps the strip for free.
	 */
	@Override
	protected double computePrefHeight(double width) {
		if (userSized) {
			return Math.max(MIN_HEIGHT, Math.min(userHeight, maximumUserHeight()));
		}
		if (editorHeight == null) {
			return MIN_HEIGHT;
		}
		return Math.min(DEFAULT_MAX_HEIGHT, editorHeight.get() * DEFAULT_HEIGHT_RATIO);
	}

	/** The top-edge drag handle (tests and automation). */
	public Region resizeHandle() {
		return resizeHandle;
	}

	/** Dragging the top edge up grows the strip: scene y moves opposite to the height. */
	private void installResizeHandle() {
		resizeGrip.getStyleClass().add("editor-timeline__resize-grip");
		resizeHandle.getStyleClass().add("editor-timeline__resize-handle");
		resizeHandle.setMinHeight(RESIZE_HANDLE_HEIGHT);
		resizeHandle.setPrefHeight(RESIZE_HANDLE_HEIGHT);
		resizeHandle.setMaxHeight(RESIZE_HANDLE_HEIGHT);
		resizeHandle.setPickOnBounds(true);
		resizeHandle.setAccessibleText("Resize the timeline");
		resizeHandle.getChildren().add(resizeGrip);
		resizeHandle.setOnMousePressed(event -> {
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			resizing = true;
			resizeMoved = false;
			resizeStartSceneY = event.getSceneY();
			resizeStartHeight = getHeight() > 0 ? getHeight() : getPrefHeight();
			resizeHandle.getStyleClass().add("editor-timeline__resize-handle--dragging");
			event.consume();
		});
		resizeHandle.setOnMouseDragged(event -> {
			if (!resizing) {
				return;
			}
			if (Math.abs(event.getSceneY() - resizeStartSceneY) < DRAG_THRESHOLD_PX) {
				return;
			}
			resizeMoved = true;
			setTimelineHeight(resizeStartHeight + (resizeStartSceneY - event.getSceneY()));
			event.consume();
		});
		resizeHandle.setOnMouseReleased(event -> {
			resizing = false;
			resizeHandle.getStyleClass().remove("editor-timeline__resize-handle--dragging");
			event.consume();
		});
		// Double-click resets to the default share; a click without dragging does nothing.
		resizeHandle.setOnMouseClicked(event -> {
			if (resizeMoved || event.getClickCount() < 2 || event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			resetTimelineHeight();
			event.consume();
		});
	}

	/** Tallest the strip may get without the preview column collapsing. */
	private double maximumUserHeight() {
		double available = getParent() == null ? 0 : getParent().getLayoutBounds().getHeight();
		return available <= 0 ? Double.MAX_VALUE : Math.max(MIN_HEIGHT, available - PREVIEW_RESERVE);
	}

	/** Mounts the real timeline view (or any host node). */
	public void setContent(Node timelineContent) {
		this.content = timelineContent;
		host.getChildren().setAll(timelineContent == null ? emptyTimeline() : timelineContent);
		if (timelineContent != null) {
			anchorToHost(timelineContent);
		}
	}

	public @Nullable Node content() {
		return content;
	}

	private void setEmptyTimeline() {
		host.getChildren().setAll(emptyTimeline());
	}

	private void anchorToHost(Node node) {
		if (node instanceof Region region) {
			region.prefWidthProperty().bind(host.widthProperty());
			region.prefHeightProperty().bind(host.heightProperty());
		}
	}

	/** Empty timeline: red playhead over a bordered clip row, like the reference screenshot. */
	private Pane emptyTimeline() {
		Pane root = new Pane();
		root.prefHeightProperty().bind(host.heightProperty());
		root.prefWidthProperty().bind(host.widthProperty());
		root.setMinHeight(0);

		Region track = new Region();
		track.getStyleClass().add("editor-timeline__track");
		track.setLayoutY(40);
		track.setLayoutX(12);
		track.prefWidthProperty().bind(root.widthProperty().subtract(24));
		track.setPrefHeight(58);
		track.setMinHeight(58);

		Region leftHandle = new Region();
		leftHandle.getStyleClass().add("editor-timeline__handle");
		leftHandle.setPrefSize(4, 44);
		leftHandle.setLayoutX(16);
		leftHandle.setLayoutY(47);

		Region rightHandle = new Region();
		rightHandle.getStyleClass().add("editor-timeline__handle");
		rightHandle.setPrefSize(4, 44);
		rightHandle.layoutXProperty().bind(root.widthProperty().subtract(20));
		rightHandle.setLayoutY(47);

		double playheadX = 28;
		Polygon triangle = new Polygon(playheadX - 6, 0, playheadX + 6, 0, playheadX, 8);
		triangle.getStyleClass().add("editor-timeline__playhead");
		Line line = new Line(playheadX, 0, playheadX, 0);
		line.getStyleClass().add("editor-timeline__playhead");
		line.setStroke(Color.web("#ef4444"));
		line.setStrokeWidth(1.5);
		line.endYProperty().bind(root.heightProperty());

		root.getChildren().addAll(track, leftHandle, rightHandle, line, triangle);
		return root;
	}
}
