package dev.gravaai.services.extensions.canvas;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS colour parsing for the extension canvas, covering the forms the documented extension API
 * needs: hex ({@code #rgb}/{@code #rgba}/{@code #rrggbb}/{@code #rrggbbaa}), {@code rgb()}/{@code
 * rgba()} in comma or space syntax, and the common named colours. Values are packed ARGB like
 * Skija's {@code Paint.setColor}.
 *
 * <p>Invalid values return null: the canvas setters ignore them, like a browser does.
 */
public final class CanvasColor {

	private static final Pattern RGB = Pattern.compile(
			"(?i)rgba?\\(\\s*([+-]?[0-9.]+%?)\\s*[, ]\\s*([+-]?[0-9.]+%?)\\s*[, ]\\s*([+-]?[0-9.]+%?)"
					+ "\\s*(?:[,/]\\s*([+-]?[0-9.]+%?)\\s*)?\\)");
	private static final Pattern HEX = Pattern.compile("(?i)^#([0-9a-f]{3,8})$");

	private static final Map<String, Integer> NAMED = namedColors();

	private CanvasColor() {}

	/** Parses a CSS colour string to packed ARGB, or null when the value is not a colour. */
	public static @Nullable Integer parse(String css) {
		if (css == null) {
			return null;
		}
		String value = css.trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty()) {
			return null;
		}
		Integer named = NAMED.get(value);
		if (named != null) {
			return named;
		}
		Matcher hex = HEX.matcher(value);
		if (hex.matches()) {
			return parseHex(hex.group(1));
		}
		Matcher rgb = RGB.matcher(value);
		if (rgb.find() && rgb.start() == 0) {
			return parseRgb(rgb);
		}
		return null;
	}

	/** Replaces the alpha channel: {@code alpha} is clamped to 0..1. */
	public static int withAlpha(int argb, double alpha) {
		int a = (int) Math.round(clamp(alpha, 0.0, 1.0) * 255.0);
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	/** The ARGB alpha channel as 0..1. */
	public static double alpha(int argb) {
		return ((argb >>> 24) & 0xFF) / 255.0;
	}

	/** Renders a packed ARGB colour as {@code rgba(r, g, b, a)}. */
	public static String toCss(int argb) {
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		double a = Math.round(alpha(argb) * 1000.0) / 1000.0;
		return "rgba(" + r + ", " + g + ", " + b + ", " + a + ")";
	}

	private static @Nullable Integer parseHex(String digits) {
		try {
			return switch (digits.length()) {
				case 3 -> {
					int r = Integer.parseInt(digits.substring(0, 1), 16) * 17;
					int g = Integer.parseInt(digits.substring(1, 2), 16) * 17;
					int b = Integer.parseInt(digits.substring(2, 3), 16) * 17;
					yield (0xFF << 24) | (r << 16) | (g << 8) | b;
				}
				case 4 -> {
					int r = Integer.parseInt(digits.substring(0, 1), 16) * 17;
					int g = Integer.parseInt(digits.substring(1, 2), 16) * 17;
					int b = Integer.parseInt(digits.substring(2, 3), 16) * 17;
					int a = Integer.parseInt(digits.substring(3, 4), 16) * 17;
					yield (a << 24) | (r << 16) | (g << 8) | b;
				}
				case 6 -> (0xFF << 24) | Integer.parseInt(digits, 16);
				case 8 -> {
					long parsed = Long.parseLong(digits, 16);
					int r = (int) ((parsed >>> 24) & 0xFF);
					int g = (int) ((parsed >>> 16) & 0xFF);
					int b = (int) ((parsed >>> 8) & 0xFF);
					int a = (int) (parsed & 0xFF);
					yield (a << 24) | (r << 16) | (g << 8) | b;
				}
				default -> null;
			};
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static @Nullable Integer parseRgb(Matcher matcher) {
		try {
			int r = channel(matcher.group(1));
			int g = channel(matcher.group(2));
			int b = channel(matcher.group(3));
			double alpha = matcher.group(4) != null ? alphaValue(matcher.group(4)) : 1.0;
			int a = (int) Math.round(clamp(alpha, 0.0, 1.0) * 255.0);
			return (a << 24) | (r << 16) | (g << 8) | b;
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static int channel(String text) {
		if (text.endsWith("%")) {
			double percent = Double.parseDouble(text.substring(0, text.length() - 1));
			return (int) Math.round(clamp(percent, 0.0, 100.0) * 255.0 / 100.0);
		}
		double value = Double.parseDouble(text);
		return (int) Math.round(clamp(value, 0.0, 255.0));
	}

	private static double alphaValue(String text) {
		if (text.endsWith("%")) {
			return Double.parseDouble(text.substring(0, text.length() - 1)) / 100.0;
		}
		return Double.parseDouble(text);
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static Map<String, Integer> namedColors() {
		Map<String, Integer> colors = new HashMap<>();
		colors.put("transparent", 0x00000000);
		colors.put("black", 0xFF000000);
		colors.put("silver", 0xFFC0C0C0);
		colors.put("gray", 0xFF808080);
		colors.put("grey", 0xFF808080);
		colors.put("white", 0xFFFFFFFF);
		colors.put("maroon", 0xFF800000);
		colors.put("red", 0xFFFF0000);
		colors.put("purple", 0xFF800080);
		colors.put("fuchsia", 0xFFFF00FF);
		colors.put("magenta", 0xFFFF00FF);
		colors.put("green", 0xFF008000);
		colors.put("lime", 0xFF00FF00);
		colors.put("olive", 0xFF808000);
		colors.put("yellow", 0xFFFFFF00);
		colors.put("navy", 0xFF000080);
		colors.put("blue", 0xFF0000FF);
		colors.put("teal", 0xFF008080);
		colors.put("aqua", 0xFF00FFFF);
		colors.put("cyan", 0xFF00FFFF);
		colors.put("orange", 0xFFFFA500);
		colors.put("pink", 0xFFFFC0CB);
		colors.put("brown", 0xFFA52A2A);
		colors.put("gold", 0xFFFFD700);
		colors.put("violet", 0xFFEE82EE);
		colors.put("indigo", 0xFF4B0082);
		colors.put("rebeccapurple", 0xFF663399);
		colors.put("darkgray", 0xFFA9A9A9);
		colors.put("darkgrey", 0xFFA9A9A9);
		colors.put("lightgray", 0xFFD3D3D3);
		colors.put("lightgrey", 0xFFD3D3D3);
		colors.put("darkred", 0xFF8B0000);
		colors.put("darkblue", 0xFF00008B);
		colors.put("darkgreen", 0xFF006400);
		colors.put("lightblue", 0xFFADD8E6);
		colors.put("lightgreen", 0xFF90EE90);
		colors.put("whitesmoke", 0xFFF5F5F5);
		colors.put("currentcolor", 0xFF000000);
		return Map.copyOf(colors);
	}
}
