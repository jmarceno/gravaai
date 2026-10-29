package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed edit to {@code <recording>.meeting.json}. Each field has its own setter, so a patch can
 * only carry known keys with the right value types; keys this build does not know about are left
 * alone by {@link MeetingMetaStore}.
 *
 * <p>Electron deletes a key by spreading {@code undefined}, which JSON.stringify drops; the
 * {@code remove…} methods are the Java equivalent.
 */
public final class MeetingMetaPatch {

	private static final Object REMOVE = new Object();

	private final Map<String, Object> changes = new LinkedHashMap<>();

	public MeetingMetaPatch kind(String kind) {
		return put("kind", kind);
	}

	public MeetingMetaPatch createdAt(String createdAt) {
		return put("createdAt", createdAt);
	}

	public MeetingMetaPatch hasVideo(boolean hasVideo) {
		return put("hasVideo", hasVideo);
	}

	public MeetingMetaPatch language(String language) {
		return put("language", language);
	}

	public MeetingMetaPatch removeLanguage() {
		return put("language", REMOVE);
	}

	/** @param durationMs the probed duration, or null when it could not be probed (stored as JSON null) */
	public MeetingMetaPatch durationMs(@Nullable Long durationMs) {
		return put("durationMs", durationMs);
	}

	/** Sets the title and leaves its source as it is (imports name the meeting after the file). */
	public MeetingMetaPatch title(String title) {
		return put("title", title);
	}

	public MeetingMetaPatch title(String title, String titleSource) {
		return put("title", title).put("titleSource", titleSource);
	}

	public MeetingMetaPatch removeTitle() {
		return put("title", REMOVE).put("titleSource", REMOVE);
	}

	public MeetingMetaPatch transcribed(String at, String source) {
		return put("transcribedAt", at).put("transcriptionSource", source);
	}

	public MeetingMetaPatch summarized(String at, String source) {
		return put("summarizedAt", at).put("summarizationSource", source);
	}

	public MeetingMetaPatch lastJob(MeetingMeta.LastJob job) {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("mode", job.mode());
		raw.put("status", job.status());
		if (job.error() != null) {
			raw.put("error", job.error());
		}
		raw.put("at", job.at());
		return put("lastJob", raw);
	}

	/** The raw object with this patch applied; the input is not changed. */
	Map<String, Object> applyTo(Map<String, Object> raw) {
		Map<String, Object> next = new LinkedHashMap<>(raw);
		changes.forEach((key, value) -> {
			if (value == REMOVE) {
				next.remove(key);
			} else {
				next.put(key, value);
			}
		});
		return next;
	}

	private MeetingMetaPatch put(String key, @Nullable Object value) {
		changes.put(key, value);
		return this;
	}
}
