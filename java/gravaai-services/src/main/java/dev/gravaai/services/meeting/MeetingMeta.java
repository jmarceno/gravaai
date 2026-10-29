package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Typed view of {@code <recording>.meeting.json}. The file is a loose JSON object owned by the
 * Electron app; unknown keys survive because {@link MeetingMetaStore} edits the raw map.
 */
public record MeetingMeta(
		@Nullable String kind,
		@Nullable String createdAt,
		@Nullable Boolean hasVideo,
		@Nullable String language,
		@Nullable Long durationMs,
		@Nullable String title,
		@Nullable String titleSource,
		@Nullable String transcribedAt,
		@Nullable String summarizedAt,
		@Nullable String transcriptionSource,
		@Nullable String summarizationSource,
		@Nullable LastJob lastJob) {

	/**
	 * The last pipeline job recorded in the metadata (status/error/at).
	 *
	 * @param mode the job mode, or null when the file recorded none
	 * @param status the job status, or null when the file recorded none
	 * @param error the failure message, or null when the job did not fail
	 * @param at the timestamp, or null when the file recorded none
	 */
	public record LastJob(@Nullable String mode, @Nullable String status,
			@Nullable String error, @Nullable String at) {
	}

	public static MeetingMeta fromRaw(Map<?, ?> raw) {
		return new MeetingMeta(
				text(raw, "kind"),
				text(raw, "createdAt"),
				bool(raw, "hasVideo"),
				text(raw, "language"),
				number(raw, "durationMs"),
				text(raw, "title"),
				text(raw, "titleSource"),
				text(raw, "transcribedAt"),
				text(raw, "summarizedAt"),
				text(raw, "transcriptionSource"),
				text(raw, "summarizationSource"),
				lastJob(raw.get("lastJob")));
	}

	private static @Nullable String text(Map<?, ?> raw, String key) {
		Object value = raw.get(key);
		return value instanceof String string ? string : null;
	}

	private static @Nullable Boolean bool(Map<?, ?> raw, String key) {
		Object value = raw.get(key);
		return value instanceof Boolean flag ? flag : null;
	}

	private static @Nullable Long number(Map<?, ?> raw, String key) {
		Object value = raw.get(key);
		return value instanceof Number parsed ? parsed.longValue() : null;
	}

	private static @Nullable LastJob lastJob(@Nullable Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		return new LastJob(text(map, "mode"), text(map, "status"), text(map, "error"), text(map, "at"));
	}
}
