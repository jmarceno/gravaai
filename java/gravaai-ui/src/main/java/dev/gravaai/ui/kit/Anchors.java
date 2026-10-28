package dev.gravaai.ui.kit;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;

/**
 * Placement maths for in-window overlays (plan §5.12). HeroUI positions overlays with React Aria's
 * anchor point plus a collision flip; this is the JavaFX equivalent: content is placed next to the
 * owner's scene bounds for a side/alignment pair and clamped to the window.
 */
public final class Anchors {

	/** Which side of the owner the content sits on. */
	public enum Side { TOP, BOTTOM, LEFT, RIGHT }

	/** Alignment along the owner edge. */
	public enum Align { START, CENTER, END }

	/** Gap between owner and content: HeroUI popover offset. */
	public static final double DEFAULT_OFFSET = 8;

	private Anchors() {}

	/** Computes the content layout position in layer coordinates (the layer fills the window). */
	public static Point2D place(Node owner, Side side, Align align, double width, double height,
			double offset, double layerWidth, double layerHeight) {
		Bounds bounds = owner.localToScene(owner.getLayoutBounds());
		double x;
		double y;
		switch (side) {
			case TOP -> {
				y = bounds.getMinY() - offset - height;
				x = alignedX(bounds, align, width);
			}
			case BOTTOM -> {
				y = bounds.getMaxY() + offset;
				x = alignedX(bounds, align, width);
			}
			case LEFT -> {
				x = bounds.getMinX() - offset - width;
				y = alignedY(bounds, align, height);
			}
			case RIGHT -> {
				x = bounds.getMaxX() + offset;
				y = alignedY(bounds, align, height);
			}
			default -> throw new IllegalArgumentException("Unknown side " + side);
		}
		// Collision handling: keep the content inside the window, like React Aria's flip/shift.
		double maxX = Math.max(0, layerWidth - width);
		double maxY = Math.max(0, layerHeight - height);
		x = Math.max(0, Math.min(x, maxX));
		y = Math.max(0, Math.min(y, maxY));
		return new Point2D(x, y);
	}

	private static double alignedX(Bounds bounds, Align align, double width) {
		return switch (align) {
			case START -> bounds.getMinX();
			case CENTER -> bounds.getCenterX() - width / 2;
			case END -> bounds.getMaxX() - width;
		};
	}

	private static double alignedY(Bounds bounds, Align align, double height) {
		return switch (align) {
			case START -> bounds.getMinY();
			case CENTER -> bounds.getCenterY() - height / 2;
			case END -> bounds.getMaxY() - height;
		};
	}

	/** Flip the side when the requested one does not fit; HeroUI's {@code shouldFlip}. */
	public static Side flipIfNeeded(Node owner, Side side, double contentWidth, double contentHeight,
			double offset, double layerWidth, double layerHeight) {
		Bounds bounds = owner.localToScene(owner.getLayoutBounds());
		return switch (side) {
			case BOTTOM -> bounds.getMaxY() + offset + contentHeight > layerHeight ? Side.TOP : Side.BOTTOM;
			case TOP -> bounds.getMinY() - offset - contentHeight < 0 ? Side.BOTTOM : Side.TOP;
			case RIGHT -> bounds.getMaxX() + offset + contentWidth > layerWidth ? Side.LEFT : Side.RIGHT;
			case LEFT -> bounds.getMinX() - offset - contentWidth < 0 ? Side.RIGHT : Side.LEFT;
		};
	}
}
