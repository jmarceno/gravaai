package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code matchesShortcut}/{@code DEFAULT_SHORTCUTS}, scoped to the timeline actions. */
class TimelineShortcutsTest {

	@Test
	@DisplayName("matches the default timeline shortcuts on Linux with ctrl")
	void matchesDefaults() {
		TimelineShortcuts.Bindings bindings = TimelineShortcuts.defaults();

		assertTrue(TimelineShortcuts.matches(KeyCode.Z, false, false, false, false,
				bindings.addZoom(), false));
		assertTrue(TimelineShortcuts.matches(KeyCode.C, false, false, false, false,
				bindings.splitClip(), false));
		assertTrue(TimelineShortcuts.matches(KeyCode.F, false, false, false, false,
				bindings.addKeyframe(), false));
		assertTrue(TimelineShortcuts.matches(KeyCode.D, true, false, false, false,
				bindings.deleteSelected(), false));
		assertFalse(TimelineShortcuts.matches(KeyCode.D, false, false, false, false,
				bindings.deleteSelected(), false));
	}

	@Test
	@DisplayName("maps ctrl to Cmd on macOS")
	void mapsPrimaryModifierOnMac() {
		TimelineShortcuts.Bindings bindings = TimelineShortcuts.defaults();

		assertTrue(TimelineShortcuts.matches(KeyCode.D, false, true, false, false,
				bindings.deleteSelected(), true));
		assertFalse(TimelineShortcuts.matches(KeyCode.D, true, false, false, false,
				bindings.deleteSelected(), true));
	}

	@Test
	@DisplayName("requires shift and alt to match exactly")
	void requiresExactModifiers() {
		TimelineShortcuts.Binding shifted = TimelineShortcuts.Binding.of("z", false, true, false);

		assertTrue(TimelineShortcuts.matches(KeyCode.Z, false, false, true, false, shifted, false));
		assertFalse(TimelineShortcuts.matches(KeyCode.Z, false, false, false, false, shifted,
				false));
	}

	@Test
	@DisplayName("port of the DOM key names for letters, space and delete")
	void mapsDomKeyNames() {
		assertEquals("c", TimelineShortcuts.keyOf(KeyCode.C));
		assertEquals(" ", TimelineShortcuts.keyOf(KeyCode.SPACE));
		assertEquals("delete", TimelineShortcuts.keyOf(KeyCode.DELETE));
		assertEquals("backspace", TimelineShortcuts.keyOf(KeyCode.BACK_SPACE));
		assertEquals("arrowright", TimelineShortcuts.keyOf(KeyCode.RIGHT));
	}
}
