package dev.gravaai.services.shortcuts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control socket is what makes single-instance work: a second launch forwards its action
 * instead of starting a rival app. These pin the path the app serves and the path gravaai-ctl
 * probes, because a mismatch silently breaks both single-instance and every "launch the app then
 * act" action.
 */
class ControlSocketPathTest {

	@Test
	@DisplayName("uses $XDG_RUNTIME_DIR/gravaai/control.sock when XDG_RUNTIME_DIR is set")
	void usesRuntimeDir(@TempDir Path runtime) {
		assertEquals(
				runtime.resolve("gravaai").resolve("control.sock"),
				ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", runtime.toString())));
	}

	@Test
	@DisplayName("falls back to $TMPDIR/gravaai-<uid>/control.sock without XDG_RUNTIME_DIR")
	void fallsBackToTmpDir(@TempDir Path tmp) {
		Path path = ControlSocket.getControlSocketPath(Map.of("TMPDIR", tmp.toString()));
		assertEquals(tmp, path.getParent().getParent());
		assertTrue(path.getParent().getFileName().toString().startsWith("gravaai-"), path.toString());
		assertEquals("control.sock", path.getFileName().toString());
	}

	@Test
	@DisplayName("an empty XDG_RUNTIME_DIR is ignored rather than resolving to a relative path")
	void ignoresBlankRuntimeDir(@TempDir Path tmp) {
		Path path = ControlSocket.getControlSocketPath(
				Map.of("XDG_RUNTIME_DIR", "  ", "TMPDIR", tmp.toString()));
		assertTrue(path.isAbsolute(), path.toString());
		assertEquals(tmp, path.getParent().getParent());
	}

	@Test
	@DisplayName("the server binds the socket it was asked for and answers a known action")
	void serverBindsAndAnswers(@TempDir Path dir) throws Exception {
		Path socket = dir.resolve("gravaai").resolve("control.sock");
		AtomicReference<GlobalShortcutAction> received = new AtomicReference<>();
		ControlSocket.Server server = ControlSocket.start(received::set, socket);
		try {
			assertTrue(Files.exists(socket), "socket file must exist while the server runs: " + socket);
			ControlSocket.SendResult result =
					ControlSocket.send("toggle-recording", socket);
			assertEquals(ControlSocket.SendResult.OK, result);
			long deadline = System.currentTimeMillis() + 2_000;
			while (received.get() == null && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			assertEquals(GlobalShortcutAction.TOGGLE_RECORDING, received.get());
		} finally {
			server.close();
		}
	}

	@Test
	@DisplayName("send reports NOT_CONNECTED when nothing is listening")
	void sendReportsNotConnected(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir);
		Path socket = dir.resolve("control.sock");
		assertEquals(ControlSocket.SendResult.NOT_CONNECTED, ControlSocket.send("show", socket));
	}
}
