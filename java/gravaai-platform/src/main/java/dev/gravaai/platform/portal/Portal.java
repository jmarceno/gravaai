package dev.gravaai.platform.portal;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.function.Supplier;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * XDG desktop portal client (plan §5.5): the FileChooser for open/save dialogs and the OpenURI
 * portal for opening URLs and files.
 *
 * <p>Every portal call creates a request object and waits for its {@code Response} signal
 * (response 0 = success, 1 = cancelled). Requests are newline-free blocking calls; use a service
 * executor. When the portal is missing the file dialogs throw {@link PortalUnavailableException}
 * so the UI can fall back to JavaFX dialogues, while {@link #openUri(URI)} and
 * {@link #openPath(Path)} fall back to {@code xdg-open} on their own.
 */
public final class Portal implements AutoCloseable {

	public static final String SERVICE = "org.freedesktop.portal.Desktop";
	public static final String PATH = "/org/freedesktop/portal/desktop";
	public static final String REQUEST_PATH_PREFIX = "/org/freedesktop/portal/desktop/request/";
	public static final String FILE_CHOOSER_INTERFACE = "org.freedesktop.portal.FileChooser";
	public static final String OPEN_URI_INTERFACE = "org.freedesktop.portal.OpenURI";
	public static final String REQUEST_INTERFACE = "org.freedesktop.portal.Request";

	private static final System.Logger LOG = System.getLogger("gravaai.platform.portal");
	private static final String PARENT_WINDOW = "";
	private static final Duration FILE_DIALOG_TIMEOUT = Duration.ofMinutes(10);
	private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration XDG_OPEN_TIMEOUT = Duration.ofSeconds(10);

	/** One file dialog filter: a label and shell-style globs (for example {@code *.mp4}). */
	public record FileFilter(String label, List<String> globs) {
		public FileFilter {
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(globs, "globs");
			globs = List.copyOf(globs);
		}
	}

	/** A file chooser request, mirroring the Electron {@code dialog.showOpenDialog/SaveDialog} options. */
	public record FileDialogRequest(String title, boolean multiple, boolean directory, List<FileFilter> filters,
			@Nullable Path currentFolder, @Nullable String currentName) {

		public FileDialogRequest {
			Objects.requireNonNull(title, "title");
			filters = filters == null ? List.of() : List.copyOf(filters);
		}

		/** A single-file open dialog with no filters. */
		public static FileDialogRequest open(String title) {
			return new FileDialogRequest(title, false, false, List.of(), null, null);
		}

		/** A single-directory dialog. */
		public static FileDialogRequest directory(String title) {
			return new FileDialogRequest(title, false, true, List.of(), null, null);
		}
	}

	@DBusInterfaceName("org.freedesktop.portal.FileChooser")
	public interface FileChooserInterface extends DBusInterface {
		DBusPath OpenFile(String parentWindow, String title, Map<String, Variant<?>> options);

		DBusPath SaveFile(String parentWindow, String title, Map<String, Variant<?>> options);
	}

	@DBusInterfaceName("org.freedesktop.portal.OpenURI")
	public interface OpenUriInterface extends DBusInterface {
		DBusPath OpenURI(String parentWindow, String uri, Map<String, Variant<?>> options);

		DBusPath OpenFile(String parentWindow, String uri, Map<String, Variant<?>> options);
	}

	@DBusInterfaceName("org.freedesktop.portal.Request")
	public interface RequestInterface extends DBusInterface {
		void Close();

		class Response extends DBusSignal {
			private final UInt32 response;
			private final Map<String, Variant<?>> results;

			public Response(String objectPath, UInt32 response, Map<String, Variant<?>> results)
					throws DBusException {
				super(objectPath, response, results);
				this.response = response;
				this.results = results;
			}

			public UInt32 getResponse() {
				return response;
			}

			public Map<String, Variant<?>> getResults() {
				return results;
			}
		}
	}

	/** {@code (us)}, one filter rule of the portal {@code filters} option. */
	private static final class FilterLine extends Struct {
		@Position(0)
		final UInt32 type;

		@Position(1)
		final String pattern;

		FilterLine(int type, String pattern) {
			this.type = new UInt32(type);
			this.pattern = pattern;
		}
	}

	/** {@code (sa(us))}, one chooser filter. */
	private static final class FilterStruct extends Struct {
		@Position(0)
		final String label;

		@Position(1)
		final List<FilterLine> lines;

		FilterStruct(String label, List<FilterLine> lines) {
			this.label = label;
			this.lines = lines;
		}
	}

	private record ResponseData(UInt32 response, Map<String, Variant<?>> results) {
	}

	private final SessionBus bus;
	private final AtomicInteger requestCounter = new AtomicInteger();
	private final String senderToken;
	private final FileChooserInterface fileChooser;
	private final OpenUriInterface openUri;

	public Portal(SessionBus bus) throws DBusException {
		this.bus = bus;
		this.senderToken = bus.uniqueName().substring(1).replace('.', '_');
		this.fileChooser = bus.connection().getRemoteObject(SERVICE, PATH, FileChooserInterface.class, false);
		this.openUri = bus.connection().getRemoteObject(SERVICE, PATH, OpenUriInterface.class, false);
	}

	public static boolean isAvailable(SessionBus bus) {
		return bus.isServiceRunning(SERVICE);
	}

	/** Opens a file chooser; empty when the user cancels. */
	public Optional<Path> openFile(FileDialogRequest request) throws IOException {
		return fileDialog(request, false);
	}

	/** Opens a save dialog; empty when the user cancels. */
	public Optional<Path> saveFile(FileDialogRequest request) throws IOException {
		return fileDialog(request, true);
	}

	private Optional<Path> fileDialog(FileDialogRequest request, boolean save) throws IOException {
		requireAvailable();
		String token = nextToken();
		Map<String, Variant<?>> options = fileDialogOptions(request, token);
		Map<String, Variant<?>> results = runRequest(token, options, FILE_DIALOG_TIMEOUT,
				() -> save
						? fileChooser.SaveFile(PARENT_WINDOW, request.title(), options)
						: fileChooser.OpenFile(PARENT_WINDOW, request.title(), options));
		return firstPath(results);
	}

	/** Opens a URL through the OpenURI portal, falling back to {@code xdg-open}. */
	public void openUri(URI uri) {
		Objects.requireNonNull(uri, "uri");
		if (isAvailable(bus)) {
			try {
				String token = nextToken();
				Map<String, Variant<?>> options = new LinkedHashMap<>();
				options.put("handle_token", new Variant<>(token));
				runRequest(token, options, OPEN_TIMEOUT,
						() -> openUri.OpenURI(PARENT_WINDOW, uri.toString(), options));
				return;
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "OpenURI portal failed for " + uri + ": " + error);
			}
		}
		xdgOpen(uri.toString());
	}

	/** Opens a local file through the OpenURI portal, falling back to {@code xdg-open}. */
	public void openPath(Path path) {
		Objects.requireNonNull(path, "path");
		Path absolute = path.toAbsolutePath().normalize();
		if (isAvailable(bus)) {
			try {
				String token = nextToken();
				Map<String, Variant<?>> options = new LinkedHashMap<>();
				options.put("handle_token", new Variant<>(token));
				runRequest(token, options, OPEN_TIMEOUT,
						() -> openUri.OpenFile(PARENT_WINDOW, absolute.toUri().toString(), options));
				return;
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "OpenFile portal failed for " + absolute + ": " + error);
			}
		}
		xdgOpen(absolute.toString());
	}

	private void requireAvailable() throws PortalUnavailableException {
		if (!isAvailable(bus)) {
			throw new PortalUnavailableException("xdg-desktop-portal is not running on the session bus");
		}
	}

	static Map<String, Variant<?>> fileDialogOptions(FileDialogRequest request, String token) {
		Map<String, Variant<?>> options = new LinkedHashMap<>();
		options.put("handle_token", new Variant<>(token));
		if (request.multiple()) {
			options.put("multiple", new Variant<>(Boolean.TRUE));
		}
		if (request.directory()) {
			options.put("directory", new Variant<>(Boolean.TRUE));
		}
		if (!request.filters().isEmpty()) {
			options.put("filters", new Variant<>(toFilterStructs(request.filters()), "a(sa(us))"));
		}
		if (request.currentFolder() != null) {
			options.put("current_folder", new Variant<>(nullTerminatedUtf8(request.currentFolder()), "ay"));
		}
		if (request.currentName() != null && !request.currentName().isEmpty()) {
			options.put("current_name", new Variant<>(request.currentName()));
		}
		return options;
	}

	private static List<FilterStruct> toFilterStructs(List<FileFilter> filters) {
		List<FilterStruct> structs = new ArrayList<>();
		for (FileFilter filter : filters) {
			List<FilterLine> lines = new ArrayList<>();
			for (String glob : filter.globs()) {
				lines.add(new FilterLine(0, glob));
			}
			structs.add(new FilterStruct(filter.label(), lines));
		}
		return structs;
	}

	private static byte[] nullTerminatedUtf8(Path path) {
		byte[] bytes = path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 1);
		out.write(bytes, 0, bytes.length);
		out.write(0);
		return out.toByteArray();
	}

	private Map<String, Variant<?>> runRequest(String token, Map<String, Variant<?>> options, Duration timeout,
			Supplier<DBusPath> call) throws IOException {
		DBusConnection connection = bus.connection();
		String predictedPath = predictedRequestPath(token);
		CompletableFuture<ResponseData> future = new CompletableFuture<>();
		AutoCloseable subscription = subscribe(connection, predictedPath, future);
		try {
			DBusPath handle = call.get();
			AutoCloseable secondSubscription = null;
			String actualPath = handle == null ? predictedPath : handle.getPath();
			if (!actualPath.equals(predictedPath) && !future.isDone()) {
				secondSubscription = subscribe(connection, actualPath, future);
			}
			try {
				ResponseData response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				if (response.response().intValue() != 0) {
					LOG.log(System.Logger.Level.DEBUG, "portal request " + token + " cancelled or failed ("
							+ response.response().intValue() + ")");
					return Map.of();
				}
				return response.results() == null ? Map.of() : response.results();
			} catch (TimeoutException error) {
				throw new IOException("portal request timed out after " + timeout, error);
			} catch (ExecutionException error) {
				throw new IOException("portal request failed: " + error.getCause(), error.getCause());
			} finally {
				closeQuietly(secondSubscription);
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for the portal", error);
		} finally {
			closeQuietly(subscription);
		}
	}

	private AutoCloseable subscribe(DBusConnection connection, String requestPath,
			CompletableFuture<ResponseData> future) throws IOException {
		try {
			org.freedesktop.dbus.interfaces.DBusSigHandler<RequestInterface.Response> handler =
					signal -> future.complete(new ResponseData(signal.getResponse(), signal.getResults()));
			return connection.addSigHandler(DBusMatchRuleBuilder.create()
					.withType(RequestInterface.Response.class)
					.withPath(requestPath)
					.build(), handler);
		} catch (DBusException error) {
			throw new IOException("could not subscribe to " + requestPath + ": " + error, error);
		}
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

	private String nextToken() {
		return "gravaai_" + requestCounter.incrementAndGet() + "_" + System.nanoTime();
	}

	String predictedRequestPath(String token) {
		return predictedRequestPath(senderToken, token);
	}

	/** The request object path the portal will use for {@code token} (plan of the portal spec). */
	static String predictedRequestPath(String senderToken, String token) {
		return REQUEST_PATH_PREFIX + senderToken + "/" + token;
	}

	private Optional<Path> firstPath(Map<String, Variant<?>> results) {
		Variant<?> uris = results.get("uris");
		if (uris == null || !(uris.getValue() instanceof List<?> list) || list.isEmpty()) {
			return Optional.empty();
		}
		Object first = list.getFirst();
		if (!(first instanceof String value)) {
			return Optional.empty();
		}
		URI uri = URI.create(value);
		if (!"file".equals(uri.getScheme())) {
			return Optional.empty();
		}
		return Optional.of(Path.of(uri));
	}

	private void xdgOpen(String target) {
		try {
			var result = ProcessRunner.of("xdg-open", target).timeout(XDG_OPEN_TIMEOUT).run();
			if (!result.succeeded()) {
				LOG.log(System.Logger.Level.WARNING, "xdg-open " + target + " failed: " + result.stderr().strip());
			}
		} catch (IOException | InterruptedException error) {
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.log(System.Logger.Level.WARNING, "xdg-open " + target + " failed: " + error);
		}
	}

	@Override
	public void close() {
		// The connection and proxies are owned by SessionBus; nothing to release here.
	}
}
