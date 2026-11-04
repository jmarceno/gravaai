package dev.gravaai.core.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Bundled key-press sound; each name is the OGG resource {@code key-<name>.ogg}. */
public enum KeySoundStyle {
	CLACK("clack"), TAP("tap"), SOFT("soft");

	private final String wire;

	KeySoundStyle(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	@JsonCreator
	public static KeySoundStyle fromWire(String value) {
		return normalize(value, CLACK);
	}

	/** Unknown wire values become the fallback, like {@code normalizeCursorClickEffectStyle}. */
	public static KeySoundStyle normalize(String value, KeySoundStyle fallback) {
		for (KeySoundStyle style : values()) {
			if (style.wire.equals(value)) {
				return style;
			}
		}
		return fallback;
	}
}
