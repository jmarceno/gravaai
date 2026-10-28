package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Colour thresholds from src/components/ui/audio-level-meter.tsx. */
class AudioLevelMeterTest {

	@DisplayName("level > 85 is danger, level > 65 is warning, otherwise accent")
	@Test
	void thresholds() {
		assertEquals("", AudioLevelMeter.colorClass(0));
		assertEquals("", AudioLevelMeter.colorClass(65));
		assertEquals("meter__fill--warning", AudioLevelMeter.colorClass(65.1));
		assertEquals("meter__fill--warning", AudioLevelMeter.colorClass(85));
		assertEquals("meter__fill--danger", AudioLevelMeter.colorClass(85.1));
		assertEquals("meter__fill--danger", AudioLevelMeter.colorClass(100));
	}
}
