package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Figure arrow direction (TS {@code ArrowDirection} union). */
public enum ArrowDirection {
	UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"),
	UP_RIGHT("up-right"), UP_LEFT("up-left"), DOWN_RIGHT("down-right"), DOWN_LEFT("down-left");

	private final String wire;

	ArrowDirection(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ArrowDirection fromWire(String value) {
		for (ArrowDirection direction : values()) {
			if (direction.wire.equals(value)) {
				return direction;
			}
		}
		throw new IllegalArgumentException("Unknown arrow direction: " + value);
	}
}
