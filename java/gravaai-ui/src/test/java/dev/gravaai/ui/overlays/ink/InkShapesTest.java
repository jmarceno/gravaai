package dev.gravaai.ui.overlays.ink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.InkTelemetry.InkTool;

/** Shape geometry and the remembered palette. */
class InkShapesTest {

	@Test
	@DisplayName("a box is the same whichever corner the drag starts from")
	void box() {
		assertEquals(new InkShapes.Box(10, 20, 30, 40), InkShapes.box(40, 60, 10, 20));
		assertEquals(new InkShapes.Box(10, 20, 30, 40), InkShapes.box(10, 20, 40, 60));
	}

	@Test
	@DisplayName("an arrow head points back along the shaft and never outgrows it")
	void arrowHead() {
		double[] head = InkShapes.arrowHead(0, 0, 100, 0, 4);
		double length = InkShapes.MIN_HEAD_LENGTH;
		assertEquals(100 - length * Math.cos(InkShapes.HEAD_HALF_ANGLE), head[0], 1e-9);
		assertEquals(length * Math.sin(InkShapes.HEAD_HALF_ANGLE), head[1], 1e-9);
		assertEquals(head[0], head[2], 1e-9);
		assertEquals(-head[1], head[3], 1e-9);
		// A 5 px arrow keeps a 5 px head.
		double[] tiny = InkShapes.arrowHead(0, 0, 5, 0, 14);
		assertEquals(5, Math.hypot(5 - tiny[0], tiny[1]), 1e-9);
		assertEquals(0, InkShapes.arrowHead(3, 3, 3, 3, 4).length);
	}

	@Test
	@DisplayName("palette preferences round-trip and clamp what is out of range")
	void preferences() {
		InkPreferences chosen = new InkPreferences(InkTool.ARROW, 3, 2, true, true);
		assertEquals(chosen, InkPreferences.fromJson(chosen.toJson()));
		assertEquals(InkPreferences.DEFAULT, InkPreferences.fromJson(Json.parseJsonWithByteOrderMark("\"x\"")));
		InkPreferences odd = InkPreferences.fromJson(Json.parseJsonWithByteOrderMark(
				"{\"tool\": \"laser\", \"color\": 99, \"width\": -4}"));
		assertEquals(InkTool.PEN, odd.tool());
		assertEquals(InkOverlay.COLORS.size() - 1, odd.colorIndex());
		assertEquals(0, odd.widthIndex());
	}
}
