package dev.gravaai.services.shortcuts;

/**
 * A key press in the shape {@code keyboardEventToBinding} expects: the DOM {@code KeyboardEvent}
 * fields (port of the argument type in {@code src/lib/globalShortcuts.ts}). The JavaFX adapter
 * that turns a {@code javafx.scene.input.KeyEvent} into this record belongs to the UI module, so
 * the services module stays free of JavaFX.
 */
public record GlobalShortcutKeyEvent(String code, boolean ctrlKey, boolean altKey, boolean shiftKey, boolean metaKey) {

	public GlobalShortcutKeyEvent {
		code = code == null ? "" : code;
	}
}
