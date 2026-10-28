package dev.gravaai.media.compose;

import io.github.humbleui.types.Rect;

/**
 * Port of the pure geometry parts of {@code src/components/video-editor/videoPlayback/
 * zoomTransform.ts} (plan §5.8 stage 3): the camera transform for a zoom (scale + translation
 * around the focus point), its inverse, and the directional motion-blur velocity computation.
 *
 * <p>The Pixi-filter routing half of the TS file ({@code applyZoomTransform}, zoom/motion blur
 * filter writes) belongs to stage 5, where the motion blur is implemented as a SkSL
 * {@code RuntimeEffect} instead of pixi-filters (plan §5.8 stage 5). Its geometry — the
 * transform quad, zoom origin inference and velocity analysis — is ported then, with its
 * original routing tests.</p>
 */
public final class ZoomTransform {

	/** {@code MIN_DIRECTIONAL_BLUR_MAGNITUDE}. */
	public static final double MIN_DIRECTIONAL_BLUR_MAGNITUDE = 0.01;

	/** {@code DIRECTIONAL_BLUR_KERNEL_SIZE}. */
	public static final int DIRECTIONAL_BLUR_KERNEL_SIZE = 13;

	/** {@code DIRECTIONAL_BLUR_OFFSET_DIVISOR}. */
	public static final double DIRECTIONAL_BLUR_OFFSET_DIVISOR = 32;

	/** {@code SPATIAL_PAN_SHUTTER_MULTIPLIER}. */
	public static final double SPATIAL_PAN_SHUTTER_MULTIPLIER = 4;

	/** {@code SPATIAL_ZOOM_SHUTTER_MULTIPLIER}. */
	public static final double SPATIAL_ZOOM_SHUTTER_MULTIPLIER = 4;

	private ZoomTransform() {}

	/** The camera transform applied to the video layer (TS {@code AppliedTransform}). */
	public record AppliedTransform(double scale, double x, double y) {
	}

	/** Directional motion blur parameters (TS {@code DirectionalMotionBlur}). */
	public record DirectionalMotionBlur(double velocityX, double velocityY, double magnitude,
			int kernelSize, double offset) {
	}

	/** Mutable motion-blur frame history (TS {@code MotionBlurState}), used by stage 5. */
	public static final class MotionBlurState {
		public double lastFrameTimeMs;
		public double prevCamX;
		public double prevCamY = 1;
		public double prevCamScale = 1;
		public boolean initialized;
	}

	/** {@code createMotionBlurState}. */
	public static MotionBlurState createMotionBlurState() {
		MotionBlurState state = new MotionBlurState();
		state.lastFrameTimeMs = 0;
		state.prevCamX = 0;
		state.prevCamY = 0;
		state.prevCamScale = 1;
		state.initialized = false;
		return state;
	}

	/**
	 * {@code computeZoomTransform}: scale up around the focus point and translate so the focus
	 * stays centred, interpolated by {@code zoomProgress}.
	 */
	public static AppliedTransform computeZoomTransform(RenderTargetSize stageSize, Rect baseMask,
			double zoomScale, double zoomProgress, double focusX, double focusY) {
		if (stageSize.width() <= 0 || stageSize.height() <= 0
				|| baseMask == null
				|| baseMask.getWidth() <= 0 || baseMask.getHeight() <= 0) {
			return new AppliedTransform(1, 0, 0);
		}
		double progress = Math.min(1, Math.max(0, zoomProgress));
		double focusStagePxX = baseMask._left + focusX * baseMask.getWidth();
		double focusStagePxY = baseMask._top + focusY * baseMask.getHeight();
		double stageCenterX = stageSize.width() / 2.0;
		double stageCenterY = stageSize.height() / 2.0;
		double scale = 1 + (zoomScale - 1) * progress;
		double finalX = stageCenterX - focusStagePxX * zoomScale;
		double finalY = stageCenterY - focusStagePxY * zoomScale;
		return new AppliedTransform(scale, finalX * progress, finalY * progress);
	}

	/** {@code computeFocusFromTransform}: the inverse of {@link #computeZoomTransform}. */
	public static ZoomFocus computeFocusFromTransform(RenderTargetSize stageSize, Rect baseMask,
			double zoomScale, double x, double y) {
		if (stageSize.width() <= 0 || stageSize.height() <= 0
				|| baseMask == null
				|| baseMask.getWidth() <= 0 || baseMask.getHeight() <= 0
				|| zoomScale <= 0) {
			return new ZoomFocus(0.5, 0.5);
		}
		double stageCenterX = stageSize.width() / 2.0;
		double stageCenterY = stageSize.height() / 2.0;
		double focusStagePxX = (stageCenterX - x) / zoomScale;
		double focusStagePxY = (stageCenterY - y) / zoomScale;
		return new ZoomFocus(
				(focusStagePxX - baseMask._left) / baseMask.getWidth(),
				(focusStagePxY - baseMask._top) / baseMask.getHeight());
	}

	private static double resolveFpsScale(double deltaSeconds) {
		double fps = 1 / Math.max(1.0 / 240, deltaSeconds);
		return fps / 60;
	}

	/** {@code computeDirectionalMotionBlur} with the default strength multiplier. */
	public static DirectionalMotionBlur computeDirectionalMotionBlur(double deltaX, double deltaY,
			double motionBlurAmount, double deltaSeconds) {
		return computeDirectionalMotionBlur(deltaX, deltaY, motionBlurAmount, deltaSeconds, 1);
	}

	/** {@code computeDirectionalMotionBlur}. */
	public static DirectionalMotionBlur computeDirectionalMotionBlur(double deltaX, double deltaY,
			double motionBlurAmount, double deltaSeconds, double strengthMultiplier) {
		double scale = motionBlurAmount * resolveFpsScale(deltaSeconds) * strengthMultiplier;
		double velocityX = deltaX * scale;
		double velocityY = deltaY * scale;
		double magnitude = Math.hypot(velocityX, velocityY);
		boolean active = magnitude >= MIN_DIRECTIONAL_BLUR_MAGNITUDE;
		return new DirectionalMotionBlur(
				active ? velocityX : 0,
				active ? velocityY : 0,
				active ? magnitude : 0,
				DIRECTIONAL_BLUR_KERNEL_SIZE,
				active ? -magnitude / DIRECTIONAL_BLUR_OFFSET_DIVISOR : 0);
	}
}
