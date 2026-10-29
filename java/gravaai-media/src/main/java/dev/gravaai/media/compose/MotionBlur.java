package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.List;

import io.github.humbleui.types.Rect;

/**
 * Port of the motion-blur analysis half of
 * {@code src/components/video-editor/videoPlayback/zoomTransform.ts} (plan §5.8 stage 5).
 *
 * <p>The TS code feeds two pixi filters (MotionBlurFilter / ZoomBlurFilter) from the camera
 * step analysis; the Java compositor renders the same motion blur as a SkSL
 * {@code RuntimeEffect} instead (plan §5.8 stage 5), so this class stops at the effect
 * parameters: {@link Effect} carries the path (directional pan blur or radial zoom blur) and the
 * exact velocity/offset/strength/centre the shader needs. The routing logic — velocity
 * thresholds, fps normalisation, zoom-vs-pan classification, zoom centre inference, the
 * stateful per-frame history and the paused-frame motion sample — is ported 1:1.</p>
 */
public final class MotionBlur {

	/** {@code DEFAULT_ZOOM_MOTION_BLUR_TUNING} from {@code video-editor/types.ts}. */
	public record Tuning(double panVelocityThreshold, double zoomVelocityThreshold,
			double maxDirectionalBlurPx, double maxRadialBlurStrength,
			double panResponsePerSecond, double zoomResponsePerSecond,
			double zoomSafeZoneRadiusPx) {

		/** {@code DEFAULT_ZOOM_MOTION_BLUR_TUNING}. */
		public static final Tuning DEFAULT = new Tuning(0, 0, 41.8, 1, 11, 9, 6);
	}

	/** A 2D point. */
	public record Point(double x, double y) {

		/** {@code (0, 0)}. */
		public static final Point ZERO = new Point(0, 0);
	}

	/**
	 * The transformed camera quad (TS {@code TransformQuad}).
	 *
	 * @param center the quad centre
	 * @param corners top-left, top-right, bottom-right, bottom-left
	 * @param diagonal the diagonal length
	 * @param size the edge lengths
	 */
	public record Quad(Point center, List<Point> corners, double diagonal, Point size) {
	}

	/** Which motion-blur path the camera step takes. */
	public enum Path {
		/** No blur this frame. */
		NONE,
		/** Directional pan blur. */
		DIRECTIONAL,
		/** Radial zoom blur. */
		ZOOM
	}

	/**
	 * The motion-blur parameters for the compositor's SkSL shader. Only the fields for
	 * {@link #path()} are meaningful.
	 *
	 * @param path the active path
	 * @param velocityX directional blur velocity x
	 * @param velocityY directional blur velocity y
	 * @param magnitude directional blur magnitude
	 * @param kernelSize directional blur kernel size ({@code DIRECTIONAL_BLUR_KERNEL_SIZE})
	 * @param offset directional blur sample offset
	 * @param zoomCenterX zoom blur centre x (stage pixels)
	 * @param zoomCenterY zoom blur centre y (stage pixels)
	 * @param zoomStrength zoom blur strength
	 * @param zoomInnerRadius zoom blur inner radius (always 0)
	 * @param zoomRadius zoom blur radius (always -1, meaning "auto")
	 */
	public record Effect(Path path, double velocityX, double velocityY, double magnitude,
			int kernelSize, double offset, double zoomCenterX, double zoomCenterY,
			double zoomStrength, double zoomInnerRadius, double zoomRadius) {

		/** No blur. */
		public static final Effect NONE = new Effect(Path.NONE, 0, 0, 0,
				ZoomTransform.DIRECTIONAL_BLUR_KERNEL_SIZE, 0, 0, 0, 0, 0, -1);
	}

	/** TS {@code CameraStepAnalysis}. */
	public record CameraStepAnalysis(Path mode, Point moveVelocity, Point moveBlurVelocity,
			double moveBlurOffset, Point zoomCenter, double zoomStrength) {
	}

	/** A paused-frame motion sample (TS {@code motionSample}). */
	public record MotionSample(ZoomTransform.AppliedTransform previousTransform, double deltaMs) {
	}

	private MotionBlur() {}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	private static double crossProduct(Point a, Point b) {
		return a.x() * b.y() - a.y() * b.x();
	}

	private static @Nullable Point intersectInfiniteLines(Point aStart, Point aEnd, Point bStart,
			Point bEnd) {
		Point aDelta = new Point(aEnd.x() - aStart.x(), aEnd.y() - aStart.y());
		Point bDelta = new Point(bEnd.x() - bStart.x(), bEnd.y() - bStart.y());
		double denominator = crossProduct(aDelta, bDelta);
		if (Math.abs(denominator) < 0.0001) {
			return null;
		}
		Point originDelta = new Point(bStart.x() - aStart.x(), bStart.y() - aStart.y());
		double t = crossProduct(originDelta, bDelta) / denominator;
		return new Point(aStart.x() + aDelta.x() * t, aStart.y() + aDelta.y() * t);
	}

	/** {@code computeTransformQuad}. */
	public static Quad computeTransformQuad(Rect baseMask, ZoomTransform.AppliedTransform transform) {
		Point topLeft = new Point(transform.x() + baseMask._left * transform.scale(),
				transform.y() + baseMask._top * transform.scale());
		Point topRight = new Point(
				transform.x() + (baseMask._left + baseMask.getWidth()) * transform.scale(),
				transform.y() + baseMask._top * transform.scale());
		Point bottomRight = new Point(
				transform.x() + (baseMask._left + baseMask.getWidth()) * transform.scale(),
				transform.y() + (baseMask._top + baseMask.getHeight()) * transform.scale());
		Point bottomLeft = new Point(transform.x() + baseMask._left * transform.scale(),
				transform.y() + (baseMask._top + baseMask.getHeight()) * transform.scale());
		Point center = new Point((topLeft.x() + bottomRight.x()) * 0.5,
				(topLeft.y() + bottomRight.y()) * 0.5);
		return new Quad(center, List.of(topLeft, topRight, bottomRight, bottomLeft),
				Math.hypot(bottomRight.x() - topLeft.x(), bottomRight.y() - topLeft.y()),
				new Point(Math.hypot(topRight.x() - topLeft.x(), topRight.y() - topLeft.y()),
						Math.hypot(bottomLeft.x() - topLeft.x(), bottomLeft.y() - topLeft.y())));
	}

	/** {@code inferZoomCenterFromQuads}. */
	public static Point inferZoomCenterFromQuads(Quad previousQuad, Quad currentQuad,
			RenderTargetSize stageSize) {
		boolean hasMeaningfulChange = Math.abs(currentQuad.center().x() - previousQuad.center().x())
				> 0.001
				|| Math.abs(currentQuad.center().y() - previousQuad.center().y()) > 0.001
				|| Math.abs(currentQuad.diagonal() - previousQuad.diagonal()) > 0.001;
		if (!hasMeaningfulChange) {
			return previousQuad.center();
		}
		java.util.List<Point[]> motionSegments = new java.util.ArrayList<>();
		for (int i = 0; i < previousQuad.corners().size(); i += 1) {
			Point start = previousQuad.corners().get(i);
			Point end = currentQuad.corners().get(i);
			if (Math.hypot(end.x() - start.x(), end.y() - start.y()) < 0.5) {
				continue;
			}
			motionSegments.add(new Point[] {start, end});
		}
		for (int i = 0; i < motionSegments.size(); i += 1) {
			for (int j = i + 1; j < motionSegments.size(); j += 1) {
				Point intersection = intersectInfiniteLines(motionSegments.get(i)[0],
						motionSegments.get(i)[1], motionSegments.get(j)[0],
						motionSegments.get(j)[1]);
				if (intersection == null) {
					continue;
				}
				double marginX = stageSize.width();
				double marginY = stageSize.height();
				return new Point(clamp(intersection.x(), -marginX, stageSize.width() + marginX),
						clamp(intersection.y(), -marginY, stageSize.height() + marginY));
			}
		}
		return currentQuad.center();
	}

	private static double resolveFpsScale(double deltaSeconds) {
		double fps = 1 / Math.max(1.0 / 240, deltaSeconds);
		return fps / 60;
	}

	private static double resolveZoomBlurStrength(double motionBlurAmount, Tuning tuning,
			double deltaSeconds) {
		double fpsScale = resolveFpsScale(deltaSeconds);
		return motionBlurAmount * fpsScale * ZoomTransform.SPATIAL_ZOOM_SHUTTER_MULTIPLIER
				* (tuning.maxRadialBlurStrength() / Tuning.DEFAULT.maxRadialBlurStrength());
	}

	private static Point computeMoveDelta(Quad previousQuad, Quad currentQuad) {
		return new Point(currentQuad.center().x() - previousQuad.center().x(),
				currentQuad.center().y() - previousQuad.center().y());
	}

	private static Point computeSizeDelta(Quad previousQuad, Quad currentQuad) {
		return new Point(Math.abs(currentQuad.size().x() - previousQuad.size().x()),
				Math.abs(currentQuad.size().y() - previousQuad.size().y()));
	}

	private static double computeZoomStrength(Quad previousQuad, Quad currentQuad) {
		// Keep the spatial approximation sampling inward (see the TS comment): outward sampling
		// can leave the captured texture and produce transparent/black frames.
		return Math.abs(
				1 - currentQuad.diagonal() / Math.max(0.0001, previousQuad.diagonal()));
	}

	private static Path classifyMotionMode(Quad previousQuad, Quad currentQuad, Tuning tuning,
			double deltaSeconds) {
		Point moveDelta = computeMoveDelta(previousQuad, currentQuad);
		Point sizeDelta = computeSizeDelta(previousQuad, currentQuad);
		double moveDistance = Math.hypot(moveDelta.x(), moveDelta.y());
		double zoomDistance = Math.hypot(sizeDelta.x(), sizeDelta.y());
		double moveVelocity = Math.hypot(moveDelta.x(), moveDelta.y())
				/ Math.max(0.0001, deltaSeconds);
		double zoomVelocity = Math.abs(Math.log(Math.max(0.0001, currentQuad.diagonal())
				/ Math.max(0.0001, previousQuad.diagonal()))) / Math.max(0.0001, deltaSeconds);
		boolean moveActive = moveDistance > 0.001
				&& moveVelocity > tuning.panVelocityThreshold();
		boolean zoomActive = zoomDistance > 0.001
				&& zoomVelocity > tuning.zoomVelocityThreshold();
		if (!moveActive && !zoomActive) {
			return Path.NONE;
		}
		// Any meaningful scale change is a zoom; the inferred zoom centre already accounts for
		// the translation of an off-centre zoom.
		if (zoomActive) {
			return Path.ZOOM;
		}
		return Path.DIRECTIONAL;
	}

	/** {@code analyzeCameraStep}. */
	public static CameraStepAnalysis analyzeCameraStep(Quad previousQuad, Quad currentQuad,
			RenderTargetSize stageSize, double motionBlurAmount, Tuning tuning,
			double deltaSeconds) {
		Path mode = classifyMotionMode(previousQuad, currentQuad, tuning, deltaSeconds);
		Point moveDelta = computeMoveDelta(previousQuad, currentQuad);
		double zoomBlurStrength = resolveZoomBlurStrength(motionBlurAmount, tuning, deltaSeconds);
		ZoomTransform.DirectionalMotionBlur directionalBlur =
				ZoomTransform.computeDirectionalMotionBlur(moveDelta.x(), moveDelta.y(),
						motionBlurAmount, deltaSeconds,
						ZoomTransform.SPATIAL_PAN_SHUTTER_MULTIPLIER
								* (tuning.maxDirectionalBlurPx()
										/ Tuning.DEFAULT.maxDirectionalBlurPx()));
		return new CameraStepAnalysis(mode, moveDelta,
				mode == Path.DIRECTIONAL
						? new Point(directionalBlur.velocityX(), directionalBlur.velocityY())
						: Point.ZERO,
				mode == Path.DIRECTIONAL ? directionalBlur.offset() : 0,
				inferZoomCenterFromQuads(previousQuad, currentQuad, stageSize),
				mode == Path.ZOOM
						? computeZoomStrength(previousQuad, currentQuad) * zoomBlurStrength
						: 0);
	}

	/** {@code applyCameraStepBlur}: turns the analysis into the shader parameters. */
	public static Effect effectFor(CameraStepAnalysis analysis) {
		double velocityX = analysis.moveBlurVelocity().x();
		double velocityY = analysis.moveBlurVelocity().y();
		double magnitude = Math.hypot(velocityX, velocityY);
		return new Effect(analysis.mode(), velocityX, velocityY, magnitude,
				ZoomTransform.DIRECTIONAL_BLUR_KERNEL_SIZE, analysis.moveBlurOffset(),
				analysis.zoomCenter().x(), analysis.zoomCenter().y(), analysis.zoomStrength(), 0,
				-1);
	}

	/** {@code resetMotionEffects} for the motion-blur state. */
	public static void resetMotionBlurState(ZoomTransform.MotionBlurState state) {
		if (state == null) {
			return;
		}
		state.lastFrameTimeMs = 0;
		state.prevCamX = 0;
		state.prevCamY = 0;
		state.prevCamScale = 1;
		state.initialized = false;
	}

	private static void initializeState(ZoomTransform.MotionBlurState state,
			ZoomTransform.AppliedTransform transform, double nowMs) {
		state.prevCamX = transform.x();
		state.prevCamY = transform.y();
		state.prevCamScale = transform.scale();
		state.lastFrameTimeMs = nowMs;
		state.initialized = true;
	}

	/**
	 * The motion-blur section of {@code applyZoomTransform}: advances the frame history/state and
	 * returns the shader parameters (or {@link Effect#NONE}).
	 *
	 * @param motionSample an explicit paused-frame sample, or {@code null}
	 * @param motionSampleProvided {@code true} when {@code motionSample} was explicitly given
	 *        (the TS {@code motionSample === null} reset branch); {@code false} means "not
	 *        provided" and runs the stateful per-frame branch
	 * @param frameTimeMs the frame time; when {@code null} the previous state time is used
	 */
	public static Effect update(ZoomTransform.MotionBlurState state, Rect baseMask,
			ZoomTransform.AppliedTransform transform, RenderTargetSize stageSize,
			boolean isPlaying, double motionBlurAmount, Tuning tuning, Double frameTimeMs,
			MotionSample motionSample, boolean motionSampleProvided) {
		if (stageSize == null || stageSize.width() <= 0 || stageSize.height() <= 0
				|| baseMask == null || baseMask.getWidth() <= 0 || baseMask.getHeight() <= 0) {
			resetMotionBlurState(state);
			return Effect.NONE;
		}
		Tuning resolvedTuning = tuning == null ? Tuning.DEFAULT : tuning;
		if (motionBlurAmount > 0 && motionSampleProvided && motionSample != null
				&& motionSample.deltaMs() > 0) {
			CameraStepAnalysis analysis = analyzeCameraStep(
					computeTransformQuad(baseMask, motionSample.previousTransform()),
					computeTransformQuad(baseMask, transform), stageSize, motionBlurAmount,
					resolvedTuning, motionSample.deltaMs() / 1000);
			if (state != null) {
				initializeState(state, transform, frameTimeMs == null ? 0 : frameTimeMs);
			}
			return effectFor(analysis);
		}
		if (motionSampleProvided) {
			resetMotionBlurState(state);
			return Effect.NONE;
		}
		if (state != null && motionBlurAmount > 0 && isPlaying) {
			double now = frameTimeMs == null ? 0 : frameTimeMs;
			if (!state.initialized) {
				initializeState(state, transform, now);
				return new Effect(Path.DIRECTIONAL, 0, 0, 0,
						ZoomTransform.DIRECTIONAL_BLUR_KERNEL_SIZE, 0, 0, 0, 0, 0, -1);
			}
			double dtMs = Math.min(80, Math.max(1, now - state.lastFrameTimeMs));
			double dtSeconds = dtMs / 1000;
			state.lastFrameTimeMs = now;
			ZoomTransform.AppliedTransform previousTransform =
					new ZoomTransform.AppliedTransform(state.prevCamScale, state.prevCamX,
							state.prevCamY);
			CameraStepAnalysis analysis = analyzeCameraStep(
					computeTransformQuad(baseMask, previousTransform),
					computeTransformQuad(baseMask, transform), stageSize, motionBlurAmount,
					resolvedTuning, dtSeconds);
			state.prevCamX = transform.x();
			state.prevCamY = transform.y();
			state.prevCamScale = transform.scale();
			return effectFor(analysis);
		}
		resetMotionBlurState(state);
		return Effect.NONE;
	}
}
