package dev.gravaai.services.shortcuts;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Gravaai's control socket: one action per line answered {@code ok}/{@code error}, exactly like
 * {@code electron/native/gravaai-ctl/main.c} expects.
 *
 * <p>Port of {@code electron/globalShortcuts/controlSocket.ts}. The socket lives at
 * {@code $XDG_RUNTIME_DIR/gravaai/control.sock}, falling back to
 * {@code $TMPDIR/gravaai-<uid>/control.sock} (must match gravaai-ctl). The same socket gives
 * single-instance forwarding (plan §5.1): a second launch sends its action and exits.
 */
public final class ControlSocket {

	private static final System.Logger LOG = System.getLogger("gravaai.shortcuts");

	/** Port of {@code MAX_LINE_BYTES}: gravaai-ctl truncates its own line at 80 bytes. */
	static final int MAX_LINE_BYTES = 64;

	/** Port of the socket's 2 s timeout (C sets SO_RCVTIMEO/SO_SNDTIMEO to 2 s). */
	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	/** Sent by a second plain launch: the running app brings its window to the front. */
	public static final String SHOW_LINE = "show";

	private static final ScheduledExecutorService TIMEOUTS =
			Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "gravaai-control-timeouts");
				thread.setDaemon(true);
				return thread;
			});

	private ControlSocket() {}

	/** Port of {@code getControlSocketPath}; must match gravaai-ctl's {@code socket_path}. */
	public static Path getControlSocketPath(Map<String, String> env) {
		String runtimeDir = trimToNull(env == null ? null : env.get("XDG_RUNTIME_DIR"));
		if (runtimeDir != null) {
			return Path.of(runtimeDir).resolve("gravaai").resolve("control.sock");
		}
		String tmpDir = trimToNull(env == null ? null : env.get("TMPDIR"));
		if (tmpDir == null) {
			tmpDir = "/tmp";
		}
		return Path.of(tmpDir).resolve("gravaai-" + currentUid()).resolve("control.sock");
	}

	public static Path getControlSocketPath() {
		return getControlSocketPath(System.getenv());
	}

	/**
	 * Port of {@code handleControlLine}: accepts known actions only and returns the reply line,
	 * without the trailing newline handling that the socket does.
	 */
	public static String handleControlLine(String line, Consumer<GlobalShortcutAction> onAction) {
		return handleControlLine(line, onAction, null);
	}

	/**
	 * Like {@link #handleControlLine(String, Consumer)}, and also accepts {@link #SHOW_LINE} when
	 * {@code onShow} is set (a second plain launch asks the running app to come to the front).
	 */
	public static String handleControlLine(
			String line, Consumer<GlobalShortcutAction> onAction, @Nullable Runnable onShow) {
		String action = line == null ? "" : line.trim();
		if (onShow != null && SHOW_LINE.equals(action)) {
			onShow.run();
			return "ok\n";
		}
		GlobalShortcutAction parsed = GlobalShortcutAction.fromWire(action);
		if (parsed == null) {
			return "error\n";
		}
		onAction.accept(parsed);
		return "ok\n";
	}

	/** Starts a server on the default socket path (throws instead of exiting when it cannot). */
	public static Server start(Consumer<GlobalShortcutAction> onAction) throws IOException {
		return start(onAction, getControlSocketPath());
	}

	/** Port of {@code startControlSocket}. */
	public static Server start(Consumer<GlobalShortcutAction> onAction, Path socketPath) throws IOException {
		return start(onAction, null, socketPath);
	}

	/** Starts a server that also answers {@link #SHOW_LINE} through {@code onShow}. */
	public static Server start(Consumer<GlobalShortcutAction> onAction, @Nullable Runnable onShow,
			Path socketPath)
			throws IOException {
		Path directory = socketPath.getParent();
		Files.createDirectories(directory);
		setPermissions(directory, "rwx------");
		Files.deleteIfExists(socketPath);

		ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.bind(UnixDomainSocketAddress.of(socketPath));
		} catch (IOException error) {
			channel.close();
			throw error;
		}
		setPermissions(socketPath, "rw-------");

		Server server = new Server(socketPath, channel, onAction, onShow);
		server.startAccepting();
		return server;
	}

	/**
	 * Port of gravaai-ctl's {@code send_action}: delivers one action line to a running app.
	 * {@link SendResult#NOT_CONNECTED} is what makes gravaai-ctl launch the app.
	 */
	public static SendResult send(String line, Path socketPath) {
		UnixDomainSocketAddress address;
		try {
			address = UnixDomainSocketAddress.of(socketPath);
		} catch (RuntimeException error) {
			return SendResult.NOT_CONNECTED;
		}
		try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
			try {
				channel.connect(address);
			} catch (IOException error) {
				return SendResult.NOT_CONNECTED;
			}
			byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
			try {
				writeFully(channel, payload);
			} catch (IOException error) {
				return SendResult.ERROR;
			}
			String reply = readReply(channel);
			if (reply.isEmpty()) {
				return SendResult.ERROR;
			}
			return reply.startsWith("ok") ? SendResult.OK : SendResult.ERROR;
		} catch (IOException error) {
			return SendResult.NOT_CONNECTED;
		}
	}

	public static SendResult send(GlobalShortcutAction action, Path socketPath) {
		return send(action.wire(), socketPath);
	}

	/** The outcome of a forwarded action; mirrors gravaai-ctl's return codes (0, 1, -1). */
	public enum SendResult {
		/** The running app accepted and will dispatch the action. */
		OK,
		/** The running app answered {@code error}, or the connection broke before a reply. */
		ERROR,
		/** No app is listening: the caller may start one. */
		NOT_CONNECTED;

		/** gravaai-ctl exits 0 only when the app accepted the action. */
		public int exitCode() {
			return switch (this) {
				case OK -> 0;
				case ERROR, NOT_CONNECTED -> 1;
			};
		}
	}

	/** The running server; closing removes the socket file. */
	public static final class Server implements AutoCloseable {

		private final Path socketPath;
		private final ServerSocketChannel channel;
		private final Consumer<GlobalShortcutAction> onAction;
		private final @Nullable Runnable onShow;
		private volatile boolean running = true;
		private @Nullable Thread acceptThread;

		private Server(Path socketPath, ServerSocketChannel channel,
				Consumer<GlobalShortcutAction> onAction, @Nullable Runnable onShow) {
			this.socketPath = socketPath;
			this.channel = channel;
			this.onAction = onAction;
			this.onShow = onShow;
		}

		public Path socketPath() {
			return socketPath;
		}

		private void startAccepting() {
			acceptThread = Thread.ofVirtual().name("gravaai-control-socket").start(() -> {
				while (running) {
					try {
						SocketChannel client = channel.accept();
						Thread.ofVirtual()
								.name("gravaai-control-connection")
								.start(() -> handleConnection(client));
					} catch (ClosedChannelException error) {
						break;
					} catch (IOException error) {
						if (running) {
							LOG.log(System.Logger.Level.WARNING, "Control socket error: " + error);
						}
					}
				}
			});
		}

		/** Port of the per-connection handler in {@code startControlSocket}. */
		private void handleConnection(SocketChannel client) {
			ScheduledFuture<?> timeout =
					TIMEOUTS.schedule(() -> closeQuietly(client), TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			try {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				ByteBuffer chunk = ByteBuffer.allocate(256);
				boolean answered = false;
				while (!answered) {
					chunk.clear();
					int read = client.read(chunk);
					if (read < 0) {
						return;
					}
					if (read == 0) {
						continue;
					}
					buffer.write(chunk.array(), 0, read);
					byte[] bytes = buffer.toByteArray();
					int newline = indexOfNewline(bytes);
					if (newline == -1 && new String(bytes, StandardCharsets.UTF_8).length() <= MAX_LINE_BYTES) {
						continue;
					}
					String line = newline == -1 ? "" : new String(bytes, 0, newline, StandardCharsets.UTF_8);
					answered = true;
					String reply = line.length() > MAX_LINE_BYTES ? "error\n" : handleControlLine(line, onAction, onShow);
					writeFully(client, reply.getBytes(StandardCharsets.UTF_8));
				}
			} catch (IOException error) {
				// The peer vanished, like the TS socket error handler: drop the connection.
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "Control socket action failed: " + error);
			} finally {
				timeout.cancel(false);
				closeQuietly(client);
			}
		}

		/** Port of the TS {@code close()}: stop accepting and remove the socket file. */
		@Override
		public void close() {
			running = false;
			try {
				channel.close();
			} catch (IOException error) {
				// Already closed.
			}
			try {
				Files.deleteIfExists(socketPath);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not remove the control socket: " + error);
			}
			if (acceptThread != null && acceptThread != Thread.currentThread()) {
				try {
					acceptThread.join(TimeUnit.SECONDS.toMillis(5));
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static String readReply(SocketChannel channel) {
		ByteBuffer buffer = ByteBuffer.allocate(15);
		try (Selector selector = Selector.open()) {
			channel.configureBlocking(false);
			channel.register(selector, SelectionKey.OP_READ);
			if (selector.select(TIMEOUT.toMillis()) == 0) {
				return "";
			}
			int read = channel.read(buffer);
			if (read <= 0) {
				return "";
			}
			byte[] reply = new byte[read];
			buffer.flip();
			buffer.get(reply);
			return new String(reply, StandardCharsets.UTF_8);
		} catch (IOException error) {
			return "";
		}
	}

	private static void writeFully(SocketChannel channel, byte[] payload) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(payload);
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private static int indexOfNewline(byte[] bytes) {
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == '\n') {
				return i;
			}
		}
		return -1;
	}

	private static void setPermissions(@Nullable Path path, String permissions) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
		} catch (IOException | UnsupportedOperationException error) {
			// Same as the TS chmod(...).catch(() => undefined).
		}
	}

	private static void closeQuietly(SocketChannel channel) {
		try {
			channel.close();
		} catch (IOException error) {
			// Already closed.
		}
	}

	private static long currentUid() {
		try {
			Object uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid");
			return uid instanceof Number number ? number.longValue() : 0L;
		} catch (IOException | RuntimeException error) {
			return 0L;
		}
	}

	private static @Nullable String trimToNull(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
