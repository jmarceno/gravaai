package dev.gravaai.ui.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * The playing-line highlight of the meeting audio player (the state machine behind the markdown
 * view): the last {@code [HH:MM:SS]} line at or before the playhead is active, a re-render resets
 * the highlight, and the notes pane can fetch a line's row to scroll it into view. Pure nodes, so
 * no JavaFX toolkit is needed (the {@code javafx.scene.control} rendering around it cannot init
 * headless).
 */
class MeetingTranscriptLinesTest {

	@Test
	@DisplayName("the playhead highlights the last line at or before it")
	void playheadHighlightsTheLastLineAtOrBefore() {
		MeetingTranscriptLines lines = new MeetingTranscriptLines();
		Node line12 = new Region();
		Node line65 = new Region();
		Node line150 = new Region();
		lines.index(12, line12);
		lines.index(65, line65);
		lines.index(150, line150);

		assertNull(lines.highlight(0), "before the first timestamp nothing is active");
		assertNull(lines.activeSeconds());
		assertTrue(line12.getStyle().isEmpty());

		assertEquals(12, lines.highlight(12));
		assertEquals(12, lines.activeSeconds());
		assertTrue(line12.getStyle().contains("background-color"), "the active line is marked");

		// Between lines the previous line stays highlighted; fractional playheads floor to the line.
		assertEquals(12, lines.highlight(50));
		assertEquals(65, lines.highlight(65.4));
		assertEquals(65, lines.activeSeconds());
		assertTrue(line12.getStyle().isEmpty(), "the previous line is cleared");

		assertEquals(150, lines.highlight(200));
		assertNotNull(lines.rowFor(150));
	}

	@Test
	@DisplayName("a fresh render resets the highlight")
	void clearResetsTheHighlight() {
		MeetingTranscriptLines lines = new MeetingTranscriptLines();
		Node line = new Region();
		lines.index(12, line);
		assertEquals(12, lines.highlight(12));

		lines.clear();
		assertNull(lines.activeSeconds());
		assertTrue(line.getStyle().isEmpty(), "the stale highlight style is gone");

		// The re-render re-indexes the rebuilt rows, and highlighting works again.
		Node rebuilt = new Region();
		lines.index(12, rebuilt);
		assertEquals(12, lines.highlight(12.5), "the highlight works after a re-render");
	}

	@Test
	@DisplayName("the row lookup covers the lines the seek plumbing needs")
	void rowLookup() {
		MeetingTranscriptLines lines = new MeetingTranscriptLines();
		Node line = new Region();
		lines.index(65, line);

		assertNotNull(lines.rowFor(65));
		assertNull(lines.rowFor(12), "no row for a line that does not exist");
	}
}
