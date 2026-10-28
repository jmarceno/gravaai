package dev.gravaai.platform.unix;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unix-domain socket server (plan §5.16, §5.17): binds a socket file, accepts connections on a
 * daemon thread and runs every handler on its own virtual thread.
 *
 * <p>The parent directory is created private ({@code 0700}) and the socket is made owner-only
 * ({@code 0600}) so other users cannot drive the control socket. A stale socket left by a crash is
 * removed on open; any other kind of file at the path is never deleted.
 */
public final class UnixSocketServer implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.platform.unix");

	/** Handles one accepted connection. */
	@FunctionalInterface
	public interface Handler {
		void handle(UnixSocketConnection connection) throws IOException;
	}

	private final Path socketPath;
	private final ServerSocketChannel channel;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Thread acceptThread;

	private UnixSocketServer(Path socketPath, ServerSocketChannel channel, Handler handler) {
		this.socketPath = socketPath;
		this.channel = channel;
		this.acceptThread = Thread.ofPlatform()
				.daemon()
				.name("unix-socket-" + socketPath.getFileName())
				.start(() -> acceptLoop(handler));
	}

	/** Binds {@code socketPath} and starts accepting connections. */
	public static UnixSocketServer open(Path socketPath, Handler handler) throws IOException {
		Path path = socketPath.toAbsolutePath().normalize();
		Path parent = path.getParent();
		if (parent == null) {
			throw new IOException("socket path has no parent directory: " + path);
		}
		Files.createDirectories(parent);
		setPermissions(parent, "rwx------");
		prepareSocketFile(path);

		ServerSocketChannel channel = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
		try {
			channel.bind(java.net.UnixDomainSocketAddress.of(path));
		} catch (IOException | RuntimeException error) {
			channel.close();
			throw error;
		}
		setPermissions(path, "rw-------");
		return new UnixSocketServer(path, channel, handler);
	}

	public Path socketPath() {
		return socketPath;
	}

	public boolean isClosed() {
		return closed.get();
	}

	private void acceptLoop(Handler handler) {
		while (!closed.get()) {
			try {
				SocketChannel accepted = channel.accept();
				Thread.ofVirtual()
						.name("unix-socket-connection")
						.start(() -> handle(accepted, handler));
			} catch (ClosedChannelException error) {
				return;
			} catch (IOException error) {
				if (!closed.get()) {
					LOG.log(System.Logger.Level.WARNING, "unix socket accept failed: " + error);
				}
				return;
			}
		}
	}

	private static void handle(SocketChannel accepted, Handler handler) {
		try (UnixSocketConnection connection = new UnixSocketConnection(accepted)) {
			handler.handle(connection);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "unix socket handler failed: " + error);
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			channel.close();
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "closing unix socket failed: " + error);
		}
		try {
			acceptThread.join(2000);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
		try {
			Files.deleteIfExists(socketPath);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not remove socket file " + socketPath + ": " + error);
		}
	}

	private static void prepareSocketFile(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (isSocket(path)) {
			Files.delete(path);
			return;
		}
		throw new IOException("refusing to replace a non-socket file at " + path);
	}

	private static boolean isSocket(Path path) {
		try {
			Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
			return mode instanceof Number number && (number.intValue() & 0170000) == 0140000;
		} catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException error) {
			return false;
		}
	}

	private static void setPermissions(Path path, String permissions) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
		} catch (IOException | UnsupportedOperationException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not set permissions on " + path + ": " + error);
		}
	}
}
