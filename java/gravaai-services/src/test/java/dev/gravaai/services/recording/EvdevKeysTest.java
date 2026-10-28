package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;

/** Ported from {@code electron/ipc/keyboard/evdevKeys.test.ts}. */
class EvdevKeysTest {

	@Test
	@DisplayName("labels letters, digits and special keys by their US position")
	void labelsLettersDigitsAndSpecialKeys() {
		assertEquals("A", EvdevKeys.evdevKeyLabel(30));
		assertEquals("S", EvdevKeys.evdevKeyLabel(31));
		assertEquals("1", EvdevKeys.evdevKeyLabel(2));
		assertEquals("0", EvdevKeys.evdevKeyLabel(11));
		assertEquals("Esc", EvdevKeys.evdevKeyLabel(1));
		assertEquals("Enter", EvdevKeys.evdevKeyLabel(28));
		assertEquals("Space", EvdevKeys.evdevKeyLabel(57));
		assertEquals("F1", EvdevKeys.evdevKeyLabel(59));
		assertEquals("F11", EvdevKeys.evdevKeyLabel(87));
		assertEquals("↑", EvdevKeys.evdevKeyLabel(103));
		assertEquals("Delete", EvdevKeys.evdevKeyLabel(111));
	}

	@Test
	@DisplayName("labels modifiers")
	void labelsModifiers() {
		assertEquals("Ctrl", EvdevKeys.evdevKeyLabel(29));
		assertEquals("Ctrl", EvdevKeys.evdevKeyLabel(97));
		assertEquals("Super", EvdevKeys.evdevKeyLabel(125));
	}

	@Test
	@DisplayName("returns null for keys that are not drawn")
	void returnsNullForUndrawnKeys() {
		assertEquals("←", EvdevKeys.evdevKeyLabel(105));
		assertNull(EvdevKeys.evdevKeyLabel(183)); // F13 has no label
		assertNull(EvdevKeys.evdevKeyLabel(9999));
	}

	@Test
	@DisplayName("marks modifier codes")
	void marksModifierCodes() {
		assertTrue(EvdevKeys.isModifierCode(42));
		assertFalse(EvdevKeys.isModifierCode(30));
	}

	@Test
	@DisplayName("keeps the special key set aligned with the labels")
	void keepsSpecialKeySetAlignedWithLabels() {
		for (int code : EvdevKeys.SPECIAL_KEY_CODES) {
			assertNotNull(EvdevKeys.evdevKeyLabel(code), "code " + code);
		}
	}

	@Test
	@DisplayName("maps every modifier code to a modifier")
	void mapsEveryModifierCode() {
		for (int code : EvdevKeys.MODIFIER_CODES.keySet()) {
			assertTrue(EvdevKeys.isModifierCode(code));
		}
	}

	@Test
	@DisplayName("sorts modifiers into the chip order")
	void sortsModifiersIntoTheChipOrder() {
		assertEquals(
				java.util.List.of(KeyModifier.CTRL, KeyModifier.ALT, KeyModifier.SHIFT),
				KeystrokeTypes.orderedModifiers(Set.of(KeyModifier.SHIFT, KeyModifier.CTRL, KeyModifier.ALT)));
		assertEquals(
				java.util.List.of(KeyModifier.SUPER),
				KeystrokeTypes.orderedModifiers(Set.of(KeyModifier.SUPER)));
		assertEquals(java.util.List.of(), KeystrokeTypes.orderedModifiers(Set.of()));
	}
}
