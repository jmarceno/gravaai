package dev.gravaai.services.automations;

/**
 * The desktop services the automation actions need, injected so the engine stays testable and the
 * services module stays free of platform/UI code (plan §5.3). The app wires it to
 * {@code dev.gravaai.platform.desktop.ClipboardService}, the notification, portal and FileManager1
 * services.
 */
public interface AutomationHost {

	/** Copies text to the system clipboard ({@code clipboard.writeText}). */
	void copyToClipboard(String text);

	/** Reveals the file in its folder ({@code shell.showItemInFolder}). */
	void revealInFolder(String path);

	/** Opens the file with its default application; throws with the OS message on failure. */
	void openPath(String path) throws Exception;

	/** Whether desktop notifications work ({@code Notification.isSupported()}). */
	boolean isNotificationSupported();

	/** Shows a desktop notification. */
	void notify(String title, String body);
}
