package dev.gravaai.core.project;

/** Tuning constants of the motion-blur shader (developer setting, usually defaults). */
public record ZoomMotionBlurTuning(
		double panVelocityThreshold,
		double zoomVelocityThreshold,
		double maxDirectionalBlurPx,
		double maxRadialBlurStrength,
		double panResponsePerSecond,
		double zoomResponsePerSecond,
		double zoomSafeZoneRadiusPx) {

	public static final ZoomMotionBlurTuning DEFAULT =
			new ZoomMotionBlurTuning(0, 0, 41.8, 1, 11, 9, 6);
}
