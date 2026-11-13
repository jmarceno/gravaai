package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;

/** Draw sessions (source time) projected onto the edited timeline. */
class InkMarkTest {

	private static ClipRegion clip(String id, long startMs, long endMs, long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	@Test
	@DisplayName("an untouched recording keeps the session where it was drawn")
	void identity() {
		List<InkMark> marks = InkMark.project(List.of(new InkMark.Session(2000, 5000, 3)),
				List.of(clip("c", 0, 10_000, 0, 1)));
		assertEquals(List.of(new InkMark(2000, 5000, 2000, 5000, 3)), marks);
	}

	@Test
	@DisplayName("a cut through a session splits it; cut footage has no band; speed shortens it")
	void cutsAndSpeed() {
		// Source 0..3 s plays as-is, source 4..8 s plays at 2x from timeline 3 s.
		List<ClipRegion> clips = List.of(clip("a", 0, 3000, 0, 1), clip("b", 3000, 5000, 4000, 2));
		List<InkMark> marks = InkMark.project(List.of(new InkMark.Session(2000, 6000, 5),
				new InkMark.Session(3200, 3800, 1)), clips);
		assertEquals(List.of(new InkMark(2000, 3000, 2000, 6000, 5), new InkMark(3000, 4000, 2000, 6000, 5)),
				marks);
	}
}
