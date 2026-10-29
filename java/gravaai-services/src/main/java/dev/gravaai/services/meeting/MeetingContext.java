package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What a summarizer receives. Only the transcript is filled today; the visual fields exist so a
 * later "screen context" stage can be added without touching callers
 * (electron/ipc/meeting/context.ts).
 */
public record MeetingContext(
		String transcript,
		@Nullable String language,
		@Nullable Long durationMs,
		@Nullable String videoPath,
		List<MeetingVisual> visuals) {

	/** A screen-context keyframe (not produced yet). */
	public record MeetingVisual(long atMs, String imagePath, String ocrText) {
	}

	public static MeetingContext text(String transcript, @Nullable String language,
			@Nullable Long durationMs) {
		return new MeetingContext(transcript, language, durationMs, null, List.of());
	}
}
