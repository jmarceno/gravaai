package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Latest-wins scrub coalescing (plan §5.9: "coalesce scrub requests, latest wins"). */
class ScrubCoalescerTest {

	@Test
	@DisplayName("keeps only the newest scrub position")
	void keepsOnlyTheNewestScrub() {
		ScrubCoalescer coalescer = new ScrubCoalescer();
		assertNull(coalescer.takeLatest());
		coalescer.submit(1.0);
		long lastGeneration = coalescer.submit(2.5);
		coalescer.submit(3.75);
		assertTrue(coalescer.hasPending());
		ScrubCoalescer.Pending pending = coalescer.takeLatest();
		assertEquals(3.75, pending.timelineSeconds());
		assertEquals(lastGeneration + 1, pending.generation());
		assertFalse(coalescer.hasPending());
		assertNull(coalescer.takeLatest());
	}

	@Test
	@DisplayName("rejects a non-finite scrub position as the start")
	void nonFiniteScrubBecomesZero() {
		ScrubCoalescer coalescer = new ScrubCoalescer();
		coalescer.submit(Double.NaN);
		assertEquals(0.0, coalescer.takeLatest().timelineSeconds());
	}
}
