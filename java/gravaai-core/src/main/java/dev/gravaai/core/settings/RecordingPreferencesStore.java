package dev.gravaai.core.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.json.JsonFile;
import tools.jackson.databind.JsonNode;

/**
 * The queued {@code recordings-settings.json} store (port of
 * {@code electron/ipc/settings/recordingPreferencesStore.ts}, plan §5.2).
 *
 * <p>Updates are read-modify-write operations serialized per store, so concurrent microphone and
 * webcam preference changes cannot clobber each other. The store stays untyped: unknown keys and
 * keys from newer versions survive.
 */
public final class RecordingPreferencesStore {

	private final Object lock = new Object();
	private final Path file;
	private CompletableFuture<Void> operationQueue = CompletableFuture.completedFuture(null);

	public RecordingPreferencesStore(Path file) {
		this.file = file;
	}

	/** {@code read}: waits for pending updates, then returns the store (invalid/missing = empty). */
	public CompletableFuture<Map<String, JsonNode>> read() {
		synchronized (lock) {
			return operationQueue.thenApply(ignored -> readFile());
		}
	}

	/**
	 * {@code update}: applies the patch to the current file contents, preserving every other key.
	 *
	 * <p>The returned future fails when the write fails; the queue itself keeps accepting later
	 * updates, like the TS promise chain that catches before chaining.
	 */
	public CompletableFuture<Void> update(RecordingPreferencesPatch patch) {
		CompletableFuture<Void> operation;
		synchronized (lock) {
			operation = operationQueue
					.handle((value, error) -> null)
					.thenRun(() -> {
						Map<String, JsonNode> store = readFile();
						merge(store, patch);
						try {
							JsonFile.writeObject(file, store);
						} catch (IOException error) {
							throw new UncheckedIOException(error);
						}
					});
			operationQueue = operation.handle((value, error) -> null);
		}
		return operation;
	}

	private Map<String, JsonNode> readFile() {
		return JsonFile.readObjectOrEmpty(file);
	}

	private static void merge(Map<String, JsonNode> store, RecordingPreferencesPatch patch) {
		// JSON.stringify({ ...existing, ...patch }): only keys present in the patch are overwritten.
		if (patch.microphoneEnabled() != null) {
			store.put("microphoneEnabled", Json.nodeFactory().booleanNode(patch.microphoneEnabled()));
		}
		if (patch.microphoneDeviceId() != null) {
			store.put("microphoneDeviceId", Json.nodeFactory().stringNode(patch.microphoneDeviceId()));
		}
		if (patch.systemAudioEnabled() != null) {
			store.put("systemAudioEnabled", Json.nodeFactory().booleanNode(patch.systemAudioEnabled()));
		}
		if (patch.webcamEnabled() != null) {
			store.put("webcamEnabled", Json.nodeFactory().booleanNode(patch.webcamEnabled()));
		}
		if (patch.webcamDeviceId() != null) {
			store.put("webcamDeviceId", Json.nodeFactory().stringNode(patch.webcamDeviceId()));
		}
	}
}
