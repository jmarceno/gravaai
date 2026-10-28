package dev.gravaai.media.playback;

/**
 * One playhead update: timeline time plus the source time it maps to.
 *
 * @param timelineSeconds the editor timeline position
 * @param sourceSeconds the source media timestamp, or null over a gap with no clip
 * @param clipId the active clip id, or null over a gap
 */
public record PlaybackTime(double timelineSeconds, Double sourceSeconds, String clipId) {
}
