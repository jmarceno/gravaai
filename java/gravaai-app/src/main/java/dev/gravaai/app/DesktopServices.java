package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;

import javafx.application.Platform;

import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.notification.Notifications;
import dev.gravaai.platform.portal.Portal;
import dev.gravaai.platform.tray.Tray;
import dev.gravaai.platform.tray.TrayIcon;
import dev.gravaai.platform.tray.TrayMenu;
import dev.gravaai.services.automations.AutomationService;

/**
 * The desktop integration AppContext owns: one D-Bus session connection and the services that ride
 * on it, plus the tray.
 *
 * <p>All of this was inline in {@code AppContext}, mixed with window, editor and recording
 * lifecycle code. It is a separate object now because it has a single concern — "talk to the
 * desktop, and degrade quietly when a service is absent" — and because every piece of it is
 * optional on a session without D-Bus.
 *
 * <p>Nothing here assumes a particular desktop environment. The portal and the file manager are
 * freedesktop services; the tray probes for whichever StatusNotifierWatcher the desktop runs. Which
 * desktop this is belongs to {@link dev.gravaai.platform.compositor.DesktopSession}, and the
 * services that care receive it.
 *
 * <p>Every failure is logged and leaves the corresponding service null: a desktop without a portal
 * must still start, record and edit.
 */
final class DesktopServices implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai");

	private final OperationSink operations;

	private @Nullable SessionBus bus;
	private @Nullable Portal portal;
	private @Nullable FileManager fileManager;
	private @Nullable Notifications notifications;
	private @Nullable AutomationService automationService;
	private @Nullable Tray tray;
	private @Nullable AutoCloseable trayRecordingSubscription;

	/**
	 * @param operations the app's tray actions (show HUD, open dashboard, stop recording, quit)
	 */
	DesktopServices(OperationSink operations) {
		this.operations = operations;
	}

	/** The tray calls these; AppContext supplies them, and each must marshal to the FX thread. */
	interface OperationSink {
		void showHud();
		void openDashboard();
		void stopRecording();
		void quit();
	}

	/** Connects the session bus and creates the services that the bus makes possible. */
	void connect() {
		// One D-Bus connection serves the tray, the portal-backed file dialogs and the file manager.
		Optional<SessionBus> connection = SessionBus.tryConnect();
		bus = connection.orElse(null);
		if (bus == null) {
			LOG.log(System.Logger.Level.INFO, "no D-Bus session; portal, file manager, tray and notifications disabled");
			return;
		}
		try {
			portal = new Portal(bus);
		} catch (Exception error) {
			LOG.log(System.Logger.Level.INFO, "xdg-desktop-portal unavailable: " + error);
		}
		try {
			fileManager = new FileManager(bus);
		} catch (Exception error) {
			LOG.log(System.Logger.Level.INFO, "FileManager1 unavailable: " + error);
		}
		try {
			notifications = new Notifications(bus);
		} catch (Exception error) {
			LOG.log(System.Logger.Level.INFO, "notifications unavailable: " + error);
		}
	}

	/** Creates the automation service; separate from {@link #connect} because it has no bus dependency. */
	void startAutomations(UserData userData) {
		try {
			automationService = new AutomationService(
					userData.automationsFile(),
					new AppAutomationHost(portal, fileManager, notifications),
					path -> {
						double seconds = MediaProbe.probe(path).durationSeconds();
						return Double.isFinite(seconds) && seconds > 0 ? seconds : null;
					},
					Path.of(System.getProperty("user.home")));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "automations unavailable: " + error);
		}
	}

	@Nullable SessionBus bus() {
		return bus;
	}

	@Nullable Portal portal() {
		return portal;
	}

	@Nullable FileManager fileManager() {
		return fileManager;
	}

	@Nullable Notifications notifications() {
		return notifications;
	}

	@Nullable AutomationService automations() {
		return automationService;
	}

	@Nullable Tray tray() {
		return tray;
	}

	/**
	 * Puts the tray icon on the panel, if the session has a StatusNotifierWatcher.
	 *
	 * @param iconPath the 32px icon the tray shows
	 * @param recordingIconPath the recording-state icon, or null to reuse {@code iconPath}
	 * @param recordingActive whether a recording is already running (the tray is then a stop button)
	 * @param sourceName what is being recorded, for the tray tooltip
	 */
	void installTray(Path iconPath, @Nullable Path recordingIconPath, boolean recordingActive,
			String sourceName) {
		if (bus == null) {
			LOG.log(System.Logger.Level.INFO, "no D-Bus session; tray disabled");
			return;
		}
		// Building a second Tray would overwrite the field with one whose exportObject() throws
		// (the paths are taken) and whose install() never runs, leaving a dead tray that swallows
		// every state update. The startup branches are exclusive today; this keeps them so.
		if (tray != null && tray.isInstalled()) {
			return;
		}
		if (!Tray.isSupported(bus)) {
			LOG.log(System.Logger.Level.INFO, "no StatusNotifierWatcher on the session bus; tray disabled");
			return;
		}
		try {
			TrayIcon icon = TrayIcon.load(iconPath);
			TrayIcon recordingIcon = recordingIconPath != null ? TrayIcon.load(recordingIconPath) : icon;
			TrayMenu.Actions actions = new TrayMenu.Actions(
					() -> Platform.runLater(operations::showHud),
					() -> Platform.runLater(operations::openDashboard),
					() -> Platform.runLater(operations::stopRecording),
					() -> Platform.runLater(operations::quit));
			Tray created = new Tray(bus, icon, recordingIcon, actions);
			created.install();
			// Assigned only after install(): a tray that failed to install must not replace a
			// working one (or leave a non-null field that the guard above would then skip).
			tray = created;
			applyTrayRecordingState(recordingActive, sourceName);
			LOG.log(System.Logger.Level.INFO, "tray installed");
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "tray unavailable: " + error);
		}
	}

	/** Keeps the tray icon/tooltip/menu in sync with recording events. */
	void subscribeTrayRecordingState(AutoCloseable subscription) {
		closeTrayRecordingSubscription();
		trayRecordingSubscription = subscription;
	}

	void applyTrayRecordingState(boolean recording, String sourceName) {
		Tray current = tray;
		if (current != null) {
			current.setRecording(recording, sourceName);
		}
	}

	/**
	 * JVM shutdown-hook path (SIGTERM/SIGINT): the FX toolkit never runs the rest of the app's
	 * shutdown then, so only the transport pieces are closed here. The tray item is unexported and
	 * the session bus disconnected while the process still owns its unique name, which is the
	 * removal a StatusNotifierHost can observe; every field is nulled as it is closed, so a normal
	 * quit that already ran {@link #close()} leaves nothing for this method to do.
	 */
	synchronized void closeForSignal() {
		Tray currentTray = tray;
		SessionBus currentBus = bus;
		if (currentTray == null && currentBus == null) {
			// The regular shutdown already closed everything (normal quit, or no bus at all).
			return;
		}
		LOG.log(System.Logger.Level.INFO, "shutdown hook: closing the tray and the session bus");
		closeTrayRecordingSubscription();
		tray = null;
		if (currentTray != null) {
			currentTray.close();
		}
		bus = null;
		if (currentBus != null) {
			currentBus.close();
		}
	}

	@Override
	public synchronized void close() {
		closeTrayRecordingSubscription();
		Tray currentTray = tray;
		tray = null;
		if (currentTray != null) {
			currentTray.close();
		}
		if (automationService != null) {
			automationService.close();
			automationService = null;
		}
		if (portal != null) {
			portal.close();
			portal = null;
		}
		if (bus != null) {
			bus.close();
			bus = null;
		}
	}

	private void closeTrayRecordingSubscription() {
		AutoCloseable subscription = trayRecordingSubscription;
		trayRecordingSubscription = null;
		if (subscription == null) {
			return;
		}
		try {
			subscription.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING,
					"could not close the tray recording subscription: " + error);
		}
	}
}
