package dev.gravaai.services.privacy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Caches PII detections by text.
 *
 * <p>The plan calls this out as the difference between ~5–15 s and minutes of PII time on a five
 * minute video: screen text repeats heavily (window titles, labels, menu items are on screen for
 * hundreds of consecutive samples), so the expensive detector only has to see each distinct line
 * once. The cache is per-run — a fresh analysis pass starts empty, because the detector's label set
 * or threshold may have changed.</p>
 *
 * <p>Not thread-safe by contract: one analysis pass owns it and calls it from its single worker.</p>
 */
public final class PiiCache {

	private final PiiDetector detector;
	private final Map<String, List<PiiDetector.PiiSpan>> byText = new HashMap<>();
	private int hits;
	private int misses;

	public PiiCache(PiiDetector detector) {
		this.detector = detector;
	}

	/** The detector's spans for {@code text}, computed once per distinct line. */
	public List<PiiDetector.PiiSpan> detect(String text) {
		List<PiiDetector.PiiSpan> cached = byText.get(text);
		if (cached != null) {
			hits++;
			return cached;
		}
		misses++;
		List<PiiDetector.PiiSpan> detected = List.copyOf(detector.detect(text));
		byText.put(text, detected);
		return detected;
	}

	/** Distinct lines seen so far — the number of real detector calls. */
	public int distinctTexts() {
		return byText.size();
	}

	/** Lookups served from the cache (repeat sightings). */
	public int hits() {
		return hits;
	}

	/** Lookups that had to run the detector. */
	public int misses() {
		return misses;
	}

	/** Drops every entry, e.g. when a new analysis pass starts. */
	public void clear() {
		byText.clear();
		hits = 0;
		misses = 0;
	}
}
