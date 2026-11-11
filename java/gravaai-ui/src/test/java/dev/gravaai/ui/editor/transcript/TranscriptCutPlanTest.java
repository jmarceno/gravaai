package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one-click filler/pause batches: what is proposed, what it costs, and what it never touches. */
class TranscriptCutPlanTest {

	private static List<TranscriptWords.Word> words() {
		return TranscriptWords.flatten(TranscriptFixtures.generatedCues());
	}

	private static List<ClipRegion> fullClip() {
		return List.of(new ClipRegion("clip-1", 0, 6_000, 0L, null, null, 1, null, null, null,
				null));
	}

	@Test
	@DisplayName("fillers plan cuts the filler word plus its trailing silence")
	void plansFillers() {
		TranscriptCutPlan plan = TranscriptCutPlan.fillers(words(), fullClip(), "en");

		assertEquals(List.of(new TranscriptCut.SourceRange(1_500, 1_950)), plan.ranges(),
				"um plays 1500-1700 and the cut swallows the 250ms of silence after it");
		assertEquals(Set.of("cue-1:1"), plan.wordKeys());
		assertEquals(450, plan.removedMs());
		assertFalse(plan.isEmpty());
	}

	@Test
	@DisplayName("fillers already cut are not proposed again")
	void skipsAlreadyCutFillers() {
		// The filler's source window is gone: only 2000-6000 is still on the timeline.
		List<ClipRegion> clips = List.of(
				new ClipRegion("clip-a", 0, 500, 0L, null, null, 1, null, null, null, null),
				new ClipRegion("clip-b", 500, 4_500, 2_000L, null, null, 1, null, null, null,
						null));

		TranscriptCutPlan plan = TranscriptCutPlan.fillers(words(), clips, "en");

		assertTrue(plan.isEmpty());
		assertEquals(0, plan.removedMs());
	}

	@Test
	@DisplayName("a word whose start was cut is only cut from the footage that remains")
	void clipsRangesToKeptFootage() {
		// The clip resumes inside the filler's trailing gap: the plan must not ask for the gone part.
		List<ClipRegion> clips = List.of(
				new ClipRegion("clip-a", 0, 1_800, 0L, null, null, 1, null, null, null, null));

		TranscriptCutPlan plan = TranscriptCutPlan.fillers(words(), clips, "en");

		assertEquals(List.of(new TranscriptCut.SourceRange(1_500, 1_800)), plan.ranges());
		assertEquals(300, plan.removedMs());
	}

	@Test
	@DisplayName("pauses plan trims every long silence down to the kept amount")
	void plansPauses() {
		// one 500-1000, um 1500-1700, three 2500-3000, four 3500-4000: gaps of 500, 800 and 500ms.
		TranscriptCutPlan plan = TranscriptCutPlan.pauses(words(), fullClip(), 500, 200);

		assertEquals(List.of(new TranscriptCut.SourceRange(1_200, 1_500),
				new TranscriptCut.SourceRange(1_900, 2_500),
				new TranscriptCut.SourceRange(3_200, 3_500)), plan.ranges());
		assertTrue(plan.wordKeys().isEmpty());
		assertEquals(1_200, plan.removedMs());
		assertFalse(plan.isEmpty());
	}

	@Test
	@DisplayName("a minimum pause above every gap proposes nothing")
	void ignoresShortPauses() {
		TranscriptCutPlan plan = TranscriptCutPlan.pauses(words(), fullClip(), 1_000, 100);

		assertTrue(plan.isEmpty());
		assertEquals(0, plan.removedMs());
	}

	@Test
	@DisplayName("pause lengths are measured on the timeline, so clip speed is accounted for")
	void measuresPausesInTimelineTime() {
		// A 2x clip: the source gaps of 500 and 800ms are half as long to the viewer (250 and
		// 400ms), so a 500ms minimum finds nothing while a 300ms one trims the long gap.
		List<ClipRegion> clips = List.of(new ClipRegion("clip-1", 0, 3_000, 0L, null, null, 2,
				null, null, null, null));

		assertTrue(TranscriptCutPlan.pauses(words(), clips, 500, 0).isEmpty());

		TranscriptCutPlan plan = TranscriptCutPlan.pauses(words(), clips, 300, 0);
		assertEquals(List.of(new TranscriptCut.SourceRange(1_700, 2_500)), plan.ranges(),
				"um -> three is 800ms of source but only 400ms on screen");
		assertEquals(800, plan.removedSourceMs());
		assertEquals(400, plan.removedMs(), "at 2x the timeline loses half the source time");
	}

	@Test
	@DisplayName("pauses keep working after an earlier cut split the clip")
	void composesWithAnEarlierCut() {
		// Remove filler words first: the sequence is two clips and um is gone. The 800ms gap between
		// um and three is no longer on the timeline, but the silence one -> three (550ms now) and
		// the 500ms before four still count.
		List<ClipRegion> afterFillerCut = List.of(
				new ClipRegion("clip-1", 0, 1_500, 0L, null, null, 1, null, null, null, null),
				new ClipRegion("clip-2", 1_500, 5_550, 1_950L, null, null, 1, null, null, null,
						null));

		TranscriptCutPlan plan = TranscriptCutPlan.pauses(words(), afterFillerCut, 500, 200);

		assertEquals(List.of(new TranscriptCut.SourceRange(1_200, 1_500),
				new TranscriptCut.SourceRange(1_950, 2_500),
				new TranscriptCut.SourceRange(3_200, 3_500)), plan.ranges(),
				"one -> three keeps 200ms; the pause before four is measured on the edited timeline");
		assertEquals(1_150, plan.removedMs());
	}
}
