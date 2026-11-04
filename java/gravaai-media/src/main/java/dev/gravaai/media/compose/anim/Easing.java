package dev.gravaai.media.compose.anim;

import java.util.Locale;
import java.util.Optional;

import dev.gravaai.media.compose.MathUtils;

/**
 * An easing curve: maps normalized progress {@code t} in [0, 1] to eased progress. The family and
 * the {@code in}/{@code out}/{@code inOut} combinators follow the documented semantics of the
 * usual motion libraries (written from the behaviour, not ported): the base curves are "in"
 * curves, {@link #out} mirrors one, {@link #inOut} joins both halves.
 */
@FunctionalInterface
public interface Easing {

	double apply(double t);

	Easing LINEAR = t -> t;
	Easing QUAD = t -> t * t;
	Easing CUBIC = t -> t * t * t;
	Easing SIN = t -> 1 - Math.cos(t * Math.PI / 2);
	Easing CIRCLE = t -> 1 - Math.sqrt(Math.max(0, 1 - t * t));
	Easing EXP = t -> t <= 0 ? 0 : Math.pow(2, 10 * (t - 1));
	Easing BOUNCE = Easing::bounceOut;
	/** CSS {@code ease}. */
	Easing EASE = bezier(0.25, 0.1, 0.25, 1);

	static Easing poly(double n) {
		return t -> Math.pow(t, n);
	}

	/** Overshoots backwards before moving forward; {@code s = 1.70158} is the classic amount. */
	static Easing back(double s) {
		return t -> t * t * ((s + 1) * t - s);
	}

	/** A spring-like wobble; {@code bounciness} 1 is the classic amount. */
	static Easing elastic(double bounciness) {
		double p = bounciness * Math.PI;
		return t -> 1 - Math.pow(Math.cos(t * Math.PI / 2), 3) * Math.cos(t * p);
	}

	/** Exactly CSS {@code cubic-bezier(x1, y1, x2, y2)}. */
	static Easing bezier(double x1, double y1, double x2, double y2) {
		return t -> MathUtils.cubicBezier(x1, y1, x2, y2, t);
	}

	/** {@code steps(n)}: jumps at the end of each of {@code n} equal intervals. */
	static Easing steps(int n) {
		int count = Math.max(1, n);
		return t -> t >= 1 ? 1 : Math.floor(t * count) / count;
	}

	static Easing in(Easing easing) {
		return easing;
	}

	static Easing out(Easing easing) {
		return t -> 1 - easing.apply(1 - t);
	}

	static Easing inOut(Easing easing) {
		return t -> t < 0.5 ? easing.apply(t * 2) / 2 : 1 - easing.apply((1 - t) * 2) / 2;
	}

	/**
	 * Parses a name such as {@code linear}, {@code ease}, {@code ease-out-cubic},
	 * {@code ease-in-out-back}, {@code bounce} or {@code cubic-bezier(0.2,0,0,1)}. Unknown names
	 * are empty so callers can report them.
	 */
	static Optional<Easing> parse(String name) {
		String value = name.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("cubic-bezier(") && value.endsWith(")")) {
			String[] parts = value.substring(13, value.length() - 1).split(",");
			if (parts.length != 4) {
				return Optional.empty();
			}
			try {
				return Optional.of(bezier(Double.parseDouble(parts[0].trim()),
						Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()),
						Double.parseDouble(parts[3].trim())));
			} catch (NumberFormatException error) {
				return Optional.empty();
			}
		}
		switch (value) {
			case "linear" -> {
				return Optional.of(LINEAR);
			}
			case "ease" -> {
				return Optional.of(EASE);
			}
			case "bounce" -> {
				return Optional.of(BOUNCE);
			}
			default -> {
			}
		}
		String mode = "in";
		String base = value;
		if (value.equals("ease-in") || value.equals("ease-out") || value.equals("ease-in-out")) {
			mode = value.equals("ease-in") ? "in" : value.equals("ease-out") ? "out" : "inOut";
			base = "cubic";
		} else if (value.startsWith("ease-in-out-")) {
			mode = "inOut";
			base = value.substring(12);
		} else if (value.startsWith("ease-out-")) {
			mode = "out";
			base = value.substring(9);
		} else if (value.startsWith("ease-in-")) {
			base = value.substring(8);
		}
		Optional<Easing> curve = baseCurve(base);
		String chosen = mode;
		return curve.map(easing -> switch (chosen) {
			case "out" -> out(easing);
			case "inOut" -> inOut(easing);
			default -> in(easing);
		});
	}

	private static Optional<Easing> baseCurve(String base) {
		return switch (base) {
			case "quad" -> Optional.of(QUAD);
			case "cubic" -> Optional.of(CUBIC);
			case "quart" -> Optional.of(poly(4));
			case "quint" -> Optional.of(poly(5));
			case "sin", "sine" -> Optional.of(SIN);
			case "circle", "circ" -> Optional.of(CIRCLE);
			case "exp", "expo" -> Optional.of(EXP);
			case "back" -> Optional.of(back(1.70158));
			case "elastic" -> Optional.of(elastic(1));
			case "bounce" -> Optional.of(BOUNCE);
			default -> Optional.empty();
		};
	}

	/** Names {@link #parse} accepts, for help text and errors. */
	static String names() {
		return "linear, ease, ease-in, ease-out, ease-in-out, ease-{in|out|in-out}-{quad|cubic|quart|quint|sine|circ|expo|back|elastic|bounce}, bounce, cubic-bezier(x1,y1,x2,y2)";
	}

	private static double bounceOut(double t) {
		if (t < 1 / 2.75) {
			return 7.5625 * t * t;
		}
		if (t < 2 / 2.75) {
			double u = t - 1.5 / 2.75;
			return 7.5625 * u * u + 0.75;
		}
		if (t < 2.5 / 2.75) {
			double u = t - 2.25 / 2.75;
			return 7.5625 * u * u + 0.9375;
		}
		double u = t - 2.625 / 2.75;
		return 7.5625 * u * u + 0.984375;
	}
}
