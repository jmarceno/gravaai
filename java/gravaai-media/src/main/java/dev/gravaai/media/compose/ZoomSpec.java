package dev.gravaai.media.compose;

import java.util.List;

/**
 * Zoom settings and regions for one compositor frame (plan §5.8 stage 3), media-local like
 * {@link LayoutSpec}: the project model / services adapter maps the editor state into this.
 *
 * @param regions the zoom regions in timeline order (may be unsorted; the resolver sorts)
 * @param zoomSmoothness the zoom spring smoothness slider (0-1, see
 *        {@link SpringSmoothing#getZoomSpringConfig(double)})
 * @param connectZooms whether chained zoom regions connect through a pan transition
 * @param zoomClassicMode classic mode snaps the camera instead of spring-smoothing it and
 *        disables cursor-follow
 * @param zoomInDurationMs zoom-in duration override, or {@code null} for the default
 * @param zoomOutDurationMs zoom-out duration override, or {@code null} for the default
 * @param cameraSpringStiffnessMultiplier camera spring tuning stiffness multiplier
 * @param cameraSpringDampingMultiplier camera spring tuning damping multiplier
 * @param cameraSpringMassMultiplier camera spring tuning mass multiplier
 * @param motionBlurAmount the zoom motion-blur amount (editor {@code zoomMotionBlur}, 0 = off)
 */
public record ZoomSpec(
		List<ZoomRegionSpec> regions,
		double zoomSmoothness,
		boolean connectZooms,
		boolean zoomClassicMode,
		Double zoomInDurationMs,
		Double zoomOutDurationMs,
		double cameraSpringStiffnessMultiplier,
		double cameraSpringDampingMultiplier,
		double cameraSpringMassMultiplier,
		double motionBlurAmount) {

	/** No zoom regions: the compositor renders the untransformed video layer. */
	public static final ZoomSpec NONE = new ZoomSpec(List.of(), 0.5, false, false, null, null,
			1, 1, 1, 0);

	public ZoomSpec {
		regions = regions == null ? List.of() : List.copyOf(regions);
		if (!Double.isFinite(zoomSmoothness) || zoomSmoothness < 0 || zoomSmoothness > 1) {
			throw new IllegalArgumentException("zoomSmoothness must be 0-1: " + zoomSmoothness);
		}
		if (!Double.isFinite(cameraSpringStiffnessMultiplier)
				|| !Double.isFinite(cameraSpringDampingMultiplier)
				|| !Double.isFinite(cameraSpringMassMultiplier)) {
			throw new IllegalArgumentException("camera spring multipliers must be finite");
		}
		if (!Double.isFinite(motionBlurAmount) || motionBlurAmount < 0) {
			throw new IllegalArgumentException(
					"motionBlurAmount must be a finite non-negative number: " + motionBlurAmount);
		}
	}

	/**
	 * Backwards-compatible constructor for callers that do not feed motion blur (the UI preview
	 * mapper): the motion-blur amount defaults to off.
	 */
	public ZoomSpec(List<ZoomRegionSpec> regions, double zoomSmoothness, boolean connectZooms,
			boolean zoomClassicMode, Double zoomInDurationMs, Double zoomOutDurationMs,
			double cameraSpringStiffnessMultiplier, double cameraSpringDampingMultiplier,
			double cameraSpringMassMultiplier) {
		this(regions, zoomSmoothness, connectZooms, zoomClassicMode, zoomInDurationMs,
				zoomOutDurationMs, cameraSpringStiffnessMultiplier,
				cameraSpringDampingMultiplier, cameraSpringMassMultiplier, 0);
	}

	/** {@code true} when this frame has zoom regions to resolve. */
	public boolean isActive() {
		return !regions.isEmpty();
	}

	/** The spring tuning the zoom camera uses. */
	public SpringSmoothing.CursorSpringTuning cameraSpringTuning() {
		return new SpringSmoothing.CursorSpringTuning(cameraSpringStiffnessMultiplier,
				cameraSpringDampingMultiplier, cameraSpringMassMultiplier);
	}
}
