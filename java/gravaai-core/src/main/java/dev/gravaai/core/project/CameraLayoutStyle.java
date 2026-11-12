package dev.gravaai.core.project;

import java.util.OptionalDouble;

/**
 * Per-segment overrides of the project webcam look on the Camera layout track. An empty value
 * keeps the project setting. {@code size}, {@code width} and {@code height} are the webcam size
 * percents (width/height double as the split share, like the Webcam settings); {@code roundness}
 * is 0..100, {@code shadow} 0..1 and {@code margin} the inset from the frame edge in pixels.
 * {@code eyeContact} is the eye-contact correction for the segment in degrees: {@code 0} switches
 * it off there, a positive value switches it on at that angle even when the project has it off.
 */
public record CameraLayoutStyle(OptionalDouble size, OptionalDouble width, OptionalDouble height,
		OptionalDouble roundness, OptionalDouble shadow, OptionalDouble margin,
		OptionalDouble eyeContact) {

	public static final CameraLayoutStyle INHERIT = new CameraLayoutStyle(OptionalDouble.empty(),
			OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
			OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());

	public CameraLayoutStyle {
		size = clamp(size, 10, 100);
		width = clamp(width, 10, 100);
		height = clamp(height, 10, 100);
		roundness = clamp(roundness, 0, 100);
		shadow = clamp(shadow, 0, 1);
		margin = clamp(margin, 0, 96);
		eyeContact = clamp(eyeContact, 0, WebcamOverlaySettings.MAX_EYE_CONTACT_ANGLE);
	}

	/** The six look overrides without an eye-contact override. */
	public CameraLayoutStyle(OptionalDouble size, OptionalDouble width, OptionalDouble height,
			OptionalDouble roundness, OptionalDouble shadow, OptionalDouble margin) {
		this(size, width, height, roundness, shadow, margin, OptionalDouble.empty());
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
			case "margin" -> margin;
			case "eyeContact" -> eyeContact;
			default -> throw new IllegalArgumentException("unknown camera style field " + field);
		};
	}

	/** A copy with one field set (or cleared with an empty value). */
	public CameraLayoutStyle with(String field, OptionalDouble value) {
		return switch (field) {
			case "size" -> new CameraLayoutStyle(value, width, height, roundness, shadow, margin,
					eyeContact);
			case "width" -> new CameraLayoutStyle(size, value, height, roundness, shadow, margin,
					eyeContact);
			case "height" -> new CameraLayoutStyle(size, width, value, roundness, shadow, margin,
					eyeContact);
			case "roundness" -> new CameraLayoutStyle(size, width, height, value, shadow, margin,
					eyeContact);
			case "shadow" -> new CameraLayoutStyle(size, width, height, roundness, value, margin,
					eyeContact);
			case "margin" -> new CameraLayoutStyle(size, width, height, roundness, shadow, value,
					eyeContact);
			case "eyeContact" -> new CameraLayoutStyle(size, width, height, roundness, shadow,
					margin, value);
			default -> throw new IllegalArgumentException("unknown camera style field " + field);
		};
	}

	public static final java.util.List<String> FIELDS = java.util.List.of("size", "width",
			"height", "roundness", "shadow", "margin", "eyeContact");
}
