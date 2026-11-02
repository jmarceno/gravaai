package dev.gravaai.services.shortcuts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.platform.portal.GlobalShortcutsPortal;

/**
 * Behaviour of {@code electron/globalShortcuts/index.ts} (the TS file has no unit test of its
 * own): config storage, the COSMIC sync loop, conflict reporting and the control socket startup.
 */
class GlobalShortcutsServiceTest {

	private static final String APP_PATH = "/opt/Gravaai/gravaai";

	@TempDir
	Path tempDir;

	private Path bundledCtl;
	private MemorySettings settings;
	private final List<GlobalShortcutAction> actions = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		bundledCtl = tempDir.resolve("bundle/gravaai-ctl");
		Files.createDirectories(bundledCtl.getParent());
		Files.writeString(bundledCtl, "ctl-binary", UTF_8);
		Files.setPosixFilePermissions(bundledCtl, PosixFilePermissions.fromString("rwxr-xr-x"));
		settings = new MemorySettings();
	}

	@Test
	@DisplayName("isCosmicDesktop matches COSMIC in XDG_CURRENT_DESKTOP")
	void isCosmicDesktop() {
		assertTrue(service(Map.of("XDG_CURRENT_DESKTOP", "COSMIC")).isCosmicDesktop());
		assertTrue(service(Map.of("XDG_CURRENT_DESKTOP", "GNOME:COSMIC")).isCosmicDesktop());
		assertFalse(service(Map.of("XDG_CURRENT_DESKTOP", "GNOME")).isCosmicDesktop());
		assertFalse(service(Map.of()).isCosmicDesktop());
	}

	@Test
	@DisplayName("on other desktops it reports cli-only commands and writes nothing")
	void cliOnlyOnOtherDesktops() {
		GlobalShortcutsService service = service(Map.of("XDG_CURRENT_DESKTOP", "GNOME"));
		GlobalShortcutsStatus status = service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		assertEquals(GlobalShortcutsStatus.Backend.CLI_ONLY, status.backend());
		assertTrue(status.registered().isEmpty());
		assertNull(status.error());
		assertEquals(
				"'/opt/Gravaai/gravaai' --toggle-recording",
				status.commands().get(GlobalShortcutAction.TOGGLE_RECORDING));
		assertEquals(4, status.commands().size());
		assertEquals(1, settings.writes);
		assertFalse(Files.exists(cosmicPaths(env("GNOME")).custom()));
	}

	@Test
	@DisplayName("on COSMIC it installs gravaai-ctl, registers the enabled bindings and removes them again")
	void cosmicRegistersAndRemoves() throws IOException {
		Map<String, String> env = env("COSMIC");
		GlobalShortcutsService service = service(env);
		GlobalShortcutsStatus status = service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);

		assertEquals(GlobalShortcutsStatus.Backend.COSMIC, status.backend());
		assertNull(status.error());
		assertTrue(status.conflicts().isEmpty());
		assertEquals(
				List.of(
						GlobalShortcutAction.TOGGLE_RECORDING,
						GlobalShortcutAction.TOGGLE_PAUSE,
						GlobalShortcutAction.TOGGLE_MEETING),
				List.copyOf(status.registered().keySet()));

		Path installed = Path.of(env.get("XDG_DATA_HOME"), "gravaai", "bin", "gravaai-ctl");
		assertEquals("ctl-binary", Files.readString(installed, UTF_8));
		assertTrue(PosixFilePermissions.toString(Files.getPosixFilePermissions(installed)).contains("x"));

		CosmicShortcutPaths paths = cosmicPaths(env);
		String custom = Files.readString(paths.custom(), UTF_8);
		assertTrue(custom.startsWith("{\n    (\n"), custom);
		List<CosmicShortcutEntry> entries = CosmicShortcuts.parseCosmicShortcuts(custom).entries();
		assertEquals(3, entries.size());
		for (CosmicShortcutEntry entry : entries) {
			assertTrue(CosmicShortcuts.isGravaaiEntry(entry));
			assertTrue(entry.spawnCommand().contains("gravaai-ctl"), entry.spawnCommand());
			assertTrue(entry.spawnCommand().contains(APP_PATH), entry.spawnCommand());
			assertTrue(entry.description().startsWith(CosmicShortcuts.GRAVAAI_DESCRIPTION_PREFIX));
		}
		assertFalse(Files.exists(Path.of(paths.custom() + ".gravaai-backup")));

		GlobalShortcutsStatus disabled = service.apply(new GlobalShortcutsConfig(false, GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings()));
		assertEquals(GlobalShortcutsStatus.Backend.COSMIC, disabled.backend());
		assertTrue(disabled.registered().isEmpty());
		assertEquals("{\n}\n", Files.readString(paths.custom(), UTF_8));
		assertTrue(Files.exists(Path.of(paths.custom() + ".gravaai-backup")));
		assertTrue(Files.readString(Path.of(paths.custom() + ".gravaai-backup"), UTF_8).contains("Gravaai: "));

		GlobalShortcutsStatus again = service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		assertEquals(3, again.registered().size());
		assertEquals(
				custom, Files.readString(paths.custom(), UTF_8), "re-enabling writes the same bytes back");
		assertFalse(Files.exists(Path.of(paths.custom() + ".tmp-gravaai")));
	}

	@Test
	@DisplayName("a parse error aborts the sync without writing the user's file")
	void parseErrorAbortsWithoutWriting() throws IOException {
		Map<String, String> env = env("COSMIC");
		CosmicShortcutPaths paths = cosmicPaths(env);
		Files.createDirectories(paths.custom().getParent());
		Files.writeString(paths.custom(), "{ broken", UTF_8);

		GlobalShortcutsService service = service(env);
		GlobalShortcutsStatus status = service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		assertEquals(GlobalShortcutsService.PARSE_ERROR_MESSAGE, status.error());
		assertTrue(status.registered().isEmpty());
		assertEquals("{ broken", Files.readString(paths.custom(), UTF_8));
		assertFalse(Files.exists(Path.of(paths.custom() + ".gravaai-backup")));
	}

	@Test
	@DisplayName("a COSMIC default conflict is reported and the entry is not written")
	void cosmicConflictIsReported() throws IOException {
		Map<String, String> env = env("COSMIC");
		CosmicShortcutPaths paths = cosmicPaths(env);
		Files.createDirectories(paths.userDefaults().getParent());
		Files.writeString(paths.userDefaults(), "{\n    (modifiers: [Super, Alt], key: \"r\"): Close,\n}\n", UTF_8);

		GlobalShortcutsService service = service(env);
		GlobalShortcutsStatus status = service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		assertEquals(
				"Super + Alt + R is used by COSMIC (Close)",
				status.conflicts().get(GlobalShortcutAction.TOGGLE_RECORDING));
		assertEquals(
				List.of(GlobalShortcutAction.TOGGLE_PAUSE, GlobalShortcutAction.TOGGLE_MEETING),
				List.copyOf(status.registered().keySet()));
		List<CosmicShortcutEntry> entries =
				CosmicShortcuts.parseCosmicShortcuts(Files.readString(paths.custom(), UTF_8)).entries();
		assertEquals(2, entries.size());
	}

	@Test
	@DisplayName("duplicate bindings inside the config are reported instead of registered twice")
	void duplicateBindingsAreReported() {
		Map<String, String> env = env("COSMIC");
		Map<GlobalShortcutAction, GlobalShortcutBinding> bindings = new LinkedHashMap<>();
		bindings.put(GlobalShortcutAction.TOGGLE_RECORDING, new GlobalShortcutBinding("r", true, false, true, false));
		bindings.put(GlobalShortcutAction.TOGGLE_PAUSE, new GlobalShortcutBinding("r", true, false, true, false));
		bindings.put(GlobalShortcutAction.CANCEL_RECORDING, null);
		bindings.put(GlobalShortcutAction.TOGGLE_MEETING, null);
		GlobalShortcutsStatus status = service(env).apply(new GlobalShortcutsConfig(true, bindings));

		assertEquals(
				"Super + Alt + R is already used by \"Start / stop recording\"",
				status.conflicts().get(GlobalShortcutAction.TOGGLE_PAUSE));
		assertEquals(List.of(GlobalShortcutAction.TOGGLE_RECORDING), List.copyOf(status.registered().keySet()));
	}

	@Test
	@DisplayName("init starts the control socket and forwards actions to the sink")
	void initStartsControlSocket() throws IOException {
		Path runtime = Files.createDirectory(tempDir.resolve("run"));
		Map<String, String> env = new LinkedHashMap<>(env("COSMIC"));
		env.put("XDG_RUNTIME_DIR", runtime.toString());
		GlobalShortcutsService service = service(env);
		try {
			service.init();
			Path socketPath = ControlSocket.getControlSocketPath(env);
			assertTrue(Files.exists(socketPath), socketPath.toString());
			assertEquals(ControlSocket.SendResult.OK, ControlSocket.send(GlobalShortcutAction.TOGGLE_PAUSE, socketPath));
			assertEquals(List.of(GlobalShortcutAction.TOGGLE_PAUSE), actions);
		} finally {
			service.close();
		}
		Path socketPath = ControlSocket.getControlSocketPath(env);
		assertFalse(Files.exists(socketPath));
	}

	@Test
	@DisplayName("apply persists the normalized config and readConfig reads it back")
	void persistRoundTrip() {
		GlobalShortcutsService service = service(env("COSMIC"));
		service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		assertNotNull(settings.value);
		assertEquals(
				GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS,
				GlobalShortcuts.normalizeGlobalShortcutsConfig(settings.value));

		MemorySettings custom = new MemorySettings();
		custom.value = Json.MAPPER.readTree("""
				{"enabled": false, "bindings": {"toggle-pause": {"key": "F9", "ctrl": true}}}
				""");
		GlobalShortcutsService other = new GlobalShortcutsService(
				custom, () -> bundledCtl, APP_PATH, env("COSMIC"), actions::add);
		GlobalShortcutsConfig config = other.readConfig();
		assertFalse(config.enabled());
		assertEquals(
				new GlobalShortcutBinding("F9", false, true, false, false),
				config.bindings().get(GlobalShortcutAction.TOGGLE_PAUSE));
		assertEquals(
				GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings().get(GlobalShortcutAction.TOGGLE_RECORDING),
				config.bindings().get(GlobalShortcutAction.TOGGLE_RECORDING));
	}

	@Test
	@DisplayName("readConfig falls back to the defaults for a missing or invalid setting")
	void readConfigFallsBack() {
		assertEquals(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS, service(env("COSMIC")).readConfig());
		settings.value = Json.MAPPER.readTree("42");
		assertEquals(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS, service(env("COSMIC")).readConfig());
	}

	/** Records what the service asks of the desktop portal, and plays the user pressing a key. */
	private static final class FakePortal implements DesktopShortcutPortal {
		final java.util.concurrent.CompletableFuture<List<GlobalShortcutsPortal.Shortcut>> bound =
				new java.util.concurrent.CompletableFuture<>();
		boolean unbound;
		java.util.function.Consumer<String> onActivated = id -> { };

		@Override
		public List<GlobalShortcutsPortal.BoundShortcut> bind(List<GlobalShortcutsPortal.Shortcut> shortcuts) {
			bound.complete(shortcuts);
			return List.of(new GlobalShortcutsPortal.BoundShortcut("toggle-recording", "Meta+Alt+R"));
		}

		@Override
		public void unbind() {
			unbound = true;
		}

		@Override
		public void close() {
		}
	}

	private GlobalShortcutsService portalService(FakePortal portal) {
		return new GlobalShortcutsService(settings, () -> bundledCtl, APP_PATH, env("KDE"), actions::add,
				null, null, onActivated -> {
					portal.onActivated = onActivated;
					return java.util.Optional.of(portal);
				});
	}

	@Test
	@DisplayName("off COSMIC, a desktop portal registers the enabled bindings as preferred triggers")
	void portalRegistersPreferredTriggers() throws Exception {
		FakePortal portal = new FakePortal();
		GlobalShortcutsStatus status = portalService(portal).apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);

		assertEquals(GlobalShortcutsStatus.Backend.PORTAL, status.backend());
		List<GlobalShortcutsPortal.Shortcut> shortcuts = portal.bound.get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(4, shortcuts.size());
		assertEquals("toggle-recording", shortcuts.getFirst().id());
		assertEquals("Start / stop recording", shortcuts.getFirst().description());
		assertEquals("ALT+LOGO+r", shortcuts.getFirst().preferredTrigger());
		// The default config leaves cancel unbound; the desktop still lists it for the user to set.
		assertEquals("", shortcuts.get(2).preferredTrigger());
		assertFalse(Files.exists(cosmicPaths(env("KDE")).custom()));
	}

	@Test
	@DisplayName("the keys the desktop bound are reported once it answers")
	void portalReportsTheBoundTriggers() throws Exception {
		FakePortal portal = new FakePortal();
		GlobalShortcutsService service = portalService(portal);
		service.apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
		portal.bound.get(5, java.util.concurrent.TimeUnit.SECONDS);

		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		while (service.getStatus().triggers().isEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(Map.of(GlobalShortcutAction.TOGGLE_RECORDING, "Meta+Alt+R"), service.getStatus().triggers());
	}

	@Test
	@DisplayName("a pressed portal shortcut is dispatched like a control-socket action")
	void portalActivationDispatches() {
		FakePortal portal = new FakePortal();
		portalService(portal).apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);

		portal.onActivated.accept("toggle-pause");
		portal.onActivated.accept("not-an-action");

		assertEquals(List.of(GlobalShortcutAction.TOGGLE_PAUSE), actions);
	}

	@Test
	@DisplayName("turning global shortcuts off unbinds them at the desktop")
	void portalDisableUnbinds() {
		FakePortal portal = new FakePortal();
		GlobalShortcutsStatus status = portalService(portal).apply(
				new GlobalShortcutsConfig(false, GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS.bindings()));

		assertEquals(GlobalShortcutsStatus.Backend.PORTAL, status.backend());
		assertTrue(portal.unbound);
		assertFalse(portal.bound.isDone());
	}

	@Test
	@DisplayName("portal triggers use the XDG shortcut format")
	void portalTriggerFormat() {
		assertEquals("CTRL+ALT+SHIFT+LOGO+F9",
				GlobalShortcutsService.portalTrigger(new GlobalShortcutBinding("F9", true, true, true, true)));
		assertEquals("CTRL+Return",
				GlobalShortcutsService.portalTrigger(new GlobalShortcutBinding("Return", false, true, false, false)));
	}

	private GlobalShortcutsService service(Map<String, String> env) {
		return new GlobalShortcutsService(settings, () -> bundledCtl, APP_PATH, env, actions::add);
	}

	private Map<String, String> env(String desktop) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("XDG_CONFIG_HOME", tempDir.resolve("config").toString());
		env.put("XDG_DATA_HOME", tempDir.resolve("data").toString());
		env.put("XDG_CURRENT_DESKTOP", desktop);
		return env;
	}

	private static CosmicShortcutPaths cosmicPaths(Map<String, String> env) {
		return CosmicShortcuts.getCosmicShortcutPaths(env);
	}

	private static final class MemorySettings implements GlobalShortcutSettings {
		private tools.jackson.databind.JsonNode value;
		private int writes;

		@Override
		public tools.jackson.databind.JsonNode read() {
			return value;
		}

		@Override
		public void write(tools.jackson.databind.JsonNode value) {
			this.value = value;
			this.writes++;
		}
	}
}
