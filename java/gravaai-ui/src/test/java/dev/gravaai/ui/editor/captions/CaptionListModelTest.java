package dev.gravaai.ui.editor.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.gravaai.core.project.CaptionCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure decisions of the caption list editor. */
class CaptionListModelTest {

	private static CaptionCue cue(String id, long start, long end, String text) {
		return new CaptionCue(id, start, end, text, null);
	}

	@Test
	@DisplayName("formats and parses caption timecodes")
	void formatsAndParsesTimecodes() {
		assertEquals("0:05.000", CaptionListModel.formatTimecode(5_000));
		assertEquals("2:05.500", CaptionListModel.formatTimecode(125_500));
		assertEquals(5_500, CaptionListModel.parseTimecode("0:05.500").orElseThrow());
		assertEquals(5_250, CaptionListModel.parseTimecode("5.25").orElseThrow());
		assertEquals(62_300, CaptionListModel.parseTimecode("1:02.300").orElseThrow());
		assertTrue(CaptionListModel.parseTimecode("nope").isEmpty());
		assertTrue(CaptionListModel.parseTimecode("x:y").isEmpty());
	}

	@Test
	@DisplayName("keeps the selected cue index and merge availability")
	void resolvesSelectionAndMerge() {
		List<CaptionCue> cues = List.of(cue("a", 0, 1000, "one"), cue("b", 1200, 2000, "two"));

		assertEquals(1, CaptionListModel.selectedIndex(cues, "b"));
		assertEquals(-1, CaptionListModel.selectedIndex(cues, "missing"));
		assertEquals(-1, CaptionListModel.selectedIndex(cues, null));
		assertTrue(CaptionListModel.canMerge(0, 2));
		assertFalse(CaptionListModel.canMerge(1, 2));
		assertEquals("b", CaptionListModel.mergeTarget(cues, 0).orElseThrow().id());
		assertTrue(CaptionListModel.mergeTarget(cues, 1).isEmpty());
	}

	@Test
	@DisplayName("clamps the split position to the cue span")
	void clampsSplitPosition() {
		CaptionCue cue = cue("a", 1000, 2000, "one two");

		assertEquals(1000, CaptionListModel.splitAt(cue, 200));
		assertEquals(1500, CaptionListModel.splitAt(cue, 1500));
		assertEquals(2000, CaptionListModel.splitAt(cue, 9000));
	}

	@Test
	@DisplayName("commits a normalized caption text only when it changed")
	void commitsNormalizedText() {
		CaptionCue cue = cue("a", 1000, 2000, "Hello");

		assertEquals("Hi there", CaptionListModel.decideTextCommit("  Hi \n\n there ", cue));
		assertNull(CaptionListModel.decideTextCommit("Hello", cue));
		assertNull(CaptionListModel.decideTextCommit("   ", cue));
		assertNull(CaptionListModel.decideTextCommit(null, cue));
	}

	@Test
	@DisplayName("rejects invalid or unchanged caption timing drafts")
	void rejectsInvalidTiming() {
		CaptionCue cue = cue("a", 1000, 2000, "Hello");

		CaptionListModel.TimingCommit commit = CaptionListModel
				.decideTimingCommit("0:00.500", "0:02.500", cue);
		assertEquals(500, commit.startMs());
		assertEquals(2_500, commit.endMs());

		assertNull(CaptionListModel.decideTimingCommit("bad", "0:02.500", cue));
		assertNull(CaptionListModel.decideTimingCommit("0:02.500", "0:01.500", cue));
		assertNull(CaptionListModel.decideTimingCommit("0:01.000", "0:02.000", cue));
	}

	@Test
	@DisplayName("builds the word-aware edit target for a cue")
	void buildsWordAwareEditTarget() {
		CaptionCue cue = new CaptionCue("a", 1000, 2000, "Hello Hello",
				List.of(new dev.gravaai.core.project.CaptionCueWord("Hello", 1000, 1500, false),
						new dev.gravaai.core.project.CaptionCueWord("Hello", 1500, 2000, true)));

		dev.gravaai.core.project.CaptionEditing.EditTarget target = CaptionListModel
				.editTarget(cue);

		assertEquals("a", target.id());
		assertEquals(1000, target.startMs());
		assertEquals(2000, target.endMs());
		assertEquals(2, target.words().size());
		assertEquals(0, target.words().get(0).cueWordIndex());
		assertFalse(target.words().get(0).leadingSpace());
		assertTrue(target.words().get(1).leadingSpace());
	}

	@Test
	@DisplayName("clears the selection when the selected caption is deleted")
	void clearsSelectionOnDelete() {
		assertNull(CaptionListModel.selectionAfterDelete("a", "a"));
		assertEquals("b", CaptionListModel.selectionAfterDelete("b", "a"));
	}
}
