package dev.gravaai.services.sources;

import java.util.List;

/**
 * Window list for the capture picker, from {@code electron/ipc/capturePickerSources.ts}.
 *
 * <p>The Windows (PowerShell/DWM) and macOS (ScreenCaptureKit) implementations are platform code
 * that is not ported (plan §2.3). On Linux the source list is used instead, exactly like the
 * original's fall-through error.
 */
public final class CapturePickerSources {

	private CapturePickerSources() {
	}

	/** Unsupported on Linux: the source list window is the picker there. */
	public static List<CapturePickerWindow> listCapturePickerWindows() {
		throw new UnsupportedOperationException("Use the source list on Linux");
	}
}
