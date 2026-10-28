package dev.gravaai.services.captions;

/** One timed word ({@code CaptionWordPayload} in electron/ipc/types.ts). */
public record CaptionWord(String text, long startMs, long endMs, boolean leadingSpace) {

	public CaptionWord {
		if (text == null) {
			throw new IllegalArgumentException("text must not be null");
		}
	}

	/** A word without the leading-space flag. */
	public static CaptionWord of(String text, long startMs, long endMs) {
		return new CaptionWord(text, startMs, endMs, false);
	}
}
