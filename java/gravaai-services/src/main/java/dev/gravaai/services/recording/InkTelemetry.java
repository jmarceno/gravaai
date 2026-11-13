package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code <video>.ink.json} sidecar: when the presenter drew on screen during a recording, and
 * what.
 *
 * <p>The ink itself is part of the video (the screen capture records the overlay), so this file
 * never draws anything. It tells the editor where the draw sessions are, so the timeline can mark
 * them and so pen presses are not mistaken for clicks. The strokes' points are kept as well, for
 * zoom framing and in case the overlay can ever be excluded from the capture.
 *
 * <p>Times are on the pause-aware capture clock, like the cursor sidecar; positions and widths are
 * normalized to the recorded frame (width as a fraction of its height).
 */
public final class InkTelemetry {

	public static final int INK_TELEMETRY_VERSION = 1;

	/** Upper bounds that keep a corrupt or hostile sidecar from exhausting memory. */
	static final int MAX_SESSIONS = 10_000;
	static final int MAX_STROKES = 50_000;
	static final int MAX_POINTS_PER_STROKE = 20_000;

	private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private InkTelemetry() {
	}

	/** What a stroke was drawn with. */
	public enum InkTool {
		PEN("pen");

		private final String wire;

		InkTool(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The tool a wire spelling names; empty for an unknown one. */
		public static Optional<InkTool> parse(String value) {
			for (InkTool tool : values()) {
				if (tool.wire.equals(value)) {
					return Optional.of(tool);
				}
			}
			return Optional.empty();
		}
	}

	/** One stretch of draw mode, from entering it to leaving it. */
	public record InkSession(long startMs, long endMs) {
		public InkSession {
			if (startMs < 0 || endMs < startMs) {
				throw new IllegalArgumentException("invalid ink session " + startMs + ".." + endMs);
			}
		}

		/** True when {@code timeMs} falls inside this session (ends inclusive). */
		public boolean contains(double timeMs) {
			return timeMs >= startMs && timeMs <= endMs;
		}
	}

	/** One sampled position of a stroke, normalized to the recorded frame. */
	public record InkPoint(long timeMs, double x, double y) {
	}

	/**
	 * One stroke.
	 *
	 * @param width the line width as a fraction of the recorded frame's height
	 * @param clearedAtMs when the stroke left the screen (undo, clear or leaving draw mode)
	 */
	public record InkStroke(
			String id,
			InkTool tool,
			String color,
			double width,
			long startMs,
			long endMs,
			long clearedAtMs,
			List<InkPoint> points) {

		public InkStroke {
			points = List.copyOf(points);
		}
	}

	/** Everything one recording's sidecar holds. */
	public record InkRecording(List<InkSession> sessions, List<InkStroke> strokes) {

		public static final InkRecording EMPTY = new InkRecording(List.of(), List.of());

		public InkRecording {
			sessions = List.copyOf(sessions);
			strokes = List.copyOf(strokes);
		}

		public boolean isEmpty() {
			return sessions.isEmpty();
		}

		/** The same recording with every time moved by {@code shiftMs} (the video-clock shift). */
		public InkRecording shifted(long shiftMs) {
			List<InkSession> movedSessions = sessions.stream()
					.map(session -> new InkSession(session.startMs() + shiftMs, session.endMs() + shiftMs))
					.toList();
			List<InkStroke> movedStrokes = strokes.stream()
					.map(stroke -> new InkStroke(stroke.id(), stroke.tool(), stroke.color(), stroke.width(),
							stroke.startMs() + shiftMs, stroke.endMs() + shiftMs,
							stroke.clearedAtMs() + shiftMs,
							stroke.points().stream()
									.map(point -> new InkPoint(point.timeMs() + shiftMs, point.x(), point.y()))
									.toList()))
					.toList();
			return new InkRecording(movedSessions, movedStrokes);
		}

		/** How many strokes started inside {@code session}. */
		public int strokeCount(InkSession session) {
			int count = 0;
			for (InkStroke stroke : strokes) {
				if (session.contains(stroke.startMs())) {
					count++;
				}
			}
			return count;
		}
	}

	// ── reading ────────────────────────────────────────────────────────────

	/** Reads the sidecar next to {@code videoPath}; empty when it is absent, unreadable or newer. */
	public static InkRecording read(String videoPath) {
		Path path = StorageFiles.inkTelemetryPath(videoPath);
		if (!Files.isRegularFile(path)) {
			return InkRecording.EMPTY;
		}
		try {
			return parse(Json.parseJsonWithByteOrderMark(Files.readString(path, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException error) {
			return InkRecording.EMPTY;
		}
	}

	/** Accepts the sidecar shape and drops anything unusable. */
	public static InkRecording parse(JsonNode root) {
		if (root == null || !root.isObject()) {
			return InkRecording.EMPTY;
		}
		JsonNode version = root.get("version");
		// A sidecar from an unknown future version is treated as no ink.
		if (version != null && version.isNumber() && version.asInt() != INK_TELEMETRY_VERSION) {
			return InkRecording.EMPTY;
		}
		List<InkSession> sessions = new ArrayList<>();
		for (JsonNode node : array(root.get("sessions"))) {
			long start = time(node.get("startMs"));
			long end = time(node.get("endMs"));
			if (start >= 0 && end >= start) {
				sessions.add(new InkSession(start, end));
			}
			if (sessions.size() >= MAX_SESSIONS) {
				break;
			}
		}
		sessions.sort(Comparator.comparingLong(InkSession::startMs));
		List<InkStroke> strokes = new ArrayList<>();
		for (JsonNode node : array(root.get("strokes"))) {
			parseStroke(node).ifPresent(strokes::add);
			if (strokes.size() >= MAX_STROKES) {
				break;
			}
		}
		strokes.sort(Comparator.comparingLong(InkStroke::startMs));
		return new InkRecording(sessions, strokes);
	}

	private static Optional<InkStroke> parseStroke(JsonNode node) {
		if (node == null || !node.isObject()) {
			return Optional.empty();
		}
		JsonNode id = node.get("id");
		JsonNode tool = node.get("tool");
		JsonNode color = node.get("color");
		JsonNode width = node.get("width");
		if (id == null || !id.isString() || id.asString().isBlank()
				|| tool == null || !tool.isString()
				|| color == null || !color.isString() || !COLOR.matcher(color.asString()).matches()
				|| width == null || !width.isNumber() || !(width.asDouble() > 0) || width.asDouble() > 1) {
			return Optional.empty();
		}
		Optional<InkTool> parsedTool = InkTool.parse(tool.asString());
		long start = time(node.get("startMs"));
		long end = time(node.get("endMs"));
		long cleared = time(node.get("clearedAtMs"));
		if (parsedTool.isEmpty() || start < 0 || end < start || cleared < end) {
			return Optional.empty();
		}
		List<InkPoint> points = new ArrayList<>();
		for (JsonNode point : array(node.get("points"))) {
			if (!point.isArray() || point.size() != 3) {
				continue;
			}
			long t = time(point.get(0));
			double x = point.get(1).asDouble(Double.NaN);
			double y = point.get(2).asDouble(Double.NaN);
			if (t < 0 || !Double.isFinite(x) || !Double.isFinite(y)) {
				continue;
			}
			points.add(new InkPoint(t, CursorTelemetry.clamp(x, 0, 1), CursorTelemetry.clamp(y, 0, 1)));
			if (points.size() >= MAX_POINTS_PER_STROKE) {
				break;
			}
		}
		if (points.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new InkStroke(id.asString(), parsedTool.get(), color.asString().toLowerCase(),
				width.asDouble(), start, end, cleared, points));
	}

	private static Iterable<JsonNode> array(JsonNode node) {
		return node != null && node.isArray() ? node : List.of();
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
	 * Writes the sidecar next to the video. A recording without draw sessions removes it, so a take
	 * where the pen was never used leaves no file behind.
	 */
	public static void write(String videoPath, InkRecording recording) throws IOException {
		Path path = StorageFiles.inkTelemetryPath(videoPath);
		if (recording.isEmpty()) {
			Files.deleteIfExists(path);
			return;
		}
		Json.writeFile(path, toJson(recording));
	}

	static ObjectNode toJson(InkRecording recording) {
		ObjectNode root = Json.nodeFactory().objectNode();
		root.put("version", INK_TELEMETRY_VERSION);
		ArrayNode sessions = root.putArray("sessions");
		for (InkSession session : recording.sessions()) {
			ObjectNode node = sessions.addObject();
			node.put("startMs", session.startMs());
			node.put("endMs", session.endMs());
		}
		ArrayNode strokes = root.putArray("strokes");
		for (InkStroke stroke : recording.strokes()) {
			ObjectNode node = strokes.addObject();
			node.put("id", stroke.id());
			node.put("tool", stroke.tool().wire());
			node.put("color", stroke.color());
			node.put("width", round(stroke.width()));
			node.put("startMs", stroke.startMs());
			node.put("endMs", stroke.endMs());
			node.put("clearedAtMs", stroke.clearedAtMs());
			ArrayNode points = node.putArray("points");
			for (InkPoint point : stroke.points()) {
				ArrayNode tuple = points.addArray();
				tuple.add(point.timeMs());
				tuple.add(round(point.x()));
				tuple.add(round(point.y()));
			}
		}
		return root;
	}

	/** Five decimals: 0.01 px on an 8K frame, and a much smaller file than full doubles. */
	private static double round(double value) {
		return Math.round(value * 100_000) / 100_000.0;
	}

	// ── using it ───────────────────────────────────────────────────────────

	/**
	 * Turns every click inside a draw session into a plain move. A press on the ink overlay is a
	 * pen stroke, not a click, so click effects, click sounds and click-driven zooms must not
	 * fire for it. The positions are kept, so the cursor still follows the pen.
	 */
	public static List<CursorTelemetryPoint> withoutClicksInSessions(
			List<CursorTelemetryPoint> samples, List<InkSession> sessions) {
		if (sessions.isEmpty()) {
			return samples;
		}
		List<CursorTelemetryPoint> filtered = new ArrayList<>(samples.size());
		for (CursorTelemetryPoint sample : samples) {
			CursorInteractionType type = sample.interactionType();
			boolean press = type != null && type != CursorInteractionType.MOVE;
			if (press && inAny(sessions, sample.timeMs())) {
				filtered.add(new CursorTelemetryPoint(sample.timeMs(), sample.cx(), sample.cy(),
						CursorInteractionType.MOVE, sample.cursorType()));
			} else {
				filtered.add(sample);
			}
		}
		return filtered;
	}

	private static boolean inAny(List<InkSession> sessions, double timeMs) {
		for (InkSession session : sessions) {
			if (session.contains(timeMs)) {
				return true;
			}
		}
		return false;
	}

	/** Reads the cursor samples of a video with clicks inside its draw sessions removed. */
	public static List<CursorTelemetryPoint> cursorTelemetryWithoutInkClicks(
			String videoPath, List<CursorTelemetryPoint> samples) {
		return withoutClicksInSessions(samples, read(videoPath).sessions());
	}
}
