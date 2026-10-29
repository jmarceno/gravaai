package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Port of {@code src/lib/exporter/annotationRenderer.ts} (plan §5.8 stage 8): text, image, figure
 * arrow and blur annotations inside the camera transform. Positions/sizes are percentages of the
 * annotation coordinate rect (the video mask rect), then transformed by the camera like the TS
 * 2D composite path; the effective text/effect scale is {@code annotationScaleFactor * zoomScale}.
 *
 * <p>Blur annotations sample already-rendered scene pixels: the compositor passes a snapshot of
 * the surface taken after stage 7, and the region is redrawn through a Skia blur image filter.
 * Known differences from the Electron reference: system fonts instead of bundled SF Pro (glyph
 * metrics differ until DM Sans/custom fonts land), and rounded boxes are drawn square.</p>
 */
final class AnnotationRenderer {

	/** Camera transform applied to annotation rects. */
	record Transform(double scale, double x, double y) {
	}

	private static final double[][] ARROW_UP = {{50, 20, 50, 80}, {50, 20, 35, 35}, {50, 20, 65, 35}};
	private static final double[][] ARROW_DOWN = {{50, 20, 50, 80}, {50, 80, 35, 65}, {50, 80, 65, 65}};
	private static final double[][] ARROW_LEFT = {{80, 50, 20, 50}, {20, 50, 35, 35}, {20, 50, 35, 65}};
	private static final double[][] ARROW_RIGHT = {{20, 50, 80, 50}, {80, 50, 65, 35}, {80, 50, 65, 65}};
	private static final double[][] ARROW_UP_RIGHT = {{25, 75, 75, 25}, {75, 25, 60, 30}, {75, 25, 70, 40}};
	private static final double[][] ARROW_UP_LEFT = {{75, 75, 25, 25}, {25, 25, 40, 30}, {25, 25, 30, 40}};
	private static final double[][] ARROW_DOWN_RIGHT = {{25, 25, 75, 75}, {75, 75, 70, 60}, {75, 75, 60, 70}};
	private static final double[][] ARROW_DOWN_LEFT = {{75, 25, 25, 75}, {25, 75, 30, 60}, {25, 75, 40, 70}};

	private AnnotationRenderer() {
	}

	static void draw(Canvas canvas, List<AnnotationSpec> annotations, long timeMs,
			int canvasWidth, int canvasHeight, Rect coordinateRect, Transform transform,
			double scaleFactor, @Nullable Image sceneSnapshot) {
		List<AnnotationSpec> active = AnnotationSpec.activeAt(annotations, timeMs);
		if (active.isEmpty()) {
			return;
		}
		Transform camera = transform;
		double effectiveScaleFactor = scaleFactor * camera.scale();
		for (AnnotationSpec annotation : active) {
			Rect untransformed = Rect.makeXYWH(
					(float) (coordinateRect._left
							+ (annotation.position().x() / 100.0) * coordinateRect.getWidth()),
					(float) (coordinateRect._top
							+ (annotation.position().y() / 100.0) * coordinateRect.getHeight()),
					(float) ((annotation.size().width() / 100.0) * coordinateRect.getWidth()),
					(float) ((annotation.size().height() / 100.0) * coordinateRect.getHeight()));
			Rect rect = new Rect((float) (untransformed._left * camera.scale() + camera.x()),
					(float) (untransformed._top * camera.scale() + camera.y()),
					(float) (untransformed.getRight() * camera.scale() + camera.x()),
					(float) (untransformed.getBottom() * camera.scale() + camera.y()));
			switch (annotation.type()) {
				case TEXT -> drawText(canvas, annotation, rect, effectiveScaleFactor);
				case IMAGE -> drawImage(canvas, annotation, rect);
				case FIGURE -> drawFigure(canvas, annotation, rect, effectiveScaleFactor);
				case BLUR -> drawBlur(canvas, annotation, rect, effectiveScaleFactor, sceneSnapshot,
						canvasWidth, canvasHeight);
			}
		}
	}

	private static void drawText(Canvas canvas, AnnotationSpec annotation, Rect rect,
			double scaleFactor) {
		AnnotationSpec.TextStyle style = annotation.style();
		Font font = resolveFont(style, (float) (style.fontSize() * scaleFactor));
		if (font == null) {
			return;
		}
		canvas.save();
		canvas.clipRect(rect);
		Paint paint = new Paint();
		try {
			paint.setAntiAlias(true);
			float containerPadding = (float) (8 * scaleFactor);
			float availableWidth = Math.max(1, rect.getWidth() - containerPadding * 2);
			List<String> lines = wrapLines(font, annotation.content(), availableWidth);
			float lineHeight = (float) (style.fontSize() * scaleFactor * 1.4);
			FontMetrics metrics = font.getMetrics();
			float textCenterY = rect._top + rect.getHeight() / 2f;
			float startY = textCenterY - ((lines.size() - 1) * lineHeight) / 2f;
			String align = style.textAlign() == null ? "center" : style.textAlign();
			for (int index = 0; index < lines.size(); index++) {
				String line = lines.get(index);
				float textWidth = font.measureTextWidth(line);
				float textX = switch (align) {
					case "left" -> rect._left + containerPadding;
					case "right" -> rect.getRight() - containerPadding - textWidth;
					default -> rect._left + rect.getWidth() / 2f - textWidth / 2f;
				};
				float baselineY = startY + index * lineHeight
						- (float) ((metrics._ascent + metrics._descent) / 2.0);
				if (style.backgroundColor() != null) {
					int background = style.backgroundColor();
					if ((background >>> 24) != 0) {
						paint.setColor(background);
						canvas.drawRect(Rect.makeXYWH(textX, baselineY + metrics._ascent,
								textWidth, lineHeight), paint);
					}
				}
				paint.setColor(style.color());
				canvas.drawString(line, textX, baselineY, font, paint);
				if (style.underline()) {
					paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
					paint.setStrokeWidth(Math.max(1f, (float) (style.fontSize() * scaleFactor / 16)));
					float underlineY = baselineY + (float) (style.fontSize() * scaleFactor * 0.15);
					canvas.drawLine(textX, underlineY, textX + textWidth, underlineY, paint);
				}
			}
		} finally {
			paint.close();
			font.close();
			canvas.restore();
		}
	}

	private static @Nullable Font resolveFont(AnnotationSpec.TextStyle style, float size) {
		// Text style weight/style maps onto the bundled DM Sans faces (reference SF Pro substitute).
		CompositorFonts.Face face = style.bold() && style.italic()
				? CompositorFonts.Face.BOLD_ITALIC
				: style.bold() ? CompositorFonts.Face.BOLD
						: style.italic() ? CompositorFonts.Face.ITALIC
								: CompositorFonts.Face.REGULAR;
		return CompositorFonts.font(face, size);
	}

	private static @Nullable String firstFamily(String fontFamily) {
		if (fontFamily == null || fontFamily.isBlank()) {
			return null;
		}
		String first = fontFamily.split(",")[0].trim();
		if (first.startsWith("\"") && first.endsWith("\"") && first.length() > 1) {
			first = first.substring(1, first.length() - 1);
		}
		return first.isBlank() ? null : first;
	}

	private static List<String> wrapLines(Font font, @Nullable String content, float availableWidth) {
		List<String> lines = new ArrayList<>();
		if (content == null) {
			return lines;
		}
		for (String rawLine : content.split("\n", -1)) {
			if (rawLine.isEmpty()) {
				lines.add("");
				continue;
			}
			StringBuilder current = new StringBuilder();
			for (String word : rawLine.split(" ", -1)) {
				String candidate = current.isEmpty() ? word : current + " " + word;
				if (!current.isEmpty() && font.measureTextWidth(candidate) > availableWidth) {
					lines.add(current.toString());
					current = new StringBuilder(word);
				} else {
					current = new StringBuilder(candidate);
				}
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
			}
		}
		return lines;
	}

	private static void drawImage(Canvas canvas, AnnotationSpec annotation, Rect rect) {
		String source = annotation.imageData() != null && !annotation.imageData().isBlank()
				? annotation.imageData()
				: annotation.content();
		byte @Nullable [] encoded = decodeDataUri(source);
		if (encoded == null) {
			return;
		}
		Image image = Image.makeDeferredFromEncodedBytes(encoded);
		if (image == null) {
			return;
		}
		try {
			double imageAspect = image.getWidth() / (double) image.getHeight();
			double boxAspect = rect.getWidth() / rect.getHeight();
			double drawWidth = rect.getWidth();
			double drawHeight = rect.getHeight();
			double drawX = rect._left;
			double drawY = rect._top;
			if (imageAspect > boxAspect) {
				drawHeight = rect.getWidth() / imageAspect;
				drawY = rect._top + (rect.getHeight() - drawHeight) / 2;
			} else {
				drawWidth = rect.getHeight() * imageAspect;
				drawX = rect._left + (rect.getWidth() - drawWidth) / 2;
			}
			canvas.drawImageRect(image,
					Rect.makeXYWH(0, 0, image.getWidth(), image.getHeight()),
					Rect.makeXYWH((float) drawX, (float) drawY, (float) drawWidth,
							(float) drawHeight),
					SamplingMode.LINEAR, null, true);
		} finally {
			image.close();
		}
	}

	private static void drawFigure(Canvas canvas, AnnotationSpec annotation, Rect rect,
			double scaleFactor) {
		AnnotationSpec.Figure figure = annotation.figure();
		if (figure == null) {
			return;
		}
		double @Nullable [][] segments = arrowSegments(figure.direction());
		if (segments == null) {
			return;
		}
		float padding = (float) (8 * scaleFactor);
		float availableWidth = Math.max(0, rect.getWidth() - padding * 2);
		float availableHeight = Math.max(0, rect.getHeight() - padding * 2);
		float scale = (float) Math.min(availableWidth / 100.0, availableHeight / 100.0);
		float offsetX = padding + (availableWidth - 100 * scale) / 2;
		float offsetY = padding + (availableHeight - 100 * scale) / 2;
		float originX = rect._left + offsetX;
		float originY = rect._top + offsetY;
		Paint paint = new Paint();
		try {
			paint.setAntiAlias(true);
			paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
			paint.setStrokeWidth((float) (figure.strokeWidth() * scale));
			paint.setStrokeCap(io.github.humbleui.skija.PaintStrokeCap.ROUND);
			// Drop shadow first, like the TS shadowColor/shadowBlur.
			paint.setColor(0x4D000000);
			for (double[] segment : segments) {
				canvas.drawLine(originX + (float) (segment[0] * scale),
						originY + (float) (segment[1] * scale) + 4 * scale,
						originX + (float) (segment[2] * scale),
						originY + (float) (segment[3] * scale) + 4 * scale, paint);
			}
			paint.setColor(figure.color());
			for (double[] segment : segments) {
				canvas.drawLine(originX + (float) (segment[0] * scale),
						originY + (float) (segment[1] * scale),
						originX + (float) (segment[2] * scale),
						originY + (float) (segment[3] * scale), paint);
			}
		} finally {
			paint.close();
		}
	}

	private static void drawBlur(Canvas canvas, AnnotationSpec annotation, Rect rect,
			double scaleFactor, @Nullable Image sceneSnapshot, int canvasWidth, int canvasHeight) {
		if (sceneSnapshot == null) {
			return;
		}
		double blurStrength = (annotation.blurIntensity() == null ? 20 : annotation.blurIntensity())
				* scaleFactor;
		double padding = Math.ceil(blurStrength * 2);
		canvas.save();
		canvas.clipRect(rect);
		float sx = (float) Math.max(0, rect._left - padding);
		float sy = (float) Math.max(0, rect._top - padding);
		float sw = (float) Math.min(canvasWidth - sx, rect.getWidth() + padding * 2);
		float sh = (float) Math.min(canvasHeight - sy, rect.getHeight() + padding * 2);
		if (sw > 0 && sh > 0) {
			try (ImageFilter filter = ImageFilter.makeBlur((float) blurStrength,
					(float) blurStrength, FilterTileMode.CLAMP);
					Paint paint = new Paint()) {
				paint.setImageFilter(filter);
				canvas.drawImageRect(sceneSnapshot,
						Rect.makeXYWH(sx, sy, sw, sh),
						Rect.makeXYWH(sx, sy, sw, sh), SamplingMode.LINEAR, paint, true);
			}
			if (annotation.blurColor() != null && !annotation.blurColor().isBlank()
					&& !"transparent".equalsIgnoreCase(annotation.blurColor())) {
				int colour = CssColour.parse(annotation.blurColor());
				if (colour != -1) {
					try (Paint paint = new Paint()) {
						paint.setColor(colour);
						canvas.drawRect(rect, paint);
					}
				}
			}
		}
		canvas.restore();
	}

	/** The polyline segments for a known arrow direction; null when absent/unknown. */
	private static double @Nullable [][] arrowSegments(@Nullable String direction) {
		if (direction == null) {
			return null;
		}
		return switch (direction) {
			case "up" -> ARROW_UP;
			case "down" -> ARROW_DOWN;
			case "left" -> ARROW_LEFT;
			case "right" -> ARROW_RIGHT;
			case "up-right" -> ARROW_UP_RIGHT;
			case "up-left" -> ARROW_UP_LEFT;
			case "down-right" -> ARROW_DOWN_RIGHT;
			case "down-left" -> ARROW_DOWN_LEFT;
			default -> null;
		};
	}

	private static byte @Nullable [] decodeDataUri(@Nullable String source) {
		if (source == null || source.isBlank()) {
			return null;
		}
		int comma = source.indexOf(',');
		if (!source.startsWith("data:image") || comma < 0) {
			return null;
		}
		try {
			return Base64.getDecoder().decode(source.substring(comma + 1)
					.getBytes(StandardCharsets.US_ASCII));
		} catch (IllegalArgumentException error) {
			return null;
		}
	}
}
