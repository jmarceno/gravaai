package dev.gravaai.core.project;

import java.util.Locale;

/**
 * Which picture layers one {@link GradeRegion} shades: the recorded screen (the main video), the
 * camera, or both. Screen and camera usually need their own calibration, so two grades may cover
 * the same span as long as they never share a layer.
 */
public enum GradeTarget {
	MAIN("main"),
	CAMERA("camera"),
	BOTH("both");

	private final String wire;

	GradeTarget(String wire) {
		this.wire = wire;
	}

	/** The persisted/agent value, e.g. {@code "camera"}. */
	public String wire() {
		return wire;
	}

	/** True when the grade shades the main video. */
	public boolean includesMain() {
		return this != CAMERA;
	}

	/** True when the grade shades the camera. */
	public boolean includesCamera() {
		return this != MAIN;
	}

	/** True when two targets share at least one layer (and so may not overlap in time). */
	public boolean overlaps(GradeTarget other) {
		return includesMain() && other.includesMain()
				|| includesCamera() && other.includesCamera();
	}

	/** Parses a wire value; {@code null}, blank or unknown falls back to {@link #MAIN}. */
	public static GradeTarget fromWire(String wire) {
		if (wire == null) {
			return MAIN;
		}
		String trimmed = wire.trim().toLowerCase(Locale.ROOT);
		for (GradeTarget target : values()) {
			if (target.wire.equals(trimmed)) {
				return target;
			}
		}
		return MAIN;
	}
}
