package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Bundled click sound, mirroring {@link CursorClickEffectStyle}; each name is an OGG resource. */
public enum ClickSoundStyle {
	CRISP("crisp"), TICK("tick"), SOFT("soft");

	private final String wire;

	ClickSoundStyle(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static ClickSoundStyle fromWire(String value) {
		return normalize(value, CRISP);
	}

	/** Unknown wire values become the fallback, like {@code normalizeCursorClickEffectStyle}. */
	public static ClickSoundStyle normalize(String value, ClickSoundStyle fallback) {
		for (ClickSoundStyle style : values()) {
			if (style.wire.equals(value)) {
				return style;
			}
		}
		return fallback;
	}
}
