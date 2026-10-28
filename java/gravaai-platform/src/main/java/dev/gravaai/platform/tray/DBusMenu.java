package dev.gravaai.platform.tray;

import java.util.List;
import java.util.Map;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSerializable;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * {@code com.canonical.dbusmenu}, the menu protocol a StatusNotifierHost uses (plan §5.5).
 *
 * <p>Implements the members the panel clients that matter (COSMIC, KDE, waybar) call:
 * {@code GetLayout}, {@code GetGroupProperties}, {@code GetProperty}, {@code Event},
 * {@code EventGroup}, {@code AboutToShow}, {@code AboutToShowGroup} and the
 * {@code LayoutUpdated} signal.
 */
@DBusInterfaceName("com.canonical.dbusmenu")
public interface DBusMenu extends DBusInterface {

	/** One menu node: {@code (ia{sv}av)}. */
	final class MenuNode extends Struct {
		@Position(0)
		public final int id;

		@Position(1)
		public final Map<String, Variant<?>> properties;

		@Position(2)
		public final List<Variant<?>> children;

		public MenuNode(int id, Map<String, Variant<?>> properties, List<Variant<?>> children) {
			this.id = id;
			this.properties = properties;
			this.children = children;
		}
	}

	/** One input entry for {@code EventGroup}: {@code (isvu)}. */
	final class MenuEvent extends Struct {
		@Position(0)
		public final int id;

		@Position(1)
		public final String eventId;

		@Position(2)
		public final Variant<?> data;

		@Position(3)
		public final UInt32 timestamp;

		public MenuEvent(int id, String eventId, Variant<?> data, UInt32 timestamp) {
			this.id = id;
			this.eventId = eventId;
			this.data = data;
			this.timestamp = timestamp;
		}
	}

	/** Node without children, for {@code a(ia{sv})}: {@code (ia{sv})}. */
	final class MenuGroup extends Struct {
		@Position(0)
		public final int id;

		@Position(1)
		public final Map<String, Variant<?>> properties;

		public MenuGroup(int id, Map<String, Variant<?>> properties) {
			this.id = id;
			this.properties = properties;
		}
	}

	/**
	 * The two out arguments of {@code GetLayout}, {@code u(ia{sv}av)}.
	 *
	 * <p>dbus-java 5.x declares hidden out arguments with {@link DBusSerializable}: the return
	 * signature is derived from the {@code deserialize(...)} parameter types and the body is built
	 * from {@code serialize()}.
	 */
	final class GetLayoutResult implements DBusSerializable {
		public final UInt32 revision;
		public final MenuNode node;

		public GetLayoutResult(UInt32 revision, MenuNode node) {
			this.revision = revision;
			this.node = node;
		}

		@Override
		public Object[] serialize() {
			return new Object[] {revision, node};
		}

		public static GetLayoutResult deserialize(UInt32 revision, MenuNode node) {
			return new GetLayoutResult(revision, node);
		}
	}

	/**
	 * The first argument is a signed int32 in the dbusmenu wire protocol (not uint32). Clients
	 * such as COSMIC and KDE call this with {@code i}; declaring {@code u} makes them fail to find
	 * the method and leaves the rendered context menu empty.
	 */
	GetLayoutResult GetLayout(int parentId, int recursionDepth, List<String> propertyNames);

	List<MenuGroup> GetGroupProperties(List<Integer> ids, List<String> propertyNames);

	Variant<?> GetProperty(int id, String name);

	void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

	List<Integer> EventGroup(List<MenuEvent> events);

	boolean AboutToShow(int id);

	/** The two {@code ai} out arguments of {@code AboutToShowGroup}. */
	final class AboutToShowGroupResult implements DBusSerializable {
		public final List<Integer> updatesNeeded;
		public final List<Integer> idErrors;

		public AboutToShowGroupResult(List<Integer> updatesNeeded, List<Integer> idErrors) {
			this.updatesNeeded = updatesNeeded;
			this.idErrors = idErrors;
		}

		@Override
		public Object[] serialize() {
			return new Object[] {updatesNeeded, idErrors};
		}

		public static AboutToShowGroupResult deserialize(List<Integer> updatesNeeded,
				List<Integer> idErrors) {
			return new AboutToShowGroupResult(updatesNeeded, idErrors);
		}
	}

	AboutToShowGroupResult AboutToShowGroup(List<Integer> ids);

	class LayoutUpdated extends DBusSignal {
		public LayoutUpdated(String objectPath, UInt32 revision, int parent) throws DBusException {
			super(objectPath, revision, parent);
		}
	}
}
