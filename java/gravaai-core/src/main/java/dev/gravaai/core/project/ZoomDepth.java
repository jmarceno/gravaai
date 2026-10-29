package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Zoom depth 1-6 (TS {@code ZoomDepth} union). */
public enum ZoomDepth {
	ONE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6);

	/** {@code ZOOM_DEPTH_SCALES}. */
	public static final ZoomDepth DEFAULT = THREE;
	public static final ZoomDepth DEFAULT_AUTO = DEFAULT;

	private final int wire;

	ZoomDepth(int wire) {
		this.wire = wire;
	}

	@JsonValue
	public int wire() {
		return wire;
	}

	/** The zoom factor for this depth ({@code ZOOM_DEPTH_SCALES}). */
	public double scale() {
		// Exhaustive switch instead of a map lookup: every depth always has a factor, so the
		// compiler (not a nullable map get) enforces the invariant.
		return switch (this) {
			case ONE -> 1.25;
			case TWO -> 1.5;
			case THREE -> 1.8;
			case FOUR -> 2.2;
			case FIVE -> 3.5;
			case SIX -> 5.0;
		};
	}

	@JsonCreator
	public static ZoomDepth fromWire(int value) {
		for (ZoomDepth depth : values()) {
			if (depth.wire == value) {
				return depth;
			}
		}
		throw new IllegalArgumentException("Unknown zoom depth: " + value);
	}
}
