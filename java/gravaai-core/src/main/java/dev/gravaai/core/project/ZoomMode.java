package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Zoom origin (TS {@code "auto" | "manual"}). */
public enum ZoomMode {
	AUTO("auto"), MANUAL("manual");

	private final String wire;

	ZoomMode(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ZoomMode fromWire(String value) {
		for (ZoomMode mode : values()) {
			if (mode.wire.equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown zoom mode: " + value);
	}
}
