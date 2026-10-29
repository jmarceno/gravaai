package dev.gravaai.core.project;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Zoom depth 1-6 (TS {@code ZoomDepth} union). */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public enum ZoomDepth {
	ONE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6);

	private static final Map<ZoomDepth, Double> SCALES = Map.of(
			ONE, 1.25, TWO, 1.5, THREE, 1.8, FOUR, 2.2, FIVE, 3.5, SIX, 5.0);

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
		return SCALES.get(this);
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
