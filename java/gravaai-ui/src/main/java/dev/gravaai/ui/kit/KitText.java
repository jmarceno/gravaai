package dev.gravaai.ui.kit;

/**
 * Pure text helpers for the kit (kept out of the JavaFX controls so they can be unit-tested
 * without a toolkit). Source: {@code src/components/ui/content-clamp.tsx}.
 */
public final class KitText {

	/** Default truncate length from content-clamp.tsx. */
	public static final int DEFAULT_TRUNCATE_LENGTH = 50;

	private KitText() {}

	/** Truncates to {@code limit} characters plus the ellipsis; shorter text is unchanged. */
	public static String truncate(String text, int limit) {
		if (text == null) {
			return "";
		}
		if (text.length() <= limit) {
			return text;
		}
		return text.substring(0, limit) + "\u2026";
	}
}
