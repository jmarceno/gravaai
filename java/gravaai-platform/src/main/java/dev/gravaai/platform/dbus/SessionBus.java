package dev.gravaai.platform.dbus;

import java.nio.file.Path;
import java.util.Optional;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;

/**
 * Session bus connection factory (plan §5.5): {@code dbus-java-core} on the native unix-socket
 * transport, no JNA and no JNI.
 *
 * <p>The address comes from {@code DBUS_SESSION_BUS_ADDRESS}; when a login session does not export
 * it, the standard {@code $XDG_RUNTIME_DIR/bus} socket is used (normally
 * {@code unix:path=/run/user/<uid>/bus}).
 */
public final class SessionBus implements AutoCloseable {

	public static final String DBUS_SERVICE = "org.freedesktop.DBus";
	public static final String DBUS_PATH = "/org/freedesktop/DBus";

	private static final System.Logger LOG = System.getLogger("gravaai.platform.dbus");

	private final DBusConnection connection;
	private final String address;
	private final DBus dbus;

	private SessionBus(DBusConnection connection, String address) throws DBusException {
		this.connection = connection;
		this.address = address;
		this.dbus = connection.getRemoteObject(DBUS_SERVICE, DBUS_PATH, DBus.class, false);
	}

	/** The address used by the session bus in this login session. */
	public static String sessionAddress() {
		String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
		if (address != null && !address.isBlank()) {
			return address;
		}
		String runtimeDirectory = System.getenv("XDG_RUNTIME_DIR");
		if (runtimeDirectory != null && !runtimeDirectory.isBlank()) {
			return "unix:path=" + Path.of(runtimeDirectory, "bus");
		}
		throw new IllegalStateException(
				"DBUS_SESSION_BUS_ADDRESS is not set and XDG_RUNTIME_DIR is unavailable");
	}

	public static SessionBus connect() throws DBusException {
		return connect(sessionAddress());
	}

	public static SessionBus connect(String address) throws DBusException {
		DBusConnection connection = DBusConnectionBuilder.forAddress(address).build();
		try {
			return new SessionBus(connection, address);
		} catch (DBusException | RuntimeException error) {
			connection.disconnect();
			throw error;
		}
	}

	/** Connects when a session bus is reachable. */
	public static Optional<SessionBus> tryConnect() {
		try {
			return Optional.of(connect());
		} catch (DBusException | RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "no session bus: " + error);
			return Optional.empty();
		}
	}

	public DBusConnection connection() {
		return connection;
	}

	/** The address this connection was opened with. */
	public String address() {
		return address;
	}

	/** This connection's unique name (for example {@code :1.42}). */
	public String uniqueName() {
		return connection.getUniqueName();
	}

	/** Whether a well-known service name is currently owned on the bus. */
	public boolean isServiceRunning(String wellKnownName) {
		try {
			return dbus.NameHasOwner(wellKnownName);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "NameHasOwner(" + wellKnownName + ") failed: " + error);
			return false;
		}
	}

	@Override
	public void close() {
		connection.disconnect();
	}
}
