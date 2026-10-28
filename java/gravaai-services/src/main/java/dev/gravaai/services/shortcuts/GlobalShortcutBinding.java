package dev.gravaai.services.shortcuts;

/**
 * One keyboard shortcut. {@code key} is the xkb keysym name as COSMIC writes it: lowercase
 * letters ("r"), digits ("1"), "F1".."F12", "Escape", "space", "Return", "Home", "End", "Prior",
 * "Next", "Insert", "Delete", "Up", "Down", "Left", "Right".
 *
 * <p>Ported from {@code GlobalShortcutBinding} in {@code src/lib/globalShortcuts.ts}; {@code sup}
 * is the "Super" modifier ({@code super} is a Java keyword).
 */
public record GlobalShortcutBinding(String key, boolean sup, boolean ctrl, boolean alt, boolean shift) {

	public GlobalShortcutBinding {
		key = key == null ? "" : key;
	}
}
