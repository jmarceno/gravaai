package dev.gravaai.ui.overlays.ink;

import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.stage.Stage;

import dev.gravaai.ui.windows.X11Windows;

/**
 * {@link dev.gravaai.platform.compositor.OverlayStacking#KEEP_ABOVE}: the window manager honours
 * {@code _NET_WM_STATE_ABOVE}, so the overlay asks for it once. It still re-raises on focus loss as
 * a backstop, because this is unverified on KDE and GNOME until their compatibility pass.
 */
final class KeepAboveStacking implements InkStacking {

	private static final System.Logger LOG = System.getLogger("gravaai.ink");

	@Override
	public void attach(Stage overlay, BooleanSupplier active) {
		try {
			X11Windows.keepAbove(overlay);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "keepAbove failed for the ink overlay: " + error);
		}
		overlay.focusedProperty().addListener((observable, was, focused) -> {
			if (!focused && active.getAsBoolean()) {
				Platform.runLater(() -> RefrontOnFocusLossStacking.raise(overlay, active));
			}
		});
	}
}
