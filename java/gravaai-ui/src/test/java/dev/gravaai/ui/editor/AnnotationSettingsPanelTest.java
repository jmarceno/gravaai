package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure presets/parsers of the annotation appearance panel (P2.6). */
class AnnotationSettingsPanelTest {

	@Test
	@DisplayName("exposes the nine 3x3 position presets in visual order")
	void exposesPositionPresets() {
		List<AnnotationSettingsPanel.PositionPreset> presets =
				AnnotationSettingsPanel.positionPresets();
		assertEquals(9, presets.size());
		assertEquals("top-left", presets.get(0).id());
		assertEquals("center", presets.get(4).id());
		assertEquals("bottom-right", presets.get(8).id());
		assertEquals(50, presets.get(4).x(), 0.001);
		assertEquals(50, presets.get(4).y(), 0.001);
	}

	@Test
	@DisplayName("offers built-in fonts and colour swatches")
	void offersFontsAndSwatches() {
		assertTrue(AnnotationSettingsPanel.builtInFontFamilies().contains("DM Sans"));
		assertTrue(AnnotationSettingsPanel.builtInFontFamilies().size() >= 8);
		assertTrue(AnnotationSettingsPanel.colorSwatches().contains("#FFFFFF"));
		assertTrue(AnnotationSettingsPanel.colorSwatches().size() >= 8);
	}

	@Test
	@DisplayName("parses and normalises hex colours")
	void parsesHexColors() {
		assertEquals("#FFFFFF", AnnotationSettingsPanel.parseHexColor("#ffffff"));
		assertEquals("#AABBCC", AnnotationSettingsPanel.parseHexColor("abc"));
		assertEquals("#123456", AnnotationSettingsPanel.parseHexColor(" #123456 "));
		assertNull(AnnotationSettingsPanel.parseHexColor("red"));
		assertNull(AnnotationSettingsPanel.parseHexColor("#12345"));
		assertNull(AnnotationSettingsPanel.parseHexColor(null));
		assertNotNull(AnnotationSettingsPanel.parseHexColor("#2563EB"));
	}

	@Test
	@DisplayName("clamps sizes into the editable range")
	void clampsSizes() {
		assertEquals(5, AnnotationSettingsPanel.clampSize(0), 0.001);
		assertEquals(100, AnnotationSettingsPanel.clampSize(140), 0.001);
		assertEquals(60, AnnotationSettingsPanel.clampSize(60), 0.001);
	}
}
