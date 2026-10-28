package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/** Ported from the {@code get/set-countdown-delay} handlers in {@code electron/ipc/register/settings.ts}. */
class CountdownDelayStoreTest {

	@TempDir
	Path dir;

	private CountdownDelayStore store() {
		return new CountdownDelayStore(dir.resolve("countdown-settings.json"));
	}

	@Test
	@DisplayName("reads three seconds when the settings file is missing or invalid")
	void readsTheDefaultDelay() throws IOException {
		assertEquals(3.0, store().getCountdownDelay());
		Path file = dir.resolve("countdown-settings.json");
		Files.writeString(file, "not json", StandardCharsets.UTF_8);
		assertEquals(3.0, store().getCountdownDelay());
		Files.writeString(file, "{\"delay\":\"soon\"}", StandardCharsets.UTF_8);
		assertEquals(3.0, store().getCountdownDelay());
	}

	@Test
	@DisplayName("writes the delay and broadcasts the change")
	void writesAndBroadcastsTheDelay() throws Exception {
		CountdownDelayStore store = store();
		List<Double> changes = new ArrayList<>();
		AutoCloseable subscription = store.onCountdownDelayChanged(changes::add);

		store.setCountdownDelay(5);

		assertEquals(5.0, store.getCountdownDelay());
		JsonNode written = Json.parseJsonWithByteOrderMark(
				Files.readString(dir.resolve("countdown-settings.json"), StandardCharsets.UTF_8));
		assertEquals(5.0, written.get("delay").asDouble());
		assertEquals(List.of(5.0), changes);

		subscription.close();
		store.setCountdownDelay(2);
		assertEquals(List.of(5.0), changes);
		assertTrue(store.getCountdownDelay() == 2.0);
	}
}
