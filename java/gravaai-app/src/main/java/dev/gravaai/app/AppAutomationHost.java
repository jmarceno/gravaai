package dev.gravaai.app;

import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.notification.Notifications;
import dev.gravaai.platform.portal.Portal;
import dev.gravaai.services.automations.AutomationHost;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;

/**
 * Desktop services for automation actions (plan §5.15): clipboard, reveal, open and notifications.
 *
 * <p>The clipboard uses AWT (java.desktop is in the runtime because JavaFX needs it); the rest goes
 * through the platform layer. Every method is safe to call from the automation service's virtual
 * threads.
 */
public final class AppAutomationHost implements AutomationHost {

	private static final System.Logger LOG = System.getLogger("gravaai.automations");

	private final Portal portal;
	private final FileManager fileManager;
	private final Notifications notifications;

	public AppAutomationHost(Portal portal, FileManager fileManager, Notifications notifications) {
		this.portal = portal;
		this.fileManager = fileManager;
		this.notifications = notifications;
	}

	@Override
	public void copyToClipboard(String text) {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(text == null ? "" : text), null);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "clipboard unavailable: " + error);
		}
	}

	@Override
	public void revealInFolder(String path) {
		if (fileManager == null) {
			LOG.log(System.Logger.Level.WARNING, "no FileManager1 to reveal " + path);
			return;
		}
		fileManager.reveal(Path.of(path));
	}

	@Override
	public void openPath(String path) throws Exception {
		if (portal == null) {
			throw new IllegalStateException("no xdg-desktop-portal to open " + path);
		}
		portal.openPath(Path.of(path));
	}

	@Override
	public boolean isNotificationSupported() {
		return notifications != null;
	}

	@Override
	public void notify(String title, String body) {
		if (notifications == null) {
			LOG.log(System.Logger.Level.INFO, "notification (no daemon): " + title + " - " + body);
			return;
		}
		notifications.notify(title, body);
	}
}
