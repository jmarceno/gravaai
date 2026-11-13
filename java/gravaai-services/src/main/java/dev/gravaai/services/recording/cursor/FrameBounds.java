package dev.gravaai.services.recording.cursor;

import dev.gravaai.services.recording.CursorTelemetry;

/**
 * The recorded frame of a screen or area recording, in global logical pixels: the rectangle the
 * cursor telemetry normalizes against. The live ink overlay normalizes through the same value, so
 * ink and cursor positions line up in the editor.
 *
 * @param x left edge
 * @param y top edge
 * @param width at least 1
 * @param height at least 1
 */
public record FrameBounds(double x, double y, double width, double height) {

	public FrameBounds {
		if (!(width >= 1) || !(height >= 1)) {
			throw new IllegalArgumentException("frame bounds must be at least 1x1: " + width + "x" + height);
		}
	}

	/** A global logical point as 0..1 of this frame, clamped into it. */
	public double[] normalize(double globalX, double globalY) {
		return new double[] {
				CursorTelemetry.clamp((globalX - x) / width, 0, 1),
				CursorTelemetry.clamp((globalY - y) / height, 0, 1)};
	}

	/** A length in logical pixels as a fraction of the frame height. */
	public double normalizeLength(double pixels) {
		return pixels / height;
	}
}
