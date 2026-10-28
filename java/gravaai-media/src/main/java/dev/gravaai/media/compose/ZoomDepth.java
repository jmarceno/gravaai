package dev.gravaai.media.compose;

/**
 * Zoom depth levels and their scale factors (port of {@code ZoomDepth} and
 * {@code ZOOM_DEPTH_SCALES} in {@code video-editor/types.ts}).
 */
public enum ZoomDepth {

	D1(1, 1.25),
	D2(2, 1.5),
	D3(3, 1.8),
	D4(4, 2.2),
	D5(5, 3.5),
	D6(6, 5.0);

	private final int level;
	private final double scale;

	ZoomDepth(int level, double scale) {
		this.level = level;
		this.scale = scale;
	}

	/** The 1-6 depth value. */
	public int level() {
		return level;
	}

	/** The zoom scale for this depth ({@code ZOOM_DEPTH_SCALES[depth]}). */
	public double scale() {
		return scale;
	}

	/**
	 * Maps a numeric depth value to its enum.
	 *
	 * @throws IllegalArgumentException for values outside 1-6
	 */
	public static ZoomDepth of(int level) {
		for (ZoomDepth depth : values()) {
			if (depth.level == level) {
				return depth;
			}
		}
		throw new IllegalArgumentException("invalid zoom depth: " + level);
	}
}
