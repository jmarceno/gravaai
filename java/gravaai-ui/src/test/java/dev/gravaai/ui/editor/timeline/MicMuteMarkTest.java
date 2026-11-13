package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;

/** Muted spans (source time) projected onto the edited timeline. */
class MicMuteMarkTest {

	private static ClipRegion clip(String id, long startMs, long endMs, long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	@Test
	@DisplayName("an untouched recording keeps the span where it was recorded")
	void identity() {
		List<MicMuteMark> marks = MicMuteMark.project(List.of(new MicMuteMark.Session(2000, 5000)),
				List.of(clip("c", 0, 10_000, 0, 1)));
		assertEquals(List.of(new MicMuteMark(2000, 5000, 2000, 5000)), marks);
	}

	@Test
	@DisplayName("a cut through a span splits it; cut footage has no band; speed shortens it")
	void cutsAndSpeed() {
		// Source 0..3 s plays as-is, source 4..8 s plays at 2x from timeline 3 s.
		List<ClipRegion> clips = List.of(clip("a", 0, 3000, 0, 1), clip("b", 3000, 5000, 4000, 2));
		List<MicMuteMark> marks = MicMuteMark.project(
				List.of(new MicMuteMark.Session(2000, 6000), new MicMuteMark.Session(3200, 3800)), clips);
		assertEquals(List.of(new MicMuteMark(2000, 3000, 2000, 6000), new MicMuteMark(3000, 4000, 2000, 6000)),
				marks);
	}
}
