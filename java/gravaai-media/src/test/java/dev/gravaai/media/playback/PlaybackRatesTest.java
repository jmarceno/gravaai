package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ported from {@code src/components/video-editor/videoPlayback/playbackRate.test.ts}: the DOM
 * runtime probe becomes an injected predicate, everything else (caching, invalid inputs, quarter
 * step range) is identical.
 */
class PlaybackRatesTest {

	@Test
	@DisplayName("probes and caches the runtime's accepted rates")
	void probesAndCachesAcceptedRates() {
		AtomicInteger calls = new AtomicInteger();
		PlaybackRates rates = new PlaybackRates(rate -> {
			calls.incrementAndGet();
			return rate <= 16;
		});

		for (double speed : new double[] {0.25, 1, 3, 15, 16}) {
			assertTrue(rates.supportsPreviewPlaybackRate(speed));
		}
		for (double speed : new double[] {20, 30}) {
			assertFalse(rates.supportsPreviewPlaybackRate(speed));
		}
		assertFalse(rates.supportsPreviewPlaybackRate(20));
		assertEquals(7, calls.get());
		for (double speed : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(rates.supportsPreviewPlaybackRate(speed));
		}
	}

	@Test
	@DisplayName("exposes only supported steps between the accepted bounds")
	void exposesOnlySupportedQuarterSteps() {
		assertEquals(
				new PlaybackRates.Range(0.25, 16),
				new PlaybackRates(rate -> rate >= 0.25 && rate <= 16)
						.getPreviewPlaybackRateRange());
		assertEquals(
				new PlaybackRates.Range(0.5, 4),
				new PlaybackRates(rate -> rate >= 0.5 && rate <= 4)
						.getPreviewPlaybackRateRange());
		assertEquals(
				new PlaybackRates.Range(1, 30),
				new PlaybackRates(rate -> rate >= 1 && rate <= 30)
						.getPreviewPlaybackRateRange());
	}

	@Test
	@DisplayName("the preview engine accepts quarter steps from 0.25x to 30x")
	void engineAcceptanceRange() {
		PlaybackRates rates = new PlaybackRates(PlaybackRates.engineProbe());
		assertEquals(new PlaybackRates.Range(0.25, 30), rates.getPreviewPlaybackRateRange());
		assertTrue(rates.supportsPreviewPlaybackRate(0.25));
		assertTrue(rates.supportsPreviewPlaybackRate(30));
		assertFalse(rates.supportsPreviewPlaybackRate(0.1));
		assertFalse(rates.supportsPreviewPlaybackRate(30.25));
	}
}
