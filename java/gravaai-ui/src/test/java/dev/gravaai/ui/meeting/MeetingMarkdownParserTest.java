package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the parsing rules of {@code MeetingMarkdown.tsx}. */
class MeetingMarkdownParserTest {

	@Test
	@DisplayName("parses leading [HH:MM:SS] timestamps")
	void parsesTimestamps() {
		assertEquals(5025, MeetingMarkdownParser.parseTimestampSeconds("[01:23:45] hello"));
		assertEquals(0, MeetingMarkdownParser.parseTimestampSeconds("[00:00:00] start"));
		assertNull(MeetingMarkdownParser.parseTimestampSeconds("no timestamp"));
		assertNull(MeetingMarkdownParser.parseTimestampSeconds(null));
		assertEquals(11, MeetingMarkdownParser.timestampPrefixLength("[01:02:03] rest"));
		assertEquals(0, MeetingMarkdownParser.timestampPrefixLength("rest"));
	}

	@Test
	@DisplayName("parses headings level 1-4 and leaves deeper hashes as paragraphs")
	void parsesHeadings() {
		assertEquals(new MeetingMarkdownParser.Heading(1, "Notes"),
				MeetingMarkdownParser.parse("# Notes").get(0));
		assertEquals(new MeetingMarkdownParser.Heading(4, "Deep"),
				MeetingMarkdownParser.parse("#### Deep").get(0));
		assertEquals(new MeetingMarkdownParser.Paragraph("##### Too deep"),
				MeetingMarkdownParser.parse("##### Too deep").get(0));
	}

	@Test
	@DisplayName("parses bullets with the React indent depth")
	void parsesBulletDepth() {
		assertEquals(new MeetingMarkdownParser.Bullet(0, null, "item"),
				MeetingMarkdownParser.parse("- item").get(0));
		assertEquals(new MeetingMarkdownParser.Bullet(1, null, "nested"),
				MeetingMarkdownParser.parse("  - nested").get(0));
		assertEquals(new MeetingMarkdownParser.Bullet(2, null, "deeper"),
				MeetingMarkdownParser.parse("    - deeper").get(0));
		assertEquals(new MeetingMarkdownParser.Bullet(3, null, "capped"),
				MeetingMarkdownParser.parse("        - capped").get(0));
	}

	@Test
	@DisplayName("parses checklist items and keeps checked state")
	void parsesChecklists() {
		assertEquals(new MeetingMarkdownParser.Bullet(0, false, "todo"),
				MeetingMarkdownParser.parse("- [ ] todo").get(0));
		assertEquals(new MeetingMarkdownParser.Bullet(0, true, "done"),
				MeetingMarkdownParser.parse("- [x] done").get(0));
		assertEquals(new MeetingMarkdownParser.Bullet(0, true, "Done"),
				MeetingMarkdownParser.parse("- [X] Done").get(0));
	}

	@Test
	@DisplayName("parses paragraphs, trims trailing whitespace and skips blank lines")
	void parsesParagraphs() {
		List<MeetingMarkdownParser.Block> blocks = MeetingMarkdownParser.parse(
				"hello world   \n\nsecond line\n");
		assertEquals(List.of(new MeetingMarkdownParser.Paragraph("hello world"),
				new MeetingMarkdownParser.Paragraph("second line")), blocks);
		assertTrue(MeetingMarkdownParser.parse("").isEmpty());
		assertTrue(MeetingMarkdownParser.parse(null).isEmpty());
	}

	@Test
	@DisplayName("keeps inline markup untouched in the block text")
	void keepsInlineMarkup() {
		assertEquals(new MeetingMarkdownParser.Paragraph("say **hello** now"),
				MeetingMarkdownParser.parse("say **hello** now").get(0));
	}
}
