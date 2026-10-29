package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

/**
 * One transcript line: start offset in milliseconds, the (trimmed) text and the speaker when the
 * recording had two separate audio tracks.
 */
public record TranscriptSegment(long startMs, String text, @Nullable Speaker speaker) {
}
