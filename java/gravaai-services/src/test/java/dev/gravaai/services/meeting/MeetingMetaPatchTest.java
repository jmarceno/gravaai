package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingMetaPatchTest {

	@Test
	@DisplayName("sets typed fields, removes keys and leaves unknown keys alone")
	void appliesToRawObject() {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("language", "pt");
		raw.put("title", "Old");
		raw.put("titleSource", "auto");
		raw.put("futureKey", 7);
		Map<String, Object> next = new MeetingMetaPatch()
				.removeLanguage()
				.removeTitle()
				.durationMs(1_500L)
				.lastJob(new MeetingMeta.LastJob("full", "done", null, "2026-10-29T10:00:00.000Z"))
				.applyTo(raw);
		assertEquals(List.of("futureKey", "durationMs", "lastJob"), List.copyOf(next.keySet()));
		assertEquals(Map.of("mode", "full", "status", "done", "at", "2026-10-29T10:00:00.000Z"),
				next.get("lastJob"));
		assertFalse(raw.isEmpty());
	}

	@Test
	@DisplayName("reads back what it wrote")
	void roundTripsThroughMeta() {
		Map<String, Object> next = new MeetingMetaPatch()
				.kind("meeting")
				.hasVideo(true)
				.title("Weekly sync", "user")
				.lastJob(new MeetingMeta.LastJob("transcribe", "failed", "boom", "now"))
				.applyTo(Map.of());
		MeetingMeta meta = MeetingMeta.fromRaw(next);
		assertEquals("meeting", meta.kind());
		assertEquals(Boolean.TRUE, meta.hasVideo());
		assertEquals("Weekly sync", meta.title());
		assertEquals("user", meta.titleSource());
		assertEquals(new MeetingMeta.LastJob("transcribe", "failed", "boom", "now"), meta.lastJob());
	}
}
