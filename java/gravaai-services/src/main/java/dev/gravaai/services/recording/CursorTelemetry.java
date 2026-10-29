package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
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
 * Cursor telemetry sampling data and persistence, ported from
 * {@code electron/ipc/cursor/telemetry.ts}.
 *
 * <p>The pure parts live here: sample normalisation (readable by the editor and the import path),
 * the WebAudio-free level maths belong to the mic meter, and the pause clock is in
 * {@link CursorCaptureClock}.
 */
public final class CursorTelemetry {

	public static final int CURSOR_TELEMETRY_VERSION = StorageFiles.CURSOR_TELEMETRY_VERSION;
	public static final int CURSOR_SAMPLE_INTERVAL_MS = StorageFiles.CURSOR_SAMPLE_INTERVAL_MS;
	public static final int MAX_CURSOR_SAMPLES = StorageFiles.MAX_CURSOR_SAMPLES;

	private CursorTelemetry() {
	}

	/** A cursor interaction marker, from {@code CursorInteractionType} in electron/ipc/types.ts. */
	public enum CursorInteractionType {
		MOVE("move"),
		CLICK("click"),
		DOUBLE_CLICK("double-click"),
		RIGHT_CLICK("right-click"),
		MIDDLE_CLICK("middle-click"),
		MOUSEUP("mouseup");

		private final String wire;

		CursorInteractionType(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		static @Nullable CursorInteractionType fromWire(String value) {
			for (CursorInteractionType type : values()) {
				if (type.wire.equals(value)) {
					return type;
				}
			}
			return null;
		}
	}

	/** A cursor visual, from {@code CursorVisualType} in electron/ipc/types.ts. */
	public enum CursorVisualType {
		ARROW("arrow"),
		TEXT("text"),
		POINTER("pointer"),
		CROSSHAIR("crosshair"),
		OPEN_HAND("open-hand"),
		CLOSED_HAND("closed-hand"),
		RESIZE_EW("resize-ew"),
		RESIZE_NS("resize-ns"),
		NOT_ALLOWED("not-allowed");

		private final String wire;

		CursorVisualType(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		static @Nullable CursorVisualType fromWire(String value) {
			for (CursorVisualType type : values()) {
				if (type.wire.equals(value)) {
					return type;
				}
			}
			return null;
		}
	}

	/** One cursor sample: normalized position and optional interaction/visual markers. */
	public record CursorTelemetryPoint(
			double timeMs,
			double cx,
			double cy,
			@Nullable CursorInteractionType interactionType,
			@Nullable CursorVisualType cursorType) {
	}

	public static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	/** Port of {@code normalizeCursorTelemetrySamples}: bounded, clamped, sorted, unknown markers dropped. */
	public static List<CursorTelemetryPoint> normalizeCursorTelemetrySamples(JsonNode raw) {
		JsonNode samplesNode = null;
		if (raw != null && raw.isArray()) {
			samplesNode = raw;
		} else if (raw != null && raw.isObject() && raw.get("samples") != null && raw.get("samples").isArray()) {
			samplesNode = raw.get("samples");
		}
		if (samplesNode == null) {
			return List.of();
		}
		List<CursorTelemetryPoint> points = new ArrayList<>();
		int index = 0;
		for (JsonNode sample : samplesNode) {
			if (index++ >= MAX_CURSOR_SAMPLES) {
				break;
			}
			if (sample == null || !sample.isObject()) {
				continue;
			}
			JsonNode timeNode = sample.get("timeMs");
			double timeMs = timeNode != null && timeNode.isNumber() && Double.isFinite(timeNode.asDouble())
					? Math.max(0, timeNode.asDouble())
					: 0;
			JsonNode cxNode = sample.get("cx");
			double cx = cxNode != null && cxNode.isNumber() && Double.isFinite(cxNode.asDouble())
					? clamp(cxNode.asDouble(), 0, 1)
					: 0.5;
			JsonNode cyNode = sample.get("cy");
			double cy = cyNode != null && cyNode.isNumber() && Double.isFinite(cyNode.asDouble())
					? clamp(cyNode.asDouble(), 0, 1)
					: 0.5;
			JsonNode interactionNode = sample.get("interactionType");
			CursorInteractionType interactionType = interactionNode != null && interactionNode.isString()
					? CursorInteractionType.fromWire(interactionNode.asString())
					: null;
			JsonNode cursorNode = sample.get("cursorType");
			CursorVisualType cursorType = cursorNode != null && cursorNode.isString()
					? CursorVisualType.fromWire(cursorNode.asString())
					: null;
			points.add(new CursorTelemetryPoint(timeMs, cx, cy, interactionType, cursorType));
		}
		points.sort(Comparator.comparingDouble(CursorTelemetryPoint::timeMs));
		return points;
	}

	private static void putNumber(ObjectNode node, String field, double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			node.put(field, (long) value);
		} else {
			node.put(field, value);
		}
	}

	/** The sidecar JSON shape of one sample; unset markers are omitted like JSON.stringify. */
	public static ObjectNode sampleToJson(CursorTelemetryPoint sample) {
		ObjectNode node = Json.nodeFactory().objectNode();
		putNumber(node, "timeMs", sample.timeMs());
		putNumber(node, "cx", sample.cx());
		putNumber(node, "cy", sample.cy());
		if (sample.interactionType() != null) {
			node.put("interactionType", sample.interactionType().wire());
		}
		if (sample.cursorType() != null) {
			node.put("cursorType", sample.cursorType().wire());
		}
		return node;
	}

	public static ArrayNode samplesToJson(List<CursorTelemetryPoint> samples) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (CursorTelemetryPoint sample : samples) {
			array.add(sampleToJson(sample));
		}
		return array;
	}

	/** Writes {@code {version, samples}}, or removes the sidecar when there is nothing to save. */
	public static List<CursorTelemetryPoint> writeCursorTelemetry(String videoPath, JsonNode samples) throws IOException {
		List<CursorTelemetryPoint> normalized = normalizeCursorTelemetrySamples(samples);
		Path telemetryPath = Path.of(RecordingPaths.getTelemetryPathForVideo(videoPath));
		if (normalized.isEmpty()) {
			Files.deleteIfExists(telemetryPath);
			return normalized;
		}
		ObjectNode root = Json.nodeFactory().objectNode();
		root.put("version", CURSOR_TELEMETRY_VERSION);
		root.set("samples", samplesToJson(normalized));
		Json.writeFile(telemetryPath, root);
		return normalized;
	}

	/** Typed convenience overload for the recorder and the import path. */
	public static List<CursorTelemetryPoint> writeCursorTelemetry(
			String videoPath, List<CursorTelemetryPoint> samples) throws IOException {
		return writeCursorTelemetry(videoPath, samplesToJson(samples));
	}

	/**
	 * Recursive-sampling helper: appends one sample and keeps the list bounded to
	 * {@link #MAX_CURSOR_SAMPLES}.
	 */
	public static void pushSample(
			List<CursorTelemetryPoint> samples,
			double cx,
			double cy,
			double timeMs,
			CursorInteractionType interactionType,
			CursorVisualType cursorType) {
		samples.add(new CursorTelemetryPoint(Math.max(0, timeMs), cx, cy, interactionType, cursorType));
		if (samples.size() > MAX_CURSOR_SAMPLES) {
			samples.remove(0);
		}
	}

	/**
	 * Merges freshly sampled points into the pending set, port of
	 * {@code snapshotCursorTelemetryForPersistence}. Duplicate times from a previous snapshot are
	 * dropped so a sample is never persisted twice.
	 */
	public static List<CursorTelemetryPoint> mergeForPersistence(
			List<CursorTelemetryPoint> activeSamples,
			List<CursorTelemetryPoint> pendingSamples) {
		if (activeSamples.isEmpty()) {
			return pendingSamples;
		}
		if (pendingSamples.isEmpty()) {
			return List.copyOf(activeSamples);
		}
		double lastPendingTimeMs = pendingSamples.get(pendingSamples.size() - 1).timeMs();
		List<CursorTelemetryPoint> merged = new ArrayList<>(pendingSamples);
		for (CursorTelemetryPoint sample : activeSamples) {
			if (sample.timeMs() > lastPendingTimeMs) {
				merged.add(sample);
			}
		}
		return merged;
	}
}
