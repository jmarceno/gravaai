package dev.gravaai.ui.windows;

import org.jspecify.annotations.Nullable;

import dev.gravaai.platform.x11.X11Bridge;
import java.util.Optional;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * FX-thread adapter for the platform's JavaFX-free {@link X11Bridge} (plan §5.4, §5.5).
 *
 * <p>Everything here must run on the JavaFX application thread: that is GTK's thread and the only
 * thread allowed to use the {@code Display*}. Use {@link #beginMove} for the HUD drag handle (the
 * COSMIC window manager reverts client-side moves) and {@link #setClickThrough} for the area
 * recording border.
 */
public final class X11Windows {

	private static final System.Logger LOG = System.getLogger("gravaai.ui.x11");

	private static @Nullable X11Bridge bridge;
	private static boolean bridgeTried;

	private X11Windows() {
	}

	/** True on X11/XWayland, i.e. when window-manager messages and input shapes are available. */
	public static boolean isAvailable() {
		return bridge().isPresent();
	}

	/**
	 * Hands an in-progress button-1 drag to the window manager, which then moves the window itself.
	 * Safe to call outside X11: it logs once and does nothing.
	 */
	public static void beginMove(Window window, double screenX, double screenY) {
		long windowId = windowId(window);
		if (windowId == 0) {
			return;
		}
		bridge().ifPresent(x11 -> x11.beginMove(windowId, (int) Math.round(screenX), (int) Math.round(screenY)));
	}

	/** Replaces the window's input shape with zero rectangles so mouse events fall through. */
	public static void setClickThrough(Window window) {
		long windowId = windowId(window);
		if (windowId == 0) {
			return;
		}
		bridge().ifPresent(x11 -> x11.setInputShapeEmpty(windowId));
	}

	/** Asks EWMH for keep-above (harmless; COSMIC ignores it, plan §3/§5.4). */
	public static void keepAbove(Window window) {
		long windowId = windowId(window);
		if (windowId == 0) {
			return;
		}
		bridge().ifPresent(x11 -> x11.keepAbove(windowId));
	}

	/** The X11 id of the JavaFX window, found by its title; 0 when it cannot be located. */
	private static long windowId(Window window) {
		requireFxThread();
		if (window == null || !window.isShowing()) {
			return 0;
		}
		String title = window instanceof Stage stage ? stage.getTitle() : null;
		if (title == null || title.isEmpty()) {
			LOG.log(System.Logger.Level.DEBUG, "window has no title; cannot map it to an X11 window");
			return 0;
		}
		return bridge().map(x11 -> x11.findWindow(title)).orElse(0L);
	}

	private static Optional<X11Bridge> bridge() {
		if (!bridgeTried) {
			bridgeTried = true;
			bridge = X11Bridge.tryOpen().orElse(null);
			if (bridge == null) {
				LOG.log(System.Logger.Level.INFO, "no X11 bridge available; window-manager helpers are disabled");
			}
		}
		return Optional.ofNullable(bridge);
	}

	private static void requireFxThread() {
		if (!Platform.isFxApplicationThread()) {
			throw new IllegalStateException("X11 window helpers must run on the JavaFX application thread");
		}
	}
}
