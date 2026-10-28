package dev.gravaai.media.compose;

/**
 * Screen insets of an active device frame (port of the renderer's {@code frameInsets}): the
 * fraction of each edge covered by the device bezel. The layout scales and centres the full
 * frame inside the remaining screen area.
 *
 * @param top top inset fraction, 0-1
 * @param right right inset fraction, 0-1
 * @param bottom bottom inset fraction, 0-1
 * @param left left inset fraction, 0-1
 */
public record FrameInsets(double top, double right, double bottom, double left) {
}
