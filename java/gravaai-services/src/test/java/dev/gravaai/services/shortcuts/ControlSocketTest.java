package dev.gravaai.services.shortcuts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code electron/globalShortcuts/controlSocket.test.ts}, plus a real-socket protocol test
 * against the bundled {@code gravaai-ctl} C helper (plan §5.17: "This must match gravaai-ctl").
 */
class ControlSocketTest {

	/** The bundled C helper: {@code java/native/build[/linux-x64]/gravaai-ctl} (P3.6 moved it). */
	private static final Path CTL = findCtl();

	private static Path findCtl() {
		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (directory != null) {
			for (String relative : List.of("java/native/build/linux-x64/gravaai-ctl",
					"java/native/build/gravaai-ctl")) {
				Path candidate = directory.resolve(relative);
				if (Files.isExecutable(candidate)) {
					return candidate;
				}
			}
			directory = directory.getParent();
		}
		return Path.of("java/native/build/linux-x64/gravaai-ctl").toAbsolutePath();
	}

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("handleControlLine accepts known actions only")
	void handleControlLineAcceptsKnownActions() {
		List<GlobalShortcutAction> seen = new ArrayList<>();
		assertEquals("ok\n", ControlSocket.handleControlLine("toggle-pause\n", seen::add));
		assertEquals("error\n", ControlSocket.handleControlLine("rm -rf /", seen::add));
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_PAUSE), seen);
	}

	@Test
	@DisplayName("getControlSocketPath prefers XDG_RUNTIME_DIR")
	void getControlSocketPathPrefersRuntimeDir() {
		assertEquals(
				Path.of("/run/user/1000/gravaai/control.sock"),
				ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", "/run/user/1000")));
		assertTrue(ControlSocket.getControlSocketPath(Map.of("TMPDIR", "/var/tmp"))
				.toString()
				.matches("/var/tmp/gravaai-\\d+/control.sock"));
	}

	@Test
	@DisplayName("the control socket answers ok for known actions and error for unknown ones")
	void realSocketProtocol() throws IOException {
		Path runtime = Files.createDirectory(tempDir.resolve("runtime"));
		List<GlobalShortcutAction> seen = new ArrayList<>();
		try (ControlSocket.Server server = ControlSocket.start(
				seen::add, ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", runtime.toString())))) {
			Path socketPath = server.socketPath();
			assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socketPath.getParent())));
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socketPath)));

			assertEquals(ControlSocket.SendResult.OK, ControlSocket.send(GlobalShortcutAction.TOGGLE_RECORDING, socketPath));
			assertEquals(ControlSocket.SendResult.ERROR, ControlSocket.send("bogus", socketPath));
			assertEquals(List.of(GlobalShortcutAction.TOGGLE_RECORDING), seen);
			assertEquals(1, ControlSocket.SendResult.ERROR.exitCode());
			assertEquals(0, ControlSocket.SendResult.OK.exitCode());
		}
		assertFalse(Files.exists(ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", runtime.toString()))));
	}

	@Test
	@DisplayName("the control socket rejects an over-long line")
	void rejectsOverlongLine() throws IOException {
		Path runtime = Files.createDirectory(tempDir.resolve("runtime"));
		List<GlobalShortcutAction> seen = new ArrayList<>();
		try (ControlSocket.Server server = ControlSocket.start(
				seen::add, ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", runtime.toString())))) {
			assertEquals(ControlSocket.SendResult.ERROR, ControlSocket.send("x".repeat(65), server.socketPath()));
			assertTrue(seen.isEmpty());
		}
	}

	@Test
	@DisplayName("send reports NOT_CONNECTED when no app is listening")
	void sendWhenNotRunning() {
		Path socketPath = tempDir.resolve("missing/gravaai/control.sock");
		assertEquals(ControlSocket.SendResult.NOT_CONNECTED, ControlSocket.send(GlobalShortcutAction.TOGGLE_PAUSE, socketPath));
	}

	@Test
	@DisplayName("gravaai-ctl delivers actions over the socket")
	void ctlDeliversActions() throws Exception {
		assumeTrue(Files.isExecutable(CTL), "gravaai-ctl is not built: " + CTL);
		Path runtime = Files.createDirectory(tempDir.resolve("runtime"));
		List<GlobalShortcutAction> seen = new ArrayList<>();
		try (ControlSocket.Server server = ControlSocket.start(
				seen::add, ControlSocket.getControlSocketPath(Map.of("XDG_RUNTIME_DIR", runtime.toString())))) {
			ProcessResult delivered = run(CTL, List.of("toggle-recording"), Map.of("XDG_RUNTIME_DIR", runtime.toString()));
			assertEquals(0, delivered.exitCode(), delivered.output());
			ProcessResult bogus = run(CTL, List.of("bogus"), Map.of("XDG_RUNTIME_DIR", runtime.toString()));
			assertNotEquals(0, bogus.exitCode(), bogus.output());
			assertEquals(List.of(GlobalShortcutAction.TOGGLE_RECORDING), seen);
		}
	}

	@Test
	@DisplayName("gravaai-ctl launches the app when it is not running")
	void ctlLaunchesTheApp() throws Exception {
		assumeTrue(Files.isExecutable(CTL), "gravaai-ctl is not built: " + CTL);
		Path dir = Files.createDirectory(tempDir.resolve("ctl"));
		Path out = dir.resolve("args.txt");
		Path app = dir.resolve("fake app");
		Files.writeString(app, "#!/bin/sh\necho \"$@\" > '" + out + "'\n");
		Files.setPosixFilePermissions(app, PosixFilePermissions.fromString("rwxr-xr-x"));
		String envRuntime = dir.resolve("missing").toString();

		ProcessResult launched = run(CTL, List.of("toggle-meeting", app.toString()), Map.of("XDG_RUNTIME_DIR", envRuntime));
		assertEquals(0, launched.exitCode(), launched.output());
		assertEquals("--toggle-meeting", Files.readString(out).strip());

		Files.delete(out);
		ProcessResult pause = run(CTL, List.of("toggle-pause", app.toString()), Map.of("XDG_RUNTIME_DIR", envRuntime));
		assertNotEquals(0, pause.exitCode(), pause.output());
		assertFalse(Files.exists(out));
	}

	private record ProcessResult(int exitCode, String output) {}

	private static ProcessResult run(Path program, List<String> args, Map<String, String> extraEnv)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(program.toString());
		command.addAll(args);
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().putAll(extraEnv);
		Process process = builder.start();
		byte[] output = process.getInputStream().readAllBytes();
		assertTrue(process.waitFor(10, TimeUnit.SECONDS), "process did not finish");
		return new ProcessResult(process.exitValue(), new String(output, UTF_8));
	}
}
