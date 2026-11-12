package dev.gravaai.services.document;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link PlainDocument} as a Word document: an OOXML package (a zip of XML parts) with
 * real heading styles, list numbering and a hyperlink relationship per link.
 *
 * <p>Word, LibreOffice and Google Docs all read this shape. Keeping it here as a plain zip writer
 * means the app carries no document library for what is, for meeting notes, a page of styled
 * paragraphs: the two parts that carry the layout ({@code styles.xml}, {@code numbering.xml}) are
 * static, and only {@code document.xml} depends on the content.
 *
 * <p>The parts are written in the order ECMA-376 expects: {@code [Content_Types].xml}, the package
 * relationships, then the document parts.
 */
public final class DocxWriter {

	private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
	private static final String R =
			"http://schemas.openxmlformats.org/officeDocument/2006/relationships";

	/** Twips in one inch: the page margins and list indents are expressed in them. */
	private static final int ONE_INCH_TWIPS = 1440;
	/** One list level's indent: half an inch keeps nesting readable without wasting the width. */
	private static final int LIST_INDENT_TWIPS = 360;
	private static final int LIST_HANGING_TWIPS = 360;

	/** numId of the bullet abstract numbering and of the decimal one (see {@link #NUMBERING}). */
	private static final int BULLET_NUM_ID = 1;
	private static final int DECIMAL_NUM_ID = 2;

	private DocxWriter() {
	}

	/** Writes the document to {@code out}; the caller owns and closes the stream. */
	public static void write(PlainDocument document, OutputStream out) throws IOException {
		LinkTable links = new LinkTable();
		String body = buildDocumentXml(document, links);
		try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			put(zip, "[Content_Types].xml", CONTENT_TYPES);
			put(zip, "_rels/.rels", ROOT_RELATIONSHIPS);
			put(zip, "word/document.xml", body);
			put(zip, "word/_rels/document.xml.rels", documentRelationships(links));
			put(zip, "word/styles.xml", STYLES);
			put(zip, "word/numbering.xml", NUMBERING);
			put(zip, "docProps/core.xml", coreProperties(document.title()));
			put(zip, "docProps/app.xml", APP_PROPERTIES);
		}
	}

	private static void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	// --- document.xml -------------------------------------------------------------------------

	private static String buildDocumentXml(PlainDocument document, LinkTable links) {
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
				.append("<w:document xmlns:w=\"").append(W).append("\" xmlns:r=\"")
				.append(R).append("\"><w:body>");

		xml.append(styledParagraph("Title", esc(document.title())));
		if (!document.metadata().isEmpty()) {
			xml.append(styledParagraph("Subtitle",
					esc(String.join("  ·  ", document.metadata()))));
		}

		for (DocumentBlock block : document.blocks()) {
			if (block instanceof DocumentBlock.Heading heading) {
				int level = Math.max(1, Math.min(6, heading.level()));
				xml.append(runsParagraph("Heading" + level, heading.runs(), links));
			} else if (block instanceof DocumentBlock.Paragraph paragraph) {
				xml.append(runsParagraph("BodyText", paragraph.runs(), links));
			} else if (block instanceof DocumentBlock.ListItem item) {
				xml.append(listItemXml(item, links));
			} else if (block instanceof DocumentBlock.Quote quote) {
				xml.append(runsParagraph("Quote", quote.runs(), links));
			} else if (block instanceof DocumentBlock.Rule) {
				xml.append(ruleXml());
			}
		}

		// A4 with 2.5 cm margins is the app's page; sectPr must close the body.
		xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
				.append("<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\"")
				.append(" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>");
		xml.append("</w:body></w:document>");
		return xml.toString();
	}

	/**
	 * A list item: the paragraph carries its numbering reference so Word and LibreOffice draw the
	 * marker themselves (a typed bullet would not be a list, and would not indent as one).
	 */
	private static String listItemXml(DocumentBlock.ListItem item, LinkTable links) {
		boolean nested = item.depth() > 0;
		int numId = item.kind() == DocumentBlock.ListItem.Kind.ORDERED
				? DECIMAL_NUM_ID
				: BULLET_NUM_ID;
		int level = Math.min(2, item.depth());
		String style = nested ? "ListNested" : "List";
		StringBuilder xml = new StringBuilder();
		xml.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/>")
				.append("<w:numPr><w:ilvl w:val=\"").append(level).append("\"/>")
				.append("<w:numId w:val=\"").append(numId).append("\"/></w:numPr>")
				.append("</w:pPr>");
		if (item.kind() == DocumentBlock.ListItem.Kind.TASK) {
			// The checkbox is text, so every viewer shows it, including the ones that ignore
			// checkboxes in Word documents.
			xml.append(textRun(item.checked() ? "\u2611 " : "\u2610 ", RunStyle.PLAIN));
		}
		xml.append(innerRuns(item.runs(), links, RunStyle.PLAIN));
		xml.append("</w:p>");
		return xml.toString();
	}

	private static String ruleXml() {
		return "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\""
				+ " w:color=\"999999\"/></w:pBdr></w:pPr></w:p>";
	}

	/** A paragraph whose whole text is one plain run. */
	private static String styledParagraph(String style, String escapedText) {
		return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>"
				+ textRun(escapedText, RunStyle.PLAIN) + "</w:p>";
	}

	private static String runsParagraph(String style, List<InlineRun> runs, LinkTable links) {
		return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>"
				+ innerRuns(runs, links, RunStyle.PLAIN) + "</w:p>";
	}

	/** The inline runs of a paragraph as Word runs (w:r), with the accumulated formatting. */
	private static String innerRuns(List<InlineRun> runs, LinkTable links, RunStyle style) {
		StringBuilder xml = new StringBuilder();
		for (InlineRun run : runs) {
			if (run instanceof InlineRun.Text text) {
				xml.append(textRun(esc(text.text()), style));
			} else if (run instanceof InlineRun.Code code) {
				xml.append(textRun(esc(code.text()), style.withCode()));
			} else if (run instanceof InlineRun.Strong strong) {
				xml.append(innerRuns(strong.runs(), links, style.withBold()));
			} else if (run instanceof InlineRun.Emphasis emphasis) {
				xml.append(innerRuns(emphasis.runs(), links, style.withItalic()));
			} else if (run instanceof InlineRun.Strike strike) {
				xml.append(innerRuns(strike.runs(), links, style.withStrike()));
			} else if (run instanceof InlineRun.Link link) {
				// Word hyperlinks wrap runs and are not themselves formatted; the label keeps
				// whatever emphasis it had inside.
				xml.append("<w:hyperlink r:id=\"").append(esc(links.id(link.destination())))
						.append("\">")
						.append(innerRuns(link.runs(), links, style.withLink()))
						.append("</w:hyperlink>");
			}
		}
		return xml.toString();
	}

	/**
	 * Accumulated run formatting. Nesting is a value, so bold inside italic stays both instead of
	 * the inner edge replacing the outer one.
	 *
	 * @param bold {@code <w:b/>}
	 * @param italic {@code <w:i/>}
	 * @param strike {@code <w:strike/>}
	 * @param code monospace face on a shaded background
	 * @param link the hyperlink colour and underline
	 */
	private record RunStyle(boolean bold, boolean italic, boolean strike, boolean code,
			boolean link) {

		static final RunStyle PLAIN = new RunStyle(false, false, false, false, false);

		RunStyle withBold() {
			return new RunStyle(true, italic, strike, code, link);
		}

		RunStyle withItalic() {
			return new RunStyle(bold, true, strike, code, link);
		}

		RunStyle withStrike() {
			return new RunStyle(bold, italic, true, code, link);
		}

		RunStyle withCode() {
			return new RunStyle(bold, italic, strike, true, link);
		}

		RunStyle withLink() {
			return new RunStyle(bold, italic, strike, code, true);
		}

		boolean isEmpty() {
			return !bold && !italic && !strike && !code && !link;
		}
	}

	private static String textRun(String escapedText, RunStyle style) {
		if (style.isEmpty()) {
			return "<w:r><w:t xml:space=\"preserve\">" + escapedText + "</w:t></w:r>";
		}
		StringBuilder properties = new StringBuilder();
		if (style.bold()) {
			properties.append("<w:b/>");
		}
		if (style.italic()) {
			properties.append("<w:i/>");
		}
		if (style.strike()) {
			properties.append("<w:strike/>");
		}
		if (style.code()) {
			properties.append("<w:rFonts w:ascii=\"DejaVu Sans Mono\" w:hAnsi=\"DejaVu Sans Mono\"/>")
					.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>");
		}
		if (style.link()) {
			properties.append("<w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>");
		}
		return "<w:r><w:rPr>" + properties + "</w:rPr><w:t xml:space=\"preserve\">"
				+ escapedText + "</w:t></w:r>";
	}

	/** XML text escaping, dropping the control characters XML 1.0 forbids outright. */
	static String esc(String text) {
		StringBuilder escaped = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			switch (character) {
				case '&' -> escaped.append("&amp;");
				case '<' -> escaped.append("&lt;");
				case '>' -> escaped.append("&gt;");
				case '"' -> escaped.append("&quot;");
				case '\'' -> escaped.append("&apos;");
				default -> {
					if (character >= 0x20 || character == '\t' || character == '\n') {
						escaped.append(character);
					}
				}
			}
		}
		return escaped.toString();
	}

	// --- package parts ------------------------------------------------------------------------

	private static final String CONTENT_TYPES =
			"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
					+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
					+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
					+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
					+ "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
					+ "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
					+ "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
					+ "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
					+ "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
					+ "</Types>";

	private static final String ROOT_RELATIONSHIPS =
			"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
					+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
					+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
					+ "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
					+ "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
					+ "</Relationships>";

	private static String documentRelationships(LinkTable links) {
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
				.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
				.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
				.append("<Relationship Id=\"rIdNumbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>");
		for (String destination : links.destinations()) {
			xml.append("<Relationship Id=\"").append(esc(links.id(destination)))
					.append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
					.append(esc(destination)).append("\" TargetMode=\"External\"/>");
		}
		xml.append("</Relationships>");
		return xml.toString();
	}

	private static String coreProperties(String title) {
		String now = Instant.now().toString();
		return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
				+ "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
				+ " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
				+ " xmlns:dcterms=\"http://purl.org/dc/terms/\""
				+ " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
				+ "<dc:title>" + esc(title) + "</dc:title>"
				+ "<dc:creator>Gravaai</dc:creator>"
				+ "<cp:lastModifiedBy>Gravaai</cp:lastModifiedBy>"
				+ "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
				+ "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
				+ "</cp:coreProperties>";
	}

	private static final String APP_PROPERTIES =
			"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
					+ "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
					+ "<Application>Gravaai</Application></Properties>";

	/**
	 * The document's type scale and the two list styles. The list styles carry the indents; the
	 * numbering reference travels on each paragraph, where the actual numId lives.
	 */
	private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
			+ "<w:styles xmlns:w=\"" + W + "\">"
			+ "<w:docDefaults><w:rPrDefault><w:rPr>"
			+ "<w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\" w:cs=\"Liberation Sans\"/>"
			+ "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/>"
			+ "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault>"
			+ "</w:docDefaults>"
			+ "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
			+ "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
			+ "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>"
			+ "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"BodyText\"/><w:qFormat/>"
			+ "<w:pPr><w:spacing w:before=\"0\" w:after=\"80\"/></w:pPr>"
			+ "<w:rPr><w:b/><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr></w:style>"
			+ "<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\"><w:name w:val=\"Subtitle\"/>"
			+ "<w:basedOn w:val=\"Normal\"/><w:qFormat/>"
			+ "<w:pPr><w:spacing w:after=\"360\"/></w:pPr>"
			+ "<w:rPr><w:color w:val=\"666666\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:style>"
			+ "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>"
			+ "<w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:pBdr><w:left w:val=\"single\""
			+ " w:sz=\"18\" w:space=\"10\" w:color=\"B0B0B0\"/></w:pBdr><w:ind w:left=\"360\"/>"
			+ "<w:spacing w:after=\"140\"/></w:pPr>"
			+ "<w:rPr><w:i/><w:color w:val=\"555555\"/></w:rPr></w:style>"
			+ "<w:style w:type=\"paragraph\" w:styleId=\"BodyText\"><w:name w:val=\"Body Text\"/>"
			+ "<w:basedOn w:val=\"Normal\"/><w:qFormat/>"
			+ "<w:pPr><w:spacing w:after=\"140\"/></w:pPr></w:style>"
			+ headingStyle(1, 34) + headingStyle(2, 28) + headingStyle(3, 24)
			+ headingStyle(4, 22) + headingStyle(5, 22) + headingStyle(6, 22)
			+ listStyle("List", "List Paragraph", LIST_INDENT_TWIPS)
			+ listStyle("ListNested", "List Paragraph 2", 2 * LIST_INDENT_TWIPS)
			+ "</w:styles>";

	private static String headingStyle(int level, int halfPoints) {
		return "<w:style w:type=\"paragraph\" w:styleId=\"Heading" + level + "\">"
				+ "<w:name w:val=\"heading " + level + "\"/><w:basedOn w:val=\"Normal\"/>"
				+ "<w:next w:val=\"BodyText\"/><w:qFormat/>"
				+ "<w:pPr><w:keepNext/><w:spacing w:before=\"" + (level == 1 ? 320 : 240)
				+ "\" w:after=\"120\"/><w:outlineLvl w:val=\"" + (level - 1) + "\"/></w:pPr>"
				+ "<w:rPr><w:b/><w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\""
				+ halfPoints + "\"/></w:rPr></w:style>";
	}

	private static String listStyle(String id, String name, int indent) {
		return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\">"
				+ "<w:name w:val=\"" + name + "\"/><w:basedOn w:val=\"Normal\"/>"
				+ "<w:pPr><w:ind w:left=\"" + indent + "\" w:hanging=\"" + LIST_HANGING_TWIPS
				+ "\"/><w:spacing w:after=\"60\"/></w:pPr></w:style>";
	}

	/**
	 * The numbering definitions. Bullets and numbers each get their own abstract numbering so the
	 * two families never share a counter; each has three levels for nesting.
	 */
	private static final String NUMBERING = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
			+ "<w:numbering xmlns:w=\"" + W + "\">"
			+ "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
			+ bulletLevel(0, LIST_INDENT_TWIPS, "\u2022", "Symbol")
			+ bulletLevel(1, 2 * LIST_INDENT_TWIPS, "\u25E6", "Courier New")
			+ bulletLevel(2, 3 * LIST_INDENT_TWIPS, "\u25AA", "Symbol")
			+ "</w:abstractNum>"
			+ "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
			+ decimalLevel(0, LIST_INDENT_TWIPS) + decimalLevel(1, 2 * LIST_INDENT_TWIPS)
			+ decimalLevel(2, 3 * LIST_INDENT_TWIPS)
			+ "</w:abstractNum>"
			+ "<w:num w:numId=\"" + BULLET_NUM_ID + "\"><w:abstractNumId w:val=\"0\"/></w:num>"
			+ "<w:num w:numId=\"" + DECIMAL_NUM_ID + "\"><w:abstractNumId w:val=\"1\"/></w:num>"
			+ "</w:numbering>";

	private static String bulletLevel(int level, int indent, String glyph, String font) {
		return "<w:lvl w:ilvl=\"" + level + "\"><w:start w:val=\"1\"/>"
				+ "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"" + glyph + "\"/>"
				+ "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"" + indent
				+ "\" w:hanging=\"" + LIST_HANGING_TWIPS + "\"/></w:pPr>"
				+ "<w:rPr><w:rFonts w:ascii=\"" + font + "\" w:hAnsi=\"" + font + "\"/></w:rPr></w:lvl>";
	}

	private static String decimalLevel(int level, int indent) {
		return "<w:lvl w:ilvl=\"" + level + "\"><w:start w:val=\"1\"/>"
				+ "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%" + (level + 1) + ".\"/>"
				+ "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"" + indent
				+ "\" w:hanging=\"" + LIST_INDENT_TWIPS + "\"/></w:pPr></w:lvl>";
	}

	/** Assigns one relationship id per distinct link destination, in first-seen order. */
	private static final class LinkTable {

		private final List<String> destinations = new ArrayList<>();

		String id(String destination) {
			int index = destinations.indexOf(destination);
			if (index < 0) {
				destinations.add(destination);
				index = destinations.size() - 1;
			}
			return "rIdLink" + index;
		}

		List<String> destinations() {
			return List.copyOf(destinations);
		}
	}
}
