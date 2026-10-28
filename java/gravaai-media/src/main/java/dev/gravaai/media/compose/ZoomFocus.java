package dev.gravaai.media.compose;

/**
 * Normalised zoom focus point (port of {@code ZoomFocus} in {@code video-editor/types.ts}).
 *
 * @param cx normalised horizontal centre, 0-1
 * @param cy normalised vertical centre, 0-1
 */
public record ZoomFocus(double cx, double cy) {
}
