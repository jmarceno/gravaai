package dev.gravaai.media.compose;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the CSS colours the editor stores for backgrounds (hex, {@code rgb()/rgba()}, and the
 * small named set the presets use). The canvas renderer delegates colour parsing to the
 * browser, so this is our exact-side replacement; invalid values return {@code -1} and callers
 * fall back to black, which is what an untouched 2D canvas fill style renders.
 */
final class CssColour {

	private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]+)$");
	private static final Pattern FUNCTION = Pattern.compile("^(rgba?)\\(([^)]*)\\)$");
	private static final Map<String, Integer> NAMED = Map.ofEntries(
			Map.entry("transparent", 0x00000000),
			Map.entry("black", 0xFF000000),
			Map.entry("white", 0xFFFFFFFF),
			Map.entry("red", 0xFFFF0000),
			Map.entry("green", 0xFF008000),
			Map.entry("blue", 0xFF0000FF),
			Map.entry("yellow", 0xFFFFFF00),
			Map.entry("orange", 0xFFFFA500),
			Map.entry("purple", 0xFF800080),
			Map.entry("pink", 0xFFFFC0CB),
			Map.entry("gray", 0xFF808080),
			Map.entry("grey", 0xFF808080));

	private CssColour() {}

	/** Returns ARGB, or {@code -1} when the value is not a supported CSS colour. */
	static int parse(String value) {
		if (value == null) {
			return -1;
		}
		String text = value.strip();
		if (text.isEmpty()) {
			return -1;
		}
		Matcher named = null;
		Integer known = NAMED.get(text.toLowerCase(Locale.ROOT));
		if (known != null) {
			return known;
		}
		Matcher hex = HEX.matcher(text);
		if (hex.matches()) {
			return parseHex(hex.group(1));
		}
		Matcher function = FUNCTION.matcher(text);
		if (function.matches()) {
			return parseFunction(function.group(1), function.group(2));
		}
		return -1;
	}

	private static int parseHex(String digits) {
		return switch (digits.length()) {
			case 3 -> 0xFF000000
					| Integer.parseInt("" + digits.charAt(0) + digits.charAt(0), 16) << 16
					| Integer.parseInt("" + digits.charAt(1) + digits.charAt(1), 16) << 8
					| Integer.parseInt("" + digits.charAt(2) + digits.charAt(2), 16);
			case 4 -> Integer.parseInt("" + digits.charAt(3) + digits.charAt(3), 16) << 24
					| Integer.parseInt("" + digits.charAt(0) + digits.charAt(0), 16) << 16
					| Integer.parseInt("" + digits.charAt(1) + digits.charAt(1), 16) << 8
					| Integer.parseInt("" + digits.charAt(2) + digits.charAt(2), 16);
			case 6 -> 0xFF000000 | Integer.parseInt(digits, 16);
			case 8 -> (int) Long.parseLong(digits, 16);
			default -> -1;
		};
	}

	private static int parseFunction(String function, String arguments) {
		String[] parts = arguments.split(",", -1);
		boolean rgba = function.equalsIgnoreCase("rgba");
		if (parts.length != (rgba ? 4 : 3)) {
			return -1;
		}
		int red = channel(parts[0]);
		int green = channel(parts[1]);
		int blue = channel(parts[2]);
		if (red < 0 || green < 0 || blue < 0) {
			return -1;
		}
		int alpha = 255;
		if (rgba) {
			double value = alpha(parts[3]);
			if (value < 0) {
				return -1;
			}
			alpha = (int) Math.round(value * 255);
		}
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private static int channel(String value) {
		String text = value.strip();
		if (text.isEmpty()) {
			return -1;
		}
		try {
			double number;
			if (text.endsWith("%")) {
				number = Double.parseDouble(text.substring(0, text.length() - 1)) * 2.55;
			} else {
				number = Double.parseDouble(text);
			}
			if (!Double.isFinite(number)) {
				return -1;
			}
			return Math.max(0, Math.min(255, (int) Math.round(number)));
		} catch (NumberFormatException malformed) {
			return -1;
		}
	}

	private static double alpha(String value) {
		String text = value.strip();
		if (text.isEmpty()) {
			return -1;
		}
		try {
			double number;
			if (text.endsWith("%")) {
				number = Double.parseDouble(text.substring(0, text.length() - 1)) / 100.0;
			} else {
				number = Double.parseDouble(text);
			}
			if (!Double.isFinite(number)) {
				return -1;
			}
			return Math.max(0, Math.min(1, number));
		} catch (NumberFormatException malformed) {
			return -1;
		}
	}
}
