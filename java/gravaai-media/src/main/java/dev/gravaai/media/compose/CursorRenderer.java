package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.List;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

/**
 * Port of the drawing half of {@code src/components/video-editor/videoPlayback/cursorRenderer.ts}
 * (plan §5.8 stage 6): smoothed cursor position (springs + time-discontinuity snap), cursor sway
 * rotation, click bounce/click-effect state and the three built-in vector cursor styles, drawn
 * into the camera transform over the video layer.
 *
 * <p>Cursor sprites come from {@link CursorAssets} (the committed SVG cursor sets, rasterised
 * and anchored exactly like the TS asset pipeline).</p>
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class CursorRenderer {

	/** {@code CURSOR_DIRECTIONAL_BLUR_STRENGTH}. */
	private static final double CURSOR_DIRECTIONAL_BLUR_STRENGTH = 2;

	/** {@code CURSOR_TIME_DISCONTINUITY_MS}. */
	private static final double CURSOR_TIME_DISCONTINUITY_MS = 100;

	/** {@code CURSOR_SWAY_SMOOTHING_MULTIPLIER}. */
	private static final double CURSOR_SWAY_SMOOTHING_MULTIPLIER = 0.7;

	/** {@code CURSOR_SWAY_SMOOTHING_OFFSET}. */
	private static final double CURSOR_SWAY_SMOOTHING_OFFSET = 0.18;

	/** {@code CURSOR_SWAY_MAX_ROTATION}. */
	private static final double CURSOR_SWAY_MAX_ROTATION = Math.PI / 18;

	/** {@code CURSOR_SWAY_SPEED_REFERENCE}. */
	private static final double CURSOR_SWAY_SPEED_REFERENCE = 1400;

	/** {@code CURSOR_SWAY_VERTICAL_WEIGHT}. */
	private static final double CURSOR_SWAY_VERTICAL_WEIGHT = 0.65;

	/** {@code CURSOR_SWAY_INTENSITY_SCALE}. */
	private static final double CURSOR_SWAY_INTENSITY_SCALE = 3;

	private static final double CURSOR_REFERENCE_VIEWPORT_WIDTH = 1920;

	/** {@code DEFAULT_CURSOR_CONFIG.dotAlpha}: the sprite alpha. */
	private static final double DOT_ALPHA = 0.95;

	/** {@code CURSOR_SHADOW_ALPHA}. */
	private static final double CURSOR_SHADOW_ALPHA = 0.35;

	/** {@code CURSOR_SHADOW_OFFSET_X} / {@code CURSOR_SHADOW_OFFSET_Y}. */
	private static final double CURSOR_SHADOW_OFFSET_X = 0;
	private static final double CURSOR_SHADOW_OFFSET_Y = 2;

	/** {@code CURSOR_SHADOW_BLUR} (pixi BlurFilter strength 3) as a Gaussian sigma. */
	private static final float CURSOR_SHADOW_BLUR_SIGMA = 1.5f;

	/** Pixi cursor textures: linear scale mode with generated mipmaps. */
	private static final SamplingMode SPRITE_SAMPLING =
			new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR);

	/** The cursor visual state (TS {@code getCursorVisualState} result). */
	record VisualState(String cursorType, @Nullable CursorSample clickSample, double clickBounceProgress,
			double clickProgress) {
	}

	private final SpringSmoothing.SpringState xSpring = SpringSmoothing.createSpringState(0.5);
	private final SpringSmoothing.SpringState ySpring = SpringSmoothing.createSpringState(0.5);
	private final SpringSmoothing.SpringState swaySpring = SpringSmoothing.createSpringState(0);
	private double x = 0.5;
	private double y = 0.5;
	private boolean initialized;
	private @Nullable Double lastTimeMs;
	private double swayRotation;
	private double lastPx;
	private double lastPy;
	private @Nullable Double lastRenderedTimeMs;
	private double lastDrawH;
	private boolean visible;
	private ZoomTransform.@Nullable DirectionalMotionBlur lastBlur;

	void reset() {
		initialized = false;
		lastTimeMs = null;
		lastRenderedTimeMs = null;
		x = 0.5;
		y = 0.5;
		swayRotation = 0;
		SpringSmoothing.resetSpringState(xSpring, 0.5);
		SpringSmoothing.resetSpringState(ySpring, 0.5);
		SpringSmoothing.resetSpringState(swaySpring, 0.0);
		visible = false;
		lastBlur = null;
	}

	boolean isVisible() {
		return visible;
	}

	/** Draws the cursor overlay for one frame inside the current camera transform. */
	void draw(Canvas canvas, Rect viewport, LayoutCrop crop, List<CursorSample> samples,
			double timeMs, CursorSpec spec) {
		visible = false;
		if (!spec.showCursor() || samples == null || samples.isEmpty()
				|| viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
			return;
		}
		CursorSample target = CursorPosition.interpolateCursorPosition(samples, timeMs);
		if (target == null) {
			return;
		}
		double[] projected = project(target.x(), target.y(), crop);
		if (projected[2] <= 0) {
			return;
		}
		VisualState visual = visualState(samples, timeMs, spec.clickBounceDurationMs(),
				spec.clickEffectDurationMs());
		double h = spec.dotRadius()
				* Math.max(0, viewport.getWidth()) / CURSOR_REFERENCE_VIEWPORT_WIDTH;
		double scaledH = h * spec.style().sizeMultiplier();

		double deltaMs = lastTimeMs == null ? 1000.0 / 60.0 : timeMs - lastTimeMs;
		boolean discontinuity = lastTimeMs != null
				&& Math.abs(timeMs - lastTimeMs) > CURSOR_TIME_DISCONTINUITY_MS;
		SpringSmoothing.SpringConfig config = SpringSmoothing.getCursorSpringConfig(
				Math.max(0, Math.min(1, spec.smoothingFactor())), spec.springTuning());
		if (!initialized || discontinuity) {
			x = projected[0];
			y = projected[1];
			initialized = true;
			SpringSmoothing.resetSpringState(xSpring, x);
			SpringSmoothing.resetSpringState(ySpring, y);
		} else {
			x = SpringSmoothing.stepSpringValue(xSpring, projected[0], deltaMs, config);
			y = SpringSmoothing.stepSpringValue(ySpring, projected[1], deltaMs, config);
		}
		lastTimeMs = timeMs;

		double px = viewport._left + x * viewport.getWidth();
		double py = viewport._top + y * viewport.getHeight();
		double swayDelta = lastRenderedTimeMs == null
				? 1000.0 / 60.0
				: Math.max(1, timeMs - lastRenderedTimeMs);
		double targetRotation = lastRenderedTimeMs == null
				? 0
				: computeSwayRotation(px - lastPx, py - lastPy, swayDelta, spec.sway());
		SpringSmoothing.SpringConfig swayConfig = swaySpringConfig(spec.smoothingFactor(),
				spec.springTuning());
		swayRotation = SpringSmoothing.stepSpringValue(swaySpring, targetRotation, swayDelta,
				swayConfig);
		if (Math.abs(swayRotation) < 0.0001 && targetRotation == 0) {
			swayRotation = 0;
		}
		ZoomTransform.DirectionalMotionBlur blur = cursorMotionBlur(samples, timeMs, viewport,
				crop, spec.motionBlur(), px, py);
		lastPx = px;
		lastPy = py;
		lastRenderedTimeMs = timeMs;

		double bounce = Math.max(0.72,
				1 - Math.sin(visual.clickBounceProgress() * Math.PI) * (0.08 * spec.clickBounce()));
		double drawH = scaledH * bounce;
		lastDrawH = drawH;
		visible = true;

		if (blur.magnitude() <= 0) {
			drawCursorLayer(canvas, viewport, crop, spec, visual, scaledH, drawH, px, py);
			return;
		}
		// The TS MotionBlurFilter on the cursor container averages kernelSize taps along the
		// velocity (the stage-5 directional shader); each tap is the whole cursor layer shifted
		// against its sample bias, summed with PLUS at 1/kernelSize.
		double reach = Math.hypot(blur.velocityX(), blur.velocityY()) + drawH * 3 + scaledH * 4;
		Rect bounds = Rect.makeLTRB((float) (px - reach), (float) (py - reach),
				(float) (px + reach), (float) (py + reach));
		int kernel = blur.kernelSize();
		double k = kernel - 1.0;
		double magnitude = Math.hypot(blur.velocityX(), blur.velocityY());
		double offset = -blur.offset() / magnitude - 0.5;
		canvas.saveLayer(bounds, null);
		try (Paint tap = new Paint()) {
			tap.setBlendMode(BlendMode.PLUS);
			tap.setAlphaf(1f / kernel);
			for (int index = -1; index < k; index++) {
				double t = index < 0 ? 0 : index / k + offset;
				canvas.saveLayer(bounds, tap);
				canvas.translate((float) (-blur.velocityX() * t), (float) (-blur.velocityY() * t));
				drawCursorLayer(canvas, viewport, crop, spec, visual, scaledH, drawH, px, py);
				canvas.restore();
			}
		}
		canvas.restore();
	}

	/**
	 * The cursor velocity blur: rendered-position deltas while playing ({@code
	 * applyCursorMotionBlur}), or the telemetry motion over one 60 fps frame for a frozen frame
	 * (first frame or a seek; {@code computeCursorFrameBlur} + {@code getFrameMotionSample}).
	 */
	private ZoomTransform.DirectionalMotionBlur cursorMotionBlur(List<CursorSample> samples,
			double timeMs, Rect viewport, LayoutCrop crop, double amount, double px, double py) {
		ZoomTransform.DirectionalMotionBlur none =
				ZoomTransform.computeDirectionalMotionBlur(0, 0, 0, 1 / 60.0);
		if (amount <= 0) {
			return none;
		}
		// A repeated render of the same media time holds the composed frame (TS sameFrameTime).
		if (lastRenderedTimeMs != null && Math.abs(timeMs - lastRenderedTimeMs) < 0.0001
				&& lastBlur != null) {
			return lastBlur;
		}
		lastBlur = frameBlur(samples, timeMs, viewport, crop, amount, px, py, none);
		return lastBlur;
	}

	private ZoomTransform.DirectionalMotionBlur frameBlur(List<CursorSample> samples,
			double timeMs, Rect viewport, LayoutCrop crop, double amount, double px, double py,
			ZoomTransform.DirectionalMotionBlur none) {
		boolean frozen = lastRenderedTimeMs == null
				|| Math.abs(timeMs - lastRenderedTimeMs) > CURSOR_TIME_DISCONTINUITY_MS;
		if (!frozen) {
			double deltaMs = Math.max(1, timeMs - lastRenderedTimeMs);
			return ZoomTransform.computeDirectionalMotionBlur(px - lastPx, py - lastPy, amount,
					deltaMs / 1000, CURSOR_DIRECTIONAL_BLUR_STRENGTH);
		}
		double previousTimeMs = Math.max(0, timeMs - 1000.0 / 60.0);
		if (timeMs <= 0 || previousTimeMs >= timeMs) {
			return none;
		}
		CursorSample current = CursorPosition.interpolateCursorPosition(samples, timeMs);
		CursorSample previous = CursorPosition.interpolateCursorPosition(samples, previousTimeMs);
		if (current == null || previous == null) {
			return none;
		}
		double[] a = project(previous.x(), previous.y(), crop);
		double[] b = project(current.x(), current.y(), crop);
		if (a[2] <= 0 || b[2] <= 0) {
			return none;
		}
		return ZoomTransform.computeDirectionalMotionBlur((b[0] - a[0]) * viewport.getWidth(),
				(b[1] - a[1]) * viewport.getHeight(), amount, (timeMs - previousTimeMs) / 1000,
				CURSOR_DIRECTIONAL_BLUR_STRENGTH);
	}

	/** Click effect, shadow sprite and cursor sprite (the TS cursor container children). */
	private void drawCursorLayer(Canvas canvas, Rect viewport, LayoutCrop crop,
			CursorSpec spec, VisualState visual, double scaledH, double drawH, double px,
			double py) {
		if (spec.clickEffect() != CursorClickEffect.NONE && visual.clickProgress() > 0
				&& visual.clickSample() != null) {
			double[] click = project(visual.clickSample().x(), visual.clickSample().y(), crop);
			if (click[2] > 0) {
				drawClickEffect(canvas, spec, viewport._left + click[0] * viewport.getWidth(),
						viewport._top + click[1] * viewport.getHeight(), scaledH,
						visual.clickProgress());
			}
		}

		CursorAssets.Asset asset = CursorAssets.get().resolve(spec.style(), visual.cursorType());
		if (spec.style() != CursorStyle.FIGMA) {
			// The separate shadow sprite: black tint, CURSOR_SHADOW_ALPHA, CURSOR_SHADOW_BLUR,
			// positioned at the cursor plus the shadow offset and sharing the sway rotation.
			canvas.save();
			canvas.translate((float) (px + CURSOR_SHADOW_OFFSET_X),
					(float) (py + CURSOR_SHADOW_OFFSET_Y));
			canvas.rotate((float) Math.toDegrees(swayRotation));
			drawSprite(canvas, asset, drawH, true);
			canvas.restore();
		}
		canvas.save();
		canvas.translate((float) px, (float) py);
		canvas.rotate((float) Math.toDegrees(swayRotation));
		drawSprite(canvas, asset, drawH, false);
		canvas.restore();
	}

	/** {@code projectCursorPositionToViewport}: maps source-frame coords through the crop. */
	static double[] project(double cx, double cy, LayoutCrop crop) {
		LayoutCrop safeCrop = crop == null ? LayoutCrop.FULL : crop;
		double cropWidth = safeCrop.width();
		double cropHeight = safeCrop.height();
		if (cropWidth <= 0 || cropHeight <= 0) {
			return new double[] {cx, cy, 0};
		}
		double projectedX = (cx - safeCrop.x()) / cropWidth;
		double projectedY = (cy - safeCrop.y()) / cropHeight;
		boolean inside = projectedX >= -1e-6 && projectedX <= 1 + 1e-6
				&& projectedY >= -1e-6 && projectedY <= 1 + 1e-6;
		return new double[] {projectedX, projectedY, inside ? 1 : 0};
	}

	/** {@code getCursorVisualState}: click bounce + click effect progress. */
	static VisualState visualState(List<CursorSample> samples, double timeMs,
			double clickBounceDuration, double clickEffectDurationMs) {
		CursorSample latestClick = findLatestInteractionSample(samples, timeMs);
		String interactionType = latestClick == null ? null : latestClick.interactionType();
		double ageMs = latestClick == null
				? Double.POSITIVE_INFINITY
				: Math.max(0, timeMs - latestClick.timeSeconds() * 1000);
		double clickEffectDelayMs = clickBounceDuration * 0.5;
		double clickEffectAgeMs = ageMs - clickEffectDelayMs;
		boolean isClickEvent = "click".equals(interactionType)
				|| "double-click".equals(interactionType) || "right-click".equals(interactionType)
				|| "middle-click".equals(interactionType);
		double bounceProgress = latestClick != null && isClickEvent && ageMs <= clickBounceDuration
				? 1 - ageMs / clickBounceDuration
				: 0;
		double progress = latestClick != null && isClickEvent && clickEffectAgeMs >= 0
				&& clickEffectAgeMs <= clickEffectDurationMs
				? 1 - clickEffectAgeMs / clickEffectDurationMs
				: 0;
		return new VisualState(findLatestStableCursorType(samples, timeMs),
				latestClick != null && isClickEvent ? latestClick : null, bounceProgress, progress);
	}

	private static @Nullable CursorSample findLatestInteractionSample(List<CursorSample> samples,
			double timeMs) {
		for (int index = samples.size() - 1; index >= 0; index -= 1) {
			CursorSample sample = samples.get(index);
			if (sample.timeSeconds() * 1000 > timeMs) {
				continue;
			}
			String type = sample.interactionType();
			if ("click".equals(type) || "double-click".equals(type) || "right-click".equals(type)
					|| "middle-click".equals(type)) {
				return sample;
			}
		}
		return null;
	}

	static String findLatestStableCursorType(List<CursorSample> samples, double timeMs) {
		int lo = 0;
		int hi = samples.size() - 1;
		while (lo < hi) {
			int mid = (int) Math.ceil((lo + hi) / 2.0);
			if (samples.get(mid).timeSeconds() * 1000 <= timeMs) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		for (int index = lo; index >= 0; index -= 1) {
			CursorSample sample = samples.get(index);
			if (sample.timeSeconds() * 1000 > timeMs) {
				continue;
			}
			if (sample.cursorType() == null) {
				continue;
			}
			String type = sample.interactionType();
			if ("click".equals(type) || "double-click".equals(type) || "right-click".equals(type)
					|| "middle-click".equals(type)) {
				continue;
			}
			return sample.cursorType();
		}
		return "arrow";
	}

	/** {@code computeCursorSwayRotation}. */
	static double computeSwayRotation(double dx, double dy, double deltaMs, @Nullable double sway) {
		if (sway <= 0) {
			return 0;
		}
		double distance = Math.hypot(dx, dy);
		if (!Double.isFinite(distance) || distance < 0.01) {
			return 0;
		}
		double seconds = SpringSmoothing.clampDeltaMs(deltaMs) / 1000;
		double speedFactor = Math.max(0,
				Math.min(1, distance / seconds / CURSOR_SWAY_SPEED_REFERENCE));
		if (speedFactor <= 0) {
			return 0;
		}
		double bias = Math.max(-1,
				Math.min(1, (dx + dy * CURSOR_SWAY_VERTICAL_WEIGHT) / distance));
		return bias * speedFactor * CURSOR_SWAY_MAX_ROTATION * sway
				* CURSOR_SWAY_INTENSITY_SCALE;
	}

	private static SpringSmoothing.SpringConfig swaySpringConfig(double smoothingFactor,
			SpringSmoothing.CursorSpringTuning tuning) {
		double mapped = Math.min(2, Math.max(0.15,
				smoothingFactor * CURSOR_SWAY_SMOOTHING_MULTIPLIER + CURSOR_SWAY_SMOOTHING_OFFSET));
		SpringSmoothing.SpringConfig base = SpringSmoothing.getCursorSpringConfig(mapped, tuning);
		return new SpringSmoothing.SpringConfig(base.stiffness(), base.damping() * 0.9,
				Math.max(0.55, base.mass() * 0.8), 0.0005, 0.02);
	}

	private static void drawClickEffect(Canvas canvas, CursorSpec spec, double px, double py,
			double cursorSize, double progress) {
		int color = spec.clickColorArgb();
		double reveal = 1 - progress;
		double alpha = progress * spec.clickEffectOpacity();
		double baseRadius = Math.max(12, cursorSize * 0.55 * spec.clickEffectScale());
		double strokeWidth = Math.max(2, cursorSize * 0.08);
		try (Paint stroke = new Paint(); Paint fill = new Paint()) {
			stroke.setMode(io.github.humbleui.skija.PaintMode.STROKE);
			fill.setMode(io.github.humbleui.skija.PaintMode.FILL);
			if (spec.clickEffect() == CursorClickEffect.RIPPLE) {
				double eased = 1 - Math.pow(progress, 3);
				double fade = Math.pow(progress, 3);
				double radius = Math.max(0.5,
						eased * cursorSize * 1.95 * spec.clickEffectScale());
				double rippleAlpha = Math.max(0, Math.min(1, fade * 0.6 * spec.clickEffectOpacity()));
				stroke.setColor(withAlpha(color, rippleAlpha));
				stroke.setStrokeWidth((float) Math.max(1, 2 * fade));
				canvas.drawCircle((float) px, (float) py, (float) radius, stroke);
				return;
			}
			if (spec.clickEffect() == CursorClickEffect.SPOTLIGHT) {
				double glowRadius = baseRadius + reveal * cursorSize * spec.clickEffectScale();
				double innerRadius = Math.max(baseRadius * 0.72, glowRadius * 0.76);
				stroke.setColor(withAlpha(color, alpha * 0.28));
				stroke.setStrokeWidth((float) Math.max(1.25, strokeWidth * 0.68));
				canvas.drawCircle((float) px, (float) py, (float) glowRadius, stroke);
				stroke.setColor(withAlpha(color, alpha * 0.5));
				stroke.setStrokeWidth((float) Math.max(1.5, strokeWidth * 0.75));
				canvas.drawCircle((float) px, (float) py, (float) innerRadius, stroke);
				return;
			}
			double outerRadius = baseRadius + reveal * cursorSize * 1.22 * spec.clickEffectScale();
			double innerRadius = Math.max(baseRadius * 0.58, outerRadius * 0.62);
			stroke.setColor(withAlpha(color, alpha * 0.72));
			stroke.setStrokeWidth((float) strokeWidth);
			canvas.drawCircle((float) px, (float) py, (float) outerRadius, stroke);
			stroke.setColor(withAlpha(color, alpha * 0.42));
			stroke.setStrokeWidth((float) Math.max(1.4, strokeWidth * 0.72));
			canvas.drawCircle((float) px, (float) py, (float) innerRadius, stroke);
			fill.setColor(withAlpha(color, alpha * 0.14));
			canvas.drawCircle((float) px, (float) py, (float) Math.max(3, baseRadius * 0.18), fill);
		}
	}

	private static int withAlpha(int argb, double alpha) {
		int clamped = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
		return (clamped << 24) | (argb & 0x00FFFFFF);
	}

	/**
	 * Draws one cursor sprite anchored at its hotspot (sprite {@code height = scaledH * bounce},
	 * {@code width = height * aspectRatio}); the shadow pass tints it black at
	 * {@code CURSOR_SHADOW_ALPHA} and blurs it by {@code CURSOR_SHADOW_BLUR}.
	 */
	private static void drawSprite(Canvas canvas, CursorAssets.@Nullable Asset asset, double drawH,
			boolean shadow) {
		Image image = asset.image();
		float height = (float) drawH;
		float width = (float) (drawH * asset.aspectRatio());
		Rect source = Rect.makeWH(image.getWidth(), image.getHeight());
		Rect destination = Rect.makeXYWH((float) (-asset.anchorX() * width),
				(float) (-asset.anchorY() * height), width, height);
		try (Paint paint = new Paint()) {
			if (shadow) {
				try (ColorFilter tint = ColorFilter.makeBlend(0xFF000000, BlendMode.SRC_IN);
						ImageFilter blur = ImageFilter.makeBlur(CURSOR_SHADOW_BLUR_SIGMA,
								CURSOR_SHADOW_BLUR_SIGMA, FilterTileMode.DECAL)) {
					paint.setColorFilter(tint);
					paint.setImageFilter(blur);
					paint.setAlphaf((float) CURSOR_SHADOW_ALPHA);
					canvas.drawImageRect(image, source, destination, SPRITE_SAMPLING, paint, true);
				}
				return;
			}
			paint.setAlphaf((float) DOT_ALPHA);
			canvas.drawImageRect(image, source, destination, SPRITE_SAMPLING, paint, true);
		}
	}
}
