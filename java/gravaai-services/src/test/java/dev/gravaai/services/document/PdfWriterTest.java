package dev.gravaai.services.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The PDF contract: Skia's writer must produce a real PDF (header, page tree, embedded fonts) for a
 * notes page, and the text must stay in the file rather than being rasterised.
 */
class PdfWriterTest {

	private static final Pattern PAGE_OBJECT = Pattern.compile("/Type\\s*/Page[^s]");

	@Test
	@DisplayName("writes a PDF with a page tree and embedded font subsets")
	void writesReadablePdf() throws Exception {
		byte[] pdf = write(document());

		assertTrue(pdf.length > 1000, "pdf is " + pdf.length + " bytes");
		String raw = new String(pdf, StandardCharsets.ISO_8859_1);
		assertTrue(raw.startsWith("%PDF-1."), raw.substring(0, 8));
		assertTrue(raw.contains("/Type /Catalog"), "no catalog");
		assertTrue(PAGE_OBJECT.matcher(raw).find(), "no page object");
		assertTrue(raw.contains("/BaseFont"), "no embedded font");
		assertTrue(raw.contains("%%EOF"), "truncated pdf");
	}

	@Test
	@DisplayName("keeps the document text extractable instead of drawing it as an image")
	void keepsTextSelectable() throws Exception {
		String raw = new String(write(document()), StandardCharsets.ISO_8859_1);
		// Text drawn by Skia is a CID-keyed font with a ToUnicode map: that is what makes a viewer
		// able to select and copy the words. A rasterised page would have neither.
		assertTrue(raw.contains("/ToUnicode"), "no ToUnicode map: the text cannot be selected");
		assertTrue(raw.contains("/Type0") && raw.contains("/CIDFontType2"),
				"the fonts are not embedded as CID fonts");
		// Skia deflates its content streams, so the operators only show after inflating them.
		String content = inflateContentStreams(write(document()));
		assertTrue(content.contains("BT") && content.contains("Tj"),
				"no text operators in the document");
	}

	@Test
	@DisplayName("adds a page per section when the notes overflow one page")
	void paginatesLongDocuments() throws Exception {
		StringBuilder longNotes = new StringBuilder();
		for (int index = 0; index < 120; index++) {
			longNotes.append("Paragraph ").append(index)
					.append(": a sentence that takes a little space on the page.\n\n");
		}
		PlainDocument document = new PlainDocument("Long meeting", List.of(),
				MarkdownDocumentParser.parseBlocks(longNotes.toString()));
		String raw = new String(write(document), StandardCharsets.ISO_8859_1);

		Matcher pages = PAGE_OBJECT.matcher(raw);
		int count = 0;
		while (pages.find()) {
			count++;
		}
		assertTrue(count > 1, "expected several pages, found " + count);
	}

	@Test
	@DisplayName("writes a document with no body blocks without failing")
	void writesEmptyDocument() throws Exception {
		byte[] pdf = write(new PlainDocument("Nothing here", List.of(), List.of()));

		assertTrue(pdf.length > 500, "pdf is " + pdf.length + " bytes");
	}

	@Test
	@DisplayName("handles the glyphs the notes use outside the Latin faces")
	void handlesFallbackGlyphs() throws Exception {
		PlainDocument document = new PlainDocument("Unicode", List.of(),
				List.of(new DocumentBlock.ListItem(0, DocumentBlock.ListItem.Kind.TASK, false,
								List.of(new InlineRun.Text("a task with \u2610 and \u2611 boxes"))),
						new DocumentBlock.Paragraph(List.of(
								new InlineRun.Text("CJK \u4F60\u597D and accents \u00E7\u00E3o")))));
		byte[] pdf = write(document);

		assertTrue(pdf.length > 500, "pdf is " + pdf.length + " bytes");
		assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1));
	}

	private static PlainDocument document() {
		return new PlainDocument("Design review", List.of("Nov 12, 2026 · 45 min"),
				MarkdownDocumentParser.parseBlocks("""
						## Summary

						The team reviewed the **dashboard** and agreed on a *compact* layout.

						## Action items

						- [ ] Maria: send the budget
						- [x] Jardel: update the spec
						- plain bullet

						## Open questions

						1. Dark theme?
						2. Empty state?
						"""));
	}

	private static byte[] write(PlainDocument document) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PdfWriter.write(document, out);
		return out.toByteArray();
	}

	/**
	 * The concatenated, inflated content streams. The PDF is Latin-1 text with binary streams, so
	 * the streams are located by their dictionary and inflated one by one.
	 */
	private static String inflateContentStreams(byte[] pdf) throws Exception {
		String raw = new String(pdf, StandardCharsets.ISO_8859_1);
		StringBuilder text = new StringBuilder();
		int from = 0;
		while (true) {
			int stream = raw.indexOf("stream", from);
			if (stream < 0) {
				break;
			}
			int start = stream + "stream".length();
			while (start < raw.length() && (raw.charAt(start) == '\r' || raw.charAt(start) == '\n')) {
				start++;
			}
			int end = raw.indexOf("endstream", start);
			if (end < 0) {
				break;
			}
			byte[] compressed = raw.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
			try (java.util.zip.InflaterInputStream inflater =
					new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(
							compressed))) {
				text.append(new String(inflater.readAllBytes(), StandardCharsets.ISO_8859_1));
			} catch (java.util.zip.ZipException notDeflated) {
				// An uncompressed stream (a font file, metadata) is not content; skip it.
				text.append(new String(compressed, StandardCharsets.ISO_8859_1));
			}
			from = end + "endstream".length();
		}
		return text.toString();
	}
}
