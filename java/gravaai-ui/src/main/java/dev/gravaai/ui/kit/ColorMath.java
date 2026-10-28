package dev.gravaai.ui.kit;

import java.util.Locale;
import javafx.scene.paint.Color;

/**
 * Colour conversions for the kit (plan §5.12 ColorPicker), pure logic so it is unit-tested without
 * a JavaFX toolkit. HeroUI's color picker works in HSB (ColorArea {@code colorSpace="hsb"},
 * ColorSlider {@code channel="hue"}); the app stores CSS colours as hex strings.
 */
public final class ColorMath {

	/** HSB colour with alpha, the space the HeroUI color controls use. */
	public record Hsba(double hue, double saturation, double brightness, double alpha) {}

	private ColorMath() {}

	/** Splits a colour into the HSB components React Aria's color picker exposes. */
	public static Hsba toHsb(Color color) {
		return new Hsba(color.getHue(), color.getSaturation(), color.getBrightness(), color.getOpacity());
	}

	/** Builds a colour from HSB; hue wraps at 360, the other channels clamp to 0..1. */
	public static Color fromHsb(Hsba hsba) {
		double hue = ((hsba.hue() % 360) + 360) % 360;
		return Color.hsb(hue, clamp01(hsba.saturation()), clamp01(hsba.brightness()),
				clamp01(hsba.alpha()));
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	/** {@code #rrggbb}, or {@code #rrggbbaa} when the colour is translucent (Color.toString("hex")). */
	public static String toHex(Color color) {
		int red = channel(color.getRed());
		int green = channel(color.getGreen());
		int blue = channel(color.getBlue());
		int alpha = channel(color.getOpacity());
		if (alpha == 255) {
			return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
		}
		return String.format(Locale.ROOT, "#%02x%02x%02x%02x", red, green, blue, alpha);
	}

	private static int channel(double value) {
		return (int) Math.round(clamp01(value) * 255);
	}

	/** Parses {@code #rgb}, {@code #rrggbb} and {@code #rrggbbaa}, or a CSS rgb()/rgba() text. */
	public static Color fromHex(String text) {
		if (text == null) {
			throw new IllegalArgumentException("Colour text must not be null");
		}
		String value = text.trim().toLowerCase(Locale.ROOT);
		if (value.equals("transparent")) {
			return Color.web("#00000000");
		}
		if (value.startsWith("rgb")) {
			return Color.web(value);
		}
		if (!value.startsWith("#")) {
			value = "#" + value;
		}
		String digits = value.substring(1);
		if (digits.length() == 3) {
			digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1)
					+ digits.charAt(2) + digits.charAt(2);
		}
		return Color.web("#" + digits);
	}

	/** A CSS colour string for {@code setStyle} backgrounds, preserving alpha (no looked-up tokens). */
	public static String toCss(Color color) {
		int red = channel(color.getRed());
		int green = channel(color.getGreen());
		int blue = channel(color.getBlue());
		if (color.getOpacity() >= 1) {
			return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
		}
		return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)", red, green, blue, color.getOpacity());
	}
}
