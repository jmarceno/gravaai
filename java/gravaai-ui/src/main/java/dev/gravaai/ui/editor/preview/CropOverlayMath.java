package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.media.compose.CameraGeometry;

/**
 * Coordinate math of the preview crop overlay: the same conversion chain as
 * {@link AnnotationOverlayMath} (panel ↔ output ↔ scene ↔ recording rect), but the overlay edits
 * the crop, whose coordinates are fractions of the <em>source frame</em> while the compositor
 * displays the cropped region inside the recording rect ({@code CameraGeometry} mask):
 *
 * <pre>
 * u        = (sourceX - crop.x) / crop.width          // position within the crop
 * sceneX   = maskX + u * maskWidth
 * screenX  = sceneX * scale + transformX
 * panelX   = screenX * panelWidth / outputWidth
 * </pre>
 *
 * <p>The mask rect already is the display rect of the live crop, so handle placement just applies
 * the camera transform to it; the inverse (pointer → source) additionally converts through the
 * crop bounds. Gestures anchor that conversion to the geometry and the crop of the drag start, so
 * it stays linear while the compositor re-fits the moving crop (the same drag-start anchoring the
 * annotation overlay uses). Pure logic (no JavaFX), so the behaviour is unit-tested without a GL
 * context.
 */
public final class CropOverlayMath {

	/** Smallest kept span of the frame, like the crop dialog ({@code MIN_SPAN}). */
	public static final double MIN_SPAN = 0.1;

	/** The draggable parts of the crop rectangle: four edges and four corners. */
	public enum Handle { TOP, BOTTOM, LEFT, RIGHT, NW, NE, SW, SE }

	/** A point in panel pixels or source fractions. */
	public record Point(double x, double y) {
	}

	private CropOverlayMath() {
	}

	/** The geometry to map through: null or frame-less falls back to the whole output frame. */
	private static CameraGeometry geometryFor(@Nullable CameraGeometry geometry,
			double panelWidth, double panelHeight) {
		if (geometry == null) {
			return CameraGeometry.fullFrame(Math.max(1, (int) Math.round(panelWidth)),
					Math.max(1, (int) Math.round(panelHeight)));
		}
		return AnnotationOverlayMath.safeGeometry(geometry, panelWidth, panelHeight);
	}

	/**
	 * Panel-pixel rectangle for the crop the compositor is displaying — the mask rect with the
	 * camera transform applied (the rect the handles are drawn on). Falls back to the full output
	 * frame when the compositor has not drawn a source frame yet.
	 */
	public static AnnotationOverlayMath.Rect rectFor(@Nullable CameraGeometry geometry,
			double panelWidth, double panelHeight) {
		CameraGeometry g = geometryFor(geometry, panelWidth, panelHeight);
		double scale = g.scale() <= 0 ? 1 : g.scale();
		double panelScaleX = panelWidth / Math.max(1, g.outputWidth());
		double panelScaleY = panelHeight / Math.max(1, g.outputHeight());
		return new AnnotationOverlayMath.Rect(
				(g.maskX() * scale + g.transformX()) * panelScaleX,
				(g.maskY() * scale + g.transformY()) * panelScaleY,
				g.maskWidth() * scale * panelScaleX,
				g.maskHeight() * scale * panelScaleY);
	}

	/**
	 * Panel pixels → source fractions through {@code crop} and {@code geometry} (the inverse of
	 * the forward chain above). Call with the crop and the geometry as they were when the gesture
	 * started, so the conversion stays anchored while the compositor re-fits the moving crop.
	 */
	public static Point sourcePointFor(double panelX, double panelY, @Nullable CropRegion crop,
			@Nullable CameraGeometry geometry, double panelWidth, double panelHeight) {
		CropRegion region = crop == null ? CropRegion.DEFAULT : crop;
		CameraGeometry g = geometryFor(geometry, panelWidth, panelHeight);
		double scale = g.scale() <= 0 ? 1 : g.scale();
		double sceneX = (panelX * g.outputWidth() / Math.max(1, panelWidth) - g.transformX())
				/ scale;
		double sceneY = (panelY * g.outputHeight() / Math.max(1, panelHeight) - g.transformY())
				/ scale;
		double u = (sceneX - g.maskX()) / Math.max(1, g.maskWidth());
		double v = (sceneY - g.maskY()) / Math.max(1, g.maskHeight());
		return new Point(region.x() + u * region.width(), region.y() + v * region.height());
	}

	/**
	 * Free drag: moves the handle's edges to the given source coordinates, keeping at least
	 * {@link #MIN_SPAN} of the frame and never inverting the rectangle.
	 */
	public static CropRegion drag(CropRegion start, Handle handle, double sourceX, double sourceY) {
		double x = start.x();
		double y = start.y();
		double width = start.width();
		double height = start.height();
		double right = start.x() + start.width();
		double bottom = start.y() + start.height();
		switch (handle) {
			case TOP -> {
				y = clamp(sourceY, 0, bottom - MIN_SPAN);
				height = bottom - y;
			}
			case BOTTOM -> height = clamp(sourceY, Math.min(y + MIN_SPAN, 1), 1) - y;
			case LEFT -> {
				x = clamp(sourceX, 0, right - MIN_SPAN);
				width = right - x;
			}
			case RIGHT -> width = clamp(sourceX, Math.min(x + MIN_SPAN, 1), 1) - x;
			case NW -> {
				x = clamp(sourceX, 0, right - MIN_SPAN);
				width = right - x;
				y = clamp(sourceY, 0, bottom - MIN_SPAN);
				height = bottom - y;
			}
			case NE -> {
				width = clamp(sourceX, Math.min(x + MIN_SPAN, 1), 1) - x;
				y = clamp(sourceY, 0, bottom - MIN_SPAN);
				height = bottom - y;
			}
			case SW -> {
				x = clamp(sourceX, 0, right - MIN_SPAN);
				width = right - x;
				height = clamp(sourceY, Math.min(y + MIN_SPAN, 1), 1) - y;
			}
			case SE -> {
				width = clamp(sourceX, Math.min(x + MIN_SPAN, 1), 1) - x;
				height = clamp(sourceY, Math.min(y + MIN_SPAN, 1), 1) - y;
			}
		}
		return new CropRegion(x, y, width, height);
	}

	/**
	 * Fits a dragged region to the locked pixel ratio ({@code ratio = width px / height px};
	 * {@code sourceWidth}/{@code sourceHeight} of 0 fall back to a square source). The dragged
	 * axis keeps the free-drag size inside its anchored bounds; the other axis follows the ratio —
	 * a corner drag anchors its opposite corner, an edge drag re-centres its fixed axis. Where the
	 * frame boundary or the minimum span forces it, the ratio gives (like every boundary-limited
	 * crop).
	 */
	public static CropRegion applyAspect(CropRegion dragged, CropRegion start, Handle handle,
			double ratio, double sourceWidth, double sourceHeight) {
		double sw = sourceWidth > 0 ? sourceWidth : 1;
		double sh = sourceHeight > 0 ? sourceHeight : 1;
		double right = start.x() + start.width();
		double bottom = start.y() + start.height();
		boolean verticalEdge = handle == Handle.TOP || handle == Handle.BOTTOM;
		boolean corner = !verticalEdge && handle != Handle.LEFT && handle != Handle.RIGHT;
		boolean westSide = handle == Handle.LEFT || handle == Handle.NW
				|| handle == Handle.SW;
		// The far edges the drag must not move (source-frame anchors of both axes).
		double primaryMax = verticalEdge
				? (handle == Handle.TOP ? bottom : 1 - start.y())
				: (westSide ? right : 1 - start.x());
		double secondaryMax = verticalEdge ? 1
				: corner
						? (handle == Handle.NW || handle == Handle.NE ? bottom : 1 - start.y())
						: 1;

		// The dragged axis keeps the pointer's size; the derived axis follows the ratio. When the
		// frame capped the derived axis, the dragged axis shrinks back so the ratio still holds.
		// The floor never pushes past the anchored edge (a loaded project may carry a crop below
		// the overlay's minimum span; the normalizer's floor is 0.01).
		double primaryFloor = Math.min(MIN_SPAN, primaryMax);
		double primary = clamp(verticalEdge ? dragged.height() : dragged.width(), primaryFloor,
				primaryMax);
		double secondary = clamp(leg(primary, verticalEdge, ratio, sw, sh), 0, secondaryMax);
		if (leg(secondary, !verticalEdge, ratio, sw, sh) < primary - 1e-12) {
			primary = clamp(leg(secondary, !verticalEdge, ratio, sw, sh), primaryFloor, primaryMax);
			secondary = clamp(leg(primary, verticalEdge, ratio, sw, sh), 0, secondaryMax);
		}

		double x;
		double y;
		double width;
		double height;
		if (verticalEdge) {
			// Height follows the pointer (bottom edge anchored for TOP, top edge for BOTTOM);
			// the width re-centres on the drag-start centre.
			height = primary;
			width = secondary;
			x = clamp((start.x() + right) / 2 - width / 2, 0, 1 - width);
			y = handle == Handle.TOP ? bottom - height : start.y();
		} else {
			// Width follows the pointer (right edge anchored for LEFT, left edge for RIGHT);
			// corners anchor the opposite corner vertically, edges re-centre.
			width = primary;
			height = secondary;
			if (handle == Handle.NW || handle == Handle.NE) {
				// The derived height grows upward from the anchored bottom edge: floor it first
				// so the top edge moves and the anchor holds (a wide locked ratio near the frame
				// bottom would otherwise push the bottom edge past the frame).
				height = Math.max(height, Math.min(MIN_SPAN, bottom));
			}
			x = handle == Handle.LEFT || handle == Handle.NW || handle == Handle.SW
					? right - width
					: start.x();
			y = corner
					? (handle == Handle.NW || handle == Handle.NE
							? bottom - height : start.y())
					: clamp((start.y() + bottom) / 2 - height / 2, 0, 1 - height);
		}
		// Boundary or minimum span can outvote the ratio in the end: stay inside the frame and
		// behind the anchored edge. The floor keeps the minimum span for re-centred axes, but
		// never past an anchored span (a loaded crop may sit below the overlay's minimum).
		double widthFloor = !verticalEdge && westSide
				? Math.min(MIN_SPAN, right)
				: Math.min(MIN_SPAN, 1 - Math.max(0, x));
		double heightFloor = (verticalEdge && handle == Handle.TOP)
				|| (corner && (handle == Handle.NW || handle == Handle.NE))
				? Math.min(MIN_SPAN, bottom)
				: Math.min(MIN_SPAN, 1 - Math.max(0, y));
		width = clamp(width, widthFloor, 1 - Math.max(0, x));
		height = clamp(height, heightFloor, 1 - Math.max(0, y));
		return new CropRegion(Math.max(0, x), Math.max(0, y), width, height);
	}

	/** The locked pixel ratio of a crop; the aspect lock captures this when it turns on. */
	public static double pixelRatio(CropRegion crop, double sourceWidth, double sourceHeight) {
		double sw = sourceWidth > 0 ? sourceWidth : 1;
		double sh = sourceHeight > 0 ? sourceHeight : 1;
		double heightPx = crop.height() * sh;
		if (heightPx <= 0) {
			return 1;
		}
		return crop.width() * sw / heightPx;
	}

	/** One ratio leg: converts a span to the other axis (pixel ratio, source-dims aware). */
	private static double leg(double value, boolean valueIsHeight, double ratio, double sw,
			double sh) {
		return valueIsHeight ? value * ratio * sh / sw : value * sw / (ratio * sh);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
