package dev.gravaai.media.compose;

/** Cursor visual styles (port of the {@code CursorStyle} union in video-editor/types.ts). */
public enum CursorStyle {
	TAHOE("tahoe"), TAHOE_INVERTED("tahoe-inverted"), WINDOWS11("windows11"), MACOS("macos"),
	DOT("dot"), FIGMA("figma"), CUSTOM("custom");

	private final String wire;

	CursorStyle(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	/**
	 * The bundled cursor sets share one geometry, so no per-style size compensation is needed.
	 * The hook stays for styles that may add their own scaling later.
	 */
	public double sizeMultiplier() {
		return 1;
	}

	/** Resolves the wire value, falling back to {@link #TAHOE} like the TS default. */
	public static CursorStyle fromWire(String value) {
		if (value != null) {
			for (CursorStyle style : values()) {
				if (style.wire.equals(value)) {
					return style;
				}
			}
		}
		return TAHOE;
	}

	/** {@code isSingleCursorStyle}: {@code dot} and {@code figma} draw one style-level asset. */
	public boolean isSingleCursorStyle() {
		return this == DOT || this == FIGMA;
	}
}
