package dev.gravaai.services.shortcuts;

/**
 * The HUD overlay as far as shortcut dispatch is concerned (port of the
 * {@code getHudOverlayWindow()?.webContents} checks in
 * {@code electron/globalShortcuts/dispatch.ts}). The UI module implements it on the JavaFX stage,
 * so the services module stays free of JavaFX.
 */
public interface GlobalShortcutHud {

	/** False once the HUD stage is destroyed. */
	boolean isAlive();

	/** True once the HUD's launch view subscribed to shortcut actions. */
	boolean isReady();

	/** Delivers one action to the HUD's launch view. */
	void deliver(GlobalShortcutAction action);
}
