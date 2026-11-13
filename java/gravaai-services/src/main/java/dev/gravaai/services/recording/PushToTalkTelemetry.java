package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.StorageFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code <video>.ptt.json} sidecar: the stretches of a push-to-talk recording where the
 * microphone was muted.
 *
 * <p>The muting itself is in the video — the mic's PCM stream carries silence while muted — so
 * this file never changes the audio. It tells the editor where the mic lane shows muted spans, so
 * a reader can tell silence that was chosen (the shortcut was not held) from silence that just
 * happened.
 *
 * <p>Times are on the pause-aware capture clock, like the cursor and ink sidecars, so they line up
 * with the video after the same video-clock shift.
 */
public final class PushToTalkTelemetry {

	public static final int PUSH_TO_TALK_TELEMETRY_VERSION = 1;

	/** Upper bounds that keep a corrupt or hostile sidecar from exhausting memory. */
	static final int MAX_SPANS = 10_000;

	private PushToTalkTelemetry() {
	}

	/** One muted stretch of the recording: silence was written from {@code startMs} to {@code endMs}. */
	public record MutedSpan(long startMs, long endMs) {
		public MutedSpan {
			if (startMs < 0 || endMs < startMs) {
				throw new IllegalArgumentException(
						"invalid muted span " + startMs + ".." + endMs);
			}
		}

		/** True when {@code timeMs} falls inside this span (ends inclusive). */
		public boolean contains(double timeMs) {
			return timeMs >= startMs && timeMs <= endMs;
		}
	}

	/** Everything one recording's sidecar holds. */
	public record Recording(List<MutedSpan> spans) {

		public static final Recording EMPTY = new Recording(List.of());

		public Recording {
			spans = List.copyOf(spans);
		}

		public boolean isEmpty() {
			return spans.isEmpty();
		}

		/** The same recording with every time moved by {@code shiftMs} (the video-clock shift). */
		public Recording shifted(long shiftMs) {
			if (shiftMs == 0) {
				return this;
			}
			return new Recording(spans.stream()
					.map(span -> new MutedSpan(span.startMs() + shiftMs, span.endMs() + shiftMs))
					.toList());
		}
	}

	// ── reading ────────────────────────────────────────────────────────────

	/** Reads the sidecar next to {@code videoPath}; empty when it is absent, unreadable or newer. */
	public static Recording read(String videoPath) {
		Path path = StorageFiles.pushToTalkTelemetryPath(videoPath);
		if (!Files.isRegularFile(path)) {
			return Recording.EMPTY;
		}
		try {
			return parse(Json.parseJsonWithByteOrderMark(Files.readString(path, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException error) {
			return Recording.EMPTY;
		}
	}

	/** Accepts the sidecar shape and drops anything unusable. */
	public static Recording parse(JsonNode root) {
		if (root == null || !root.isObject()) {
			return Recording.EMPTY;
		}
		JsonNode version = root.get("version");
		// A sidecar from an unknown future version is treated as no spans.
		if (version != null && version.isNumber() && version.asInt() != PUSH_TO_TALK_TELEMETRY_VERSION) {
			return Recording.EMPTY;
		}
		List<MutedSpan> spans = new ArrayList<>();
		JsonNode array = root.get("spans");
		if (array != null && array.isArray()) {
			for (JsonNode node : array) {
				long start = time(node.get("startMs"));
				long end = time(node.get("endMs"));
				if (start >= 0 && end >= start) {
					spans.add(new MutedSpan(start, end));
				}
				if (spans.size() >= MAX_SPANS) {
					break;
				}
			}
		}
		spans.sort(Comparator.comparingLong(MutedSpan::startMs));
		return new Recording(spans);
	}

	/** A non-negative finite time in ms, or -1 when the node is not one. */
	private static long time(JsonNode node) {
		if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble()) || node.asDouble() < 0) {
			return -1;
		}
		return Math.round(node.asDouble());
	}

	// ── writing ────────────────────────────────────────────────────────────

	/**
	 * Writes the sidecar next to the video. A recording without muted spans removes it, so a take
	 * without push-to-talk leaves no file behind.
	 */
	public static void write(String videoPath, Recording recording) throws IOException {
		Path path = StorageFiles.pushToTalkTelemetryPath(videoPath);
		if (recording.isEmpty()) {
			Files.deleteIfExists(path);
			return;
		}
		Json.writeFile(path, toJson(recording));
	}

	static ObjectNode toJson(Recording recording) {
		ObjectNode root = Json.nodeFactory().objectNode();
		root.put("version", PUSH_TO_TALK_TELEMETRY_VERSION);
		ArrayNode spans = root.putArray("spans");
		for (MutedSpan span : recording.spans()) {
			ObjectNode node = spans.addObject();
			node.put("startMs", span.startMs());
			node.put("endMs", span.endMs());
		}
		return root;
	}

	// ── using it ───────────────────────────────────────────────────────────
}
