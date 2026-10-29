package dev.gravaai.services.agent;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.project.ArrowDirection;
import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomRegion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Raw-op field readers for the agent planner (port of the validation helpers in
 * {@code src/components/video-editor/agent/ops.ts}). Error messages are byte-identical to the TS
 * ones because the agent reads them back through MCP.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class AgentOpReader {

	/** A validation failure; the planner attaches the op index. */
	static final class OpError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		OpError(String message) {
			super(message);
		}
	}

	/** Annotation geometry as 0-1 fractions (defaults 0.5/0.5/0.3/0.2). */
	record Geometry(double x, double y, double w, double h) {}

	/** Annotation text style input. */
	record StyleInput(@Nullable Double fontSize, @Nullable Boolean bold, @Nullable Boolean italic, String color,
			String backgroundColor, @Nullable String align, @Nullable String fontFamily) {}

	/** A validated fade-black transition request; {@code ms == null} means remove. */
	record TransitionInput(boolean present, @Nullable Long ms) {}

	private AgentOpReader() {}

	static ObjectNode readRawOp(JsonNode input, int index) {
		if (input == null || !input.isObject()) {
			throw new OpError("op " + index + " must be an object with an \"op\" field");
		}
		return (ObjectNode) input;
	}

	static String opName(ObjectNode raw, int index) {
		JsonNode op = raw.path("op");
		if (!op.isString()) {
			throw new OpError("op " + index + " needs an \"op\" name");
		}
		return op.asString();
	}

	static double number(JsonNode raw, String key) {
		return number(raw, key, key);
	}

	static double number(JsonNode raw, String key, String label) {
		JsonNode value = raw.path(key);
		if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
			throw new OpError("\"" + label + "\" must be a number");
		}
		return value.asDouble();
	}

	static @Nullable Double optionalNumber(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		return number(raw, key);
	}

	static String string(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (!value.isString() || value.asString().trim().isEmpty()) {
			throw new OpError("\"" + key + "\" must be a non-empty string");
		}
		return value.asString();
	}

	static @Nullable String optionalString(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		return string(raw, key);
	}

	static @Nullable Boolean optionalBoolean(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isBoolean()) {
			throw new OpError("\"" + key + "\" must be true or false");
		}
		return value.asBoolean();
	}

	static @Nullable JsonNode optionalObject(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isObject()) {
			throw new OpError("\"" + key + "\" must be an object");
		}
		return value;
	}

	/** {@code readRange}: [from, to] with from >= 0 and to > from (multiplied by 1000 later). */
	static double[] readRange(JsonNode raw, String fromKey, String toKey) {
		double from = number(raw, fromKey);
		double to = number(raw, toKey);
		if (from < 0) {
			throw new OpError("\"" + fromKey + "\" must be >= 0");
		}
		if (to <= from) {
			throw new OpError("\"" + toKey + "\" must be greater than \"" + fromKey + "\"");
		}
		return new double[] {from, to};
	}

	/** {@code readFraction}: optional 0-1 number. */
	static Double readFraction(JsonNode raw, String key) {
		Double value = optionalNumber(raw, key);
		if (value != null && (value < 0 || value > 1)) {
			throw new OpError("\"" + key + "\" must be between 0 and 1");
		}
		return value;
	}

	static Geometry geometry(JsonNode raw) {
		Double x = readFraction(raw, "x");
		Double y = readFraction(raw, "y");
		Double w = readFraction(raw, "w");
		Double h = readFraction(raw, "h");
		return new Geometry(x == null ? 0.5 : x, y == null ? 0.5 : y, w == null ? 0.3 : w,
				h == null ? 0.2 : h);
	}

	static @Nullable ZoomDepth readDepth(JsonNode raw, boolean required) {
		JsonNode depth = raw.path("depth");
		if (!required && (depth.isMissingNode() || depth.isNull())) {
			return null;
		}
		double value = number(raw, "depth");
		if (!isInteger(value) || value < 1 || value > 6) {
			throw new OpError("\"depth\" must be an integer between 1 and 6 (1 = closest,"
					+ " 6 = tightest)");
		}
		return ZoomDepth.fromWire((int) value);
	}

	static @Nullable ZoomFocus readFocus(JsonNode raw) {
		JsonNode focus = optionalObject(raw, "focus");
		if (focus == null) {
			return null;
		}
		Double x = readFraction(focus, "x");
		Double y = readFraction(focus, "y");
		if (x == null) {
			throw new OpError("\"focus.x\" is required (0–1 across the frame)");
		}
		if (y == null) {
			throw new OpError("\"focus.y\" is required (0–1 down the frame)");
		}
		return new ZoomFocus(x, y);
	}

	static @Nullable StyleInput readStyle(JsonNode raw) {
		JsonNode style = optionalObject(raw, "style");
		if (style == null) {
			return null;
		}
		JsonNode fontFamily = style.path("fontFamily");
		if (!fontFamily.isMissingNode() && !fontFamily.isNull() && !fontFamily.isString()) {
			throw new OpError("\"style.fontFamily\" must be a string");
		}
		String align = null;
		JsonNode alignNode = style.path("align");
		if (!alignNode.isMissingNode() && !alignNode.isNull()) {
			if (!alignNode.isString() || !List.of("left", "center", "right")
					.contains(alignNode.asString())) {
				throw new OpError("\"style.align\" must be left, center or right");
			}
			align = alignNode.asString();
		}
		Double fontSize = optionalNumber(style, "fontSize");
		if (fontSize != null && (fontSize < 8 || fontSize > 200)) {
			throw new OpError("\"style.fontSize\" must be between 8 and 200 (pixels)");
		}
		return new StyleInput(fontSize, optionalBoolean(style, "bold"),
				optionalBoolean(style, "italic"), optionalString(style, "color"),
				optionalString(style, "backgroundColor"), align,
				fontFamily.isString() ? fontFamily.asString() : null);
	}

	static @Nullable ArrowDirection readArrow(JsonNode raw, boolean required) {
		JsonNode arrow = raw.path("arrow");
		if (!required && (arrow.isMissingNode() || arrow.isNull())) {
			return null;
		}
		String value = string(raw, "arrow");
		for (ArrowDirection direction : ArrowDirection.values()) {
			if (direction.wire().equals(value)) {
				return direction;
			}
		}
		throw new OpError("\"arrow\" must be one of up, down, left, right, up-right, up-left,"
				+ " down-right, down-left");
	}

	static @Nullable ClipCameraLayout readCameraLayout(JsonNode raw) {
		String layout = optionalString(raw, "cameraLayout");
		if (layout == null) {
			return null;
		}
		for (ClipCameraLayout candidate : ClipCameraLayout.values()) {
			if (candidate.wire().equals(layout)) {
				return candidate;
			}
		}
		throw new OpError("\"cameraLayout\" must be default, hide-camera or camera-only");
	}

	static TransitionInput readTransition(JsonNode raw) {
		JsonNode transition = raw.path("transition");
		if (transition.isMissingNode()) {
			return new TransitionInput(false, null);
		}
		if (transition.isNull()) {
			return new TransitionInput(true, null);
		}
		if (!transition.isObject()) {
			throw new OpError("\"transition\" must be an object or null");
		}
		double ms = number(transition, "ms");
		if (ms < ClipTransition.MIN_CLIP_TRANSITION_MS || ms > ClipTransition.MAX_CLIP_TRANSITION_MS) {
			throw new OpError("\"transition.ms\" must be between 200 and 2000");
		}
		JsonNode kind = transition.path("kind");
		if (!kind.isMissingNode() && !kind.isNull()
				&& !"fade-black".equals(kind.isString() ? kind.asString() : null)) {
			throw new OpError("\"transition.kind\" must be \"fade-black\"");
		}
		return new TransitionInput(true, Math.round(ms));
	}

	static double readSpeedRate(JsonNode raw) {
		double rate = number(raw, "rate");
		for (SpeedRegion.SpeedOption option : SpeedRegion.SPEED_OPTIONS) {
			if (Math.abs(option.speed() - rate) < 1e-9) {
				return option.speed();
			}
		}
		StringBuilder presets = new StringBuilder();
		for (SpeedRegion.SpeedOption option : SpeedRegion.SPEED_OPTIONS) {
			if (presets.length() > 0) {
				presets.append(", ");
			}
			presets.append(option.speed());
		}
		throw new OpError("\"rate\" must be one of " + presets
				+ " (speed regions use fixed presets)");
	}

	static List<double[]> readRanges(JsonNode raw) {
		JsonNode value = raw.path("ranges");
		if (!value.isArray() || value.isEmpty()) {
			throw new OpError("\"ranges\" must be a non-empty array of [from, to] pairs in seconds");
		}
		List<double[]> ranges = new ArrayList<>();
		for (int index = 0; index < value.size(); index++) {
			JsonNode entry = value.get(index);
			if (!entry.isArray() || entry.size() != 2) {
				throw new OpError("\"ranges[" + index + "]\" must be a [from, to] pair");
			}
			JsonNode from = entry.get(0);
			JsonNode to = entry.get(1);
			if (!from.isNumber() || !to.isNumber() || !Double.isFinite(from.asDouble())
					|| !Double.isFinite(to.asDouble())) {
				throw new OpError("\"ranges[" + index + "]\" must contain two numbers");
			}
			if (from.asDouble() < 0) {
				throw new OpError("\"ranges[" + index + "][0]\" must be >= 0");
			}
			if (to.asDouble() <= from.asDouble()) {
				throw new OpError("\"ranges[" + index + "][1]\" must be greater than ranges["
						+ index + "][0]");
			}
			ranges.add(new double[] {from.asDouble(), to.asDouble()});
		}
		return List.copyOf(ranges);
	}

	static Map<String, JsonNode> readValues(JsonNode raw) {
		JsonNode nested = optionalObject(raw, "values");
		if (nested == null) {
			nested = optionalObject(raw, "settings");
		}
		Map<String, JsonNode> values = new LinkedHashMap<>();
		if (nested != null) {
			for (Map.Entry<String, JsonNode> entry : nested.properties()) {
				values.put(entry.getKey(), entry.getValue());
			}
		} else {
			for (Map.Entry<String, JsonNode> entry : raw.properties()) {
				if (entry.getKey().equals("op") || entry.getKey().equals("values")) {
					continue;
				}
				values.put(entry.getKey(), entry.getValue());
			}
		}
		if (values.isEmpty()) {
			throw new OpError("expected at least one value to set");
		}
		return values;
	}

	static List<String> readStringArray(JsonNode raw, String key) {
		JsonNode value = raw.path(key);
		if (!value.isArray() || value.isEmpty()) {
			throw new OpError("\"" + key + "\" must be a non-empty array");
		}
		List<String> strings = new ArrayList<>();
		for (JsonNode entry : value) {
			if (!entry.isString() || entry.asString().trim().isEmpty()) {
				throw new OpError("\"" + key + "\" must contain strings");
			}
			strings.add(entry.asString().trim());
		}
		return List.copyOf(strings);
	}

	static @Nullable Integer readCount(JsonNode raw) {
		Double n = optionalNumber(raw, "n");
		if (n == null) {
			return null;
		}
		if (!isInteger(n) || n < 1) {
			throw new OpError("\"n\" must be a positive integer");
		}
		return (int) (double) n;
	}

	private static boolean isInteger(double value) {
		return Double.isFinite(value) && value == Math.rint(value);
	}
}
