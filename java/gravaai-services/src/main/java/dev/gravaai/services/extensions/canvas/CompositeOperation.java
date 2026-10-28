package dev.gravaai.services.extensions.canvas;

import java.util.Locale;

import io.github.humbleui.skija.BlendMode;

/**
 * Mapping from CSS {@code globalCompositeOperation} names to Skija blend modes. Unknown names
 * return null and the canvas keeps the current mode, like a browser ignoring an invalid value.
 */
public final class CompositeOperation {

	public static final String DEFAULT = "source-over";

	private CompositeOperation() {}

	/** The Skija blend mode for a CSS composite operation, or null when unknown. */
	public static BlendMode toBlendMode(String css) {
		if (css == null) {
			return null;
		}
		return switch (css.trim().toLowerCase(Locale.ROOT)) {
			case "source-over" -> BlendMode.SRC_OVER;
			case "source-in" -> BlendMode.SRC_IN;
			case "source-out" -> BlendMode.SRC_OUT;
			case "source-atop" -> BlendMode.SRC_ATOP;
			case "destination-over" -> BlendMode.DST_OVER;
			case "destination-in" -> BlendMode.DST_IN;
			case "destination-out" -> BlendMode.DST_OUT;
			case "destination-atop" -> BlendMode.DST_ATOP;
			case "xor" -> BlendMode.XOR;
			case "copy" -> BlendMode.SRC;
			case "lighter" -> BlendMode.PLUS;
			case "multiply" -> BlendMode.MULTIPLY;
			case "screen" -> BlendMode.SCREEN;
			case "overlay" -> BlendMode.OVERLAY;
			case "darken" -> BlendMode.DARKEN;
			case "lighten" -> BlendMode.LIGHTEN;
			case "color-dodge" -> BlendMode.COLOR_DODGE;
			case "color-burn" -> BlendMode.COLOR_BURN;
			case "hard-light" -> BlendMode.HARD_LIGHT;
			case "soft-light" -> BlendMode.SOFT_LIGHT;
			case "difference" -> BlendMode.DIFFERENCE;
			case "exclusion" -> BlendMode.EXCLUSION;
			case "hue" -> BlendMode.HUE;
			case "saturation" -> BlendMode.SATURATION;
			case "color" -> BlendMode.COLOR;
			case "luminosity" -> BlendMode.LUMINOSITY;
			default -> null;
		};
	}

	/** The CSS name of a blend mode, for the getter. */
	public static String toCss(BlendMode mode) {
		if (mode == null) {
			return DEFAULT;
		}
		return switch (mode) {
			case SRC_OVER -> "source-over";
			case SRC_IN -> "source-in";
			case SRC_OUT -> "source-out";
			case SRC_ATOP -> "source-atop";
			case DST_OVER -> "destination-over";
			case DST_IN -> "destination-in";
			case DST_OUT -> "destination-out";
			case DST_ATOP -> "destination-atop";
			case XOR -> "xor";
			case SRC -> "copy";
			case PLUS -> "lighter";
			case MULTIPLY -> "multiply";
			case SCREEN -> "screen";
			case OVERLAY -> "overlay";
			case DARKEN -> "darken";
			case LIGHTEN -> "lighten";
			case COLOR_DODGE -> "color-dodge";
			case COLOR_BURN -> "color-burn";
			case HARD_LIGHT -> "hard-light";
			case SOFT_LIGHT -> "soft-light";
			case DIFFERENCE -> "difference";
			case EXCLUSION -> "exclusion";
			case HUE -> "hue";
			case SATURATION -> "saturation";
			case COLOR -> "color";
			case LUMINOSITY -> "luminosity";
			default -> DEFAULT;
		};
	}
}
