package dev.gravaai.platform.portal;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt64;
import org.freedesktop.dbus.types.Variant;
import org.jspecify.annotations.Nullable;

import dev.gravaai.platform.dbus.SessionBus;

/**
 * The {@code org.freedesktop.portal.GlobalShortcuts} portal: system-wide shortcuts on desktops that
 * do not let apps edit their keyboard settings directly (KDE Plasma 6, GNOME 48+, and others).
 *
 * <p>The desktop owns the shortcuts. The app proposes an id, a description and a preferred trigger
 * for each; the desktop asks the user to confirm them once (KDE shows a "Global Shortcuts
 * Requested" dialog), remembers the answer, and lets the user change the keys in its own settings
 * later. Each press arrives as an {@code Activated} signal carrying the shortcut id.
 *
 * <p>Unsandboxed apps must tell the portal who they are before their first call, through
 * {@code org.freedesktop.host.portal.Registry.Register}; the id is the basename of the app's
 * {@code .desktop} file, which is also where the desktop takes the name it shows the user. That
 * registration binds the whole D-Bus connection, so this class opens its own instead of sharing
 * the app's.
 */
public final class GlobalShortcutsPortal implements AutoCloseable {

	public static final String INTERFACE = "org.freedesktop.portal.GlobalShortcuts";
	private static final String SESSION_INTERFACE = "org.freedesktop.portal.Session";
	private static final String REGISTRY_INTERFACE = "org.freedesktop.host.portal.Registry";
	private static final System.Logger LOG = System.getLogger("gravaai.platform.portal");
	/** The first bind waits for the user to answer the desktop's confirmation dialog. */
	private static final Duration BIND_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

	/** One shortcut to register: {@code preferredTrigger} uses the XDG shortcuts format, e.g. {@code CTRL+ALT+r}. */
	public record Shortcut(String id, String description, String preferredTrigger) {
		public Shortcut {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(description, "description");
			Objects.requireNonNull(preferredTrigger, "preferredTrigger");
		}
	}

	/** A shortcut as the desktop registered it; {@code trigger} is its human-readable key, e.g. {@code Ctrl+Alt+R}. */
	public record BoundShortcut(String id, String trigger) {
	}

	@DBusInterfaceName(INTERFACE)
	public interface GlobalShortcutsInterface extends DBusInterface {
		DBusPath CreateSession(Map<String, Variant<?>> options);

		DBusPath BindShortcuts(DBusPath sessionHandle, List<ShortcutStruct> shortcuts, String parentWindow,
				Map<String, Variant<?>> options);

		class Activated extends DBusSignal {
			private final DBusPath sessionHandle;
			private final String shortcutId;

			public Activated(String objectPath, DBusPath sessionHandle, String shortcutId, UInt64 timestamp,
					Map<String, Variant<?>> options) throws DBusException {
				super(objectPath, sessionHandle, shortcutId, timestamp, options);
				this.sessionHandle = sessionHandle;
				this.shortcutId = shortcutId;
			}

			public DBusPath getSessionHandle() {
				return sessionHandle;
			}

			public String getShortcutId() {
				return shortcutId;
			}
		}
	}

	@DBusInterfaceName(SESSION_INTERFACE)
	public interface SessionInterface extends DBusInterface {
		void Close();
	}

	@DBusInterfaceName(REGISTRY_INTERFACE)
	public interface RegistryInterface extends DBusInterface {
		void Register(String appId, Map<String, Variant<?>> options);
	}

	/** {@code (sa{sv})}: one shortcut of {@code BindShortcuts}. */
	public static final class ShortcutStruct extends Struct {
		@Position(0)
		public final String id;

		@Position(1)
		public final Map<String, Variant<?>> properties;

		public ShortcutStruct(String id, Map<String, Variant<?>> properties) {
			this.id = id;
			this.properties = properties;
		}
	}

	private final SessionBus bus;
	private final GlobalShortcutsInterface portal;
	private final String senderToken;
	private final AutoCloseable activatedSubscription;
	private final AtomicInteger requestCounter = new AtomicInteger();
	private final Object lock = new Object();
	private @Nullable DBusPath session;

	private GlobalShortcutsPortal(SessionBus bus, Consumer<String> onActivated) throws DBusException {
		this.bus = bus;
		this.portal = bus.connection().getRemoteObject(Portal.SERVICE, Portal.PATH, GlobalShortcutsInterface.class,
				false);
		this.senderToken = bus.uniqueName().substring(1).replace('.', '_');
		DBusSigHandler<GlobalShortcutsInterface.Activated> handler = signal -> {
			DBusPath current;
			synchronized (lock) {
				current = session;
			}
			// Presses on a session this app already closed (a rebind) are stale.
			if (current != null && current.getPath().equals(signal.getSessionHandle().getPath())) {
				onActivated.accept(signal.getShortcutId());
			}
		};
		this.activatedSubscription = bus.connection().addSigHandler(DBusMatchRuleBuilder.create()
				.withType(GlobalShortcutsInterface.Activated.class)
				.build(), handler);
	}

	/**
	 * Opens the portal for {@code appId}, or empty when this desktop has no GlobalShortcuts portal
	 * (GNOME before 48, a session without xdg-desktop-portal).
	 *
	 * @param appId the basename of the app's {@code .desktop} file
	 * @param onActivated receives the id of each shortcut the user presses, on a D-Bus thread
	 */
	public static Optional<GlobalShortcutsPortal> open(String appId, Consumer<String> onActivated) {
		SessionBus bus;
		try {
			bus = SessionBus.connect();
		} catch (DBusException | RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "no session bus for global shortcuts: " + error);
			return Optional.empty();
		}
		try {
			if (!Portal.isAvailable(bus) || interfaceVersion(bus) < 1) {
				bus.close();
				return Optional.empty();
			}
			register(bus, appId);
			return Optional.of(new GlobalShortcutsPortal(bus, onActivated));
		} catch (DBusException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "the global shortcuts portal is unusable: " + error);
			bus.close();
			return Optional.empty();
		}
	}

	private static int interfaceVersion(SessionBus bus) {
		try {
			Properties properties = bus.connection().getRemoteObject(Portal.SERVICE, Portal.PATH, Properties.class,
					false);
			Object version = properties.Get(INTERFACE, "version");
			return version instanceof Number number ? number.intValue() : 0;
		} catch (DBusException | DBusExecutionException error) {
			return 0;
		}
	}

	/**
	 * Tells the portal which app this connection belongs to. Older portals (before 1.19) have no
	 * registry and derive the id themselves, so a missing method is not an error.
	 */
	private static void register(SessionBus bus, String appId) throws DBusException {
		RegistryInterface registry = bus.connection().getRemoteObject(Portal.SERVICE, Portal.PATH,
				RegistryInterface.class, false);
		try {
			registry.Register(appId, Map.of());
		} catch (DBusExecutionException error) {
			LOG.log(System.Logger.Level.INFO, "portal registry unavailable, continuing without it: " + error);
		}
	}

	/**
	 * Replaces the registered shortcuts with {@code shortcuts}. Blocks until the desktop answers,
	 * which the first time means until the user confirms its dialog.
	 *
	 * @return what the desktop actually bound, which may differ from the preferred triggers
	 */
	public List<BoundShortcut> bind(List<Shortcut> shortcuts) throws IOException {
		synchronized (lock) {
			closeSession();
			DBusPath created = createSession();
			session = created;
			List<ShortcutStruct> structs = new ArrayList<>();
			for (Shortcut shortcut : shortcuts) {
				Map<String, Variant<?>> properties = new LinkedHashMap<>();
				properties.put("description", new Variant<>(shortcut.description()));
				properties.put("preferred_trigger", new Variant<>(shortcut.preferredTrigger()));
				structs.add(new ShortcutStruct(shortcut.id(), properties));
			}
			String token = nextToken();
			Map<String, Variant<?>> options = Map.of("handle_token", new Variant<>(token));
			Map<String, Variant<?>> results = request(token, BIND_TIMEOUT,
					() -> portal.BindShortcuts(created, structs, "", options));
			return boundShortcuts(results.get("shortcuts"));
		}
	}

	/** Unregisters every shortcut; they stay listed in the desktop's settings but stop firing here. */
	public void unbind() {
		synchronized (lock) {
			closeSession();
		}
	}

	private DBusPath createSession() throws IOException {
		String token = nextToken();
		Map<String, Variant<?>> options = new LinkedHashMap<>();
		options.put("handle_token", new Variant<>(token));
		options.put("session_handle_token", new Variant<>(nextToken()));
		Map<String, Variant<?>> results = request(token, SESSION_TIMEOUT, () -> portal.CreateSession(options));
		Variant<?> handle = results.get("session_handle");
		Object value = handle == null ? null : handle.getValue();
		if (value instanceof DBusPath path) {
			return path;
		}
		if (value instanceof String path) {
			return new DBusPath(path);
		}
		throw new IOException("the global shortcuts portal returned no session");
	}

	private void closeSession() {
		DBusPath current = session;
		session = null;
		if (current == null) {
			return;
		}
		try {
			bus.connection().getRemoteObject(Portal.SERVICE, current.getPath(), SessionInterface.class, false).Close();
		} catch (DBusException | DBusExecutionException error) {
			LOG.log(System.Logger.Level.DEBUG, "closing the shortcuts session failed: " + error);
		}
	}

	/** {@code a(sa{sv})} from the bind results; the struct arrives as a Struct or a plain array. */
	static List<BoundShortcut> boundShortcuts(@Nullable Variant<?> shortcuts) {
		List<BoundShortcut> bound = new ArrayList<>();
		if (shortcuts == null || !(shortcuts.getValue() instanceof List<?> list)) {
			return bound;
		}
		for (Object entry : list) {
			Object[] fields = entry instanceof Struct struct ? struct.getParameters()
					: entry instanceof Object[] array ? array : new Object[0];
			if (fields.length < 2 || !(fields[0] instanceof String id) || !(fields[1] instanceof Map<?, ?> properties)) {
				continue;
			}
			Object trigger = properties.get("trigger_description");
			if (trigger instanceof Variant<?> variant) {
				trigger = variant.getValue();
			}
			bound.add(new BoundShortcut(id, trigger instanceof String text ? text : ""));
		}
		return bound;
	}

	private Map<String, Variant<?>> request(String token, Duration timeout, Supplier<DBusPath> call)
			throws IOException {
		CompletableFuture<Portal.RequestInterface.Response> response = new CompletableFuture<>();
		String predicted = Portal.predictedRequestPath(senderToken, token);
		AutoCloseable subscription = subscribe(predicted, response);
		AutoCloseable actual = null;
		try {
			DBusPath handle = call.get();
			if (handle != null && !handle.getPath().equals(predicted) && !response.isDone()) {
				actual = subscribe(handle.getPath(), response);
			}
			Portal.RequestInterface.Response answer = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (answer.getResponse().intValue() != 0) {
				throw new IOException("the desktop declined the global shortcuts request ("
						+ answer.getResponse().intValue() + ")");
			}
			return answer.getResults() == null ? Map.of() : answer.getResults();
		} catch (DBusExecutionException error) {
			throw new IOException("global shortcuts portal call failed: " + error.getMessage(), error);
		} catch (TimeoutException error) {
			throw new IOException("the global shortcuts portal did not answer within " + timeout, error);
		} catch (ExecutionException error) {
			throw new IOException("global shortcuts portal request failed: " + error.getCause(), error.getCause());
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for the global shortcuts portal", error);
		} finally {
			closeQuietly(actual);
			closeQuietly(subscription);
		}
	}

	private AutoCloseable subscribe(String path, CompletableFuture<Portal.RequestInterface.Response> response)
			throws IOException {
		try {
			DBusSigHandler<Portal.RequestInterface.Response> handler = response::complete;
			return bus.connection().addSigHandler(DBusMatchRuleBuilder.create()
					.withType(Portal.RequestInterface.Response.class)
					.withPath(path)
					.build(), handler);
		} catch (DBusException error) {
			throw new IOException("could not subscribe to " + path + ": " + error, error);
		}
	}

	private String nextToken() {
		return "gravaai_gs_" + requestCounter.incrementAndGet() + "_" + System.nanoTime();
	}

	private static void closeQuietly(@Nullable AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.DEBUG, "could not remove a portal signal handler: " + error);
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			closeSession();
		}
		closeQuietly(activatedSubscription);
		bus.close();
	}
}
