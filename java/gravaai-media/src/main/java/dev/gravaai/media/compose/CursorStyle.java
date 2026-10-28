package dev.gravaai.media.compose;

/** Cursor visual styles (port of the {@code CursorStyle} union in video-editor/types.ts). */
public enum CursorStyle {
	TAHOE("tahoe"), TAHOE_INVERTED("tahoe-inverted"), WINDOWS11("windows11"), MACOS("macos"),
	DOT("dot"), FIGMA("figma"), CUSTOM("custom");

	/** {@code WINDOWS11_CURSOR_VIEWBOX_HEIGHT / WINDOWS11_ARROW_CONTENT_HEIGHT}. */
	private static final double WINDOWS11_SIZE_MULTIPLIER = 32 / 19.0625;

	private final String wire;

	CursorStyle(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	/**
	 * {@code MACOS_CURSOR_STYLE_SIZE_MULTIPLIER}: Tahoe pointer content/asset height over the
	 * macOS pointer content/asset height (measured from the raw pointer assets).
	 */
	private static final double MACOS_SIZE_MULTIPLIER = (851.0 / 958.0) / (386.0 / 746.0);

	/** {@code getCursorStyleSizeMultiplier} ({@code cursorStyleSizeMultipliers}). */
	public double sizeMultiplier() {
		return switch (this) {
			case WINDOWS11 -> WINDOWS11_SIZE_MULTIPLIER;
			case MACOS -> MACOS_SIZE_MULTIPLIER;
			default -> 1;
		};
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
