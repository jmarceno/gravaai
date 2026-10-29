package dev.gravaai.media.compose;

import java.util.List;

/**
 * Captions for one compositor frame (plan §5.8 stage 9), media-local like {@link FrameInput}:
 * the resolved auto-caption settings plus the cues that can be visible at the frame time.
 *
 * <p>Every component is non-null: the project model's optional fields are resolved to the TS
 * defaults by the caller at the boundary ({@link ProjectEditorInputs#captionSpec}), so the
 * renderer never has to guess.</p>
 */
public record CaptionSpec(
		boolean enabled,
		String fontFamily,
		double fontSize,
		double bottomOffset,
		double maxWidth,
		int maxRows,
		String animationStyle,
		double boxRadius,
		String textColor,
		String inactiveTextColor,
		double backgroundOpacity,
		List<Cue> cues) {

	/** The TS default caption font stack (SF Pro is not redistributable; DM Sans substitutes). */
	static final String DEFAULT_FONT_FAMILY =
			"\"SF Pro Text\", \"SF Pro Display\", \"Helvetica Neue\", sans-serif";

	/** The TS default caption text colour. */
	static final String DEFAULT_TEXT_COLOR = "#FFFFFF";

	/** The TS default inactive (already spoken) caption text colour. */
	static final String DEFAULT_INACTIVE_TEXT_COLOR = "#A3A3A3";

	/** One caption cue ({@code startMs..endMs} inclusive at the end like the renderer). */
	public record Cue(String id, long startMs, long endMs, String text,
			List<dev.gravaai.core.project.CaptionCueWord> words) {

		public Cue {
			words = List.copyOf(words);
		}

		/** A cue without word timings (proportional timing, like the TS no-timing path). */
		public Cue(String id, long startMs, long endMs, String text) {
			this(id, startMs, endMs, text, List.of());
		}
	}

	/** No captions. */
	public static final CaptionSpec DISABLED = new CaptionSpec(false, DEFAULT_FONT_FAMILY, 30, 3,
			62, 1, "none", 17.5, DEFAULT_TEXT_COLOR, DEFAULT_INACTIVE_TEXT_COLOR, 0.9, List.of());

	public CaptionSpec {
		cues = List.copyOf(cues);
	}

	/** Copies with a different cue list, keeping the settings. */
	public CaptionSpec withCues(List<Cue> nextCues) {
		return new CaptionSpec(enabled, fontFamily, fontSize, bottomOffset, maxWidth, maxRows,
				animationStyle, boxRadius, textColor, inactiveTextColor, backgroundOpacity,
				nextCues);
	}
}
