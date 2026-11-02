package dev.gravaai.media.compose;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * The glass loupe ("Magnifying Glass"): a circular lens that magnifies the rendered scene around
 * the cursor. The compositor calls it after the annotations with a snapshot of the frame, so
 * blurred regions stay blurred under the lens and the cursor/webcam are magnified like any other
 * screen content. The look follows the reference lens: soft drop shadow, bright rim with a faint
 * chromatic fringe, inner glass shading and a specular highlight.
 *
 * <p>Every layer is a shader-filled, anti-aliased circle rather than a clip: GL targets without
 * MSAA rasterize clips hard, which would leave a jagged lens edge.</p>
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

	/** The chromatic fringe just inside the rim: a cool edge top-left, a warm edge bottom-right. */
	private static final int FRINGE_COOL = 0x5560C8FF;
	private static final int FRINGE_WARM = 0x55FF9A3C;

	/** How long the lens takes to grow in at a region start and to shrink out at its end. */
	static final double GROW_IN_MS = 220;
	static final double SHRINK_OUT_MS = 180;

	/** The lens radius at the very start of the grow-in, as a fraction of its full radius. */
	private static final double START_RADIUS_FRACTION = 0.55;

	private LoupeRenderer() {
	}

	/**
	 * How present a loupe is at {@code timeMs} inside its {@code [startMs, endMs]} region: eases
	 * 0→1 over {@link #GROW_IN_MS} after the start and 1→0 over {@link #SHRINK_OUT_MS} before the
	 * end (both shortened for a very short region), so the lens never pops in or out.
	 */
	static double presence(double timeMs, double startMs, double endMs) {
		double duration = endMs - startMs;
		if (duration <= 0 || timeMs < startMs || timeMs > endMs) {
			return 0;
		}
		double in = Math.min(GROW_IN_MS, duration / 2);
		double out = Math.min(SHRINK_OUT_MS, duration / 2);
		double grow = in <= 0 ? 1 : Math.min(1, (timeMs - startMs) / in);
		double shrink = out <= 0 ? 1 : Math.min(1, (endMs - timeMs) / out);
		return easeOutCubic(Math.min(grow, shrink));
	}

	private static double easeOutCubic(double t) {
		double inverse = 1 - Math.max(0, Math.min(1, t));
		return 1 - inverse * inverse * inverse;
	}

	/**
	 * Draws one loupe lens magnifying {@code scene} (a snapshot of the rendered frame, same pixel
	 * space as the canvas) by {@code magnification} around the focus ({@code fx}, {@code fy}).
	 * The lens is centred on the focus but kept fully inside the frame: near an edge it slides
	 * inward and the focus sits off-centre in the glass instead of the lens being cut off.
	 * {@code presence} (0..1, see {@link #presence}) grows the lens from a smaller, unmagnified,
	 * transparent glass to its full size.
	 */
	static void draw(Canvas canvas, Image scene, double fx, double fy, double radius,
			double magnification, double scaleFactor, double presence) {
		double amount = Math.max(0, Math.min(1, presence));
		if (scene == null || radius <= 0.5 || magnification <= 1 || amount <= 0) {
			return;
		}
		double inset = radius + Math.max(2, RIM_WIDTH_REFERENCE * scaleFactor);
		double cx = clampInside(fx, inset, scene.getWidth());
		double cy = clampInside(fy, inset, scene.getHeight());
		float r = (float) (radius * (START_RADIUS_FRACTION + (1 - START_RADIUS_FRACTION) * amount));
		double zoom = 1 + (magnification - 1) * amount;
		boolean layered = amount < 1;
		if (layered) {
			float pad = r * 0.5f;
			canvas.saveLayerAlpha(Rect.makeLTRB((float) cx - r - pad, (float) cy - r - pad,
					(float) cx + r + pad, (float) cy + r + pad), (int) Math.round(amount * 255));
		}
		try {
			drawShadow(canvas, cx, cy, r, scaleFactor);
			drawMagnifiedScene(canvas, scene, fx, fy, cx, cy, r, zoom);
			drawGlassShading(canvas, cx, cy, r);
			drawRim(canvas, cx, cy, r, scaleFactor);
		} finally {
			if (layered) {
				canvas.restore();
			}
		}
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

	/**
	 * The magnified scene filled into the lens circle: the snapshot scaled by {@code zoom} about
	 * the focus. The focus is shown where it really is relative to the lens (pulled just inside
	 * the glass when the lens had to slide away from a frame edge), so the lens stays continuous
	 * as it slides. Beyond the snapshot the shader clamps the outermost pixels instead of
	 * stretching a sample square, so the magnification stays undistorted.
	 */
	private static void drawMagnifiedScene(Canvas canvas, Image scene, double fx, double fy,
			double cx, double cy, float r, double zoom) {
		double dx = fx - cx;
		double dy = fy - cy;
		double distance = Math.hypot(dx, dy);
		double reach = r * 0.85;
		double shown = distance > reach ? reach / distance : 1;
		double px = cx + dx * shown;
		double py = cy + dy * shown;
		float scale = (float) zoom;
		Matrix33 sceneToLens = new Matrix33(scale, 0, (float) (px - zoom * fx),
				0, scale, (float) (py - zoom * fy), 0, 0, 1);
		try (Shader shader = scene.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				SamplingMode.LINEAR, sceneToLens); Paint paint = new Paint()) {
			paint.setAntiAlias(true);
			paint.setShader(shader);
			canvas.drawCircle((float) cx, (float) cy, r, paint);
		}
	}

	/** Clamps a lens centre so a lens of {@code inset} half-size stays inside {@code [0, size]}. */
	private static double clampInside(double value, double inset, double size) {
		if (2 * inset >= size) {
			return size / 2;
		}
		return Math.min(size - inset, Math.max(inset, value));
	}

	/**
	 * Inner glass shading: a radial fill that is transparent over the content, darkens just
	 * inside the edge (curvature) and lights up at the very rim, a soft specular wash at the
	 * top-left, and a faint chromatic fringe where the glass bends the light most.
	 */
	private static void drawGlassShading(Canvas canvas, double cx, double cy, float r) {
		int[] edgeColours = {0x00000000, 0x00000000, 0x14000000, 0x33FFFFFF};
		float[] edgePositions = {0f, 0.82f, 0.95f, 1f};
		fillLens(canvas, cx, cy, r, Shader.makeRadialGradient((float) cx, (float) cy, r,
				edgeColours, edgePositions));
		// The specular gradient fades out well inside its own radius, so filling the lens
		// circle with it keeps the highlight inside the glass.
		float specularX = (float) (cx - r * 0.38);
		float specularY = (float) (cy - r * 0.45);
		fillLens(canvas, cx, cy, r, Shader.makeRadialGradient(specularX, specularY, r * 0.6f,
				new int[] {0x30FFFFFF, 0x10FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.45f, 1f}));
		drawFringe(canvas, cx - r * 0.012, cy - r * 0.012, r, FRINGE_COOL);
		drawFringe(canvas, cx + r * 0.012, cy + r * 0.012, r, FRINGE_WARM);
	}

	private static void fillLens(Canvas canvas, double cx, double cy, float r, Shader shader) {
		try (shader; Paint paint = new Paint()) {
			paint.setAntiAlias(true);
			paint.setShader(shader);
			canvas.drawCircle((float) cx, (float) cy, r, paint);
		}
	}

	/** One soft coloured ring hugging the inside of the rim, offset to fake dispersion. */
	private static void drawFringe(Canvas canvas, double cx, double cy, float r, int colour) {
		float width = Math.max(1f, r * 0.02f);
		try (Paint fringe = new Paint()) {
			fringe.setAntiAlias(true);
			fringe.setMode(PaintMode.STROKE);
			fringe.setStrokeWidth(width);
			fringe.setColor(colour);
			fringe.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL, width * 0.6f));
			canvas.drawCircle((float) cx, (float) cy, r - width * 1.5f, fringe);
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
