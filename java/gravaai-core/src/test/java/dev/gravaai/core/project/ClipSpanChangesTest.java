package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code clipSpanChange.test.ts}. */
class ClipSpanChangesTest {

	private static final ClipRegion CLIP =
			new ClipRegion("clip", 1000, 4000, 3000L, null, null, 3, null, null, null, null);

	@Test
	@DisplayName("moves footage without changing its source window")
	void movesFootage() {
		ClipRegion moved = ClipSpanChanges.changeClipSpan(CLIP, 2000, 5000, 12000);
		assertEquals(new ClipRegion("clip", 2000, 5000, 3000L, null, null, 3, null, null, null, null),
				moved);
	}

	@Test
	@DisplayName("advances the source in-point by the trimmed duration times speed")
	void advancesSourceInPoint() {
		ClipRegion resized = ClipSpanChanges.changeClipSpan(CLIP, 2000, 4000, 12000);
		assertEquals(new ClipRegion("clip", 2000, 4000, 6000L, null, null, 3, null, null, null, null),
				resized);
	}

	@Test
	@DisplayName("does not extend beyond source EOF after a move")
	void clampsAtSourceEof() {
		ClipRegion moved = ClipSpanChanges.changeClipSpan(CLIP, 2000, 5000, 12000);
		assertEquals(moved, ClipSpanChanges.changeClipSpan(moved, 2000, 6000, 12000));
	}

	@Test
	@DisplayName("does not extend a moved clip before its source starts")
	void clampsAtSourceStart() {
		ClipRegion moved = new ClipRegion("clip", 2000, 6000, 0L, null, null, 3, null, null, null, null);
		assertEquals(moved, ClipSpanChanges.changeClipSpan(moved, 1000, 6000, 12000));
	}

	@Test
	@DisplayName("does not reveal a neighboring recording when extending an imported clip")
	void doesNotRevealNeighbor() {
		ClipRegion imported = new ClipRegion("imported", 0, 2000, 10000L, 10000L, 12000L, 1,
				null, null, null, null);
		assertEquals(imported, ClipSpanChanges.changeClipSpan(imported, -500, 2000, 20000));
		assertEquals(imported, ClipSpanChanges.changeClipSpan(imported, 0, 2500, 20000));
	}

	@ParameterizedTest(name = "uses normal speed when resizing corrupt speed {0}")
	@ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
	@DisplayName("uses normal speed when resizing corrupt speed")
	void usesNormalSpeedForCorruptSpeed(double speed) {
		ClipRegion result = ClipSpanChanges.changeClipSpan(
				new ClipRegion("bad", 0, 3000, 0L, null, null, speed, null, null, null, null),
				1000, 3000, 5000);
		assertEquals(new ClipRegion("bad", 1000, 3000, 1000L, null, null, speed, null, null, null, null),
				result);
	}
}
