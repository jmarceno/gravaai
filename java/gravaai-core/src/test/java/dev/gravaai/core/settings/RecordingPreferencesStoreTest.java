package dev.gravaai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

/** Port of {@code electron/ipc/settings/recordingPreferencesStore.test.ts} (plan §5.2). */
class RecordingPreferencesStoreTest {

	@TempDir
	Path directory;

	private Path file;
	private RecordingPreferencesStore store;

	@BeforeEach
	void setUp() {
		file = directory.resolve("recording.json");
		store = new RecordingPreferencesStore(file);
	}

	@Test
	@DisplayName("preserves concurrent microphone and webcam preference updates")
	void preservesConcurrentUpdates() {
		CompletableFuture.allOf(
				store.update(new RecordingPreferencesPatch(true, null, null, null, null)),
				store.update(new RecordingPreferencesPatch(null, "preferred-mic", null, null, null)),
				store.update(new RecordingPreferencesPatch(null, null, null, true, null)),
				store.update(new RecordingPreferencesPatch(null, null, null, null, "preferred-camera")))
				.join();

		Map<String, JsonNode> contents = store.read().join();
		assertEquals(4, contents.size());
		assertTrue(contents.get("microphoneEnabled").booleanValue());
		assertEquals("preferred-mic", contents.get("microphoneDeviceId").asText());
		assertTrue(contents.get("webcamEnabled").booleanValue());
		assertEquals("preferred-camera", contents.get("webcamDeviceId").asText());
	}

	@Test
	@DisplayName("serializes queued device updates without losing a later one")
	void serializesQueuedUpdates() {
		CompletableFuture<?>[] updates = new CompletableFuture<?>[6];
		for (int index = 0; index < updates.length; index++) {
			updates[index] = store.update(
					new RecordingPreferencesPatch(null, null, null, null, "camera-" + index));
		}
		CompletableFuture.allOf(updates).join();

		assertEquals("camera-5", store.read().join().get("webcamDeviceId").asText());
	}

	@Test
	@DisplayName("keeps unknown keys when applying a patch")
	void keepsUnknownKeys() throws IOException {
		Files.writeString(file, "{\"microphoneEnabled\":false,\"futureSetting\":{\"nested\":1}}",
				StandardCharsets.UTF_8);

		store.update(new RecordingPreferencesPatch(null, null, null, true, null)).join();

		Map<String, JsonNode> contents = store.read().join();
		assertEquals(3, contents.size());
		assertTrue(contents.get("webcamEnabled").booleanValue());
		assertEquals(false, contents.get("microphoneEnabled").booleanValue());
		assertEquals(1, contents.get("futureSetting").get("nested").intValue());
	}

	@Test
	@DisplayName("waits for pending updates before reading")
	void readWaitsForPendingUpdates() {
		CompletableFuture<Void> update =
				store.update(new RecordingPreferencesPatch(true, null, null, null, null));

		assertTrue(store.read().join().get("microphoneEnabled").booleanValue());
		update.join();
	}

	@Test
	@DisplayName("keeps accepting updates after a failed write")
	void keepsAcceptingUpdatesAfterFailure() throws IOException {
		Files.createDirectory(file);

		assertThrows(CompletionException.class,
				() -> store.update(new RecordingPreferencesPatch(true, null, null, null, null)).join());

		Files.delete(file);
		store.update(new RecordingPreferencesPatch(null, null, null, true, null)).join();
		assertTrue(store.read().join().get("webcamEnabled").booleanValue());
	}
}
