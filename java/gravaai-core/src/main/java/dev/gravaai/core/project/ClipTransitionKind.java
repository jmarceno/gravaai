package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Clip transition kind (TS {@code "fade-black"}). */
public enum ClipTransitionKind {
	FADE_BLACK("fade-black");

	private final String wire;

	ClipTransitionKind(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ClipTransitionKind fromWire(String value) {
		for (ClipTransitionKind kind : values()) {
			if (kind.wire.equals(value)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("Unknown clip transition: " + value);
	}
}
