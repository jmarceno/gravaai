package dev.gravaai.ui.overlays.ink;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.InkTelemetry.InkTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the ink palette remembers between draw sessions: the last tool, colour and width, whether
 * strokes fade on their own, and whether the palette is hidden (keys keep working without it).
 *
 * @param colorIndex index into {@link InkOverlay#COLORS}
 * @param widthIndex index into the tool's widths (thin, medium, thick)
 * @param disappearing each stroke fades {@link InkOverlay#DISAPPEAR_AFTER} after it is drawn
 * @param paletteHidden the palette is not shown (it is part of the recorded video)
 */
public record InkPreferences(InkTool tool, int colorIndex, int widthIndex, boolean disappearing,
		boolean paletteHidden) {

	/** The settings key the app stores this under. */
	public static final String SETTING = "inkPreferences";

	public static final InkPreferences DEFAULT = new InkPreferences(InkTool.PEN, 0, 1, false, false);

	public InkPreferences {
		colorIndex = Math.clamp(colorIndex, 0, InkOverlay.COLORS.size() - 1);
		widthIndex = Math.clamp(widthIndex, 0, 2);
	}

	public InkPreferences withTool(InkTool next) {
		return new InkPreferences(next, colorIndex, widthIndex, disappearing, paletteHidden);
	}

	public InkPreferences withColor(int next) {
		return new InkPreferences(tool, next, widthIndex, disappearing, paletteHidden);
	}

	public InkPreferences withWidth(int next) {
		return new InkPreferences(tool, colorIndex, next, disappearing, paletteHidden);
	}

	public InkPreferences withDisappearing(boolean next) {
		return new InkPreferences(tool, colorIndex, widthIndex, next, paletteHidden);
	}

	public InkPreferences withPaletteHidden(boolean next) {
		return new InkPreferences(tool, colorIndex, widthIndex, disappearing, next);
	}

	public ObjectNode toJson() {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("tool", tool.wire());
		node.put("color", colorIndex);
		node.put("width", widthIndex);
		node.put("disappearing", disappearing);
		node.put("paletteHidden", paletteHidden);
		return node;
	}

	/** Reads a stored value; anything missing or invalid falls back to the default. */
	public static InkPreferences fromJson(@Nullable JsonNode node) {
		if (node == null || !node.isObject()) {
			return DEFAULT;
		}
		InkTool tool = node.path("tool").isString()
				? InkTool.parse(node.path("tool").asString()).orElse(DEFAULT.tool())
				: DEFAULT.tool();
		return new InkPreferences(tool,
				node.path("color").isInt() ? node.path("color").asInt() : DEFAULT.colorIndex(),
				node.path("width").isInt() ? node.path("width").asInt() : DEFAULT.widthIndex(),
				node.path("disappearing").isBoolean() && node.path("disappearing").asBoolean(),
				node.path("paletteHidden").isBoolean() && node.path("paletteHidden").asBoolean());
	}
}
