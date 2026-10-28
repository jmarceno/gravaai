package dev.gravaai.platform.notification;

import java.util.List;
import java.util.Map;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import dev.gravaai.platform.dbus.SessionBus;

/**
 * Freedesktop notifications over D-Bus (plan §5.5), the replacement for Electron's
 * {@code new Notification(...)}.
 *
 * <p>{@link #notify(String, String)} is fire-and-forget for the caller and may block on the bus;
 * call it from a service executor, not from the JavaFX thread.
 */
public final class Notifications {

	public static final String SERVICE = "org.freedesktop.Notifications";
	public static final String PATH = "/org/freedesktop/Notifications";
	public static final String APP_NAME = "Gravaai";
	/** Let the server pick the timeout, like Electron's default. */
	public static final int DEFAULT_EXPIRE_TIMEOUT = -1;

	/** The subset of {@code org.freedesktop.Notifications} the app uses. */
	@DBusInterfaceName("org.freedesktop.Notifications")
	public interface NotificationsInterface extends DBusInterface {
		UInt32 Notify(String appName, UInt32 replacesId, String appIcon, String summary, String body,
				List<String> actions, Map<String, Variant<?>> hints, int expireTimeout);

		void CloseNotification(UInt32 id);
	}

	private final SessionBus bus;
	private final NotificationsInterface notifications;

	public Notifications(SessionBus bus) throws DBusException {
		this.bus = bus;
		this.notifications = bus.connection().getRemoteObject(SERVICE, PATH, NotificationsInterface.class, false);
	}

	public static boolean isAvailable(SessionBus bus) {
		return bus.isServiceRunning(SERVICE);
	}

	/** Shows a notification and returns the server-assigned id (usable with {@link #close(long)}). */
	public long notify(String summary, String body) {
		UInt32 id = notifications.Notify(APP_NAME, new UInt32(0), "", summary, body,
				List.<String>of(), Map.<String, Variant<?>>of(), DEFAULT_EXPIRE_TIMEOUT);
		return id.longValue();
	}

	/** Asks the server to close a notification that is still on screen. */
	public void close(long notificationId) {
		notifications.CloseNotification(new UInt32(notificationId));
	}
}
