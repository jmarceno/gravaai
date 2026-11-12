package dev.gravaai.services.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Markdown-to-block rules the three writers share. */
class MarkdownDocumentParserTest {

	private static final String NOTES = """
			# Design review

			## Decisions

			- Keep the **compact** cards
			- Add the *hover* preview later

			## Action items

			- [ ] Maria: send the budget
			- [x] Jardel: update the spec

			## Open questions

			1. Do we need a dark theme?
			2. Who owns the empty state?
			""";

	@Test
	@DisplayName("keeps heading levels and section text in order")
	void parsesHeadings() {
		List<DocumentBlock> blocks = MarkdownDocumentParser.parseBlocks(NOTES);

		DocumentBlock.Heading title = assertInstanceOf(DocumentBlock.Heading.class, blocks.get(0));
		assertEquals(1, title.level());
		assertEquals("Design review", title.plainText());
		assertEquals("Decisions", blocks.get(1).plainText());
		assertEquals(2, ((DocumentBlock.Heading) blocks.get(1)).level());
	}

	@Test
	@DisplayName("keeps bold and italic as inline runs")
	void keepsInlineFormatting() {
		List<DocumentBlock> blocks = MarkdownDocumentParser.parseBlocks(NOTES);
		DocumentBlock.ListItem bullet = items(blocks).get(0);

		assertEquals("Keep the compact cards", bullet.plainText());
		assertTrue(bullet.runs().stream().anyMatch(run -> run instanceof InlineRun.Strong));
		DocumentBlock.ListItem second = items(blocks).get(1);
		assertTrue(second.runs().stream().anyMatch(run -> run instanceof InlineRun.Emphasis));
	}

	@Test
	@DisplayName("marks checklists as tasks with their state, once per item")
	void parsesTasks() {
		List<DocumentBlock.ListItem> items = items(MarkdownDocumentParser.parseBlocks(NOTES));
		DocumentBlock.ListItem open = items.stream()
				.filter(item -> item.kind() == DocumentBlock.ListItem.Kind.TASK)
				.filter(item -> !item.checked()).findFirst().orElseThrow();
		DocumentBlock.ListItem done = items.stream()
				.filter(item -> item.kind() == DocumentBlock.ListItem.Kind.TASK)
				.filter(DocumentBlock.ListItem::checked).findFirst().orElseThrow();

		assertEquals("Maria: send the budget", open.plainText());
		assertEquals("Jardel: update the spec", done.plainText());
		// The checkbox must not turn the item into a second bullet.
		assertEquals(2, items.stream()
				.filter(item -> item.kind() == DocumentBlock.ListItem.Kind.TASK).count());
	}

	@Test
	@DisplayName("numbers ordered items from 1 and restarts per list")
	void numbersOrderedLists() {
		List<DocumentBlock.ListItem> ordered = items(MarkdownDocumentParser.parseBlocks(NOTES)).stream()
				.filter(item -> item.kind() == DocumentBlock.ListItem.Kind.ORDERED).toList();

		assertEquals(List.of("Do we need a dark theme?", "Who owns the empty state?"),
				ordered.stream().map(DocumentBlock::plainText).toList());
		assertEquals(List.of(1, 2), ordered.stream().map(DocumentBlock.ListItem::number).toList());
	}

	@Test
	@DisplayName("keeps nesting depth on the item that is nested")
	void keepsNesting() {
		List<DocumentBlock.ListItem> items = items(MarkdownDocumentParser.parseBlocks("""
				- parent
				  - child
				    - grandchild
				"""));

		assertEquals(List.of(0, 1, 2), items.stream().map(DocumentBlock.ListItem::depth).toList());
	}

	@Test
	@DisplayName("turns inline code and links into their own runs and keeps the destination")
	void parsesCodeAndLinks() {
		List<DocumentBlock> blocks = MarkdownDocumentParser.parseBlocks(
				"See `layout.tsx` and the [spec](https://example.com/spec).");

		DocumentBlock.Paragraph paragraph = assertInstanceOf(DocumentBlock.Paragraph.class,
				blocks.get(0));
		assertTrue(paragraph.runs().stream().anyMatch(run -> run instanceof InlineRun.Code code
				&& code.text().equals("layout.tsx")));
		InlineRun.Link link = paragraph.runs().stream()
				.filter(InlineRun.Link.class::isInstance)
				.map(InlineRun.Link.class::cast).findFirst().orElseThrow();
		assertEquals("spec", link.text());
		assertEquals("https://example.com/spec", link.destination());
	}

	@Test
	@DisplayName("renders a quote as its own block")
	void parsesQuotes() {
		List<DocumentBlock> blocks = MarkdownDocumentParser.parseBlocks("> a quiet aside");

		DocumentBlock.Quote quote = assertInstanceOf(DocumentBlock.Quote.class, blocks.get(0));
		assertEquals("a quiet aside", quote.plainText());
	}

	@Test
	@DisplayName("round-trips through Markdown with the original markers")
	void roundTripsMarkdown() {
		PlainDocument document = MarkdownDocumentParser.parse("Design review",
				List.of("2026-11-12"), NOTES);
		String markdown = document.toMarkdown();

		assertTrue(markdown.startsWith("# Design review\n"), markdown);
		assertTrue(markdown.contains("- [ ] Maria: send the budget"), markdown);
		assertTrue(markdown.contains("- [x] Jardel: update the spec"), markdown);
		assertTrue(markdown.contains("1. Do we need a dark theme?"), markdown);
		// And the result parses back to the same items.
		assertEquals(items(document.blocks()), items(MarkdownDocumentParser.parseBlocks(markdown)));
	}

	private static List<DocumentBlock.ListItem> items(List<DocumentBlock> blocks) {
		return blocks.stream().filter(DocumentBlock.ListItem.class::isInstance)
				.map(DocumentBlock.ListItem.class::cast).toList();
	}
}
