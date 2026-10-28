package dev.gravaai.services.sources;

/** A rectangle in global display points (top-left origin), from {@code electron/ipc/types.ts}. */
public record CaptureArea(double x, double y, double width, double height) {

	public boolean isFinite() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height);
	}

	public boolean isUsable() {
		return isFinite() && width > 0 && height > 0;
	}

	/** Formats a point value the way JavaScript's {@code Number.toString()} prints it. */
	public static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}
}
