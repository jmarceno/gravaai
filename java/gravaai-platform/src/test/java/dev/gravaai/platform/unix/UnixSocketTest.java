package dev.gravaai.platform.unix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class UnixSocketTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("accepts clients and echoes newline-delimited requests")
	void acceptsClientAndEchoesLine() throws Exception {
		Path socket = tempDir.resolve("run/control.sock");

		try (UnixSocketServer _ = UnixSocketServer.open(socket, connection -> {
			String request = connection.readLine();
			connection.writeLine("ok:" + request);
		})) {
			assertTrue(Files.exists(socket));
			try (UnixSocketConnection client = UnixSocketClient.connect(socket)) {
				client.writeLine("start-recording");
				assertEquals("ok:start-recording", client.readLine());
			}
			try (UnixSocketConnection client = UnixSocketClient.connect(socket)) {
				client.writeLine("stop-recording");
				assertEquals("ok:stop-recording", client.readLine());
			}
		}
	}

	@Test
	@DisplayName("creates private runtime directory and socket")
	void createsOwnerOnlyFiles() throws Exception {
		Path runtimeDirectory = tempDir.resolve("runtime");
		Path socket = runtimeDirectory.resolve("control.sock");

		try (UnixSocketServer _ = UnixSocketServer.open(socket, connection -> {})) {
			assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(runtimeDirectory)));
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
		}
	}

	@Test
	@DisplayName("removes the socket file when the server closes")
	void removesSocketOnClose() throws Exception {
		Path socket = tempDir.resolve("control.sock");
		UnixSocketServer server = UnixSocketServer.open(socket, connection -> {});
		assertTrue(Files.exists(socket));

		server.close();

		assertFalse(Files.exists(socket));
		assertTrue(UnixSocketClient.tryConnect(socket).isEmpty());
	}

	@Test
	@DisplayName("can rebind after a clean close")
	void rebindsAfterClose() throws Exception {
		Path socket = tempDir.resolve("control.sock");
		try (UnixSocketServer server = UnixSocketServer.open(socket, connection -> {})) {
			assertTrue(server.isClosed() == false);
		}
		try (UnixSocketServer _ = UnixSocketServer.open(socket, connection -> connection.writeLine("again"))) {
			try (UnixSocketConnection client = UnixSocketClient.connect(socket)) {
				assertEquals("again", client.readLine());
			}
		}
	}

	@Test
	@DisplayName("refuses to replace a non-socket file")
	void refusesToReplaceRegularFile() throws Exception {
		Path path = tempDir.resolve("control.sock");
		Files.writeString(path, "not a socket");

		assertThrows(IOException.class, () -> UnixSocketServer.open(path, connection -> {}));
		assertTrue(Files.isRegularFile(path));
	}

	@Test
	@DisplayName("tryConnect is empty when nothing is listening")
	void tryConnectEmptyWhenMissing() {
		assertTrue(UnixSocketClient.tryConnect(tempDir.resolve("missing.sock")).isEmpty());
		assertThrows(IOException.class, () -> UnixSocketClient.connect(tempDir.resolve("missing.sock")));
	}

	@Test
	@DisplayName("readLine returns null at end of stream")
	void readLineReturnsNullAtEof() throws Exception {
		Path socket = tempDir.resolve("control.sock");
		try (UnixSocketServer _ = UnixSocketServer.open(socket, connection -> {
			// close immediately
		})) {
			try (UnixSocketConnection client = UnixSocketClient.connect(socket)) {
				assertNull(client.readLine());
			}
		}
	}
}
