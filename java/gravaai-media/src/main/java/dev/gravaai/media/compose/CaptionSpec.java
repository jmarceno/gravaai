package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Captions for one compositor frame (plan §5.8 stage 9), media-local like {@link FrameInput}:
 * the resolved auto-caption settings plus the cues that can be visible at the frame time.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public record CaptionSpec(
		boolean enabled,
		String fontFamily,
		@Nullable double fontSize,
		@Nullable double bottomOffset,
		@Nullable double maxWidth,
		@Nullable int maxRows,
		String animationStyle,
		@Nullable double boxRadius,
		@Nullable String textColor,
		String inactiveTextColor,
		double backgroundOpacity,
		List<Cue> cues) {

	/** One caption cue ({@code startMs..endMs} inclusive at the end like the renderer). */
	public record Cue(String id, long startMs, long endMs, String text,
			@Nullable List<dev.gravaai.core.project.CaptionCueWord> words) {

		public Cue {
			words = words == null ? List.of() : List.copyOf(words);
		}

		/** A cue without word timings (proportional timing, like the TS no-timing path). */
		public Cue(String id, long startMs, long endMs, String text) {
			this(id, startMs, endMs, text, List.of());
		}
	}

	/** No captions. */
	public static final CaptionSpec DISABLED = new CaptionSpec(false,
			"\"SF Pro Text\", \"SF Pro Display\", \"Helvetica Neue\", sans-serif", 30, 3, 62, 1,
			"none", 17.5, "#FFFFFF", "#A3A3A3", 0.9, List.of());

	public CaptionSpec {
		cues = cues == null ? List.of() : List.copyOf(cues);
		fontFamily = fontFamily == null || fontFamily.isBlank()
				? "\"SF Pro Text\", \"SF Pro Display\", \"Helvetica Neue\", sans-serif"
				: fontFamily;
		animationStyle = animationStyle == null ? "none" : animationStyle;
		textColor = textColor == null ? "#FFFFFF" : textColor;
		inactiveTextColor = inactiveTextColor == null ? "#A3A3A3" : inactiveTextColor;
	}

	/** Copies with a different cue list, keeping the settings. */
	public CaptionSpec withCues(List<Cue> nextCues) {
		return new CaptionSpec(enabled, fontFamily, fontSize, bottomOffset, maxWidth, maxRows,
				animationStyle, boxRadius, textColor, inactiveTextColor, backgroundOpacity,
				nextCues);
	}
}
