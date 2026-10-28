package dev.gravaai.services.captions;

import java.util.List;

/** One caption cue ({@code CaptionCuePayload} in electron/ipc/types.ts). */
public record CaptionCue(String id, long startMs, long endMs, String text, List<CaptionWord> words) {

	public CaptionCue {
		if (id == null || text == null) {
			throw new IllegalArgumentException("id and text must not be null");
		}
		words = words == null ? List.of() : List.copyOf(words);
	}

	public CaptionCue(String id, long startMs, long endMs, String text) {
		this(id, startMs, endMs, text, List.of());
	}

	public boolean hasWords() {
		return !words.isEmpty();
	}

	public CaptionCue withWords(List<CaptionWord> nextWords) {
		return new CaptionCue(id, startMs, endMs, text, nextWords);
	}

	public CaptionCue withTiming(long startMs, long endMs) {
		return new CaptionCue(id, startMs, endMs, text, words);
	}

	public CaptionCue withText(String nextText) {
		return new CaptionCue(id, startMs, endMs, nextText, words);
	}

	public CaptionCue withId(String nextId) {
		return new CaptionCue(nextId, startMs, endMs, text, words);
	}
}
