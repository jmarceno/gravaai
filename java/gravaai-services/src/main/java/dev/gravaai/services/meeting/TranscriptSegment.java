package dev.gravaai.services.meeting;

/**
 * One transcript line: start offset in milliseconds, the (trimmed) text and the speaker when the
 * recording had two separate audio tracks.
 */
public record TranscriptSegment(long startMs, String text, Speaker speaker) {
}
