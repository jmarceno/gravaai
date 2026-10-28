package dev.gravaai.media.compose;

/** Click-effect styles (port of {@code CursorClickEffectStyle}). */
public enum CursorClickEffect {
	NONE("none"), RIPPLE("ripple"), SPOTLIGHT("spotlight"), ECHO("echo");

	private final String wire;

	CursorClickEffect(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static CursorClickEffect fromWire(String value) {
		if (value != null) {
			for (CursorClickEffect effect : values()) {
				if (effect.wire.equals(value)) {
					return effect;
				}
			}
		}
		return NONE;
	}
}
