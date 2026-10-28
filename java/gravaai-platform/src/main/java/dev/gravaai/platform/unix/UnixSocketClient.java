package dev.gravaai.platform.unix;

import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Connects to a Gravaai unix-domain socket (control socket, single-instance forwarding, agent socket). */
public final class UnixSocketClient {

	private UnixSocketClient() {}

	/** Connects, failing when nothing is listening. */
	public static UnixSocketConnection connect(Path socketPath) throws IOException {
		Path path = socketPath.toAbsolutePath().normalize();
		if (!Files.exists(path)) {
			throw new ConnectException("no socket at " + path);
		}
		SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.connect(UnixDomainSocketAddress.of(path));
		} catch (IOException | RuntimeException error) {
			channel.close();
			throw error;
		}
		return new UnixSocketConnection(channel);
	}

	/** Connects when a live server is listening; empty when the socket is missing or refuses. */
	public static Optional<UnixSocketConnection> tryConnect(Path socketPath) {
		try {
			return Optional.of(connect(socketPath));
		} catch (IOException | RuntimeException error) {
			return Optional.empty();
		}
	}
}
