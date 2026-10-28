package dev.gravaai.core.project;

import tools.jackson.databind.JsonNode;

/** Crop/roundness helpers used by the project normaliser (port of the pure parts of
 *  {@code src/components/video-editor/webcamOverlay.ts}). */
public final class WebcamOverlay {

	public static final int WEBCAM_REFERENCE_VIEWPORT_WIDTH = 1920;
	private static final double WEBCAM_REFERENCE_VIEWPORT_HEIGHT = 1080;

	private WebcamOverlay() {}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	private static Double finite(JsonNode value) {
		return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
				? value.doubleValue()
				: null;
	}

	/** {@code convertLegacyWebcamRadiusToRoundness}. */
	public static double convertLegacyWebcamRadiusToRoundness(
			double radiusPixels, double widthPercent, double heightPercent) {
		double referenceShortSide = WEBCAM_REFERENCE_VIEWPORT_HEIGHT
				* (clamp(Math.min(widthPercent, heightPercent), 10, 100) / 100);
		double normalizedRadius = clamp(Math.max(0, radiusPixels) / (referenceShortSide / 2), 0, 1);
		return normalizedRadius * normalizedRadius * 100;
	}

	/** {@code normalizeWebcamCropRegion}. */
	public static CropRegion normalizeWebcamCropRegion(JsonNode cropRegion) {
		JsonNode candidate = cropRegion != null && cropRegion.isObject() ? cropRegion : null;
		Double rawX = finite(candidate == null ? null : candidate.get("x"));
		Double rawY = finite(candidate == null ? null : candidate.get("y"));
		Double rawWidth = finite(candidate == null ? null : candidate.get("width"));
		Double rawHeight = finite(candidate == null ? null : candidate.get("height"));

		double x = clamp(rawX != null ? rawX : 0, 0, 0.99);
		double y = clamp(rawY != null ? rawY : 0, 0, 0.99);
		double width = clamp(rawWidth != null ? rawWidth : 1, 0.01, 1 - x);
		double height = clamp(rawHeight != null ? rawHeight : 1, 0.01, 1 - y);
		return new CropRegion(x, y, width, height);
	}
}
