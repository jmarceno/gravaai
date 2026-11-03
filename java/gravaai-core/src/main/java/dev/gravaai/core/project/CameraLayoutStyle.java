package dev.gravaai.core.project;

import java.util.OptionalDouble;

/**
 * Per-segment overrides of the project webcam look on the Camera layout track. An empty value
 * keeps the project setting. {@code size}, {@code width} and {@code height} are the webcam size
 * percents (width/height double as the split share, like the Webcam settings); {@code roundness}
 * is 0..100 and {@code shadow} 0..1.
 */
public record CameraLayoutStyle(OptionalDouble size, OptionalDouble width, OptionalDouble height,
		OptionalDouble roundness, OptionalDouble shadow) {

	public static final CameraLayoutStyle INHERIT = new CameraLayoutStyle(OptionalDouble.empty(),
			OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
			OptionalDouble.empty());

	public CameraLayoutStyle {
		size = clamp(size, 10, 100);
		width = clamp(width, 10, 100);
		height = clamp(height, 10, 100);
		roundness = clamp(roundness, 0, 100);
		shadow = clamp(shadow, 0, 1);
	}

	private static OptionalDouble clamp(OptionalDouble value, double min, double max) {
		if (value.isEmpty() || !Double.isFinite(value.getAsDouble())) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(Math.max(min, Math.min(max, value.getAsDouble())));
	}

	public boolean isInherit() {
		return equals(INHERIT);
	}

	/** One field by its project-JSON name ({@code size}, {@code width}, ...). */
	public OptionalDouble get(String field) {
		return switch (field) {
			case "size" -> size;
			case "width" -> width;
			case "height" -> height;
			case "roundness" -> roundness;
			case "shadow" -> shadow;
			default -> throw new IllegalArgumentException("unknown camera style field " + field);
		};
	}

	/** A copy with one field set (or cleared with an empty value). */
	public CameraLayoutStyle with(String field, OptionalDouble value) {
		return switch (field) {
			case "size" -> new CameraLayoutStyle(value, width, height, roundness, shadow);
			case "width" -> new CameraLayoutStyle(size, value, height, roundness, shadow);
			case "height" -> new CameraLayoutStyle(size, width, value, roundness, shadow);
			case "roundness" -> new CameraLayoutStyle(size, width, height, value, shadow);
			case "shadow" -> new CameraLayoutStyle(size, width, height, roundness, value);
			default -> throw new IllegalArgumentException("unknown camera style field " + field);
		};
	}

	public static final java.util.List<String> FIELDS =
			java.util.List.of("size", "width", "height", "roundness", "shadow");
}
