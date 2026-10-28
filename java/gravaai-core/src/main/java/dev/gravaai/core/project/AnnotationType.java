package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Annotation kind (TS {@code "text" | "image" | "figure" | "blur"}). */
public enum AnnotationType {
	TEXT("text"), IMAGE("image"), FIGURE("figure"), BLUR("blur");

	private final String wire;

	AnnotationType(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static AnnotationType fromWire(String value) {
		for (AnnotationType type : values()) {
			if (type.wire.equals(value)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown annotation type: " + value);
	}
}
