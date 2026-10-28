package dev.gravaai.services.sources;

import java.util.List;

/**
 * Capture-picker drag/move/resize geometry, ported from
 * {@code src/components/capture-picker/areaGeometry.ts}. Pure math so the handler code and the
 * JavaFX overlay share one implementation.
 */
public final class CapturePickerGeometry {

	/** A rectangle in overlay-local coordinates. */
	public record AreaRect(double x, double y, double width, double height) {
	}

	/** The overlay size the rectangle is clamped into. */
	public record Bounds(double width, double height) {
	}

	/** One resize handle. */
	public enum AreaHandle {
		NW("nw"),
		N("n"),
		NE("ne"),
		E("e"),
		SE("se"),
		S("s"),
		SW("sw"),
		W("w");

		private final String wire;

		AreaHandle(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		boolean includes(String part) {
			return wire.contains(part);
		}
	}

	public static final List<AreaHandle> AREA_HANDLES = List.of(
			AreaHandle.NW, AreaHandle.N, AreaHandle.NE, AreaHandle.E,
			AreaHandle.SE, AreaHandle.S, AreaHandle.SW, AreaHandle.W);

	/** Matches MIN_CAPTURE_AREA_SIZE in electron/ipc/sourceArea.ts. */
	public static final int MIN_AREA_SIZE = 32;

	/** Anything positioned by an {@link AreaRect}. */
	public interface RectEntry {
		AreaRect rect();
	}

	private CapturePickerGeometry() {
	}

	static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	/**
	 * Puts the origin at the top-left corner when a drag went up or left, and keeps the rectangle
	 * inside the overlay.
	 */
	public static AreaRect normalizeAreaRect(AreaRect rect, Bounds bounds) {
		double left = clamp(Math.min(rect.x(), rect.x() + rect.width()), 0, bounds.width());
		double right = clamp(Math.max(rect.x(), rect.x() + rect.width()), 0, bounds.width());
		double top = clamp(Math.min(rect.y(), rect.y() + rect.height()), 0, bounds.height());
		double bottom = clamp(Math.max(rect.y(), rect.y() + rect.height()), 0, bounds.height());
		return new AreaRect(
				Math.round(left), Math.round(top), Math.round(right - left), Math.round(bottom - top));
	}

	/** Moves the rectangle by a drag offset without letting it leave the overlay. */
	public static AreaRect moveAreaRect(AreaRect rect, double dx, double dy, Bounds bounds) {
		return new AreaRect(
				clamp(Math.round(rect.x() + dx), 0, Math.max(0, bounds.width() - rect.width())),
				clamp(Math.round(rect.y() + dy), 0, Math.max(0, bounds.height() - rect.height())),
				rect.width(),
				rect.height());
	}

	/** Drags one edge or corner; dragging past the opposite edge flips the rectangle. */
	public static AreaRect resizeAreaRect(
			AreaRect rect, AreaHandle handle, double dx, double dy, Bounds bounds) {
		double left = rect.x();
		double top = rect.y();
		double right = rect.x() + rect.width();
		double bottom = rect.y() + rect.height();
		if (handle.includes("w")) {
			left += dx;
		}
		if (handle.includes("e")) {
			right += dx;
		}
		if (handle.includes("n")) {
			top += dy;
		}
		if (handle.includes("s")) {
			bottom += dy;
		}
		return normalizeAreaRect(new AreaRect(left, top, right - left, bottom - top), bounds);
	}

	public static boolean rectContains(AreaRect rect, double x, double y) {
		return x >= rect.x() && x < rect.x() + rect.width()
				&& y >= rect.y() && y < rect.y() + rect.height();
	}

	/** The frontmost entry under the point, from a list ordered front to back. */
	public static <T extends RectEntry> T frontmostAt(List<T> entries, double x, double y) {
		for (T entry : entries) {
			if (rectContains(entry.rect(), x, y)) {
				return entry;
			}
		}
		return null;
	}
}
