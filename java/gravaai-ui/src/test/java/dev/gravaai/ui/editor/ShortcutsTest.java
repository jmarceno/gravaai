package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code src/lib/shortcuts.ts} behaviours (plan §5.12, P2.6). */
class ShortcutsTest {

	@Test
	@DisplayName("keeps the default bindings")
	void keepsDefaultBindings() {
		Map<Shortcuts.Action, Shortcuts.Binding> defaults = Shortcuts.defaults();
		assertEquals(new Shortcuts.Binding("z", false, false, false),
				defaults.get(Shortcuts.Action.ADD_ZOOM));
		assertEquals(new Shortcuts.Binding("c", false, false, false),
				defaults.get(Shortcuts.Action.SPLIT_CLIP));
		assertEquals(new Shortcuts.Binding(" ", false, false, false),
				defaults.get(Shortcuts.Action.PLAY_PAUSE));
		assertEquals(new Shortcuts.Binding("d", true, false, false),
				defaults.get(Shortcuts.Action.DELETE_SELECTED));
		assertEquals(List.of(Shortcuts.Action.values()), List.of(Shortcuts.Action.values()));
	}

	@Test
	@DisplayName("compares bindings case-insensitively and modifier-by-modifier")
	void comparesBindings() {
		assertTrue(Shortcuts.bindingsEqual(Shortcuts.Binding.of("Z"), Shortcuts.Binding.of("z")));
		assertFalse(Shortcuts.bindingsEqual(new Shortcuts.Binding("z", true, false, false),
				Shortcuts.Binding.of("z")));
		assertFalse(Shortcuts.bindingsEqual(new Shortcuts.Binding("z", false, true, false),
				Shortcuts.Binding.of("z")));
		assertFalse(Shortcuts.bindingsEqual(new Shortcuts.Binding("z", false, false, true),
				Shortcuts.Binding.of("z")));
	}

	@Test
	@DisplayName("reports fixed-shortcut conflicts before configurable ones")
	void reportsConflicts() {
		Map<Shortcuts.Action, Shortcuts.Binding> config = Shortcuts.defaults();
		assertInstanceOf(Shortcuts.FixedConflict.class,
				Shortcuts.findConflict(new Shortcuts.Binding("tab", false, true, false),
						Shortcuts.Action.ADD_ZOOM, config));
		assertInstanceOf(Shortcuts.ConfigurableConflict.class,
				Shortcuts.findConflict(Shortcuts.Binding.of("c"), Shortcuts.Action.ADD_ZOOM,
						config));
		assertNull(Shortcuts.findConflict(Shortcuts.Binding.of("q"),
				Shortcuts.Action.ADD_ZOOM, config));
	}

	@Test
	@DisplayName("matches key events against the platform primary modifier")
	void matchesShortcuts() {
		Shortcuts.Binding ctrlD = new Shortcuts.Binding("d", true, false, false);
		assertTrue(Shortcuts.matchesShortcut("d", true, false, false, false, ctrlD, false));
		assertFalse(Shortcuts.matchesShortcut("d", false, true, false, false, ctrlD, false));
		assertTrue(Shortcuts.matchesShortcut("d", false, true, false, false, ctrlD, true));
		assertTrue(Shortcuts.matchesShortcut("D", true, false, false, false, ctrlD, false));
		assertFalse(Shortcuts.matchesShortcut("d", true, false, true, false, ctrlD, false));
	}

	@Test
	@DisplayName("formats bindings with platform labels")
	void formatsBindings() {
		assertEquals("Z", Shortcuts.formatBinding(Shortcuts.Binding.of("z"), false));
		assertEquals("Ctrl + D",
				Shortcuts.formatBinding(new Shortcuts.Binding("d", true, false, false), false));
		assertEquals("⌘ + D",
				Shortcuts.formatBinding(new Shortcuts.Binding("d", true, false, false), true));
		assertEquals("⇧ + TAB",
				Shortcuts.formatBinding(new Shortcuts.Binding("tab", false, true, false), true));
		assertEquals("Space", Shortcuts.formatBinding(Shortcuts.Binding.of(" "), false));
		assertEquals("⌫", Shortcuts.formatBinding(Shortcuts.Binding.of("backspace"), false));
	}

	@Test
	@DisplayName("merges a partial configuration over the defaults")
	void mergesWithDefaults() {
		Map<Shortcuts.Action, Shortcuts.Binding> partial = new LinkedHashMap<>();
		partial.put(Shortcuts.Action.ADD_ZOOM, Shortcuts.Binding.of("q"));
		Map<Shortcuts.Action, Shortcuts.Binding> merged = Shortcuts.mergeWithDefaults(partial);

		assertEquals(Shortcuts.Binding.of("q"), merged.get(Shortcuts.Action.ADD_ZOOM));
		assertEquals(Shortcuts.Binding.of("c"), merged.get(Shortcuts.Action.SPLIT_CLIP));
		assertEquals(6, merged.size());
	}
}
