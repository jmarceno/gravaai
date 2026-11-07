package dev.gravaai.platform.tray;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.interfaces.Properties.PropertiesChanged;
import org.freedesktop.dbus.errors.PropertyReadOnly;
import org.freedesktop.dbus.errors.UnknownProperty;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import dev.gravaai.platform.dbus.SessionBus;

/**
 * StatusNotifierItem tray (plan §5.5): exports {@code /StatusNotifierItem} and a
 * {@code com.canonical.dbusmenu} menu at {@code /MenuBar}, then registers itself with
 * {@code org.kde.StatusNotifierWatcher}. COSMIC's panel watches that watcher.
 *
 * <p>The menu is {@link TrayMenu}: <i>Show Overlay</i>, <i>Open Gravaai</i>, <i>Stop Recording</i>
 * while recording, a separator and <i>Quit</i>. The recording state swaps the icon to the
 * rec-button variant and updates the tooltip ({@code Title}) like Electron's
 * {@code updateTrayMenu(recording)}.
 *
 * <p>D-Bus method calls arrive on the connection's receiving thread; the {@link TrayMenu.Actions}
 * must therefore be thread-safe (the app posts to the JavaFX thread).
 */
public final class Tray implements AutoCloseable {

	public static final String SNI_PATH = "/StatusNotifierItem";
	public static final String SNI_INTERFACE = "org.kde.StatusNotifierItem";
	public static final String MENU_PATH = "/MenuBar";
	public static final String MENU_INTERFACE = "com.canonical.dbusmenu";
	public static final String WATCHER_SERVICE = "org.kde.StatusNotifierWatcher";
	/** COSMIC runs the same watcher under this name as well; used as a fallback. */
	public static final String COSMIC_WATCHER_SERVICE = "com.system76.CosmicStatusNotifierWatcher";
	public static final String WATCHER_PATH = "/StatusNotifierWatcher";
	public static final String CATEGORY = "ApplicationStatus";
	public static final String ITEM_ID = "gravaai";
	public static final String APP_NAME = "Gravaai";
	public static final String STATUS_ACTIVE = "Active";

	private static final System.Logger LOG = System.getLogger("gravaai.platform.tray");

	/** The subset of {@code org.kde.StatusNotifierWatcher} the tray uses. */
	@DBusInterfaceName("org.kde.StatusNotifierWatcher")
	public interface StatusNotifierWatcher extends DBusInterface {
		void RegisterStatusNotifierItem(String service);
	}

	private record State(boolean recording, @Nullable String sourceName) {
		State {
			sourceName = sourceName == null ? "" : sourceName;
		}
	}

	private final SessionBus bus;
	private final TrayIcon icon;
	private final TrayIcon recordingIcon;
	private final TrayMenu.Actions actions;
	private final StatusNotifierItemImpl item;
	private final DBusMenuImpl menu;
	private final AtomicReference<State> state = new AtomicReference<>(new State(false, ""));
	private final AtomicInteger revision = new AtomicInteger(1);
	private volatile boolean installed;
	/** The watcher the item is currently registered with, for the reconnect handler. */
	private volatile String registeredWith = "";
	/** Unique name of the watcher process that last registered this item. */
	private volatile String registeredOwner = "";
	private @Nullable AutoCloseable watcherSubscription;

	public Tray(SessionBus bus, TrayIcon icon, TrayIcon recordingIcon, TrayMenu.Actions actions) {
		this.bus = bus;
		this.icon = icon;
		this.recordingIcon = recordingIcon;
		this.actions = actions;
		this.item = new StatusNotifierItemImpl();
		this.menu = new DBusMenuImpl();
	}

	/** Whether a StatusNotifierWatcher is on the session bus (KDE's name, or COSMIC's alias). */
	public static boolean isSupported(SessionBus bus) {
		return watcherService(bus) != null;
	}

	/** The watcher service name in use, or {@code null} when there is none. */
	static @Nullable String watcherService(SessionBus bus) {
		if (bus.isServiceRunning(WATCHER_SERVICE)) {
			return WATCHER_SERVICE;
		}
		if (bus.isServiceRunning(COSMIC_WATCHER_SERVICE)) {
			return COSMIC_WATCHER_SERVICE;
		}
		return null;
	}

	/** Exports the D-Bus objects and registers the item with the watcher. */
	public void install() throws DBusException {
		if (installed) {
			throw new IllegalStateException("the tray is already installed");
		}
		String service = watcherService(bus);
		if (service == null) {
			throw new DBusException("no StatusNotifierWatcher on the session bus ("
					+ WATCHER_SERVICE + " / " + COSMIC_WATCHER_SERVICE + ")");
		}
		DBusConnection connection = bus.connection();
		connection.exportObject(SNI_PATH, item);
		connection.exportObject(MENU_PATH, menu);
		register(connection, service);
		subscribeWatcherRestarts(connection);
		installed = true;
		LOG.log(System.Logger.Level.INFO, "tray installed as " + connection.getUniqueName() + SNI_PATH);
	}

	/** Tells {@code service} that this connection exports a StatusNotifierItem at {@link #SNI_PATH}. */
	private void register(DBusConnection connection, String service) throws DBusException {
		connection.getRemoteObject(service, WATCHER_PATH, StatusNotifierWatcher.class, false)
				.RegisterStatusNotifierItem(SNI_PATH);
		registeredWith = service;
	}

	/**
	 * Re-registers when the watcher becomes available again.
	 *
	 * <p>A tray item only reaches the panel through the watcher, and the watcher is restarted on
	 * its own (COSMIC's is a separate service). Its name changes owner and the previous
	 * registration is gone with the old process, so without this the icon stays missing until the
	 * application restarts. Both COSMIC's own name and KDE's alias are watched, because which one
	 * the item registered with depends on what was running at install time.
	 */
	private void subscribeWatcherRestarts(DBusConnection connection) throws DBusException {
		DBusSigHandler<DBus.NameOwnerChanged> handler = signal -> {
			if (!installed) {
				return;
			}
			if (!WATCHER_SERVICE.equals(signal.name) && !COSMIC_WATCHER_SERVICE.equals(signal.name)) {
				return;
			}
			String newOwner = signal.newOwner == null ? "" : signal.newOwner;
			if (newOwner.isEmpty()) {
				// The watcher went away. Forget the dedupe key: where the watcher lives in the
				// desktop process itself (GNOME's appindicator extension runs inside
				// gnome-shell), disabling and re-enabling it brings the name back under the
				// same unique name, and a surviving key would skip the re-registration.
				registeredOwner = "";
				return;
			}
			// COSMIC's watcher owns both names, so one restart reports two owner changes. The
			// unique name identifies the process: register once per new owner.
			if (newOwner.equals(registeredOwner)) {
				return;
			}
			String service = watcherService(bus);
			if (service == null) {
				return;
			}
			try {
				register(connection, service);
				registeredOwner = newOwner;
				LOG.log(System.Logger.Level.INFO, "tray re-registered with " + service);
			} catch (DBusException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "tray re-registration failed: " + error);
			}
		};
		try {
			watcherSubscription = connection.addSigHandler(DBus.NameOwnerChanged.class, handler);
		} catch (DBusException error) {
			throw new DBusException("could not watch the tray host for restarts: " + error, error);
		}
	}

	public boolean isInstalled() {
		return installed;
	}

	public boolean isRecording() {
		return state.get().recording();
	}

	public void setRecording(boolean recording) {
		setRecording(recording, "");
	}

	/**
	 * Switches the tray between idle and recording (plan §5.5, §2.1).
	 *
	 * @param sourceName the selected source shown in the {@code Recording: <name>} tooltip
	 */
	public void setRecording(boolean recording, String sourceName) {
		State previous = state.getAndSet(new State(recording, sourceName));
		if (!installed) {
			return;
		}
		if (previous.equals(state.get())) {
			return;
		}
		publishState();
	}

	/** Primary activation: stop a running recording first, otherwise open the main window (main.ts createTray). */
	private void activate() {
		if (state.get().recording()) {
			actions.stopRecording().run();
		} else {
			actions.openGravaai().run();
		}
	}

	private void publishState() {
		Map<String, Variant<?>> changed = new LinkedHashMap<>();
		changed.put("Title", new Variant<>(title()));
		changed.put("IconPixmap", new Variant<>(List.of(currentIcon().toPixmap()), "a(iiay)"));
		try {
			send(new StatusNotifierItem.NewTitle(SNI_PATH));
			send(new StatusNotifierItem.NewIcon(SNI_PATH));
			send(new PropertiesChanged(SNI_PATH, SNI_INTERFACE, changed, List.of()));
		} catch (DBusException error) {
			LOG.log(System.Logger.Level.WARNING, "could not build the tray state signals: " + error);
		}
		emitLayoutUpdated(TrayMenu.ROOT_ID);
	}

	private void emitLayoutUpdated(int parentId) {
		try {
			send(new DBusMenu.LayoutUpdated(MENU_PATH, new UInt32(revision.incrementAndGet()), parentId));
		} catch (DBusException error) {
			LOG.log(System.Logger.Level.WARNING, "could not emit LayoutUpdated: " + error);
		}
	}

	private void send(DBusSignal signal) {
		bus.connection().sendMessage(signal);
	}

	private String title() {
		State current = state.get();
		return current.recording() ? "Recording: " + current.sourceName() : APP_NAME;
	}

	private TrayIcon currentIcon() {
		return state.get().recording() ? recordingIcon : icon;
	}

	private TrayMenu menuModel() {
		return new TrayMenu(state.get().recording());
	}

	private boolean dispatchEvent(int id, String eventId) {
		if ("clicked".equals(eventId)) {
			return menuModel().dispatch(id, actions);
		}
		return false;
	}

	private DBusMenu.MenuNode layoutNode(int id, int recursionDepth) {
		TrayMenu model = menuModel();
		if (id == TrayMenu.ROOT_ID) {
			List<Variant<?>> children = new ArrayList<>();
			if (recursionDepth != 0) {
				for (TrayMenu.Item item : model.items()) {
					children.add(new Variant<>(layoutNode(item.id(), recursionDepth - 1)));
				}
			}
			return new DBusMenu.MenuNode(TrayMenu.ROOT_ID, rootProperties(), children);
		}
		TrayMenu.Item item = model.item(id);
		if (item == null) {
			return new DBusMenu.MenuNode(id, Map.of(), List.of());
		}
		return new DBusMenu.MenuNode(item.id(), itemProperties(item), List.of());
	}

	private static Map<String, Variant<?>> rootProperties() {
		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		properties.put("children-display", new Variant<>("submenu"));
		return properties;
	}

	private static Map<String, Variant<?>> itemProperties(TrayMenu.Item item) {
		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		properties.put("label", new Variant<>(item.label()));
		properties.put("enabled", new Variant<>(Boolean.TRUE));
		properties.put("visible", new Variant<>(Boolean.TRUE));
		properties.put("type", new Variant<>(item.type()));
		return properties;
	}

	private List<DBusMenu.MenuGroup> groups(List<Integer> ids) {
		TrayMenu model = menuModel();
		List<Integer> requested = ids == null || ids.isEmpty() ? allIds(model) : ids;
		List<DBusMenu.MenuGroup> groups = new ArrayList<>();
		for (Integer id : requested) {
			if (id == null) {
				continue;
			}
			if (id == TrayMenu.ROOT_ID) {
				groups.add(new DBusMenu.MenuGroup(TrayMenu.ROOT_ID, rootProperties()));
				continue;
			}
			TrayMenu.Item item = model.item(id);
			if (item != null) {
				groups.add(new DBusMenu.MenuGroup(item.id(), itemProperties(item)));
			}
		}
		return groups;
	}

	private static List<Integer> allIds(TrayMenu model) {
		List<Integer> ids = new ArrayList<>();
		ids.add(TrayMenu.ROOT_ID);
		for (TrayMenu.Item item : model.items()) {
			ids.add(item.id());
		}
		return ids;
	}

	@Override
	public void close() {
		if (!installed) {
			return;
		}
		// Before the flag: the handler checks it, and a watcher that appears while
		// the objects are being unexported must not re-register them.
		installed = false;
		AutoCloseable subscription = watcherSubscription;
		watcherSubscription = null;
		if (subscription != null) {
			try {
				subscription.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.DEBUG, "could not remove the watcher handler: " + error);
			}
		}
		// The protocol has no unregister call: the watcher drops an item when the unique name that
		// registered it leaves the bus. Unexporting the objects first would only leave the panel
		// pointing at paths that no longer answer, so the entry stays until the process exits
		// (or the watcher restarts), and the unexport below is what makes it inert.
		try {
			bus.connection().unExportObject(MENU_PATH);
			bus.connection().unExportObject(SNI_PATH);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not unexport the tray objects: " + error);
		}
	}

	private final class StatusNotifierItemImpl implements StatusNotifierItem, Properties {

		@Override
		public String getObjectPath() {
			return SNI_PATH;
		}

		@Override
		public void Activate(int x, int y) {
			activate();
		}

		@Override
		public void SecondaryActivate(int x, int y) {
			actions.openGravaai().run();
		}

		@Override
		public void ContextMenu(int x, int y) {
			emitLayoutUpdated(TrayMenu.ROOT_ID);
		}

		/** The StatusNotifierItem properties; see {@link StatusNotifierItem} for why Properties is explicit. */
		private Map<String, Variant<?>> properties() {
			Map<String, Variant<?>> values = new LinkedHashMap<>();
			values.put("Category", new Variant<>(CATEGORY));
			values.put("Id", new Variant<>(ITEM_ID));
			values.put("Title", new Variant<>(title()));
			values.put("Status", new Variant<>(STATUS_ACTIVE));
			values.put("IconPixmap", new Variant<>(List.of(currentIcon().toPixmap()), "a(iiay)"));
			values.put("Menu", new Variant<>(new DBusPath(MENU_PATH)));
			values.put("ItemIsMenu", new Variant<>(Boolean.FALSE));
			return values;
		}

		// dbus-java declares a generic <A> A Get(...); the bus only ever sees a Variant.
		@SuppressWarnings("unchecked")
		@Override
		public Variant<?> Get(String interfaceName, String propertyName) {
			if (!SNI_INTERFACE.equals(interfaceName)) {
				throw new UnknownProperty("no such interface: " + interfaceName);
			}
			Variant<?> value = properties().get(propertyName);
			if (value == null) {
				throw new UnknownProperty("no such property: " + propertyName);
			}
			return value;
		}

		@Override
		public Map<String, Variant<?>> GetAll(String interfaceName) {
			if (!SNI_INTERFACE.equals(interfaceName)) {
				return Map.of();
			}
			return properties();
		}

		@Override
		public <A> void Set(String interfaceName, String propertyName, A value) {
			throw new PropertyReadOnly("property is read-only: " + propertyName);
		}
	}

	private final class DBusMenuImpl implements DBusMenu {

		@Override
		public String getObjectPath() {
			return MENU_PATH;
		}

		@Override
		public GetLayoutResult GetLayout(int parentId, int recursionDepth, List<String> propertyNames) {
			return new GetLayoutResult(new UInt32(revision.get()),
					layoutNode(parentId, recursionDepth));
		}

		@Override
		public List<MenuGroup> GetGroupProperties(List<Integer> ids, List<String> propertyNames) {
			return groups(ids);
		}

		@Override
		public Variant<?> GetProperty(int id, String name) {
			Map<String, Variant<?>> properties;
			if (id == TrayMenu.ROOT_ID) {
				properties = rootProperties();
			} else {
				TrayMenu.Item item = menuModel().item(id);
				if (item == null) {
					throw new UnknownProperty("no such menu item: " + id);
				}
				properties = itemProperties(item);
			}
			Variant<?> value = properties.get(name);
			if (value == null) {
				throw new UnknownProperty("no such menu property: " + name);
			}
			return value;
		}

		@Override
		public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
			dispatchEvent(id, eventId);
		}

		@Override
		public List<Integer> EventGroup(List<MenuEvent> events) {
			List<Integer> errors = new ArrayList<>();
			if (events == null) {
				return errors;
			}
			for (MenuEvent event : events) {
				if (event == null || !dispatchEvent(event.id, event.eventId)) {
					errors.add(event == null ? -1 : event.id);
				}
			}
			return errors;
		}

		@Override
		public boolean AboutToShow(int id) {
			emitLayoutUpdated(id);
			return true;
		}

		@Override
		public AboutToShowGroupResult AboutToShowGroup(List<Integer> ids) {
			List<Integer> updates = new ArrayList<>();
			List<Integer> errors = new ArrayList<>();
			if (ids != null) {
				TrayMenu model = menuModel();
				for (Integer id : ids) {
					if (id == null) {
						continue;
					}
					if (id == TrayMenu.ROOT_ID || model.item(id) != null) {
						updates.add(id);
					} else {
						errors.add(id);
					}
				}
			}
			for (Integer id : updates) {
				emitLayoutUpdated(id);
			}
			return new AboutToShowGroupResult(List.copyOf(updates), List.copyOf(errors));
		}
	}
}
