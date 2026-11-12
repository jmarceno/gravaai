package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Annotation kind (TS {@code "text" | "image" | "figure" | "blur" | "loupe" | "spotlight"}). */
public enum AnnotationType {
	TEXT("text"), IMAGE("image"), FIGURE("figure"), BLUR("blur"), LOUPE("loupe"),
	SPOTLIGHT("spotlight");

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
		return parse(value).orElseThrow(
				() -> new IllegalArgumentException("Unknown annotation type: " + value));
	}

	/** The type a wire spelling names; empty for an unknown or absent one. */
	public static java.util.Optional<AnnotationType> parse(@org.jspecify.annotations.Nullable String value) {
		for (AnnotationType type : values()) {
			if (type.wire.equals(value)) {
				return java.util.Optional.of(type);
			}
		}
		return java.util.Optional.empty();
	}
}
