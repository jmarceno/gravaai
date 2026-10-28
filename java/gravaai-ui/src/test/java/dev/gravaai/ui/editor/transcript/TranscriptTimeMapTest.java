package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Caption-speed behaviours for transcripts: source <-> timeline mapping at 1x, 2x and 4x. */
class TranscriptTimeMapTest {

	private static TranscriptTimeMap mapAtSpeed(double speed) {
		ProjectEditorState state = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(TranscriptFixtures.speedEditor(speed)));
		return new TranscriptTimeMap(state.clipRegions());
	}

	@Test
	@DisplayName("maps source words to the timeline at 1x, 2x and 4x")
	void mapsSourceToTimeline() {
		for (double speed : new double[] {1, 2, 4}) {
			TranscriptTimeMap map = mapAtSpeed(speed);

			assertEquals(500 / speed, map.sourceToTimelineMs(500), 0.001,
					"source start / speed at " + speed + "x");
			assertEquals(4_000 / speed, map.sourceToTimelineMs(4_000), 0.001,
					"source end / speed at " + speed + "x");
			assertEquals(500 / speed / 1000.0, map.seekSecondsForSourceMs(500), 0.001);
			assertEquals((4_000 / speed), map.timelineDurationMs(4_000, speed), 0.001);
		}
	}

	@Test
	@DisplayName("maps timeline times back to source and finds the spoken word")
	void mapsTimelineToSource() {
		TranscriptTimeMap map = mapAtSpeed(2);
		List<TranscriptWords.Word> words = TranscriptWords.flatten(
				TranscriptFixtures.generatedCues());

		assertEquals(1_000, map.timelineToSourceMs(500), 0.001);
		assertEquals(2_000, map.timelineToSourceMs(1_000), 0.001);
		TranscriptWords.Word active = map.activeWord(words, 1_375);
		assertEquals("cue-1:2", active == null ? null : active.key(),
				"timeline 1375ms is source 2750ms = the word three at 2x");
	}

	@Test
	@DisplayName("returns null outside the kept footage")
	void returnsNullOutsideClips() {
		TranscriptTimeMap map = mapAtSpeed(1);

		assertNull(map.timelineToSourceMs(7_000));
		assertNull(map.sourceToTimelineMs(9_000));
		assertNull(map.seekSecondsForSourceMs(9_000));
	}
}
