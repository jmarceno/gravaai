package dev.gravaai.ui.overlays.ink;

import java.util.Optional;

import javafx.geometry.Rectangle2D;

/**
 * Where the palette sits on the ink overlay, in scene coordinates. Pure arithmetic so it is tested
 * without a window.
 *
 * <p>By default the palette hangs top-centre of the overlay stage and dragging may take it anywhere
 * on that stage. When an area is being recorded the stage still covers the whole monitor while only
 * the area is captured, so the palette hangs top-centre of the area instead and dragging keeps it
 * inside: a palette outside the area would be on the screen but not in the video.
 */
final class InkPalettePlacement {

	/** Gap between the palette and the top edge it hangs from. */
	static final double TOP_MARGIN = 16;

	private InkPalettePlacement() {
	}

	/** The palette's top-left corner, in scene coordinates. */
	record Position(double x, double y) {
	}

	/** Top-centre of the bounds the palette belongs to: the recorded area when there is one. */
	static Position initial(Rectangle2D stage, Optional<Rectangle2D> area, double paletteWidth,
			double paletteHeight) {
		Spot bounds = bounds(stage, area);
		return inside(bounds, paletteWidth, paletteHeight,
				bounds.x() + (bounds.width() - paletteWidth) / 2, bounds.y() + TOP_MARGIN);
	}

	/** A drag target kept inside the same bounds as {@link #initial}. */
	static Position dragged(Rectangle2D stage, Optional<Rectangle2D> area, double paletteWidth,
			double paletteHeight, double sceneX, double sceneY) {
		return inside(bounds(stage, area), paletteWidth, paletteHeight, sceneX, sceneY);
	}

	/**
	 * The rectangle, in scene coordinates, the palette belongs inside: the recorded area mapped onto
	 * the stage, or the whole stage when the whole monitor is recorded.
	 */
	private static Spot bounds(Rectangle2D stage, Optional<Rectangle2D> area) {
		return area
				.map(rect -> new Spot(rect.getMinX() - stage.getMinX(), rect.getMinY() - stage.getMinY(),
						rect.getWidth(), rect.getHeight()))
				.orElseGet(() -> new Spot(0, 0, stage.getWidth(), stage.getHeight()));
	}

	/**
	 * The top-left corner that keeps the palette inside {@code bounds}. A palette that cannot fit
	 * (an area slimmer than the palette) is centred on that edge instead of pinned to it.
	 */
	private static Position inside(Spot bounds, double paletteWidth, double paletteHeight, double x, double y) {
		return new Position(fit(x, paletteWidth, bounds.x(), bounds.width()),
				fit(y, paletteHeight, bounds.y(), bounds.height()));
	}

	private static double fit(double position, double paletteLength, double boundsStart, double boundsLength) {
		double max = boundsStart + boundsLength - paletteLength;
		if (max < boundsStart) {
			return boundsStart + (boundsLength - paletteLength) / 2;
		}
		return Math.min(Math.max(position, boundsStart), max);
	}

	private record Spot(double x, double y, double width, double height) {
	}
}
