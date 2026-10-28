package dev.gravaai.ui.overlays;

import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.services.sources.CaptureArea;

/**
 * Overlay-side implementation of {@link RecordingService.HudHooks}: the app passes the HUD
 * hide/restore/show-cursor callbacks (owned by the HUD/launch window) and this class handles the
 * area border. This keeps the recording service free of JavaFX.
 *
 * <p>Wiring contract for {@code AppContext}:
 * <pre>{@code
 * RecordingService.HudHooks hooks = new RecordingOverlayHooks(
 *     hud::hideForCapture, hud::restoreAfterCapture, hud::showCursor, areaBorder);
 * }</pre>
 */
public final class RecordingOverlayHooks implements RecordingService.HudHooks {

	private final Runnable hideForCapture;
	private final Runnable restoreAfterCapture;
	private final Runnable showCursor;
	private final AreaRecordingBorder areaBorder;

	public RecordingOverlayHooks(
			Runnable hideForCapture,
			Runnable restoreAfterCapture,
			Runnable showCursor,
			AreaRecordingBorder areaBorder) {
		this.hideForCapture = hideForCapture;
		this.restoreAfterCapture = restoreAfterCapture;
		this.showCursor = showCursor;
		this.areaBorder = areaBorder;
	}

	@Override
	public void hideForCapture() {
		hideForCapture.run();
	}

	@Override
	public void restoreAfterCapture() {
		restoreAfterCapture.run();
	}

	@Override
	public void showAreaRecordingBorder(CaptureArea area) {
		areaBorder.show(area);
	}

	@Override
	public void hideAreaRecordingBorder() {
		areaBorder.hide();
	}

	@Override
	public void showCursor() {
		showCursor.run();
	}
}
