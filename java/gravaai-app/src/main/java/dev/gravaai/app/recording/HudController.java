package dev.gravaai.app.recording;

import dev.gravaai.services.sources.CaptureArea;

/**
 * The HUD/launch-window operations the recording wiring needs (plan §7.1 P1.7). The HUD owner
 * implements it when the HUD lands; {@link #noOp()} keeps the wiring runnable meanwhile, so a
 * recording can be started from a shortcut or the dev entry point without a HUD.
 */
public interface HudController {

	/** Called before the capture helper grabs the screen, so the HUD is not recorded. */
	void hideForCapture();

	/** Restores the HUD when a recording fails to start. */
	void restoreAfterCapture();

	/** Shows the real cursor again when recording stops ({@code cursorHider.ts}). */
	void showCursor();

	/** Shows the click-through border around an area recording (also owned by the wiring). */
	void showAreaBorder(CaptureArea area);

	void hideAreaBorder();

	/** The countdown's remaining seconds, or null when the countdown is over. */
	void setCountdown(Integer remaining);

	/** A HUD that ignores everything; used until P1.7 lands. */
	static HudController noOp() {
		return new HudController() {
			@Override
			public void hideForCapture() {
			}

			@Override
			public void restoreAfterCapture() {
			}

			@Override
			public void showCursor() {
			}

			@Override
			public void showAreaBorder(CaptureArea area) {
			}

			@Override
			public void hideAreaBorder() {
			}

			@Override
			public void setCountdown(Integer remaining) {
			}
		};
	}
}
