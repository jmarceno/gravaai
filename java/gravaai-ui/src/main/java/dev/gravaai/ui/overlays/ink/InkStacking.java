package dev.gravaai.ui.overlays.ink;

import java.util.function.BooleanSupplier;

import javafx.stage.Stage;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.platform.compositor.OverlayStacking;

/**
 * How the ink overlay stays above the windows it covers. Window managers differ here (COSMIC
 * ignores keep-above for XWayland windows, KWin and Mutter honour it), so each mechanism lives in
 * its own class and the overlay never asks which desktop it runs on. The desktop's choice comes
 * from {@link DesktopSession#overlayStacking()}.
 */
public interface InkStacking {

	/**
	 * Called once, right after the overlay stage is shown, on the FX thread.
	 *
	 * @param overlay the shown overlay stage
	 * @param active true while draw mode is on; a stacking must stop acting once it is false
	 */
	void attach(Stage overlay, BooleanSupplier active);

	/** The mechanism this session's desktop needs. */
	static InkStacking forSession(DesktopSession session) {
		return forMechanism(session.overlayStacking());
	}

	/** The class that implements one mechanism. */
	static InkStacking forMechanism(OverlayStacking mechanism) {
		return switch (mechanism) {
			case REFRONT_ON_FOCUS_LOSS -> new RefrontOnFocusLossStacking();
			case KEEP_ABOVE -> new KeepAboveStacking();
		};
	}
}
