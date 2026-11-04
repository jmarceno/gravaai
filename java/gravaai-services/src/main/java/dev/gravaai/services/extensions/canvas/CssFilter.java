package dev.gravaai.services.extensions.canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;

/** The CSS filter functions {@code ctx.filter} supports: {@code blur} and {@code drop-shadow}. */
sealed interface CssFilter {

	record Blur(double radius) implements CssFilter {}

	record DropShadow(double dx, double dy, double blur, int color) implements CssFilter {}

	Pattern FUNCTION = Pattern.compile("(blur|drop-shadow)\\(((?:[^()]|\\([^()]*\\))*)\\)");
	Pattern LENGTH = Pattern.compile("^-?[0-9]*\\.?[0-9]+(?:px)?$");

	/** Parses a filter list; empty when any part is not understood. */
	static Optional<List<CssFilter>> parse(String css) {
		List<CssFilter> filters = new ArrayList<>();
		Matcher matcher = FUNCTION.matcher(css);
		int at = 0;
		while (matcher.find()) {
			if (!css.substring(at, matcher.start()).isBlank()) {
				return Optional.empty();
			}
			at = matcher.end();
			String args = matcher.group(2).trim();
			if (matcher.group(1).equals("blur")) {
				if (!LENGTH.matcher(args).matches()) {
					return Optional.empty();
				}
				filters.add(new Blur(Math.max(0, length(args))));
			} else {
				Optional<DropShadow> shadow = dropShadow(args);
				if (shadow.isEmpty()) {
					return Optional.empty();
				}
				filters.add(shadow.get());
			}
		}
		if (filters.isEmpty() || !css.substring(at).isBlank()) {
			return Optional.empty();
		}
		return Optional.of(List.copyOf(filters));
	}

	private static Optional<DropShadow> dropShadow(String args) {
		// Lengths come first; the colour (which may contain spaces inside rgba()) is the rest.
		List<Double> lengths = new ArrayList<>();
		String rest = args;
		while (lengths.size() < 3) {
			String trimmed = rest.stripLeading();
			int space = trimmed.indexOf(' ');
			String token = space < 0 ? trimmed : trimmed.substring(0, space);
			if (token.isEmpty() || !LENGTH.matcher(token).matches()) {
				break;
			}
			lengths.add(length(token));
			rest = space < 0 ? "" : trimmed.substring(space);
		}
		if (lengths.size() < 2) {
			return Optional.empty();
		}
		String colorText = rest.trim();
		Integer color = colorText.isEmpty() ? Integer.valueOf(0x80000000) : CanvasColor.parse(colorText);
		if (color == null) {
			return Optional.empty();
		}
		return Optional.of(new DropShadow(lengths.get(0), lengths.get(1),
				lengths.size() > 2 ? Math.max(0, lengths.get(2)) : 0, color));
	}

	private static double length(String token) {
		return Double.parseDouble(token.endsWith("px") ? token.substring(0, token.length() - 2) : token);
	}

	/** Chains the filters (first applied first); null when there is nothing to do. */
	static @Nullable ImageFilter build(List<CssFilter> filters) {
		ImageFilter result = null;
		for (CssFilter filter : filters) {
			ImageFilter next = switch (filter) {
				case Blur blur -> blur.radius() <= 0 ? null
						: ImageFilter.makeBlur((float) blur.radius(), (float) blur.radius(),
								FilterTileMode.DECAL, result, (io.github.humbleui.types.Rect) null);
				case DropShadow shadow -> ImageFilter.makeDropShadow((float) shadow.dx(),
						(float) shadow.dy(), (float) (shadow.blur() / 2), (float) (shadow.blur() / 2),
						shadow.color(), result);
			};
			if (next != null) {
				if (result != null) {
					result.close();
				}
				result = next;
			}
		}
		return result;
	}
}
