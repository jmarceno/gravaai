package dev.gravaai.media.compose;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.AutoCaptionAnimation;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionLayout;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Rect;

/**
 * Port of {@code src/lib/exporter/captionRenderer.ts} (plan §5.8 stage 9): scaled font/padding/
 * box, bottom offset, max width/rows, centred squircle box and word-by-word text.
 *
 * <p>Which words show and the enter/exit animation come from the core port of
 * {@code buildActiveCaptionLayout} ({@link CaptionLayout}), with the cues' real word timings, so
 * export, preview and the editor agree on paging. Per-word "spoken" highlighting is disabled in
 * TS too ({@code getCaptionWordVisualState}). Known difference: DM Sans instead of SF Pro.
 */
final class CaptionRenderer {

	private static final double CAPTION_LINE_HEIGHT = 1.32;
	private static final double DEFAULT_MAX_WIDTH = 62;
	private static final double DEFAULT_FONT_SIZE = 30;

	private CaptionRenderer() {
	}

	static void draw(Canvas canvas, CaptionSpec captions, int width, int height, long timeMs) {
		if (!captions.enabled() || captions.cues().isEmpty()) {
			return;
		}
		double fontSize = scaledFontSize(captions.fontSize(), width, captions.maxWidth());
		Font font = resolveFont(captions.fontFamily(), (float) fontSize);
		if (font == null) {
			return;
		}
		try {
			double paddingX = fontSize * 1.1;
			double paddingY = fontSize * 0.78;
			double targetWidth = Math.max(1, width * (captions.maxWidth() / 100.0));
			double textMaxWidth = Math.max(fontSize * 4, targetWidth - paddingX * 2);
			CaptionLayout.ActiveCaptionLayout layout = CaptionLayout.buildActiveCaptionLayout(
					coreCues(captions), timeMs, coreSettings(captions), textMaxWidth,
					font::measureTextWidth);
			if (layout == null || layout.visibleLines().isEmpty()) {
				return;
			}
			List<CaptionLayout.LineLayout> lines = layout.visibleLines();
			double lineHeight = fontSize * CAPTION_LINE_HEIGHT;
			double textBlockHeight = lines.size() * lineHeight;
			double boxHeight = textBlockHeight + paddingY * 2;
			double centerX = width / 2.0;
			double centerY = height - (height * captions.bottomOffset()) / 100.0 - boxHeight / 2;
			double maxLineWidth = 0;
			for (CaptionLayout.LineLayout line : lines) {
				maxLineWidth = Math.max(maxLineWidth, line.width());
			}
			double boxWidth = Math.min(width * (captions.maxWidth() / 100.0) + paddingX * 2,
					maxLineWidth + paddingX * 2);
			double radius = Math.max(0,
					captions.boxRadius() * (fontSize / Math.max(1, DEFAULT_FONT_SIZE)));
			canvas.save();
			canvas.translate((float) centerX, (float) (centerY + layout.translateY()));
			canvas.scale((float) layout.scale(), (float) layout.scale());
			try (Paint paint = new Paint()) {
				// rgba(0, 0, 0, backgroundOpacity)
				int boxAlpha = (int) Math.round(
						Math.max(0, Math.min(1, captions.backgroundOpacity())) * 255
								* layout.opacity());
				paint.setColor(boxAlpha << 24);
				drawRoundedBox(canvas, -boxWidth / 2, -boxHeight / 2, boxWidth, boxHeight, radius,
						paint);
				int textArgb = CssColour.parse(captions.textColor());
				if (textArgb == -1) {
					textArgb = 0xFFFFFFFF;
				}
				double lineTop = -boxHeight / 2 + paddingY;
				for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
					CaptionLayout.LineLayout line = lines.get(lineIndex);
					double cursorX = -line.width() / 2;
					double lineY = lineTop + lineHeight * lineIndex + lineHeight / 2;
					for (CaptionLayout.WordLayout word : line.words()) {
						String segment = (word.leadingSpace() ? " " : "") + word.text();
						paint.setColor(textArgb);
						float segmentWidth = font.measureTextWidth(segment);
						float baseline = (float) lineY - (font.getMetrics()._ascent
								+ font.getMetrics()._descent) / 2f;
						canvas.drawString(segment, (float) cursorX, baseline, font, paint);
						cursorX += segmentWidth;
					}
				}
			}
			canvas.restore();
		} finally {
			font.close();
		}
	}

	private static List<CaptionCue> coreCues(CaptionSpec captions) {
		List<CaptionCue> cues = new ArrayList<>(captions.cues().size());
		for (CaptionSpec.Cue cue : captions.cues()) {
			cues.add(new CaptionCue(cue.id(), cue.startMs(), cue.endMs(), cue.text(),
					cue.words().isEmpty() ? null : cue.words()));
		}
		return cues;
	}

	private static AutoCaptionSettings coreSettings(CaptionSpec captions) {
		AutoCaptionAnimation animation = AutoCaptionAnimation.fromWire(captions.animationStyle());
		return new AutoCaptionSettings(true, false, "auto", captions.fontFamily(),
				captions.fontSize(), captions.bottomOffset(), captions.maxWidth(),
				captions.maxRows(), animation == null ? AutoCaptionAnimation.NONE : animation,
				captions.boxRadius(), captions.textColor(), captions.inactiveTextColor(),
				captions.backgroundOpacity());
	}

	private static void drawRoundedBox(Canvas canvas, double x, double y, double width,
			double height, double radius, Paint paint) {
		if (radius > 0.5 && width > 0 && height > 0) {
			try (Path path = SquircleGeometry.toPath(new SquircleGeometry.SquircleRect(
					(float) x, (float) y, (float) width, (float) height, radius))) {
				if (path != null) {
					canvas.drawPath(path, paint);
					return;
				}
			}
		}
		canvas.drawRect(Rect.makeXYWH((float) x, (float) y, (float) width, (float) height),
				paint);
	}

	private static double scaledFontSize(double fontSize, int containerWidth,
			double maxWidthPercent) {
		double targetWidth = Math.max(1, containerWidth * (maxWidthPercent / 100.0));
		double referenceWidth = 1920 * (DEFAULT_MAX_WIDTH / 100.0);
		return Math.max(14, fontSize * (targetWidth / referenceWidth));
	}

	private static Font resolveFont(String fontFamily, float size) {
		// CAPTION_FONT_WEIGHT = 400. The references use the SF Pro/system stack, which is not
		// redistributable; the bundled DM Sans regular is the documented substitute and keeps the
		// metrics identical between preview and export. (User custom fonts land in a later pass.)
		return CompositorFonts.font(CompositorFonts.Face.REGULAR, size);
	}
}
