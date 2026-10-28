package dev.gravaai.platform.tray;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * {@code org.kde.StatusNotifierItem} (plan §5.5).
 *
 * <p>Only the methods and signals are declared here. The properties ({@code Category},
 * {@code Id}, {@code Title}, {@code Status}, {@code IconPixmap}, {@code Menu},
 * {@code ItemIsMenu}) are served through {@code org.freedesktop.DBus.Properties} on the exported
 * object: the specification requires {@code Properties.Get} to return a variant, which the
 * annotation-less explicit implementation in {@link Tray} guarantees (COSMIC's panel reads
 * properties with a strict zbus proxy and falls back to a generic icon otherwise).
 */
@DBusInterfaceName("org.kde.StatusNotifierItem")
public interface StatusNotifierItem extends DBusInterface {

	/** Primary activation (the host's left click). */
	void Activate(int x, int y);

	/** Secondary activation (middle click / double click). */
	void SecondaryActivate(int x, int y);

	/** The host is about to show the context menu. */
	void ContextMenu(int x, int y);

	/** The tooltip text changed. */
	class NewTitle extends DBusSignal {
		public NewTitle(String objectPath) throws DBusException {
			super(objectPath);
		}
	}

	/** The icon changed. */
	class NewIcon extends DBusSignal {
		public NewIcon(String objectPath) throws DBusException {
			super(objectPath);
		}
	}

	/** The status changed ({@code Active}, {@code Passive}, {@code NeedsAttention}). */
	class NewStatus extends DBusSignal {
		public NewStatus(String objectPath, String status) throws DBusException {
			super(objectPath, status);
		}
	}
}
