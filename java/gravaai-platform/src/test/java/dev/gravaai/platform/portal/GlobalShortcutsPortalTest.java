package dev.gravaai.platform.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading what the desktop bound out of a {@code BindShortcuts} response. */
class GlobalShortcutsPortalTest {

	@Test
	@DisplayName("the bound shortcuts carry the desktop's own trigger description")
	void readsBoundShortcuts() {
		Variant<?> shortcuts = new Variant<>(List.of(
				new Object[] {"toggle-recording", Map.of(
						"description", new Variant<>("Start / stop recording"),
						"trigger_description", new Variant<>("Ctrl+Alt+R"))},
				new Object[] {"cancel-recording", Map.of(
						"description", new Variant<>("Cancel recording"))}),
				"a(sa{sv})");

		assertEquals(
				List.of(new GlobalShortcutsPortal.BoundShortcut("toggle-recording", "Ctrl+Alt+R"),
						new GlobalShortcutsPortal.BoundShortcut("cancel-recording", "")),
				GlobalShortcutsPortal.boundShortcuts(shortcuts));
	}

	@Test
	@DisplayName("a response without shortcuts binds nothing")
	void missingShortcutsBindNothing() {
		assertTrue(GlobalShortcutsPortal.boundShortcuts(null).isEmpty());
		assertTrue(GlobalShortcutsPortal.boundShortcuts(new Variant<>("unexpected")).isEmpty());
	}
}
