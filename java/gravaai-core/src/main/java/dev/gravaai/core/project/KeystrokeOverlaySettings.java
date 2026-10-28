package dev.gravaai.core.project;

/** Key-press overlay settings (port of the settings part of {@code keystrokeOverlay.ts}). */
public record KeystrokeOverlaySettings(
		boolean enabled,
		KeystrokeOverlayPosition position,
		double fontSize,
		long lingerMs,
		long fadeMs,
		long groupingMs,
		double bottomOffset) {

	public static final KeystrokeOverlaySettings DEFAULT = new KeystrokeOverlaySettings(
			true, KeystrokeOverlayPosition.BOTTOM_CENTER, 34, 900, 150, 600, 12);
}
