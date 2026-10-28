package dev.gravaai.services.extensions.canvas;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.HostAccess;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * A Skija-backed {@code CanvasRenderingContext2D} for extension render hooks (plan §5.18: "a
 * Canvas 2D-compatible object implemented over Skija").
 *
 * <p>Implements exactly the subset {@code EXTENSIONS.md} documents and the plan lists: paths,
 * fill/stroke, transforms, gradients, text, {@code drawImage}, {@code globalAlpha}, composite
 * operations and shadows. Drawing state follows the HTML canvas model: {@code save}/{@code
 * restore} push and pop style/transform state, and the current path survives them. The underlying
 * Skija {@link Canvas} belongs to the caller (a raster surface in tests, the compositor's GPU
 * surface in preview/export); this object only draws into it and closes its own path builder.
 */
public final class Canvas2D implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(Canvas2D.class.getName());

	public static final String DEFAULT_FONT = "10px sans-serif";

	private static final Pattern FONT = Pattern.compile(
			"(?i)^\\s*(?:(italic|oblique)\\s+)?(?:(bold|[0-9]{3})\\s+)?([0-9]*\\.?[0-9]+)px(?:/[^\\s]+)?\\s+(.+?)\\s*$");

	/** System typefaces resolved by family/style; Skia owns them, so they stay cached. */
	private static final Map<String, Typeface> TYPEFACES = new ConcurrentHashMap<>();

	private final Canvas canvas;
	private final double width;
	private final double height;
	private final Deque<Snapshot> savedStates = new ArrayDeque<>();

	private PathBuilder path;
	private double lastX;
	private double lastY;
	private double subpathStartX;
	private double subpathStartY;
	private boolean contourOpen;

	private int fillColor = 0xFF000000;
	private CanvasGradient fillGradient;
	private int strokeColor = 0xFF000000;
	private CanvasGradient strokeGradient;
	private double lineWidth = 1.0;
	private double globalAlpha = 1.0;
	private BlendMode composite = BlendMode.SRC_OVER;
	private int shadowColor = 0x00000000;
	private double shadowBlur;
	private double shadowOffsetX;
	private double shadowOffsetY;
	private FontSpec font = FontSpec.DEFAULT;
	private String textAlign = "start";
	private String textBaseline = "alphabetic";
	private PaintStrokeCap lineCap = PaintStrokeCap.BUTT;
	private PaintStrokeJoin lineJoin = PaintStrokeJoin.MITER;
	private double miterLimit = 10.0;

	private boolean closed;

	public Canvas2D(Canvas canvas, double width, double height) {
		this.canvas = canvas;
		this.width = width;
		this.height = height;
	}

	// ------------------------------------------------------------------------
	// Drawing state
	// ------------------------------------------------------------------------

	@HostAccess.Export
	public Object getFillStyle() {
		return fillGradient != null ? fillGradient : CanvasColor.toCss(fillColor);
	}

	@HostAccess.Export
	public void setFillStyle(Object value) {
		Style style = style(value);
		if (style != null) {
			fillColor = style.color();
			fillGradient = style.gradient();
		}
	}

	@HostAccess.Export
	public Object getStrokeStyle() {
		return strokeGradient != null ? strokeGradient : CanvasColor.toCss(strokeColor);
	}

	@HostAccess.Export
	public void setStrokeStyle(Object value) {
		Style style = style(value);
		if (style != null) {
			strokeColor = style.color();
			strokeGradient = style.gradient();
		}
	}

	@HostAccess.Export
	public double getLineWidth() {
		return lineWidth;
	}

	@HostAccess.Export
	public void setLineWidth(double value) {
		if (Double.isFinite(value) && value > 0) {
			lineWidth = value;
		}
	}

	@HostAccess.Export
	public double getGlobalAlpha() {
		return globalAlpha;
	}

	@HostAccess.Export
	public void setGlobalAlpha(double value) {
		if (Double.isFinite(value) && value >= 0 && value <= 1) {
			globalAlpha = value;
		}
	}

	@HostAccess.Export
	public String getGlobalCompositeOperation() {
		return CompositeOperation.toCss(composite);
	}

	@HostAccess.Export
	public void setGlobalCompositeOperation(String value) {
		BlendMode mode = CompositeOperation.toBlendMode(value);
		if (mode != null) {
			composite = mode;
		}
	}

	@HostAccess.Export
	public String getShadowColor() {
		return CanvasColor.toCss(shadowColor);
	}

	@HostAccess.Export
	public void setShadowColor(String value) {
		Integer parsed = CanvasColor.parse(value);
		if (parsed != null) {
			shadowColor = parsed;
		}
	}

	@HostAccess.Export
	public double getShadowBlur() {
		return shadowBlur;
	}

	@HostAccess.Export
	public void setShadowBlur(double value) {
		if (Double.isFinite(value) && value >= 0) {
			shadowBlur = value;
		}
	}

	@HostAccess.Export
	public double getShadowOffsetX() {
		return shadowOffsetX;
	}

	@HostAccess.Export
	public void setShadowOffsetX(double value) {
		if (Double.isFinite(value)) {
			shadowOffsetX = value;
		}
	}

	@HostAccess.Export
	public double getShadowOffsetY() {
		return shadowOffsetY;
	}

	@HostAccess.Export
	public void setShadowOffsetY(double value) {
		if (Double.isFinite(value)) {
			shadowOffsetY = value;
		}
	}

	@HostAccess.Export
	public String getFont() {
		return font.css();
	}

	@HostAccess.Export
	public void setFont(String value) {
		FontSpec parsed = FontSpec.parse(value);
		if (parsed != null) {
			font = parsed;
		}
	}

	@HostAccess.Export
	public String getTextAlign() {
		return textAlign;
	}

	@HostAccess.Export
	public void setTextAlign(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("start") || normalized.equals("end") || normalized.equals("left")
				|| normalized.equals("right") || normalized.equals("center")) {
			textAlign = normalized;
		}
	}

	@HostAccess.Export
	public String getTextBaseline() {
		return textBaseline;
	}

	@HostAccess.Export
	public void setTextBaseline(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("alphabetic") || normalized.equals("top") || normalized.equals("middle")
				|| normalized.equals("bottom") || normalized.equals("hanging")
				|| normalized.equals("ideographic")) {
			textBaseline = normalized;
		}
	}

	@HostAccess.Export
	public String getLineCap() {
		return switch (lineCap) {
			case BUTT -> "butt";
			case ROUND -> "round";
			case SQUARE -> "square";
		};
	}

	@HostAccess.Export
	public void setLineCap(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		switch (normalized) {
			case "butt" -> lineCap = PaintStrokeCap.BUTT;
			case "round" -> lineCap = PaintStrokeCap.ROUND;
			case "square" -> lineCap = PaintStrokeCap.SQUARE;
			default -> {
				// Invalid values are ignored, like a browser.
			}
		}
	}

	@HostAccess.Export
	public String getLineJoin() {
		return switch (lineJoin) {
			case MITER -> "miter";
			case ROUND -> "round";
			case BEVEL -> "bevel";
		};
	}

	@HostAccess.Export
	public void setLineJoin(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		switch (normalized) {
			case "miter" -> lineJoin = PaintStrokeJoin.MITER;
			case "round" -> lineJoin = PaintStrokeJoin.ROUND;
			case "bevel" -> lineJoin = PaintStrokeJoin.BEVEL;
			default -> {
				// Invalid values are ignored, like a browser.
			}
		}
	}

	@HostAccess.Export
	public double getMiterLimit() {
		return miterLimit;
	}

	@HostAccess.Export
	public void setMiterLimit(double value) {
		if (Double.isFinite(value) && value > 0) {
			miterLimit = value;
		}
	}

	// ------------------------------------------------------------------------
	// State stack and transforms
	// ------------------------------------------------------------------------

	@HostAccess.Export
	public void save() {
		canvas.save();
		savedStates.push(snapshot());
	}

	@HostAccess.Export
	public void restore() {
		Snapshot snapshot = savedStates.poll();
		if (snapshot == null) {
			return;
		}
		apply(snapshot);
		canvas.restore();
	}

	@HostAccess.Export
	public void translate(double x, double y) {
		canvas.translate((float) x, (float) y);
	}

	@HostAccess.Export
	public void rotate(double angleRadians) {
		canvas.rotate((float) Math.toDegrees(angleRadians));
	}

	@HostAccess.Export
	public void scale(double x, double y) {
		canvas.scale((float) x, (float) y);
	}

	@HostAccess.Export
	public void transform(double a, double b, double c, double d, double e, double f) {
		canvas.concat(matrix(a, b, c, d, e, f));
	}

	@HostAccess.Export
	public void setTransform(double a, double b, double c, double d, double e, double f) {
		canvas.setMatrix(matrix(a, b, c, d, e, f));
	}

	@HostAccess.Export
	public void resetTransform() {
		canvas.resetMatrix();
	}

	// ------------------------------------------------------------------------
	// Paths
	// ------------------------------------------------------------------------

	@HostAccess.Export
	public void beginPath() {
		closePathBuilder();
		path = new PathBuilder();
		contourOpen = false;
		lastX = 0;
		lastY = 0;
	}

	@HostAccess.Export
	public void closePath() {
		if (path == null || !contourOpen) {
			return;
		}
		path.closePath();
		contourOpen = false;
		lastX = subpathStartX;
		lastY = subpathStartY;
	}

	@HostAccess.Export
	public void moveTo(double x, double y) {
		if (!finite(x, y)) {
			return;
		}
		builder().moveTo((float) x, (float) y);
		lastX = x;
		lastY = y;
		subpathStartX = x;
		subpathStartY = y;
		contourOpen = true;
	}

	@HostAccess.Export
	public void lineTo(double x, double y) {
		if (!finite(x, y)) {
			return;
		}
		if (!contourOpen) {
			moveTo(x, y);
			return;
		}
		builder().lineTo((float) x, (float) y);
		lastX = x;
		lastY = y;
	}

	@HostAccess.Export
	public void quadraticCurveTo(double cpx, double cpy, double x, double y) {
		if (!finite(cpx, cpy, x, y)) {
			return;
		}
		if (!contourOpen) {
			moveTo(cpx, cpy);
		}
		builder().quadTo((float) cpx, (float) cpy, (float) x, (float) y);
		lastX = x;
		lastY = y;
	}

	@HostAccess.Export
	public void bezierCurveTo(double cp1x, double cp1y, double cp2x, double cp2y, double x, double y) {
		if (!finite(cp1x, cp1y, cp2x, cp2y, x, y)) {
			return;
		}
		if (!contourOpen) {
			moveTo(cp1x, cp1y);
		}
		builder().cubicTo((float) cp1x, (float) cp1y, (float) cp2x, (float) cp2y, (float) x, (float) y);
		lastX = x;
		lastY = y;
	}

	/** {@code arc(cx, cy, r, startAngle, endAngle)} in radians, clockwise. */
	@HostAccess.Export
	public void arc(double cx, double cy, double radius, double startAngle, double endAngle) {
		arc(cx, cy, radius, startAngle, endAngle, false);
	}

	/** Port of {@code CanvasRenderingContext2D.arc}; angles are radians. */
	@HostAccess.Export
	public void arc(
			double cx, double cy, double radius, double startAngle, double endAngle, boolean anticlockwise) {
		if (!finite(cx, cy, radius, startAngle, endAngle) || radius < 0) {
			return;
		}
		double startX = cx + radius * Math.cos(startAngle);
		double startY = cy + radius * Math.sin(startAngle);
		if (contourOpen) {
			lineTo(startX, startY);
		} else {
			moveTo(startX, startY);
		}
		double startDegrees = Math.toDegrees(startAngle);
		double sweepDegrees = Math.toDegrees(endAngle - startAngle);
		if (anticlockwise) {
			while (sweepDegrees > 0) {
				sweepDegrees -= 360.0;
			}
			if (sweepDegrees < -360.0) {
				sweepDegrees = -360.0;
			}
		} else {
			while (sweepDegrees < 0) {
				sweepDegrees += 360.0;
			}
			if (sweepDegrees > 360.0) {
				sweepDegrees = 360.0;
			}
		}
		if (sweepDegrees == 0) {
			return;
		}
		builder().addArc(
				Rect.makeXYWH((float) (cx - radius), (float) (cy - radius),
						(float) (radius * 2), (float) (radius * 2)),
				(float) startDegrees,
				(float) sweepDegrees);
		double endX = cx + radius * Math.cos(endAngle);
		double endY = cy + radius * Math.sin(endAngle);
		lastX = endX;
		lastY = endY;
		contourOpen = true;
	}

	@HostAccess.Export
	public void rect(double x, double y, double w, double h) {
		if (!finite(x, y, w, h)) {
			return;
		}
		builder().addRect(Rect.makeXYWH((float) x, (float) y, (float) w, (float) h));
		lastX = x;
		lastY = y;
		subpathStartX = x;
		subpathStartY = y;
		contourOpen = false;
	}

	@HostAccess.Export
	public void fill() {
		Path snapshot = snapshotPath();
		if (snapshot == null) {
			return;
		}
		try (snapshot; PaintResources resources = paint(fillStyle(), false)) {
			canvas.drawPath(snapshot, resources.paint());
		}
	}

	@HostAccess.Export
	public void stroke() {
		Path snapshot = snapshotPath();
		if (snapshot == null) {
			return;
		}
		try (snapshot; PaintResources resources = paint(strokeStyle(), true)) {
			canvas.drawPath(snapshot, resources.paint());
		}
	}

	@HostAccess.Export
	public void clip() {
		Path snapshot = snapshotPath();
		if (snapshot == null) {
			return;
		}
		try (snapshot) {
			canvas.clipPath(snapshot);
		}
	}

	// ------------------------------------------------------------------------
	// Rectangles, text and images
	// ------------------------------------------------------------------------

	@HostAccess.Export
	public void fillRect(double x, double y, double w, double h) {
		drawRect(x, y, w, h, fillStyle(), false);
	}

	@HostAccess.Export
	public void strokeRect(double x, double y, double w, double h) {
		drawRect(x, y, w, h, strokeStyle(), true);
	}

	@HostAccess.Export
	public void clearRect(double x, double y, double w, double h) {
		if (!finite(x, y, w, h)) {
			return;
		}
		canvas.save();
		try {
			canvas.clipRect(Rect.makeXYWH((float) x, (float) y, (float) w, (float) h));
			canvas.clear(0);
		} finally {
			canvas.restore();
		}
	}

	@HostAccess.Export
	public void fillText(String text, double x, double y) {
		drawText(text, x, y, false);
	}

	@HostAccess.Export
	public void strokeText(String text, double x, double y) {
		drawText(text, x, y, true);
	}

	@HostAccess.Export
	public void drawImage(CanvasImage image, double dx, double dy) {
		drawImageRect(image, 0, 0, image.getWidth(), image.getHeight(), dx, dy, image.getWidth(), image.getHeight());
	}

	@HostAccess.Export
	public void drawImage(CanvasImage image, double dx, double dy, double dw, double dh) {
		drawImageRect(image, 0, 0, image.getWidth(), image.getHeight(), dx, dy, dw, dh);
	}

	@HostAccess.Export
	public void drawImage(
			CanvasImage image,
			double sx,
			double sy,
			double sw,
			double sh,
			double dx,
			double dy,
			double dw,
			double dh) {
		drawImageRect(image, sx, sy, sw, sh, dx, dy, dw, dh);
	}

	@HostAccess.Export
	public CanvasGradient createLinearGradient(double x0, double y0, double x1, double y1) {
		return CanvasGradient.linear(x0, y0, x1, y1);
	}

	@HostAccess.Export
	public CanvasGradient createRadialGradient(
			double x0, double y0, double r0, double x1, double y1, double r1) {
		return CanvasGradient.radial(x0, y0, r0, x1, y1, r1);
	}

	@HostAccess.Export
	public double getCanvasWidth() {
		return width;
	}

	@HostAccess.Export
	public double getCanvasHeight() {
		return height;
	}

	// ------------------------------------------------------------------------
	// Internals
	// ------------------------------------------------------------------------

	private void drawRect(double x, double y, double w, double h, Style style, boolean stroke) {
		if (!finite(x, y, w, h)) {
			return;
		}
		try (PaintResources resources = paint(style, stroke)) {
			canvas.drawRect(Rect.makeXYWH((float) x, (float) y, (float) w, (float) h), resources.paint());
		}
	}

	private void drawText(String text, double x, double y, boolean stroke) {
		if (text == null || text.isEmpty() || !finite(x, y)) {
			return;
		}
		Font skFont = createFont();
		try (skFont) {
			TextLine line = TextLine.make(text, skFont);
			try (line) {
				double drawX = switch (textAlign) {
					case "right", "end" -> x - line.getWidth();
					case "center" -> x - line.getWidth() / 2.0;
					default -> x;
				};
				// Skija reports the typographic ascent as a negative offset from the baseline.
				double drawY = switch (textBaseline) {
					case "top", "hanging" -> y - line.getAscent();
					case "middle" -> y - (line.getAscent() + line.getDescent()) / 2.0;
					case "bottom", "ideographic" -> y - line.getDescent();
					default -> y;
				};
				try (PaintResources resources =
						paint(stroke ? strokeStyle() : fillStyle(), stroke)) {
					canvas.drawString(text, (float) drawX, (float) drawY, skFont, resources.paint());
				}
			}
		}
	}

	private void drawImageRect(
			CanvasImage image,
			double sx,
			double sy,
			double sw,
			double sh,
			double dx,
			double dy,
			double dw,
			double dh) {
		if (image == null || !finite(sx, sy, sw, sh, dx, dy, dw, dh)) {
			return;
		}
		Rect source = Rect.makeXYWH((float) sx, (float) sy, (float) sw, (float) sh);
		Rect destination = Rect.makeXYWH((float) dx, (float) dy, (float) dw, (float) dh);
		try (PaintResources resources = paint(fillStyle(), false)) {
			canvas.drawImageRect(image.skiaImage(), source, destination, SamplingMode.LINEAR, resources.paint(), true);
		}
	}

	private Font createFont() {
		FontStyle style = font.bold() && font.italic()
				? FontStyle.BOLD_ITALIC
				: font.bold() ? FontStyle.BOLD : font.italic() ? FontStyle.ITALIC : FontStyle.NORMAL;
		String key = font.family() + "|" + style;
		Typeface typeface = TYPEFACES.computeIfAbsent(key, ignored -> {
			Typeface matched = FontMgr.getDefault().matchFamilyStyle(font.family(), style);
			if (matched == null) {
				matched = FontMgr.getDefault().matchFamilyStyle("sans-serif", style);
			}
			return matched;
		});
		if (typeface == null) {
			Font fallback = new Font();
			fallback.setSize((float) font.size());
			return fallback;
		}
		return new Font(typeface, (float) font.size());
	}

	private Path snapshotPath() {
		if (path == null) {
			return null;
		}
		Path snapshot = path.snapshot();
		if (snapshot == null) {
			return null;
		}
		if (snapshot.isEmpty()) {
			snapshot.close();
			return null;
		}
		return snapshot;
	}

	private Style fillStyle() {
		return new Style(fillColor, fillGradient);
	}

	private Style strokeStyle() {
		return new Style(strokeColor, strokeGradient);
	}

	private PaintResources paint(Style style, boolean stroke) {
		Paint paint = new Paint();
		Shader shader = null;
		ImageFilter filter = null;
		try {
			paint.setAntiAlias(true);
			if (stroke) {
				paint.setMode(PaintMode.STROKE);
				paint.setStrokeWidth((float) lineWidth);
				paint.setStrokeCap(lineCap);
				paint.setStrokeJoin(lineJoin);
				paint.setStrokeMiter((float) miterLimit);
			} else {
				paint.setMode(PaintMode.FILL);
			}
			paint.setBlendMode(composite);
			double baseAlpha = 1.0;
			if (style.gradient() != null) {
				shader = style.gradient().shader();
			}
			if (shader != null) {
				paint.setShader(shader);
			} else {
				paint.setColor(style.color());
				baseAlpha = CanvasColor.alpha(style.color());
			}
			double effectiveAlpha = baseAlpha * globalAlpha;
			if (effectiveAlpha != 1.0) {
				paint.setAlphaf((float) effectiveAlpha);
			}
			if (shadowColor != 0 && CanvasColor.alpha(shadowColor) > 0) {
				float sigma = (float) Math.max(0, shadowBlur / 2.0);
				filter = ImageFilter.makeDropShadow(
						(float) shadowOffsetX,
						(float) shadowOffsetY,
						sigma,
						sigma,
						shadowColor);
				paint.setImageFilter(filter);
			}
			return new PaintResources(paint, shader, filter);
		} catch (RuntimeException error) {
			closeQuietly(shader);
			closeQuietly(filter);
			paint.close();
			throw error;
		}
	}

	private PathBuilder builder() {
		if (path == null) {
			path = new PathBuilder();
		}
		return path;
	}

	private void closePathBuilder() {
		if (path != null) {
			path.close();
			path = null;
		}
	}

	private Snapshot snapshot() {
		return new Snapshot(
				fillColor, fillGradient, strokeColor, strokeGradient, lineWidth, globalAlpha, composite,
				shadowColor, shadowBlur, shadowOffsetX, shadowOffsetY, font, textAlign, textBaseline,
				lineCap, lineJoin, miterLimit);
	}

	private void apply(Snapshot snapshot) {
		fillColor = snapshot.fillColor();
		fillGradient = snapshot.fillGradient();
		strokeColor = snapshot.strokeColor();
		strokeGradient = snapshot.strokeGradient();
		lineWidth = snapshot.lineWidth();
		globalAlpha = snapshot.globalAlpha();
		composite = snapshot.composite();
		shadowColor = snapshot.shadowColor();
		shadowBlur = snapshot.shadowBlur();
		shadowOffsetX = snapshot.shadowOffsetX();
		shadowOffsetY = snapshot.shadowOffsetY();
		font = snapshot.font();
		textAlign = snapshot.textAlign();
		textBaseline = snapshot.textBaseline();
		lineCap = snapshot.lineCap();
		lineJoin = snapshot.lineJoin();
		miterLimit = snapshot.miterLimit();
	}

	/** Parses a fill/stroke style value: a CSS colour string or a gradient host object. */
	private static Style style(Object value) {
		if (value instanceof CanvasGradient gradient) {
			return new Style(0x00000000, gradient);
		}
		if (value instanceof String css) {
			Integer color = CanvasColor.parse(css);
			return color == null ? null : new Style(color, null);
		}
		return null;
	}

	private static Matrix33 matrix(double a, double b, double c, double d, double e, double f) {
		// Skia matrices are row-major: [scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1].
		return new Matrix33(
				(float) a, (float) c, (float) e,
				(float) b, (float) d, (float) f,
				0f, 0f, 1f);
	}

	private static boolean finite(double... values) {
		for (double value : values) {
			if (!Double.isFinite(value)) {
				return false;
			}
		}
		return true;
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "Extension canvas close failed: " + error);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		closePathBuilder();
		savedStates.clear();
	}

	/** The paint plus the Skija resources backing it; closing releases all three. */
	private record PaintResources(Paint paint, Shader shader, ImageFilter filter) implements AutoCloseable {
		@Override
		public void close() {
			paint.close();
			closeQuietly(shader);
			closeQuietly(filter);
		}
	}

	private record Style(int color, CanvasGradient gradient) {}

	private record Snapshot(
			int fillColor,
			CanvasGradient fillGradient,
			int strokeColor,
			CanvasGradient strokeGradient,
			double lineWidth,
			double globalAlpha,
			BlendMode composite,
			int shadowColor,
			double shadowBlur,
			double shadowOffsetX,
			double shadowOffsetY,
			FontSpec font,
			String textAlign,
			String textBaseline,
			PaintStrokeCap lineCap,
			PaintStrokeJoin lineJoin,
			double miterLimit) {}

	/** A parsed CSS {@code font} shorthand: family, size and the bold/italic bits. */
	private record FontSpec(String family, double size, boolean bold, boolean italic, String css) {

		static final FontSpec DEFAULT = new FontSpec("sans-serif", 10, false, false, DEFAULT_FONT);

		static FontSpec parse(String css) {
			if (css == null) {
				return null;
			}
			Matcher matcher = FONT.matcher(css);
			if (!matcher.matches()) {
				return null;
			}
			String style = matcher.group(1);
			String weight = matcher.group(2);
			double size;
			try {
				size = Double.parseDouble(matcher.group(3));
			} catch (NumberFormatException error) {
				return null;
			}
			if (!(size > 0) || !Double.isFinite(size)) {
				return null;
			}
			boolean italic = style != null;
			boolean bold = "bold".equalsIgnoreCase(weight) || (weight != null && Integer.parseInt(weight) >= 600);
			String family = firstFamily(matcher.group(4));
			return new FontSpec(family, size, bold, italic, css.trim());
		}

		private static String firstFamily(String list) {
			String first = list.split(",")[0].trim();
			if (first.length() >= 2 && (first.startsWith("\"") && first.endsWith("\"")
					|| first.startsWith("'") && first.endsWith("'"))) {
				first = first.substring(1, first.length() - 1);
			}
			return first.isEmpty() ? "sans-serif" : first;
		}
	}
}
