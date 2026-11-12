package dev.gravaai.services.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The DOCX package contract: the parts a Word processor needs, well-formed XML, and the structure
 * (headings, real list numbering, checkboxes) that the meeting notes export promises.
 */
class DocxWriterTest {

	private static final String W_NAMESPACE =
			"http://schemas.openxmlformats.org/wordprocessingml/2006/main";

	private static final String NOTES = """
			## Summary

			The team reviewed the **dashboard**.

			## Action items

			- [ ] Maria: send the budget
			- [x] Jardel: update the spec
			- plain bullet
			- Another *one*

			## Open questions

			1. Dark theme?
			2. Empty state?
			""";

	@Test
	@DisplayName("writes the parts a Word processor reads, all well-formed")
	void writesWellFormedPackage() throws Exception {
		Map<String, byte[]> parts = write(notesDocument());

		assertTrue(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels",
				"word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml",
				"word/numbering.xml", "docProps/core.xml", "docProps/app.xml")), parts.keySet()
						.toString());
		for (Map.Entry<String, byte[]> part : parts.entrySet()) {
			// Every part must parse; a malformed one is what makes Word refuse the file.
			assertNotNull(parse(part.getValue()), part.getKey());
		}
	}

	@Test
	@DisplayName("declares heading styles and points each heading at its level")
	void writesHeadingStyles() throws Exception {
		Map<String, byte[]> parts = write(notesDocument());
		Document document = parse(parts.get("word/document.xml"));

		List<String> used = valuesOf(document, "pStyle");
		assertTrue(used.contains("Title"), used.toString());
		assertTrue(used.contains("Heading2"), used.toString());
		assertFalse(used.contains("Heading7"), used.toString());

		// The style names live in w:name/@w:val, not in element text.
		List<String> names = valuesOf(parse(parts.get("word/styles.xml")), "name");
		assertTrue(names.contains("heading 2"), names.toString());
	}

	@Test
	@DisplayName("makes bullets and numbers real list numbering, not typed glyphs")
	void writesListNumbering() throws Exception {
		Map<String, byte[]> parts = write(notesDocument());
		Document document = parse(parts.get("word/document.xml"));

		List<String> ids = valuesOf(document, "numId");
		// One numId for the bullets and one for the ordered list.
		assertTrue(ids.contains("1"), ids.toString());
		assertTrue(ids.contains("2"), ids.toString());

		// The glyphs live in w:lvlText/@w:val, so a bullet really is a bullet in the numbering.
		List<String> markers = valuesOf(parse(parts.get("word/numbering.xml")), "lvlText");
		assertTrue(markers.contains("\u2022"), markers.toString());
	}

	@Test
	@DisplayName("shows the checklist state next to task items")
	void writesTaskState() throws Exception {
		Document document = parse(write(notesDocument()).get("word/document.xml"));
		String text = document.getDocumentElement().getTextContent();

		assertTrue(text.contains("\u2610 Maria: send the budget"), text);
		assertTrue(text.contains("\u2611 Jardel: update the spec"), text);
	}

	@Test
	@DisplayName("keeps bold and italic as run properties")
	void writesInlineFormatting() throws Exception {
		Document document = parse(write(notesDocument()).get("word/document.xml"));

		assertTrue(document.getElementsByTagName("w:b").getLength() > 0);
		assertTrue(document.getElementsByTagName("w:i").getLength() > 0);
	}

	@Test
	@DisplayName("escapes markup characters so the XML stays well-formed")
	void escapesSpecialCharacters() throws Exception {
		PlainDocument document = new PlainDocument("A & B <notes>", List.of(),
				List.of(new DocumentBlock.Paragraph(List.of(
						new InlineRun.Text("5 < 6 & 7 > 4 \"quoted\" 'apostrophe'")))));
		String xml = new String(write(document).get("word/document.xml"), StandardCharsets.UTF_8);

		assertTrue(xml.contains("A &amp; B &lt;notes&gt;"), xml);
		assertTrue(xml.contains("5 &lt; 6 &amp; 7 &gt; 4"), xml);
		assertNotNull(parse(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("writes a hyperlink relationship for each link")
	void writesHyperlinks() throws Exception {
		PlainDocument document = new PlainDocument("Links", List.of(),
				List.of(new DocumentBlock.Paragraph(List.of(
						new InlineRun.Text("See "),
						new InlineRun.Link(List.of(new InlineRun.Text("the spec")),
								"https://example.com/spec")))));
		Map<String, byte[]> parts = write(document);

		String relationships = new String(parts.get("word/_rels/document.xml.rels"),
				StandardCharsets.UTF_8);
		assertTrue(relationships.contains("https://example.com/spec"), relationships);
		String body = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
		assertTrue(body.contains("<w:hyperlink"), body);
	}

	@Test
	@DisplayName("drops control characters XML forbids instead of writing a broken part")
	void dropsControlCharacters() throws Exception {
		PlainDocument document = new PlainDocument("Bell\u0000", List.of(),
				List.of(new DocumentBlock.Paragraph(List.of(new InlineRun.Text("a\u0007b")))));
		byte[] xml = write(document).get("word/document.xml");

		assertNotNull(parse(xml));
		String text = new String(xml, StandardCharsets.UTF_8);
		assertFalse(text.contains("\u0000"), text);
		assertFalse(text.contains("\u0007"), text);
	}

	private static PlainDocument notesDocument() {
		return new PlainDocument("Design review", List.of("Nov 12 · 45 min"),
				MarkdownDocumentParser.parseBlocks(NOTES));
	}

	/** Writes the document and returns its parts, in the zip's order. */
	private static Map<String, byte[]> write(PlainDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DocxWriter.write(document, out);
		Map<String, byte[]> parts = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				parts.put(entry.getName(), zip.readAllBytes());
			}
		}
		// The first entry has to be the content-types part for the package to open.
		assertEquals("[Content_Types].xml", parts.keySet().iterator().next(), parts.keySet().toString());
		return parts;
	}

	/** The {@code w:val} of every element with the given qualified tag, in document order. */
	private static List<String> valuesOf(Document document, String localName) {
		NodeList nodes = document.getElementsByTagNameNS(W_NAMESPACE, localName);
		List<String> values = new ArrayList<>();
		for (int index = 0; index < nodes.getLength(); index++) {
			values.add(((Element) nodes.item(index)).getAttributeNS(W_NAMESPACE, "val"));
		}
		return values;
	}

	private static Document parse(byte[] xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setExpandEntityReferences(false);
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
	}
}
