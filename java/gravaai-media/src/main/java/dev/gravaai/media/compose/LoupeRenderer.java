package dev.gravaai.media.compose;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * The glass loupe ("Magnifying Glass"): a circular lens that magnifies the rendered scene around
 * the cursor. The compositor calls it after the annotations with a snapshot of the frame, so
 * blurred regions stay blurred under the lens and the cursor/webcam are magnified like any other
 * screen content. The look follows the reference lens: soft drop shadow, bright rim, inner glass
 * shading and a specular highlight.
 */
final class LoupeRenderer {

	/** Drop shadow alpha (0x59 ≈ 35%). */
	private static final int SHADOW_ALPHA = 0x59;

	/** Drop shadow sigma as a fraction of the radius, scaled by the output size. */
	private static final float SHADOW_SIGMA_RADIUS_FRACTION = 0.05f;

	/** Drop shadow vertical offset as a fraction of the radius. */
	private static final float SHADOW_OFFSET_Y = 0.06f;

	/** The bright glass rim colour (85% white) and its width at the 1920x1080 reference. */
	private static final int RIM_COLOUR = 0xD9FFFFFF;
	private static final double RIM_WIDTH_REFERENCE = 2.5;

	private LoupeRenderer() {
	}

	/**
	 * Draws one loupe lens centred at ({@code cx}, {@code cy}) with the given radius, magnifying
	 * {@code scene} (a snapshot of the rendered frame, same pixel space as the canvas) by
	 * {@code magnification}.
	 */
	static void draw(Canvas canvas, Image scene, double cx, double cy, double radius,
			double magnification, double scaleFactor) {
		if (scene == null || radius <= 0.5 || magnification <= 1) {
			return;
		}
		float r = (float) radius;
		drawShadow(canvas, cx, cy, r, scaleFactor);
		drawMagnifiedScene(canvas, scene, cx, cy, r, magnification);
		drawRim(canvas, cx, cy, r, scaleFactor);
	}

	private static void drawShadow(Canvas canvas, double cx, double cy, float r,
			double scaleFactor) {
		float sigma = Math.max(2f, r * SHADOW_SIGMA_RADIUS_FRACTION * (float) scaleFactor);
		try (Paint shadow = new Paint()) {
			shadow.setAntiAlias(true);
			shadow.setColor(SHADOW_ALPHA << 24);
			shadow.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma));
			canvas.drawCircle((float) cx, (float) (cy + r * SHADOW_OFFSET_Y), r, shadow);
		}
	}

	/** The magnified scene fill plus the inner glass shading, clipped to the lens circle. */
	private static void drawMagnifiedScene(Canvas canvas, Image scene, double cx, double cy,
			float r, double magnification) {
		// The square of side 2r/magnification around the cursor fills the lens; clamped to the
		// snapshot bounds so a lens near the frame edge stretches instead of sampling outside.
		double srcSide = (2 * r) / magnification;
		float left = (float) Math.max(0, cx - srcSide / 2);
		float top = (float) Math.max(0, cy - srcSide / 2);
		float right = (float) Math.min(scene.getWidth(), cx + srcSide / 2);
		float bottom = (float) Math.min(scene.getHeight(), cy + srcSide / 2);
		if (right <= left || bottom <= top) {
			return;
		}
		Rect lens = Rect.makeLTRB((float) cx - r, (float) cy - r, (float) cx + r, (float) cy + r);
		try (Path circle = Path.makeOval(lens)) {
			canvas.save();
			canvas.clipPath(circle, true);
			try {
				canvas.drawImageRect(scene, Rect.makeLTRB(left, top, right, bottom), lens,
						SamplingMode.LINEAR, null, true);
				drawGlassShading(canvas, cx, cy, r);
			} finally {
				canvas.restore();
			}
		}
	}

	/**
	 * Inner glass shading: a radial fill that is transparent over the content, darkens just
	 * inside the edge (curvature) and lights up at the very rim, plus a soft specular wash at
	 * the top-left.
	 */
	private static void drawGlassShading(Canvas canvas, double cx, double cy, float r) {
		int[] edgeColours = {0x00000000, 0x00000000, 0x12000000, 0x2EFFFFFF};
		float[] edgePositions = {0f, 0.84f, 0.96f, 1f};
		try (Shader shader = Shader.makeRadialGradient((float) cx, (float) cy, r,
				edgeColours, edgePositions); Paint paint = new Paint()) {
			paint.setAntiAlias(true);
			paint.setShader(shader);
			canvas.drawCircle((float) cx, (float) cy, r, paint);
		}
		float specularX = (float) (cx - r * 0.38);
		float specularY = (float) (cy - r * 0.45);
		float specularR = r * 0.55f;
		int[] specularColours = {0x2EFFFFFF, 0x00FFFFFF};
		float[] specularPositions = {0f, 1f};
		try (Shader shader = Shader.makeRadialGradient(specularX, specularY, specularR,
				specularColours, specularPositions); Paint paint = new Paint()) {
			paint.setAntiAlias(true);
			paint.setShader(shader);
			canvas.drawCircle(specularX, specularY, specularR, paint);
		}
	}

	private static void drawRim(Canvas canvas, double cx, double cy, float r,
			double scaleFactor) {
		float rimWidth = (float) Math.max(1.5, RIM_WIDTH_REFERENCE * scaleFactor);
		try (Paint rim = new Paint()) {
			rim.setAntiAlias(true);
			rim.setMode(PaintMode.STROKE);
			rim.setStrokeWidth(rimWidth);
			rim.setColor(RIM_COLOUR);
			canvas.drawCircle((float) cx, (float) cy, r - rimWidth / 2, rim);
		}
	}
}
