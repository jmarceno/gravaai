package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** Global auto-caption settings. */
public record AutoCaptionSettings(
		boolean enabled,
		boolean timelineQuickAdd,
		String language,
		String fontFamily,
		@Nullable double fontSize,
		@Nullable double bottomOffset,
		@Nullable double maxWidth,
		@Nullable int maxRows,
		AutoCaptionAnimation animationStyle,
		@Nullable double boxRadius,
		@Nullable String textColor,
		String inactiveTextColor,
		double backgroundOpacity) {

	/** {@code DEFAULT_AUTO_CAPTION_SETTINGS}; {@code getDefaultCaptionFontFamily} in types.ts. */
	public static AutoCaptionSettings defaults() {
		return new AutoCaptionSettings(false, true, "auto", defaultCaptionFontFamily(), 30, 3, 62, 1,
				AutoCaptionAnimation.FADE, 17.5, "#FFFFFF", "#A3A3A3", 0.9);
	}

	/** {@code getDefaultCaptionFontFamily}. */
	public static String defaultCaptionFontFamily() {
		return "\"SF Pro Text\", \"SF Pro Display\", \"Helvetica Neue\", sans-serif";
	}
}
