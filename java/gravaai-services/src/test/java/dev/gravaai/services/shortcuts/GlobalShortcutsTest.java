package dev.gravaai.services.shortcuts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;

/**
 * Port of {@code src/lib/globalShortcuts.test.ts}.
 */
class GlobalShortcutsTest {

	private static GlobalShortcutBinding binding(String key, boolean sup, boolean ctrl, boolean alt, boolean shift) {
		return new GlobalShortcutBinding(key, sup, ctrl, alt, shift);
	}

	private static GlobalShortcutKeyEvent keyEvent(
			String code, boolean ctrl, boolean alt, boolean shift, boolean meta) {
		return new GlobalShortcutKeyEvent(code, ctrl, alt, shift, meta);
	}

	@Test
	@DisplayName("normalizeGlobalShortcutsConfig returns the defaults for garbage")
	void normalizeReturnsDefaultsForGarbage() {
		for (var value : new tools.jackson.databind.JsonNode[] {
				null,
				Json.MAPPER.readTree("null"),
				Json.MAPPER.readTree("42"),
				Json.MAPPER.readTree("\"x\""),
				Json.MAPPER.readTree("[]"),
				Json.MAPPER.readTree("{\"bindings\":\"nope\"}")}) {
			assertEquals(
					GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS,
					GlobalShortcuts.normalizeGlobalShortcutsConfig(value),
					"value: " + value);
		}
	}

	@Test
	@DisplayName("normalizeGlobalShortcutsConfig keeps valid parts, explicit nulls and falls back for invalid bindings")
	void normalizeKeepsValidParts() {
		GlobalShortcutsConfig config = GlobalShortcuts.normalizeGlobalShortcutsConfig(Json.MAPPER.readTree("""
				{
				  "enabled": false,
				  "bindings": {
				    "toggle-recording": {"key": "F9", "ctrl": true, "alt": true},
				    "toggle-pause": null,
				    "toggle-meeting": {"key": "m"},
				    "unknown": {"key": "x", "super": true}
				  }
				}
				"""));
		Map<GlobalShortcutAction, GlobalShortcutBinding> bindings = config.bindings();

		assertFalse(config.enabled());
		assertEquals(
				binding("F9", false, true, true, false), bindings.get(GlobalShortcutAction.TOGGLE_RECORDING));
		assertNull(bindings.get(GlobalShortcutAction.TOGGLE_PAUSE));
		assertEquals(
				GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.TOGGLE_MEETING),
				bindings.get(GlobalShortcutAction.TOGGLE_MEETING));
		assertNull(bindings.get(GlobalShortcutAction.CANCEL_RECORDING));
		assertFalse(bindings.containsKey(null));
		assertEquals(4, bindings.size());
	}

	@Test
	@DisplayName("isValidGlobalBinding needs Super, Ctrl or Alt and a supported key")
	void isValidGlobalBinding() {
		assertFalse(GlobalShortcuts.isValidGlobalBinding(binding("r", false, false, false, false)));
		assertFalse(GlobalShortcuts.isValidGlobalBinding(binding("r", false, false, false, true)));
		assertTrue(GlobalShortcuts.isValidGlobalBinding(binding("r", true, false, false, false)));
		assertTrue(GlobalShortcuts.isValidGlobalBinding(binding("F12", false, true, false, false)));
		assertFalse(GlobalShortcuts.isValidGlobalBinding(binding("F13", false, true, false, false)));
		assertFalse(GlobalShortcuts.isValidGlobalBinding(binding("R", false, false, true, false)));
		assertTrue(GlobalShortcuts.isValidGlobalBinding(binding("Prior", false, false, true, false)));
		assertFalse(GlobalShortcuts.isValidGlobalBinding(binding("semicolon", false, false, true, false)));
	}

	@Test
	@DisplayName("keyboardEventToBinding maps codes to xkb keysym names")
	void keyboardEventMapsCodes() {
		record Case(String code, String key) {}
		List<Case> table = List.of(
				new Case("KeyA", "a"),
				new Case("KeyZ", "z"),
				new Case("Digit0", "0"),
				new Case("Digit9", "9"),
				new Case("F1", "F1"),
				new Case("F12", "F12"),
				new Case("Space", "space"),
				new Case("Enter", "Return"),
				new Case("Escape", "Escape"),
				new Case("Home", "Home"),
				new Case("End", "End"),
				new Case("PageUp", "Prior"),
				new Case("PageDown", "Next"),
				new Case("Insert", "Insert"),
				new Case("Delete", "Delete"),
				new Case("ArrowUp", "Up"),
				new Case("ArrowDown", "Down"),
				new Case("ArrowLeft", "Left"),
				new Case("ArrowRight", "Right"));
		for (Case entry : table) {
			GlobalShortcutBinding mapped = GlobalShortcuts.keyboardEventToBinding(keyEvent(entry.code(), false, false, false, false));
			assertNotNull(mapped, entry.code());
			assertEquals(entry.key(), mapped.key(), entry.code());
		}
	}

	@Test
	@DisplayName("keyboardEventToBinding maps modifiers and rejects modifier-only or unknown keys")
	void keyboardEventMapsModifiers() {
		assertEquals(
				binding("r", true, true, true, true),
				GlobalShortcuts.keyboardEventToBinding(keyEvent("KeyR", true, true, true, true)));
		assertNull(GlobalShortcuts.keyboardEventToBinding(keyEvent("ShiftLeft", false, false, true, false)));
		assertNull(GlobalShortcuts.keyboardEventToBinding(keyEvent("MetaLeft", false, false, false, true)));
		assertNull(GlobalShortcuts.keyboardEventToBinding(keyEvent("Semicolon", false, false, false, false)));
		assertNull(GlobalShortcuts.keyboardEventToBinding(keyEvent("F13", false, false, false, false)));
	}

	@Test
	@DisplayName("formatGlobalBinding orders modifiers and labels keys")
	void formatGlobalBinding() {
		assertEquals("Super + Alt + R", GlobalShortcuts.formatGlobalBinding(binding("r", true, false, true, false)));
		assertEquals(
				"Ctrl + Alt + Shift + F9",
				GlobalShortcuts.formatGlobalBinding(binding("F9", false, true, true, true)));
		assertEquals("Super + Space", GlobalShortcuts.formatGlobalBinding(binding("space", true, false, false, false)));
		assertEquals("Ctrl + Page Up", GlobalShortcuts.formatGlobalBinding(binding("Prior", false, true, false, false)));
	}

	@Test
	@DisplayName("parseCliAction finds an action flag anywhere in argv")
	void parseCliAction() {
		assertEquals(
				GlobalShortcutAction.TOGGLE_RECORDING,
				GlobalShortcuts.parseCliAction(List.of("/opt/gravaai", "--toggle-recording")));
		assertEquals(
				GlobalShortcutAction.TOGGLE_MEETING,
				GlobalShortcuts.parseCliAction(List.of("/opt/gravaai", "--no-sandbox", "--toggle-meeting", "x")));
		assertNull(GlobalShortcuts.parseCliAction(List.of("/opt/gravaai", "toggle-pause")));
		assertNull(GlobalShortcuts.parseCliAction(List.of("/opt/gravaai", "--toggle-nothing")));
		assertNull(GlobalShortcuts.parseCliAction(List.of()));
	}

	@Test
	@DisplayName("bindingsEqual compares modifiers and keys case-insensitively")
	void bindingsEqual() {
		assertTrue(GlobalShortcuts.bindingsEqual(
				binding("r", true, false, false, false), binding("R", true, false, false, false)));
		assertFalse(GlobalShortcuts.bindingsEqual(
				binding("r", true, false, false, false), binding("r", true, false, false, true)));
		assertFalse(GlobalShortcuts.bindingsEqual(
				binding("r", false, false, true, false), binding("p", false, false, true, false)));
	}

	@Test
	@DisplayName("DEFAULT_GLOBAL_SHORTCUTS and labels match the TS tables")
	void defaultsAndLabels() {
		assertTrue(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.enabled());
		assertEquals("r", GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.TOGGLE_RECORDING).key());
		assertEquals("p", GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.TOGGLE_PAUSE).key());
		assertNull(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.CANCEL_RECORDING));
		assertEquals("m", GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.TOGGLE_MEETING).key());
		assertEquals("Start / stop recording", GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(GlobalShortcutAction.TOGGLE_RECORDING));
		assertEquals("Pause / resume", GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(GlobalShortcutAction.TOGGLE_PAUSE));
		assertEquals("Cancel recording", GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(GlobalShortcutAction.CANCEL_RECORDING));
		assertEquals("Start / stop meeting", GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(GlobalShortcutAction.TOGGLE_MEETING));
	}
}
