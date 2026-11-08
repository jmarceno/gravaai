package dev.gravaai.services.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unique-string cache is the piece that keeps PII detection off the critical path: screen text
 * repeats on every sample, so the detector should see each distinct line exactly once.
 */
class PiiCacheTest {

	private static final PiiDetector.PiiSpan SPAN =
			new PiiDetector.PiiSpan("email", 0, 5, 0.9f);

	@Test
	@DisplayName("repeated lines run the detector once and come back from the cache")
	void repeatedTextIsCached() {
		int[] calls = {0};
		PiiDetector detector = new PiiDetector() {
			@Override
			public List<PiiSpan> detect(String text) {
				calls[0]++;
				return List.of(SPAN);
			}

			@Override
			public String describe() {
				return "fake";
			}
		};
		PiiCache cache = new PiiCache(detector);
		List<PiiDetector.PiiSpan> first = cache.detect("john.smith@example.com");
		for (int i = 0; i < 100; i++) {
			assertSame(first, cache.detect("john.smith@example.com"),
					"a cached line must return the same immutable snapshot");
		}
		assertEquals(1, calls[0], "the detector must see a repeated line once");
		assertEquals(1, cache.distinctTexts());
		assertEquals(100, cache.hits());
		assertEquals(1, cache.misses());
	}

	@Test
	@DisplayName("distinct lines each reach the detector")
	void distinctLinesAreDetected() {
		int[] calls = {0};
		PiiDetector detector = new PiiDetector() {
			@Override
			public List<PiiSpan> detect(String text) {
				calls[0]++;
				return text.contains("@") ? List.of(SPAN) : List.of();
			}

			@Override
			public String describe() {
				return "fake";
			}
		};
		PiiCache cache = new PiiCache(detector);
		assertTrue(cache.detect("nothing here").isEmpty());
		assertEquals(List.of(SPAN), cache.detect("a@b.co"));
		assertEquals(2, calls[0]);
		assertEquals(2, cache.distinctTexts());
		assertEquals(0, cache.hits());
	}

	@Test
	@DisplayName("clear() resets the counters for a fresh run")
	void clearResets() {
		PiiDetector detector = new PiiDetector() {
			@Override
			public List<PiiSpan> detect(String text) {
				return List.of();
			}

			@Override
			public String describe() {
				return "fake";
			}
		};
		PiiCache cache = new PiiCache(detector);
		cache.detect("a");
		cache.detect("a");
		cache.clear();
		assertEquals(0, cache.distinctTexts());
		assertEquals(0, cache.hits());
		assertEquals(0, cache.misses());
	}
}
