package dev.gravaai.platform.smoke;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.notification.Notifications;
import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.tray.Tray;
import dev.gravaai.platform.tray.TrayIcon;
import dev.gravaai.platform.tray.TrayMenu;
import dev.gravaai.platform.x11.X11Bridge;

/**
 * Manual acceptance smoke for plan §7.1 P1.4. Run it on the COSMIC session with
 * {@code -Dgravaai.smoke=true} and {@code WAYLAND_DISPLAY}, {@code DISPLAY} and
 * {@code DBUS_SESSION_BUS_ADDRESS} exported; it puts a real tray item on the panel, shows a
 * notification, reveals a file in the file manager and screenshots each step to
 * {@code /tmp/gravaai-platform-smoke/}.
 *
 * <p>The X11 check expects a running Gravaai window (the jpackage app image, plan §5.19); the
 * screenshot of that step is the proof the ported {@code findWindow} descends into COSMIC frames.
 */
@EnabledIfSystemProperty(named = "gravaai.smoke", matches = "true")
@Timeout(180)
class PlatformSmokeTest {

	private static final System.Logger LOG = System.getLogger("gravaai.platform.smoke");
	private static final Path OUTPUT = Path.of("/tmp/gravaai-platform-smoke");

	@Test
	@DisplayName("smoke: tray icon, recording icon and notification appear on COSMIC")
	void trayAndNotificationSmoke() throws Exception {
		Path repoRoot = repoRoot();
		TrayIcon icon = TrayIcon.load(repoRoot.resolve("icons/icons/png/32x32.png"));
		TrayIcon recordingIcon = TrayIcon.load(classpathPath("/icons/rec-button.png"));
		AtomicInteger actions = new AtomicInteger();
		TrayMenu.Actions trayActions = new TrayMenu.Actions(actions::incrementAndGet, actions::incrementAndGet,
				actions::incrementAndGet, actions::incrementAndGet);

		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(Tray.isSupported(bus), "no org.kde.StatusNotifierWatcher on the bus");
			Tray tray = new Tray(bus, icon, recordingIcon, trayActions);
			try {
				tray.install();
				Thread.sleep(1500);
				Path idle = screenshot("tray-idle");

				tray.setRecording(true, "Display 1");
				Thread.sleep(1500);
				Path recording = screenshot("tray-recording");
				tray.setRecording(false);

				Notifications notifications = new Notifications(bus);
				notifications.notify("Gravaai", "Platform smoke: tray, menu and notifications work");
				Thread.sleep(1200);
				Path notification = screenshot("notification");

				assertScreenshot(idle);
				assertNotEquals(0, Files.size(idle), "idle tray screenshot is empty");
				assertScreenshot(recording);
				assertScreenshot(notification);
			} finally {
				tray.close();
			}
		}
	}

	@Test
	@DisplayName("smoke: FileManager1 reveals a file in the COSMIC file manager")
	void fileManagerRevealSmoke() throws Exception {
		Path file = Files.createTempFile("gravaai-reveal-smoke-", ".txt");
		Files.writeString(file, "reveal me");
		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(FileManager.isAvailable(bus), "no org.freedesktop.FileManager1 on the bus");
			FileManager fileManager = new FileManager(bus);
			fileManager.reveal(file);
			Thread.sleep(1500);
			Path screenshot = screenshot("file-manager-reveal");
			assertScreenshot(screenshot);
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	@DisplayName("smoke: X11Bridge finds the running JavaFX window by title")
	void x11BridgeFindsRunningAppWindow() throws Exception {
		assertTrue(System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank(),
				"run the smoke with DISPLAY set");
		try (X11Bridge bridge = X11Bridge.open()) {
			assertNotEquals(0, bridge.rootWindow());
			long window = bridge.findWindow("Gravaai");
			Assumptions.assumeTrue(window != 0,
					"no running Gravaai window: start the app image before this smoke (plan §7.1 P1.4)");
			LOG.log(System.Logger.Level.INFO, "smoke: X11Bridge found the Gravaai window 0x"
					+ Long.toHexString(window) + " under root 0x" + Long.toHexString(bridge.rootWindow()));
			Path screenshot = screenshot("x11-window-found");
			assertScreenshot(screenshot);
		}
	}

	private static void assertScreenshot(Path screenshot) {
		assertTrue(Files.isRegularFile(screenshot), "missing screenshot " + screenshot);
	}

	private static Path screenshot(String name) throws Exception {
		Files.createDirectories(OUTPUT);
		ProcessResult result = ProcessRunner
				.of("cosmic-screenshot", "--interactive=false", "--notify=false", "-s", OUTPUT.toString())
				.timeout(Duration.ofSeconds(60))
				.run();
		assertTrue(result.succeeded(), "cosmic-screenshot failed: " + result.stderr());
		String printed = result.stdout().strip();
		assertFalse(printed.isBlank(), "cosmic-screenshot did not print a file name");
		Path source = Path.of(printed.lines().findFirst().orElseThrow());
		Path target = OUTPUT.resolve(name + ".png");
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(source);
		LOG.log(System.Logger.Level.INFO, "smoke: screenshot " + name + " -> " + target);
		return target;
	}

	private static Path repoRoot() {
		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
			if (Files.isRegularFile(candidate.resolve("icons/icons/png/32x32.png"))
					&& Files.isRegularFile(candidate.resolve("docs/java-rewrite-plan.md"))) {
				return candidate;
			}
		}
		throw new IllegalStateException("could not locate the repository root with the icon assets from "
				+ directory);
	}

	/**
	 * A test resource as a file path. The tray PNGs live on the test classpath instead of
	 * {@code public/}, which is removed in P3.6.
	 */
	private static Path classpathPath(String resource) throws Exception {
		java.net.URL url = PlatformSmokeTest.class.getResource(resource);
		if (url == null) {
			throw new IllegalStateException("missing test resource " + resource);
		}
		return Path.of(url.toURI());
	}
}
