package dev.gravaai.core.project;

import java.util.Locale;
import java.util.regex.Pattern;

/** Defaults and normalisers that types.ts keeps next to its types (plan §5.11). */
public final class EditorDefaults {

	public static final String DEFAULT_WALLPAPER_PATH = "/wallpapers/tahoe-light.jpg";

	public static final String DEFAULT_CURSOR_STYLE = "tahoe";
	public static final CursorClickEffectStyle DEFAULT_CURSOR_CLICK_EFFECT = CursorClickEffectStyle.NONE;
	public static final String DEFAULT_CURSOR_CLICK_EFFECT_COLOR = "#2563EB";
	public static final double DEFAULT_CURSOR_CLICK_EFFECT_SCALE = 1;
	public static final double DEFAULT_CURSOR_CLICK_EFFECT_OPACITY = 1;
	public static final double DEFAULT_CURSOR_CLICK_EFFECT_DURATION_MS = 600;
	public static final double DEFAULT_CURSOR_SIZE = 3.0;
	public static final double DEFAULT_CURSOR_SMOOTHING = 0.67;
	public static final double DEFAULT_CURSOR_MOTION_BLUR = 0.6;
	public static final double DEFAULT_CURSOR_CLICK_BOUNCE = 2;
	public static final double DEFAULT_CURSOR_CLICK_BOUNCE_DURATION = 350;
	public static final double DEFAULT_CURSOR_SWAY = 0.4;
	public static final double DEFAULT_ZOOM_SMOOTHNESS = 0.5;
	public static final double DEFAULT_ZOOM_MOTION_BLUR = 0.35;
	public static final double DEFAULT_ZOOM_IN_DURATION_MS = 1522.575;
	public static final double DEFAULT_ZOOM_IN_OVERLAP_MS = 500;
	public static final double DEFAULT_ZOOM_OUT_DURATION_MS = 1015.05;
	public static final double DEFAULT_CONNECTED_ZOOM_GAP_MS = 1500;
	public static final double DEFAULT_CONNECTED_ZOOM_DURATION_MS = 1000;
	public static final ZoomTransitionEasing DEFAULT_ZOOM_IN_EASING = ZoomTransitionEasing.GRAAVAI;
	public static final ZoomTransitionEasing DEFAULT_ZOOM_OUT_EASING = ZoomTransitionEasing.GRAAVAI;
	public static final ZoomTransitionEasing DEFAULT_CONNECTED_ZOOM_EASING = ZoomTransitionEasing.GLIDE;

	private static final Pattern COLOR =
			Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

	private EditorDefaults() {}

	/** {@code normalizeCursorClickEffectColor}: only #rgb/#rrggbb, expanded and uppercased. */
	public static String normalizeCursorClickEffectColor(String value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String trimmed = value.trim();
		if (!COLOR.matcher(trimmed).matches()) {
			return fallback;
		}
		if (trimmed.length() == 4) {
			char red = trimmed.charAt(1);
			char green = trimmed.charAt(2);
			char blue = trimmed.charAt(3);
			return ("#" + red + red + green + green + blue + blue).toUpperCase(Locale.ROOT);
		}
		return trimmed.toUpperCase(Locale.ROOT);
	}
}
