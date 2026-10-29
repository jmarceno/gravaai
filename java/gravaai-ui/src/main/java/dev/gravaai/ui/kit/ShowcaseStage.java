package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Window placement for the kit showcase (plan §5.4: use {@link Screen} for geometry).
 *
 * <p>This machine's primary display is a portrait 1080x1920 monitor next to a landscape
 * 1920x1080 one, so a wide gallery must be placed on the widest screen explicitly instead of
 * relying on the window manager's default placement. The window is clamped to that screen's
 * visual bounds and centred.
 */
public final class ShowcaseStage {

	/** The widest usable screen (landscape preferred, then the largest area). */
	public static @Nullable Screen showcaseScreen() {
		Screen best = null;
		double bestArea = -1;
		for (Screen screen : Screen.getScreens()) {
			Rectangle2D bounds = screen.getVisualBounds();
			double area = bounds.getWidth() * bounds.getHeight();
			boolean wider = bounds.getWidth() >= bounds.getHeight();
			boolean bestWider = best != null
					&& best.getVisualBounds().getWidth() >= best.getVisualBounds().getHeight();
			if (best == null || (wider && !bestWider) || (wider == bestWider && area > bestArea)) {
				best = screen;
				bestArea = area;
			}
		}
		return best;
	}

	/** Sizes the stage to at most 1440x1000 and centres it inside the chosen screen. */
	public static void place(Stage stage) {
		Screen screen = showcaseScreen();
		if (screen == null) {
			stage.setWidth(1440);
			stage.setHeight(1000);
			return;
		}
		Rectangle2D bounds = screen.getVisualBounds();
		double width = Math.min(1440, Math.max(900, bounds.getWidth() - 40));
		double height = Math.min(1000, Math.max(600, bounds.getHeight() - 80));
		stage.setWidth(width);
		stage.setHeight(height);
		stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
		stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
	}

	private ShowcaseStage() {}
}
