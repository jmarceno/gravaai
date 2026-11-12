package dev.gravaai.services.document;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.compose.CompositorFonts;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Document;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.OutputWStream;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.WStream;
import io.github.humbleui.skija.shaper.Shaper;
import io.github.humbleui.types.Rect;

/**
 * Writes a {@link PlainDocument} as a text-only PDF through Skija's PDF backend (Skia's
 * {@code SkPDF}), which the compositor already ships — so the app needs no PDF library.
 *
 * <p>Skia embeds the font subsets and keeps the text extractable and selectable, so the result is a
 * real document rather than a picture of one. The page is A4 with 2 cm margins; the type scale
 * matches the DOCX and the app's own notes rendering.
 *
 * <p>The faces come from {@link CompositorFonts}, the compositor's cache, so an export embeds the
 * same DM Sans files the app draws with. The shaper resolves glyphs through the system font
 * manager, so characters DM Sans lacks (checkboxes, CJK, other scripts) reach the page through a
 * fallback face instead of tofu.
 */
public final class PdfWriter {

	private static final float PAGE_WIDTH = 595.28f;
	private static final float PAGE_HEIGHT = 841.89f;
	private static final float MARGIN = 56.7f;

	private static final float BODY_SIZE = 10.5f;
	private static final float CODE_SIZE = 9.75f;
	/** Body line height as a multiple of the font size. */
	private static final float LINE_HEIGHT = 1.45f;
	/** Extra space before a heading, as a multiple of its size. */
	private static final float HEADING_SPACE_BEFORE = 1.1f;
	private static final float LIST_INDENT = 18f;

	private static final int COLOR_TEXT = 0xFF1A1A1A;
	private static final int COLOR_MUTED = 0xFF666666;
	private static final int COLOR_LINK = 0xFF0F766E;
	private static final int COLOR_CODE_BACKGROUND = 0xFFF2F2F2;
	private static final int COLOR_RULE = 0xFFCCCCCC;

	private PdfWriter() {
	}

	/** Writes the PDF to {@code out}; the caller owns and closes the stream. */
	public static void write(PlainDocument document, OutputStream out) throws IOException {
		Shaper shaper = Shaper.makeBestAvailable(FontMgr.getDefault());
		try (WStream stream = new OutputWStream(out);
				Document pdf = Document.makePDF(stream)) {
			PdfPage page = new PdfPage(pdf, shaper);
			try {
				page.render(document);
			} finally {
				page.close();
			}
		} finally {
			shaper.close();
		}
	}

	/** One open PDF page plus the cursor the blocks advance. */
	private static final class PdfPage {

		private final Document pdf;
		private final Shaper shaper;
		/**
		 * Paints are pooled per colour and closed with the page: a document draws thousands of
		 * words in a handful of colours, so one paint each is all it needs.
		 */
		private final java.util.Map<Integer, Paint> paints = new java.util.HashMap<>();
		/** Fonts are pooled per face and size and closed with the page. */
		private final java.util.Map<FontKey, Font> fonts = new java.util.HashMap<>();
		private final Paint fillPaint;
		/**
		 * The page being drawn. {@link #render} always opens a page first and closes the last one
		 * at the end, so the drawing helpers can rely on it being present.
		 */
		private Canvas canvas;
		private float cursorY = MARGIN;

		PdfPage(Document pdf, Shaper shaper) {
			this.pdf = pdf;
			this.shaper = shaper;
			this.fillPaint = paint(COLOR_CODE_BACKGROUND);
			this.canvas = pdf.beginPage(PAGE_WIDTH, PAGE_HEIGHT);
		}

		void render(PlainDocument document) {
			addTitle(document.title());
			if (!document.metadata().isEmpty()) {
				addMetadata(String.join("  \u00B7  ", document.metadata()));
			}
			for (DocumentBlock block : document.blocks()) {
				switch (block) {
					case DocumentBlock.Heading heading -> addHeading(heading);
					case DocumentBlock.Paragraph paragraph -> {
						addFlow(paragraph.runs(), MARGIN, BODY_SIZE);
						cursorY += BODY_SIZE * 0.55f;
					}
					case DocumentBlock.ListItem item -> addListItem(item);
					case DocumentBlock.Quote quote -> addQuote(quote);
					case DocumentBlock.Rule rule -> addRule();
				}
			}
			pdf.endPage();
		}

		void close() {
			for (Paint paint : paints.values()) {
				paint.close();
			}
			for (Font font : fonts.values()) {
				font.close();
			}
		}

		// --- block rendering -----------------------------------------------------------------

		private void addTitle(String title) {
			float size = 22f;
			for (String line : wrap(title, CompositorFonts.Face.BOLD, size, contentWidth())) {
				ensureSpace(size * LINE_HEIGHT);
				drawLine(line, MARGIN, cursorY + size, CompositorFonts.Face.BOLD, size, COLOR_TEXT);
				cursorY += size * LINE_HEIGHT;
			}
			cursorY += size * 0.35f;
		}

		private void addMetadata(String metadata) {
			float size = 9f;
			for (String line : wrap(metadata, CompositorFonts.Face.REGULAR, size, contentWidth())) {
				ensureSpace(size * LINE_HEIGHT);
				drawLine(line, MARGIN, cursorY + size, CompositorFonts.Face.REGULAR, size,
						COLOR_MUTED);
				cursorY += size * LINE_HEIGHT;
			}
			cursorY += size * 1.2f;
		}

		private void addHeading(DocumentBlock.Heading heading) {
			float size = switch (heading.level()) {
				case 1 -> 17f;
				case 2 -> 14f;
				case 3 -> 12.5f;
				default -> 11.5f;
			};
			cursorY += size * HEADING_SPACE_BEFORE;
			for (String line : wrap(heading.plainText(), CompositorFonts.Face.BOLD, size,
					contentWidth())) {
				ensureSpace(size * LINE_HEIGHT);
				drawLine(line, MARGIN, cursorY + size, CompositorFonts.Face.BOLD, size,
						COLOR_TEXT);
				cursorY += size * LINE_HEIGHT;
			}
			cursorY += size * 0.25f;
		}

		private void addListItem(DocumentBlock.ListItem item) {
			float indent = MARGIN + LIST_INDENT * Math.min(3, item.depth());
			String marker = switch (item.kind()) {
				case BULLET -> "\u2022";
				case TASK -> item.checked() ? "\u2611" : "\u2610";
				case ORDERED -> item.number() + ".";
			};
			// The marker sits in its own column; the text wraps back to the text column. The column
			// is at least LIST_INDENT wide, and wider when the number needs it.
			ensureSpace(BODY_SIZE * LINE_HEIGHT);
			float baseline = cursorY + BODY_SIZE;
			float markerWidth = Math.max(LIST_INDENT, wordWidth(marker, font(
					CompositorFonts.Face.REGULAR, BODY_SIZE)) + 6);
			drawLine(marker, indent, baseline, CompositorFonts.Face.REGULAR, BODY_SIZE,
					COLOR_MUTED);
			if (!addFlow(item.runs(), indent + markerWidth, BODY_SIZE)) {
				// An empty checklist entry still occupies its line: without this the next item
				// was drawn over the lone checkbox.
				cursorY += BODY_SIZE * LINE_HEIGHT;
			}
			cursorY += BODY_SIZE * 0.3f;
		}

		/**
		 * A quote: a quiet rule down its left edge with the text inset from it. The bar is drawn one
		 * line at a time, so it follows the text across a page break instead of being stretched over
		 * a page boundary or left behind on the previous page.
		 */
		private void addQuote(DocumentBlock.Quote quote) {
			float barX = MARGIN + 4;
			float textX = MARGIN + 14;
			float lineHeight = BODY_SIZE * LINE_HEIGHT;
			cursorY += 4;
			ensureSpace(lineHeight);
			float lineStartY = cursorY;
			float penX = textX;
			boolean lineHasText = false;
			for (StyledWord word : styledWords(quote.runs(), BODY_SIZE, Style.PLAIN)) {
				Font font = font(word.face(), word.size());
				float width = wordWidth(word.text(), font);
				float space = penX <= textX + 0.01f ? 0 : wordWidth(" ", font);
				if (penX > textX + 0.01f && penX + space + width > PAGE_WIDTH - MARGIN) {
					closeQuoteLine(barX, lineStartY, lineHasText);
					cursorY += lineHeight;
					ensureSpace(lineHeight);
					lineStartY = cursorY;
					penX = textX;
					space = 0;
					lineHasText = false;
				}
				penX += space;
				drawWord(word, font, penX, cursorY + BODY_SIZE, BODY_SIZE);
				penX += width;
				lineHasText = true;
			}
			closeQuoteLine(barX, lineStartY, lineHasText);
			cursorY += lineHeight + BODY_SIZE * 0.4f;
		}

		/** The bar for one finished quote line. */
		private void closeQuoteLine(float barX, float lineStartY, boolean hasText) {
			if (!hasText) {
				return;
			}
			canvas.drawRect(new Rect(barX, lineStartY + 2, barX + 3,
					lineStartY + BODY_SIZE * LINE_HEIGHT - 2), paint(COLOR_RULE));
		}

		private void addRule() {
			cursorY += 8;
			ensureSpace(16);
			canvas.drawLine(MARGIN, cursorY, PAGE_WIDTH - MARGIN, cursorY, paint(COLOR_RULE));
			cursorY += 10;
		}

		/**
		 * Renders inline runs as flowing text at {@code x}, wrapping at the page edge. Every run
		 * keeps its own face and colour, so bold/italic/code survive inside a wrapped line.
		 *
		 * @return false when the runs hold no visible word, so nothing was drawn or advanced
		 */
		private boolean addFlow(List<InlineRun> runs, float x, float size) {
			float lineHeight = size * LINE_HEIGHT;
			List<StyledWord> words = styledWords(runs, size, Style.PLAIN);
			if (words.isEmpty()) {
				return false;
			}
			float right = PAGE_WIDTH - MARGIN;
			float currentX = x;
			ensureSpace(lineHeight);
			for (StyledWord word : words) {
				Font font = font(word.face(), word.size());
				boolean firstOnLine = currentX <= x + 0.01f;
				float space = firstOnLine ? 0 : wordWidth(" ", font);
				float width = wordWidth(word.text(), font);
				if (!firstOnLine && currentX + space + width > right) {
					cursorY += lineHeight;
					ensureSpace(lineHeight);
					currentX = x;
					space = 0;
				}
				currentX += space;
				float baseline = cursorY + size;
				drawWord(word, font, currentX, baseline, size);
				currentX += width;
			}
			cursorY += lineHeight;
			return true;
		}

		/** Draws one word, with the shading a code run needs behind it. */
		private void drawWord(StyledWord word, Font font, float x, float baseline, float size) {
			float width = wordWidth(word.text(), font);
			if (word.style().code()) {
				canvas.drawRect(new Rect(x - 1, baseline - size, x + width + 1,
						baseline + size * 0.3f), fillPaint);
			}
			drawLine(word.text(), x, baseline, font, word.color());
			if (word.style().link()) {
				canvas.drawLine(x, baseline + size * 0.12f, x + width, baseline + size * 0.12f,
						paint(word.color()));
			}
			if (word.style().strike()) {
				// A PDF text run has no strike flag, so the line is drawn through the x-height.
				float strikeY = baseline - size * 0.3f;
				canvas.drawLine(x, strikeY, x + width, strikeY, paint(word.color()));
			}
		}

		private void drawLine(String text, float x, float baseline, CompositorFonts.Face face,
				float size, int color) {
			drawLine(text, x, baseline, font(face, size), color);
		}

		private void drawLine(String text, float x, float baseline, Font font, int color) {
			if (text.isEmpty()) {
				return;
			}
			try (TextLine line = shaper.shapeLine(text, font)) {
				canvas.drawTextLine(line, x, baseline, paint(color));
			}
		}

		/** The pooled font for a face and size (closed with the page). */
		private Font font(CompositorFonts.Face face, float size) {
			return fonts.computeIfAbsent(new FontKey(face, size),
					key -> CompositorFonts.font(key.face(), key.size()));
		}

		// --- text measuring ------------------------------------------------------------------

		private static float contentWidth() {
			return PAGE_WIDTH - 2 * MARGIN;
		}

		/** Greedy word wrap against {@code width}; a word longer than the line stays on its own. */
		private List<String> wrap(String text, CompositorFonts.Face face, float size, float width) {
			List<String> lines = new ArrayList<>();
			Font font = font(face, size);
			for (String paragraph : text.split("\n")) {
				String[] words = paragraph.strip().split("\\s+");
				StringBuilder line = new StringBuilder();
				for (String word : words) {
					if (word.isEmpty()) {
						continue;
					}
					String candidate = line.isEmpty() ? word : line + " " + word;
					if (line.isEmpty() || wordWidth(candidate, font) <= width) {
						line.setLength(0);
						line.append(candidate);
					} else {
						lines.add(line.toString());
						line.setLength(0);
						line.append(word);
					}
				}
				if (!line.isEmpty()) {
					lines.add(line.toString());
				}
			}
			return lines.isEmpty() ? List.of("") : lines;
		}

		private float wordWidth(String text, Font font) {
			try (TextLine line = shaper.shapeLine(text, font)) {
				return line.getWidth();
			}
		}

		/**
		 * Flattens the inline tree into words, each carrying the face, colour and decorations it
		 * inherited. Splitting at spaces is what lets the flow wrap without losing formatting.
		 */
		private static List<StyledWord> styledWords(List<InlineRun> runs, float size, Style style) {
			List<StyledWord> words = new ArrayList<>();
			for (InlineRun run : runs) {
				switch (run) {
					case InlineRun.Text text -> addWords(words, text.text(), size, style);
					case InlineRun.Code code ->
							addWords(words, code.text(), size - 0.75f, style.withCode());
					case InlineRun.Strong strong ->
							words.addAll(styledWords(strong.runs(), size, style.withBold()));
					case InlineRun.Emphasis emphasis ->
							words.addAll(styledWords(emphasis.runs(), size, style.withItalic()));
					case InlineRun.Strike strike ->
							words.addAll(styledWords(strike.runs(), size, style.withStrike()));
					case InlineRun.Link link ->
							words.addAll(styledWords(link.runs(), size, style.withLink()));
				}
			}
			return words;
		}

		private static void addWords(List<StyledWord> words, String text, float size, Style style) {
			String content = text.replace('\n', ' ').strip();
			if (content.isEmpty()) {
				return;
			}
			// Code is a touch smaller so it reads as code without breaking the line rhythm.
			float wordSize = style.code() ? CODE_SIZE : size;
			for (String part : content.split(" ")) {
				if (!part.isEmpty()) {
					words.add(new StyledWord(part, style.face(), wordSize,
							style.link() ? COLOR_LINK : COLOR_TEXT, style));
				}
			}
		}

		/** The pooled paint for a colour (closed with the page; callers never close it). */
		private Paint paint(int color) {
			return paints.computeIfAbsent(color,
					key -> new Paint().setColor(key).setAntiAlias(true));
		}

		/**
		 * Starts a new page when the next block would cross the bottom margin, so no block is
		 * clipped at the paper edge.
		 */
		private void ensureSpace(float height) {
			if (cursorY + height <= PAGE_HEIGHT - MARGIN) {
				return;
			}
			pdf.endPage();
			canvas = pdf.beginPage(PAGE_WIDTH, PAGE_HEIGHT);
			cursorY = MARGIN;
		}
	}

	/**
	 * Accumulated inline formatting, as a value: bold inside a link stays both instead of the inner
	 * property replacing the outer one.
	 */
	private record Style(boolean bold, boolean italic, boolean code, boolean link,
			boolean strike) {

		static final Style PLAIN = new Style(false, false, false, false, false);

		Style withBold() {
			return new Style(true, italic, code, link, strike);
		}

		Style withItalic() {
			return new Style(bold, true, code, link, strike);
		}

		Style withCode() {
			return new Style(bold, italic, true, link, strike);
		}

		Style withLink() {
			return new Style(bold, italic, code, true, strike);
		}

		Style withStrike() {
			return new Style(bold, italic, code, link, true);
		}

		CompositorFonts.Face face() {
			if (code) {
				// DM Sans has no monospace cut; the regular face keeps the document consistent.
				return italic ? CompositorFonts.Face.ITALIC : CompositorFonts.Face.REGULAR;
			}
			if (bold && italic) {
				return CompositorFonts.Face.BOLD_ITALIC;
			}
			if (bold) {
				return CompositorFonts.Face.BOLD;
			}
			return italic ? CompositorFonts.Face.ITALIC : CompositorFonts.Face.REGULAR;
		}
	}

	/** One word of a styled flow: text, the face and size it inherits, and its colour. */
	private record StyledWord(String text, CompositorFonts.Face face, float size, int color,
			Style style) {
	}

	/** Pool key for a face at a size, so one page reuses its fonts. */
	private record FontKey(CompositorFonts.Face face, float size) {
	}
}
