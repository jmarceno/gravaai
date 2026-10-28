package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Cursor click effect (TS {@code CursorClickEffectStyle}). */
public enum CursorClickEffectStyle {
	NONE("none"), SPOTLIGHT("spotlight"), RIPPLE("ripple"), ECHO("echo");

	private final String wire;

	CursorClickEffectStyle(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static CursorClickEffectStyle fromWire(String value) {
		return normalizeStyle(value, NONE);
	}

	/** {@code normalizeCursorClickEffectStyle}: the retired "burst" effect becomes "echo". */
	public static CursorClickEffectStyle normalizeStyle(String value, CursorClickEffectStyle fallback) {
		if ("burst".equals(value)) {
			return ECHO;
		}
		for (CursorClickEffectStyle style : values()) {
			if (style.wire.equals(value)) {
				return style;
			}
		}
		return fallback;
	}
}
