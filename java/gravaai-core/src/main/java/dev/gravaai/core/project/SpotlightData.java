package dev.gravaai.core.project;

/**
 * Spotlight-annotation payload: the frame outside the region's rectangle is dimmed so the area
 * stays bright ({@code dimPercent}), with the hole's edge softened over {@code featherPercent}
 * of the rectangle's short side.
 *
 * @param dimPercent     scrim opacity over the rest of the frame, 0 (invisible) to 100 (black)
 * @param featherPercent softness of the bright area's edge, as a percent of the rectangle's
 *                       short side (0 for a crisp edge)
 */
public record SpotlightData(double dimPercent, double featherPercent) {

	public static final SpotlightData DEFAULT = new SpotlightData(60.0, 25.0);

	public SpotlightData {
		dimPercent = clamp(dimPercent);
		featherPercent = clamp(featherPercent);
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(100, value));
	}
}
