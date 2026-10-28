package dev.gravaai.app.recording;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javafx.application.Platform;

import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.ui.launch.HudOverlayWindow;

/**
 * {@link HudController} over the real {@link HudOverlayWindow} (plan §7.1 P1.7).
 *
 * <p>The wiring is created before the HUD (the HUD needs the recording port, which needs the
 * wiring), so the window is attached afterwards with {@link #attach}. Until then every call is a
 * no-op, which is also what happens when the HUD cannot be built.
 *
 * <p>Threading: {@code RecordingService.startNativeScreenRecording} runs on the HUD start worker
 * (a virtual thread), so {@link #hideForCapture()} and {@link #restoreAfterCapture()} marshal to
 * the JavaFX thread and wait. Calling {@code Stage.hide()} from the worker threw
 * {@code "Stage.hide() must be called on the FX application thread"} and the recording never
 * started.
 *
 * <p>Notes from the HUD integration:
 * <ul>
 *   <li>{@code showCursor} is a no-op: the HUD/launch windows own the cursor state.</li>
 *   <li>{@code showAreaBorder}/{@code hideAreaBorder} are no-ops here: the click-through border is
 *       owned by {@code RecordingWiring}'s {@code AreaRecordingBorder} through
 *       {@code RecordingOverlayHooks}, so the HUD does not duplicate it.</li>
 *   <li>{@code setCountdown} is a no-op: {@link HudOverlayWindow} has no countdown handle; the
 *       countdown is rendered by {@code CountdownOverlay}. The HUD only sees
 *       {@code HudRecordingPort.State.countdownActive}.</li>
 * </ul>
 */
public final class HudWindowController implements HudController {

	/** Max wait for the FX thread; a stuck FX thread must not hang the recording start. */
	private static final long FX_TIMEOUT_MS = 3_000;

	/**
	 * How long to let the compositor unmap the HUD before the capture helper grabs the screen
	 * ({@code hideHudForCapture} in {@code electron/windows.ts} waits 250 ms for the same reason).
	 */
	static final long CAPTURE_SETTLE_MS = 250;

	private static final System.Logger LOG = System.getLogger("gravaai.hud.window");

	private volatile HudOverlayWindow hud;

	/** Attaches the real HUD window; call on the JavaFX thread after it is built. */
	public void attach(HudOverlayWindow hud) {
		this.hud = hud;
	}

	@Override
	public void hideForCapture() {
		HudOverlayWindow window = hud;
		if (window == null) {
			return;
		}
		if (!runOnFxThreadAndWait(window::hideForCapture, "hide the HUD for capture")) {
			return;
		}
		// Give the compositor a few frames to unmap the HUD before the capture starts.
		try {
			Thread.sleep(CAPTURE_SETTLE_MS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void restoreAfterCapture() {
		HudOverlayWindow window = hud;
		if (window == null) {
			return;
		}
		runOnFxThreadAndWait(window::restoreAfterCapture, "restore the HUD after capture");
	}

	/** Runs one HUD action on the FX thread (inline when already there); false on timeout. */
	private static boolean runOnFxThreadAndWait(Runnable action, String what) {
		if (Platform.isFxApplicationThread()) {
			action.run();
			return true;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		try {
			Platform.runLater(() -> {
				try {
					action.run();
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "could not " + what + ": " + error);
				} finally {
					done.complete(null);
				}
			});
		} catch (IllegalStateException noToolkit) {
			LOG.log(System.Logger.Level.WARNING, "could not " + what + ": " + noToolkit);
			return false;
		}
		try {
			done.get(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			return true;
		} catch (TimeoutException error) {
			LOG.log(System.Logger.Level.WARNING, "timed out waiting to " + what);
			return false;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return false;
		} catch (CompletionException error) {
			return true;
		} catch (java.util.concurrent.ExecutionException error) {
			return true;
		}
	}

	@Override
	public void showCursor() {
		// The HUD owns the cursor; nothing to restore here.
	}

	@Override
	public void showAreaBorder(CaptureArea area) {
		// RecordingWiring owns the real click-through border.
	}

	@Override
	public void hideAreaBorder() {
		// RecordingWiring owns the real click-through border.
	}

	@Override
	public void setCountdown(Integer remaining) {
		// HudOverlayWindow has no countdown handle; CountdownOverlay renders it.
	}
}
