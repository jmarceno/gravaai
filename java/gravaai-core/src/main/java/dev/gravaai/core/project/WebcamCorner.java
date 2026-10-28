package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** One of the four camera corners. */
public enum WebcamCorner {
	TOP_LEFT("top-left"), TOP_RIGHT("top-right"), BOTTOM_LEFT("bottom-left"), BOTTOM_RIGHT("bottom-right");

	private final String wire;

	WebcamCorner(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static WebcamCorner fromWire(String value) {
		for (WebcamCorner corner : values()) {
			if (corner.wire.equals(value)) {
				return corner;
			}
		}
		return BOTTOM_RIGHT;
	}

	/** {@code fromWire} without a fallback: null for unknown values. */
	public static WebcamCorner fromWireOrNull(String value) {
		for (WebcamCorner corner : values()) {
			if (corner.wire.equals(value)) {
				return corner;
			}
		}
		return null;
	}
}
