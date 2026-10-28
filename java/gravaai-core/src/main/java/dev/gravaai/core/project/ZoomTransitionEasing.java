package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Zoom easing preset (TS {@code ZoomTransitionEasing}). */
public enum ZoomTransitionEasing {
	GRAAVAI("gravaai"), GLIDE("glide"), SMOOTH("smooth"), SNAPPY("snappy"), LINEAR("linear");

	private final String wire;

	ZoomTransitionEasing(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ZoomTransitionEasing fromWire(String value) {
		for (ZoomTransitionEasing easing : values()) {
			if (easing.wire.equals(value)) {
				return easing;
			}
		}
		return GRAAVAI;
	}

	/** {@code normalizeZoomTransitionEasing}: unknown values fall back. */
	public static ZoomTransitionEasing normalize(String value, ZoomTransitionEasing fallback) {
		for (ZoomTransitionEasing easing : values()) {
			if (easing.wire.equals(value)) {
				return easing;
			}
		}
		return fallback;
	}
}
