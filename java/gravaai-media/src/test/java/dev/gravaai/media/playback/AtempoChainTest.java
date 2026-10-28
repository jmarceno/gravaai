package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.audio.AtempoChain;

/** Tests for the atempo chaining used by preview speed regions (plan §5.9). */
class AtempoChainTest {

	@Test
	@DisplayName("normal speed needs no filter")
	void normalSpeedNeedsNoFilter() {
		assertEquals(List.of(), AtempoChain.factors(1));
		assertEquals(List.of(), AtempoChain.filters(1));
		assertEquals("", AtempoChain.filterChain(1));
	}

	@Test
	@DisplayName("keeps every atempo factor inside the supported 0.5..2 range")
	void keepsFactorsInsideSupportedRange() {
		for (double rate : new double[] {0.25, 0.5, 0.75, 1.25, 1.5, 2, 3, 4, 0.125, 0.3}) {
			double product = 1;
			for (double factor : AtempoChain.factors(rate)) {
				assertEquals(true, factor >= AtempoChain.MIN_FACTOR && factor <= AtempoChain.MAX_FACTOR,
						"factor " + factor + " for rate " + rate);
				product *= factor;
			}
			assertEquals(rate, product, 1e-9);
		}
	}

	@Test
	@DisplayName("chains atempo for slow and fast regions")
	void chainsAtempoForSlowAndFastRegions() {
		assertEquals(List.of("atempo=0.5"), AtempoChain.filters(0.5));
		assertEquals(List.of("atempo=0.5", "atempo=0.5"), AtempoChain.filters(0.25));
		assertEquals(List.of("atempo=0.5", "atempo=0.5", "atempo=0.5"), AtempoChain.filters(0.125));
		assertEquals(List.of("atempo=2", "atempo=1.5"), AtempoChain.filters(3));
		assertEquals(List.of("atempo=2", "atempo=2"), AtempoChain.filters(4));
		assertEquals(List.of("atempo=0.5", "atempo=0.6"), AtempoChain.filters(0.3));
		assertEquals(List.of("atempo=1.25"), AtempoChain.filters(1.25));
		assertEquals("atempo=2,atempo=1.5", AtempoChain.filterChain(3));
	}

	@Test
	@DisplayName("rejects a non-positive or non-finite rate")
	void rejectsInvalidRates() {
		assertThrows(IllegalArgumentException.class, () -> AtempoChain.filters(0));
		assertThrows(IllegalArgumentException.class, () -> AtempoChain.filters(-2));
		assertThrows(IllegalArgumentException.class, () -> AtempoChain.filters(Double.NaN));
	}
}
