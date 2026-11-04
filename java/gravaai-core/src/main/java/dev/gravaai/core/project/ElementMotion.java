package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Host-applied animation of a whole element (plan §5.3 "motion"): keyframed opacity, offset,
 * scale and rotation around the element box, evaluated by the animation kernel. Presets expand
 * into explicit keyframes when they are added, so the project file only stores curves that the
 * agent (and the inspector) can read and tweak.
 */
public final class ElementMotion {

	private ElementMotion() {}

	/** What a track animates. Offsets are in percent of the output frame; rotation in degrees. */
	public enum Property {
		OPACITY("opacity", 1),
		TRANSLATE_X("translateX", 0),
		TRANSLATE_Y("translateY", 0),
		SCALE("scale", 1),
		ROTATE("rotate", 0);

		private final String wire;
		private final double rest;

		Property(String wire, double rest) {
			this.wire = wire;
			this.rest = rest;
		}

		public String wire() {
			return wire;
		}

		/** The value when no track animates this property. */
		public double rest() {
			return rest;
		}

		public static java.util.Optional<Property> fromWire(String value) {
			for (Property property : values()) {
				if (property.wire.equalsIgnoreCase(value) || property.name().equalsIgnoreCase(value)) {
					return java.util.Optional.of(property);
				}
			}
			return java.util.Optional.empty();
		}
	}

	/**
	 * One key: {@code value} at {@code atMs} from the element start, or from its end when
	 * {@code fromEnd} (so exits follow the element when it is resized). {@code easing} shapes the
	 * segment that ends at this key (an {@code Easing.parse} name; {@code "linear"} default).
	 */
	public record Keyframe(long atMs, boolean fromEnd, double value, String easing) {
		public Keyframe {
			atMs = Math.max(0, atMs);
			value = Double.isFinite(value) ? value : 0;
			easing = easing.isBlank() ? "linear" : easing.trim().toLowerCase(Locale.ROOT);
		}

		/** Absolute local time of this key for an element lasting {@code durationMs}. */
		public long resolve(long durationMs) {
			return Math.max(0, Math.min(durationMs, fromEnd ? durationMs - atMs : atMs));
		}
	}

	/** Keyframes for one property, kept in authoring order (resolved order at evaluation). */
	public record Track(Property property, List<Keyframe> keyframes) {
		public Track {
			keyframes = List.copyOf(keyframes);
			if (keyframes.isEmpty()) {
				throw new IllegalArgumentException("a motion track needs at least one keyframe");
			}
		}

		/** Keys sorted by resolved time for an element of {@code durationMs}. */
		public List<Keyframe> resolved(long durationMs) {
			List<Keyframe> sorted = new ArrayList<>(keyframes);
			sorted.sort(Comparator.comparingLong(key -> key.resolve(durationMs)));
			return sorted;
		}
	}

	/**
	 * Trails: the element is drawn {@code samples} times across {@code shutter} of a frame
	 * interval (at the component's nominal fps), oldest faintest. {@link #NONE} draws once.
	 */
	public record Blur(int samples, double shutter) {
		public static final Blur NONE = new Blur(1, 0);

		public Blur {
			samples = Math.max(1, Math.min(16, samples));
			shutter = Double.isFinite(shutter) ? Math.max(0, Math.min(4, shutter)) : 0;
		}

		public boolean enabled() {
			return samples > 1 && shutter > 0;
		}
	}

	/** Names accepted by {@link #preset}. */
	public static final List<String> PRESETS = List.of("fade", "rise", "slide-left", "slide-right",
			"slide-up", "slide-down", "spring-pop", "zoom", "spin");

	/**
	 * Expands a preset into tracks: an entrance over {@code durationMs} from the start when
	 * {@code in}, and the mirrored exit before the end when {@code out}.
	 */
	public static List<Track> preset(String name, boolean in, boolean out, long durationMs) {
		long d = Math.max(50, durationMs);
		String key = name.trim().toLowerCase(Locale.ROOT);
		List<Track> tracks = new ArrayList<>();
		switch (key) {
			case "fade" -> tracks.add(pair(Property.OPACITY, 0, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
			case "rise" -> {
				tracks.add(pair(Property.OPACITY, 0, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
				tracks.add(pair(Property.TRANSLATE_Y, 4, 0, in, out, d, "ease-out-cubic", "ease-in-cubic", -4));
			}
			case "slide-left", "slide-right", "slide-up", "slide-down" -> {
				Property axis = key.endsWith("left") || key.endsWith("right") ? Property.TRANSLATE_X
						: Property.TRANSLATE_Y;
				double from = switch (key) {
					case "slide-left" -> -12;
					case "slide-right" -> 12;
					case "slide-up" -> 12;
					default -> -12;
				};
				tracks.add(pair(Property.OPACITY, 0, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
				tracks.add(pair(axis, from, 0, in, out, d, "ease-out-expo", "ease-in-expo", from));
			}
			case "spring-pop" -> {
				tracks.add(pair(Property.OPACITY, 0, 1, in, out, Math.max(50, d / 2), "linear", "linear"));
				tracks.add(pair(Property.SCALE, 0.5, 1, in, out, d, "spring", "ease-in-back"));
			}
			case "zoom" -> {
				tracks.add(pair(Property.OPACITY, 0, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
				tracks.add(pair(Property.SCALE, 1.2, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
			}
			case "spin" -> {
				tracks.add(pair(Property.OPACITY, 0, 1, in, out, d, "ease-out-cubic", "ease-in-cubic"));
				tracks.add(pair(Property.ROTATE, -90, 0, in, out, d, "ease-out-back", "ease-in-back", 90));
				tracks.add(pair(Property.SCALE, 0.6, 1, in, out, d, "ease-out-back", "ease-in-back"));
			}
			default -> throw new IllegalArgumentException("unknown motion preset \"" + name
					+ "\" (known: " + String.join(", ", PRESETS) + ")");
		}
		return tracks;
	}

	private static Track pair(Property property, double hidden, double shown, boolean in,
			boolean out, long d, String inEasing, String outEasing) {
		return pair(property, hidden, shown, in, out, d, inEasing, outEasing, hidden);
	}

	private static Track pair(Property property, double hidden, double shown, boolean in,
			boolean out, long d, String inEasing, String outEasing, double exitHidden) {
		List<Keyframe> keys = new ArrayList<>();
		if (in) {
			keys.add(new Keyframe(0, false, hidden, "linear"));
			keys.add(new Keyframe(d, false, shown, inEasing));
		}
		if (out) {
			keys.add(new Keyframe(d, true, shown, in ? "linear" : "linear"));
			keys.add(new Keyframe(0, true, exitHidden, outEasing));
		}
		if (keys.isEmpty()) {
			keys.add(new Keyframe(0, false, shown, "linear"));
		}
		return new Track(property, keys);
	}

	/**
	 * Combines tracks: keyframes of the same property are concatenated (so {@code "fade"} in
	 * plus {@code "slide-down"} out on opacity yields one curve), one track per property.
	 */
	public static List<Track> combine(List<Track> tracks) {
		java.util.EnumMap<Property, List<Keyframe>> byProperty = new java.util.EnumMap<>(Property.class);
		for (Track track : tracks) {
			byProperty.computeIfAbsent(track.property(), ignored -> new ArrayList<>())
					.addAll(track.keyframes());
		}
		List<Track> result = new ArrayList<>();
		byProperty.forEach((property, keys) -> result.add(new Track(property, keys)));
		return List.copyOf(result);
	}
}
