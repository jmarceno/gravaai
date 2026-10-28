package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code renderInline} in {@code MeetingMarkdown.tsx}. */
class MeetingInlineTest {

	@Test
	@DisplayName("tokenizes bold, code and italic runs in order")
	void tokenizesRuns() {
		assertEquals(List.of(new MeetingInline.Text("hello "), new MeetingInline.Bold("bold"),
				new MeetingInline.Text(" world")), MeetingInline.tokenize("hello **bold** world"));
		assertEquals(List.of(new MeetingInline.Text("use "), new MeetingInline.Code("x"),
				new MeetingInline.Text(" here")),
				MeetingInline.tokenize("use \u0060x\u0060 here"));
		assertEquals(List.of(new MeetingInline.Italic("it")), MeetingInline.tokenize("*it*"));
		assertEquals(List.of(new MeetingInline.Italic("it")), MeetingInline.tokenize("_it_"));
	}

	@Test
	@DisplayName("keeps unmatched markup as literal text")
	void keepsUnmatchedMarkup() {
		assertEquals(List.of(new MeetingInline.Text("*")), MeetingInline.tokenize("*"));
		assertEquals(List.of(new MeetingInline.Text("**")), MeetingInline.tokenize("**"));
		assertEquals(List.of(new MeetingInline.Text("a * b")), MeetingInline.tokenize("a * b"));
	}

	@Test
	@DisplayName("returns no tokens for empty input")
	void emptyInput() {
		assertTrue(MeetingInline.tokenize("").isEmpty());
		assertTrue(MeetingInline.tokenize(null).isEmpty());
	}
}
