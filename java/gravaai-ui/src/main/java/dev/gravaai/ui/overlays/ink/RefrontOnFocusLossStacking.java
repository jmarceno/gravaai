package dev.gravaai.ui.overlays.ink;

import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * {@link dev.gravaai.platform.compositor.OverlayStacking#REFRONT_ON_FOCUS_LOSS}: the window manager
 * ignores keep-above, so whenever the overlay loses focus while draw mode is on it raises itself and
 * takes focus back.
 *
 * <p>Measured on COSMIC (2026-11-13 spike): a freshly mapped overlay is above every window, a
 * window opened later covers it, and {@code toFront()} + {@code requestFocus()} brings the overlay
 * back above it with focus.
 */
final class RefrontOnFocusLossStacking implements InkStacking {

	@Override
	public void attach(Stage overlay, BooleanSupplier active) {
		overlay.focusedProperty().addListener((observable, was, focused) -> {
			if (!focused && active.getAsBoolean()) {
				// Deferred: raising inside the focus change itself races the window manager's own
				// stacking of the window that just took focus.
				Platform.runLater(() -> raise(overlay, active));
			}
		});
	}

	static void raise(Stage overlay, BooleanSupplier active) {
		if (active.getAsBoolean() && overlay.isShowing() && !overlay.isFocused()) {
			overlay.toFront();
			overlay.requestFocus();
		}
	}
}
