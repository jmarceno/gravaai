package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Webcam position preset wires: saved projects depend on them staying stable. */
class WebcamPositionPresetTest {

	@Test
	@DisplayName("the split screen presets round-trip their wires and know they are split")
	void splitWiresRoundTrip() {
		for (WebcamPositionPreset preset : new WebcamPositionPreset[] {
				WebcamPositionPreset.SPLIT_RIGHT, WebcamPositionPreset.SPLIT_LEFT,
				WebcamPositionPreset.SPLIT_TOP, WebcamPositionPreset.SPLIT_BOTTOM}) {
			assertEquals(preset, WebcamPositionPreset.fromWire(preset.wire()));
			assertEquals(preset, WebcamPositionPreset.fromWireOrNull(preset.wire()));
			assertTrue(preset.isSplit(), preset.wire() + " must report isSplit");
			assertFalse(preset.isCorner(), preset.wire() + " must not be a corner");
		}
		assertEquals("split-right", WebcamPositionPreset.SPLIT_RIGHT.wire());
		assertEquals("split-left", WebcamPositionPreset.SPLIT_LEFT.wire());
		assertEquals("split-top", WebcamPositionPreset.SPLIT_TOP.wire());
		assertEquals("split-bottom", WebcamPositionPreset.SPLIT_BOTTOM.wire());
	}

	@Test
	@DisplayName("unknown wires keep the documented fallbacks")
	void unknownWiresFallBack() {
		assertEquals(WebcamPositionPreset.BOTTOM_RIGHT, WebcamPositionPreset.fromWire("nope"));
		assertNull(WebcamPositionPreset.fromWireOrNull("nope"));
	}
}
