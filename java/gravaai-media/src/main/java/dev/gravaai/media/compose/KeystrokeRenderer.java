package dev.gravaai.media.compose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontSlant;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Typeface;

/**
 * Port of {@code src/lib/exporter/keystrokeRenderer.ts} + the grouping/layout half of
 * {@code src/components/video-editor/keystrokeOverlay.ts} (plan §5.8 stage 10): presses grouped by
 * the grouping window, one group active at a time, fading in/out over {@code fadeMs}, laid out as
 * keycap chips wrapping at 90% of the frame width and positioned by the overlay settings.
 */
final class KeystrokeRenderer {

	private static final int KEYSTROKE_REFERENCE_HEIGHT = 1080;
	private static final double KEYSTROKE_MIN_FONT_PX = 9;
	private static final int MAX_KEYSTROKE_PRESSES_PER_GROUP = 6;
	private static final int CHIP_FILL = 0xCC000000;
	private static final int CHIP_TEXT = 0xFFFFFFFF;
	private static final int CHIP_STROKE = 0x29FFFFFF;
	private static final int SEPARATOR_TEXT = 0xBFFFFFFF;
	private static final String SEPARATOR = "+";

	private record Group(long startMs, long endMs, List<List<String>> presses) {
	}

	private record Chip(String label, double x, double y, double width, double height,
			double centerX, double centerY) {
	}

	private record Separator(double x, double y, double width, double height, double centerX,
			double centerY) {
	}

	private record Layout(double fontPx, double x, double y, double boxWidth, double boxHeight,
			List<Chip> chips, List<Separator> separators) {
	}

	private KeystrokeRenderer() {
	}

	static void draw(Canvas canvas, KeystrokeSpec spec, int width, int height, long timeMs) {
		if (spec == null || !spec.enabled() || spec.presses().isEmpty()) {
			return;
		}
		List<Group> groups = buildGroups(spec);
		Group active = null;
		for (Group group : groups) {
			if (group.startMs() <= timeMs && timeMs < group.endMs()) {
				active = group;
			}
		}
		if (active == null) {
			return;
		}
		double opacity = groupOpacity(active, timeMs, spec.fadeMs());
		if (opacity <= 0) {
			return;
		}
		double fontPx = Math.max(KEYSTROKE_MIN_FONT_PX,
				spec.fontSize() * (height / (double) KEYSTROKE_REFERENCE_HEIGHT));
		Font font = resolveFont(fontPx);
		if (font == null) {
			return;
		}
		try {
			Layout layout = measure(font, active, spec, width, height);
			drawLayout(canvas, layout, spec, opacity, font);
		} finally {
			font.close();
		}
	}

	private static List<Group> buildGroups(KeystrokeSpec spec) {
		List<KeystrokeSpec.Press> sorted = new ArrayList<>(spec.presses());
		sorted.sort(Comparator.comparingLong(KeystrokeSpec.Press::timeMs));
		List<Group> groups = new ArrayList<>();
		List<KeystrokeSpec.Press> current = new ArrayList<>();
		List<List<String>> chips = new ArrayList<>();
		for (KeystrokeSpec.Press press : sorted) {
			if (press.chipLabels().isEmpty()) {
				continue;
			}
			KeystrokeSpec.Press last = current.isEmpty() ? null : current.get(current.size() - 1);
			if (last != null && press.timeMs() - last.timeMs() > spec.groupingMs()) {
				groups.add(new Group(current.get(0).timeMs(),
						current.get(current.size() - 1).timeMs() + spec.lingerMs(),
						List.copyOf(chips)));
				current = new ArrayList<>();
				chips = new ArrayList<>();
			}
			current.add(press);
			chips.add(press.chipLabels());
			if (chips.size() > MAX_KEYSTROKE_PRESSES_PER_GROUP) {
				chips.remove(0);
			}
		}
		if (!current.isEmpty()) {
			groups.add(new Group(current.get(0).timeMs(),
					current.get(current.size() - 1).timeMs() + spec.lingerMs(),
					List.copyOf(chips)));
		}
		return groups;
	}

	/** {@code keystrokeGroupOpacity}. */
	private static double groupOpacity(Group group, long timeMs, long fadeMs) {
		if (timeMs < group.startMs() || timeMs >= group.endMs()) {
			return 0;
		}
		if (fadeMs <= 0) {
			return 1;
		}
		double fadeIn = (timeMs - group.startMs()) / (double) fadeMs;
		double fadeOut = (group.endMs() - timeMs) / (double) fadeMs;
		return Math.min(1, Math.min(fadeIn, Math.max(0, fadeOut)));
	}

	/** {@code measureKeystrokeGroupLayout}. */
	private static Layout measure(Font font, Group group, KeystrokeSpec spec, double width,
			double height) {
		double fontPx = font.getSize();
		double chipPadX = fontPx * 0.42;
		double chipPadY = fontPx * 0.2;
		double chipHeight = fontPx * 1.3 + chipPadY * 2;
		double separatorWidth = fontPx * 0.52;
		double pressGap = fontPx * 0.85;
		double rowGap = fontPx * 0.55;
		double boxPadX = fontPx * 0.25;
		double boxPadY = fontPx * 0.25;
		double sidePadding = width * 0.03;
		double maxRowWidth = width * 0.9;

		List<Chip> chips = new ArrayList<>();
		List<Separator> separators = new ArrayList<>();
		double rowWidth = 0;
		int rowCount = 0;
		double widestRow = 0;
		rowCount = 1;

		for (List<String> press : group.presses()) {
			if (press.isEmpty()) {
				continue;
			}
			List<Double> chipWidths = new ArrayList<>();
			double pressWidth = 0;
			for (String label : press) {
				double chipWidth = Math.max(fontPx * 1.1,
						font.measureTextWidth(label) + chipPadX * 2);
				chipWidths.add(chipWidth);
				pressWidth += chipWidth;
			}
			pressWidth += separatorWidth * Math.max(0, press.size() - 1);
			if (rowWidth > 0 && rowWidth + pressGap + pressWidth > maxRowWidth) {
				widestRow = Math.max(widestRow, rowWidth);
				rowWidth = 0;
				rowCount++;
			}
			if (rowWidth > 0) {
				rowWidth += pressGap;
			}
			double rowY = rowCount > 1 ? (rowCount - 1) * (chipHeight + rowGap) : 0;
			double chipX = boxPadX + rowWidth;
			for (int index = 0; index < press.size(); index++) {
				if (index > 0) {
					double separatorX = chipX;
					chipX += separatorWidth;
					separators.add(new Separator(separatorX, rowY, separatorWidth, chipHeight,
							separatorX + separatorWidth / 2, rowY + chipHeight / 2));
				}
				double chipWidth = chipWidths.get(index);
				chips.add(new Chip(press.get(index), chipX, rowY, chipWidth, chipHeight,
						chipX + chipWidth / 2, rowY + chipHeight / 2));
				chipX += chipWidth;
			}
			rowWidth += pressWidth;
			widestRow = Math.max(widestRow, rowWidth);
		}

		double boxWidth = widestRow + boxPadX * 2;
		double boxHeight = rowCount * chipHeight + Math.max(0, rowCount - 1) * rowGap
				+ boxPadY * 2;
		double offsetPx = (height * spec.bottomOffset()) / 100.0;
		double x = switch (spec.position()) {
			case "bottom-left" -> sidePadding;
			case "bottom-right" -> width - boxWidth - sidePadding;
			default -> (width - boxWidth) / 2;
		};
		double y = "top-center".equals(spec.position()) ? offsetPx : height - offsetPx - boxHeight;

		List<Chip> shiftedChips = new ArrayList<>();
		for (Chip chip : chips) {
			shiftedChips.add(new Chip(chip.label(), chip.x() + x, chip.y() + y, chip.width(),
					chip.height(), chip.centerX() + x, chip.centerY() + y));
		}
		List<Separator> shiftedSeparators = new ArrayList<>();
		for (Separator separator : separators) {
			shiftedSeparators.add(new Separator(separator.x() + x, separator.y() + y,
					separator.width(), separator.height(), separator.centerX() + x,
					separator.centerY() + y));
		}
		return new Layout(fontPx, x, y, boxWidth, boxHeight, List.copyOf(shiftedChips),
				List.copyOf(shiftedSeparators));
	}

	private static void drawLayout(Canvas canvas, Layout layout, KeystrokeSpec spec,
			double opacity, Font font) {
		float fontPx = (float) layout.fontPx();
		double radius = layout.fontPx() * 0.3;
		try (Paint fill = new Paint(); Paint stroke = new Paint()) {
			fill.setAntiAlias(true);
			stroke.setAntiAlias(true);
			// The group opacity multiplies each colour's own alpha (canvas globalAlpha).
			fill.setColor(withOpacity(CHIP_FILL, opacity));
			stroke.setColor(withOpacity(CHIP_STROKE, opacity));
			stroke.setMode(PaintMode.STROKE);
			stroke.setStrokeWidth(Math.max(1f, (float) (layout.fontPx() * 0.04)));
			for (Chip chip : layout.chips()) {
				try (Path path = SquircleGeometry.toPath(new SquircleGeometry.SquircleRect(
						(float) chip.x(), (float) chip.y(), (float) chip.width(),
						(float) chip.height(), radius))) {
					if (path != null) {
						canvas.drawPath(path, fill);
						canvas.drawPath(path, stroke);
					}
				}
			}
		}
		try (Paint text = new Paint()) {
			text.setAntiAlias(true);
			text.setColor(withOpacity(CHIP_TEXT, opacity));
			for (Chip chip : layout.chips()) {
				float width = font.measureTextWidth(chip.label());
				float baseline = (float) chip.centerY()
						- (font.getMetrics()._ascent + font.getMetrics()._descent) / 2f
						+ fontPx * 0.02f;
				canvas.drawString(chip.label(), (float) chip.centerX() - width / 2f, baseline,
						font, text);
			}
			if (!layout.chips().isEmpty()) {
				font.setSize(fontPx * 0.8f);
				text.setColor(withOpacity(SEPARATOR_TEXT, opacity));
				for (Separator separator : layout.separators()) {
					float width = font.measureTextWidth(SEPARATOR);
					float baseline = (float) separator.centerY()
							- (font.getMetrics()._ascent + font.getMetrics()._descent) / 2f;
					canvas.drawString(SEPARATOR, (float) separator.centerX() - width / 2f,
							baseline, font, text);
				}
			}
		}
	}

	private static int withOpacity(int argb, double opacity) {
		int alpha = (int) Math.round((argb >>> 24) * Math.max(0, Math.min(1, opacity)));
		return alpha << 24 | (argb & 0x00FFFFFF);
	}

	private static Font resolveFont(double fontPx) {
		// KEYSTROKE_CHIP_FONT_WEIGHT = 600: the bundled SemiBold face matches the reference.
		return CompositorFonts.font(CompositorFonts.Face.SEMI_BOLD, (float) fontPx);
	}
}
