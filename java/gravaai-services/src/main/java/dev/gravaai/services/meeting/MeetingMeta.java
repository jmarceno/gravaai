package dev.gravaai.services.meeting;

import java.util.Map;

/**
 * Typed view of {@code <recording>.meeting.json}. The file is a loose JSON object owned by the
 * Electron app; unknown keys survive because {@link MeetingMetaStore} edits the raw map.
 */
public record MeetingMeta(
		String kind,
		String createdAt,
		Boolean hasVideo,
		String language,
		Long durationMs,
		String title,
		String titleSource,
		String transcribedAt,
		String summarizedAt,
		String transcriptionSource,
		String summarizationSource,
		LastJob lastJob) {

	/** The last pipeline job recorded in the metadata (status/error/at). */
	public record LastJob(String mode, String status, String error, String at) {
	}

	public static MeetingMeta fromRaw(Map<String, Object> raw) {
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

	private static String text(Map<String, Object> raw, String key) {
		Object value = raw.get(key);
		return value instanceof String string ? string : null;
	}

	private static Boolean bool(Map<String, Object> raw, String key) {
		Object value = raw.get(key);
		return value instanceof Boolean flag ? flag : null;
	}

	private static Long number(Map<String, Object> raw, String key) {
		Object value = raw.get(key);
		return value instanceof Number parsed ? parsed.longValue() : null;
	}

	@SuppressWarnings("unchecked")
	private static LastJob lastJob(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		Map<String, Object> raw = (Map<String, Object>) map;
		return new LastJob(text(raw, "mode"), text(raw, "status"), text(raw, "error"), text(raw, "at"));
	}
}
