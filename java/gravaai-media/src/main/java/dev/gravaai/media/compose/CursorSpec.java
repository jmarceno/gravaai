package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

/**
 * Cursor overlay configuration for one compositor frame (plan §5.8 stage 6), media-local like the
 * other specs: the services adapter maps the editor cursor settings into it.
 *
 * @param showCursor the editor {@code showCursor} toggle
 * @param style the cursor style
 * @param dotRadius {@code DEFAULT_CURSOR_CONFIG.dotRadius * editor.cursorSize}
 * @param smoothingFactor cursor motion smoothing (spring slider)
 * @param stiffnessMultiplier cursor spring stiffness tuning multiplier
 * @param dampingMultiplier cursor spring damping tuning multiplier
 * @param massMultiplier cursor spring mass tuning multiplier
 * @param motionBlur cursor motion-blur amount (rendered by the stage-5 shader machinery later)
 * @param clickEffect click effect style
 * @param clickColorArgb click effect colour as ARGB (alpha ignored)
 * @param clickEffectScale click effect scale
 * @param clickEffectOpacity click effect opacity
 * @param clickEffectDurationMs click effect duration
 * @param clickBounce click bounce amount
 * @param clickBounceDurationMs click bounce duration
 * @param sway cursor sway amount
 */
public record CursorSpec(boolean showCursor, CursorStyle style, double dotRadius,
		double smoothingFactor, double stiffnessMultiplier, double dampingMultiplier,
		double massMultiplier, double motionBlur, CursorClickEffect clickEffect,
		@Nullable int clickColorArgb, double clickEffectScale, double clickEffectOpacity,
		double clickEffectDurationMs, double clickBounce, double clickBounceDurationMs,
		@Nullable double sway) {

	/** Defaults from {@code DEFAULT_CURSOR_CONFIG} (cursor hidden). */
	public static final CursorSpec DEFAULT = new CursorSpec(false, CursorStyle.TAHOE, 28, 0.18,
			1, 1, 1, 0, CursorClickEffect.NONE, 0xFF2563EB, 1, 1, 600, 1, 350, 0);

	public CursorSpec {
		if (!Double.isFinite(dotRadius) || dotRadius <= 0) {
			throw new IllegalArgumentException("dotRadius must be positive: " + dotRadius);
		}
		if (!Double.isFinite(smoothingFactor)) {
			throw new IllegalArgumentException("smoothingFactor must be finite");
		}
		if (!Double.isFinite(clickEffectDurationMs) || clickEffectDurationMs <= 0) {
			throw new IllegalArgumentException("clickEffectDurationMs must be positive");
		}
		if (!Double.isFinite(clickBounceDurationMs) || clickBounceDurationMs <= 0) {
			throw new IllegalArgumentException("clickBounceDurationMs must be positive");
		}
	}

	/** The media-local spring tuning. */
	public SpringSmoothing.CursorSpringTuning springTuning() {
		return new SpringSmoothing.CursorSpringTuning(stiffnessMultiplier, dampingMultiplier,
				massMultiplier);
	}
}
