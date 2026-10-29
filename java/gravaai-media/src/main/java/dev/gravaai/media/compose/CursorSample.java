package dev.gravaai.media.compose;

/**
 * One cursor telemetry sample at a media timestamp (port of {@code CursorTelemetryPoint}).
 *
 * @param timeSeconds the media time in seconds
 * @param x the normalised cursor x (0-1 of the source frame)
 * @param y the normalised cursor y (0-1 of the source frame)
 * @param cursorType the cursor sprite type ({@code arrow}, {@code pointer}, ...), may be null
 * @param interactionType the interaction type ({@code click}, {@code double-click},
 *        {@code right-click}, {@code middle-click}, {@code mouseup}), may be null
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public record CursorSample(double timeSeconds, double x, double y, String cursorType,
		String interactionType) {

	public CursorSample {
		if (!Double.isFinite(timeSeconds) || !Double.isFinite(x) || !Double.isFinite(y)) {
			throw new IllegalArgumentException("cursor sample values must be finite");
		}
	}

	/** A position-only sample. */
	public CursorSample(double timeSeconds, double x, double y) {
		this(timeSeconds, x, y, null, null);
	}
}
