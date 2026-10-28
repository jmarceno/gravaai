package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where the key-press chips sit. */
public enum KeystrokeOverlayPosition {
	BOTTOM_CENTER("bottom-center"), BOTTOM_LEFT("bottom-left"),
	BOTTOM_RIGHT("bottom-right"), TOP_CENTER("top-center");

	private final String wire;

	KeystrokeOverlayPosition(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static KeystrokeOverlayPosition fromWire(String value) {
		for (KeystrokeOverlayPosition position : values()) {
			if (position.wire.equals(value)) {
				return position;
			}
		}
		return BOTTOM_CENTER;
	}

	public static KeystrokeOverlayPosition fromWireOrNull(String value) {
		for (KeystrokeOverlayPosition position : values()) {
			if (position.wire.equals(value)) {
				return position;
			}
		}
		return null;
	}
}
