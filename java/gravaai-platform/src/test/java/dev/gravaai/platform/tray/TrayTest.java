package dev.gravaai.platform.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * Live tray test on the session bus: registers a real StatusNotifierItem, reads it back over a
 * second connection and drives the menu through {@code gdbus}, so the wire format of the tray
 * (properties, {@code u(ia{sv}av)} layouts, variants) is verified end to end.
 */
@Timeout(120)
class TrayTest {

	private static TrayIcon testIcon(int red, int green) {
		int[] pixels = new int[32 * 32];
		Arrays.fill(pixels, (0xFF << 24) | (red << 16) | (green << 8) | 0x80);
		return TrayIcon.fromArgb(32, 32, pixels);
	}

	@Test
	@DisplayName("installs a StatusNotifierItem with a dbusmenu and switches to recording state")
	void installsAndServesTray() throws Exception {
		Path gdbus = Path.of("/usr/bin/gdbus");
		Path dbusSend = Path.of("/usr/bin/dbus-send");
		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(Tray.isSupported(bus), "no org.kde.StatusNotifierWatcher on the bus");
			Assumptions.assumeTrue(Files.isExecutable(gdbus), "gdbus is not installed");
			Assumptions.assumeTrue(Files.isExecutable(dbusSend), "dbus-send is not installed");

			AtomicInteger showOverlay = new AtomicInteger();
			AtomicInteger openGravaai = new AtomicInteger();
			AtomicInteger stopRecording = new AtomicInteger();
			AtomicInteger quit = new AtomicInteger();
			TrayMenu.Actions actions = new TrayMenu.Actions(showOverlay::incrementAndGet,
					openGravaai::incrementAndGet, stopRecording::incrementAndGet, quit::incrementAndGet);

			try (SessionBus probe = SessionBus.connect()) {
				Tray tray = new Tray(bus, testIcon(0x20, 0x40), testIcon(0xE0, 0x10), actions);
				try {
					tray.install();
					assertTrue(tray.isInstalled());

					Properties properties = probe.connection().getRemoteObject(bus.uniqueName(),
							Tray.SNI_PATH, Properties.class, false);
					Map<String, Variant<?>> all = properties.GetAll(Tray.SNI_INTERFACE);
					assertEquals(Tray.CATEGORY, all.get("Category").getValue());
					assertEquals(Tray.ITEM_ID, all.get("Id").getValue());
					assertEquals(Tray.APP_NAME, all.get("Title").getValue());
					assertEquals(Tray.STATUS_ACTIVE, all.get("Status").getValue());
					assertEquals(Tray.MENU_PATH, all.get("Menu").getValue().toString());
					assertPixmap(all.get("IconPixmap").getValue(), 0xFF, 0x20);

					Properties watcher = probe.connection().getRemoteObject(Tray.WATCHER_SERVICE,
							Tray.WATCHER_PATH, Properties.class, false);
					Object registeredValue = watcher.Get("org.kde.StatusNotifierWatcher",
							"RegisteredStatusNotifierItems");
					List<?> registered = (List<?>) registeredValue;
					assertTrue(registered.stream().map(String::valueOf)
							.anyMatch(item -> item.contains(bus.uniqueName())),
							"watcher items: " + registered);

					String idleLayout = menuLayout(bus.uniqueName());
					assertTrue(idleLayout.contains(TrayMenu.LABEL_SHOW_OVERLAY), idleLayout);
					assertTrue(idleLayout.contains(TrayMenu.LABEL_OPEN_GRAVAAI), idleLayout);
					assertTrue(idleLayout.contains(TrayMenu.LABEL_QUIT), idleLayout);
					assertFalse(idleLayout.contains(TrayMenu.LABEL_STOP_RECORDING), idleLayout);

					// gdbus reads the method's introspection metadata and adapts its argument types.
					// Real hosts call the dbusmenu protocol directly, where GetLayout's parent id is a
					// signed int32. A uint32 declaration makes that call fail with UnknownMethod and
					// renders an empty menu in COSMIC/KDE, so exercise the exact wire signature too.
					String rawLayout = rawMenuLayout(bus.uniqueName());
					assertTrue(rawLayout.contains(TrayMenu.LABEL_SHOW_OVERLAY), rawLayout);
					assertTrue(rawLayout.contains(TrayMenu.LABEL_OPEN_GRAVAAI), rawLayout);
					assertTrue(rawLayout.contains(TrayMenu.LABEL_QUIT), rawLayout);
					assertTrue(rawLayout.contains("children-display"), rawLayout);

					String groupProperties = gdbus("call", "--session", "--dest", bus.uniqueName(),
							"--object-path", Tray.MENU_PATH, "--method",
							"com.canonical.dbusmenu.GetGroupProperties", "[0, 1, 2, 4, 5]", "[]");
					assertTrue(groupProperties.contains(TrayMenu.LABEL_SHOW_OVERLAY), groupProperties);
					assertTrue(groupProperties.contains(TrayMenu.LABEL_QUIT), groupProperties);
					assertTrue(groupProperties.contains("separator"), groupProperties);
					String showOverlayLabel = menuProperty(bus.uniqueName(),
							String.valueOf(TrayMenu.SHOW_OVERLAY_ID), "label");
					assertTrue(showOverlayLabel.contains(TrayMenu.LABEL_SHOW_OVERLAY), showOverlayLabel);
					assertTrue(menuProperty(bus.uniqueName(),
							String.valueOf(TrayMenu.SHOW_OVERLAY_ID), "enabled").contains("true"));
					assertTrue(menuProperty(bus.uniqueName(),
							String.valueOf(TrayMenu.SHOW_OVERLAY_ID), "visible").contains("true"));
					assertTrue(menuProperty(bus.uniqueName(),
							String.valueOf(TrayMenu.SHOW_OVERLAY_ID), "type").contains("standard"));
					assertTrue(menuProperty(bus.uniqueName(), String.valueOf(TrayMenu.ROOT_ID),
							"children-display").contains("submenu"));
					assertTrue(gdbus("call", "--session", "--dest", bus.uniqueName(),
							"--object-path", Tray.MENU_PATH, "--method",
							"com.canonical.dbusmenu.AboutToShow", "0").contains("true"));
					String aboutGroup = gdbus("call", "--session", "--dest", bus.uniqueName(),
							"--object-path", Tray.MENU_PATH, "--method",
							"com.canonical.dbusmenu.AboutToShowGroup", "[0, 1]");
					assertTrue(aboutGroup.contains("[0, 1]"), aboutGroup);
					assertTrue(aboutGroup.contains("[]"), aboutGroup);
					String eventGroupErrors = gdbus("call", "--session", "--dest", bus.uniqueName(),
							"--object-path", Tray.MENU_PATH, "--method",
							"com.canonical.dbusmenu.EventGroup",
							"[(1, 'clicked', <uint32 0>, uint32 0)]");
					assertTrue(eventGroupErrors.contains("[]"), eventGroupErrors);
					assertEquals(1, showOverlay.get());

					tray.setRecording(true, "Display 1");
					assertEquals("Recording: Display 1", property(properties, "Title"));
					assertPixmap(property(properties, "IconPixmap"), 0xFF, 0xE0);
					String recordingLayout = menuLayout(bus.uniqueName());
					assertTrue(recordingLayout.contains(TrayMenu.LABEL_STOP_RECORDING), recordingLayout);

					// State equality includes sourceName: changing only the selected source must still
					// republish the SNI Title/tooltip.
					tray.setRecording(true, "Window 2");
					assertEquals("Recording: Window 2", property(properties, "Title"));

					call(bus.uniqueName(), Tray.SNI_PATH, "org.kde.StatusNotifierItem.Activate", "0", "0");
					assertEquals(1, stopRecording.get());

					tray.setRecording(false);
					assertEquals(Tray.APP_NAME, property(properties, "Title"));
					call(bus.uniqueName(), Tray.SNI_PATH, "org.kde.StatusNotifierItem.Activate", "0", "0");
					assertEquals(1, openGravaai.get());
					assertEquals(0, quit.get());
				} finally {
					tray.close();
				}
			}
		}
	}

	private static void assertPixmap(Object pixmapsValue, int expectedAlpha, int expectedRed) {
		List<?> pixmaps = (List<?>) pixmapsValue;
		assertEquals(1, pixmaps.size());
		Object[] pixmap = (Object[]) pixmaps.getFirst();
		assertEquals(32, ((Number) pixmap[0]).intValue());
		assertEquals(32, ((Number) pixmap[1]).intValue());
		byte[] data = toBytes(pixmap[2]);
		assertEquals(32 * 32 * 4, data.length);
		assertEquals(expectedAlpha, data[0] & 0xFF);
		assertEquals(expectedRed, data[1] & 0xFF);
	}

	/** dbus-java hands nested {@code ay} arrays back as a list of bytes; accept both forms. */
	private static byte[] toBytes(Object value) {
		if (value instanceof byte[] bytes) {
			return bytes;
		}
		List<?> list = (List<?>) value;
		byte[] bytes = new byte[list.size()];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = ((Number) list.get(index)).byteValue();
		}
		return bytes;
	}

	private static Object property(Properties properties, String name) {
		Object value = properties.GetAll(Tray.SNI_INTERFACE).get(name);
		return value instanceof Variant<?> variant ? variant.getValue() : value;
	}

	private static String menuLayout(String destination) throws Exception {
		return gdbus("call", "--session", "--dest", destination, "--object-path", Tray.MENU_PATH,
				"--method", "com.canonical.dbusmenu.GetLayout", "0", "1", "[]");
	}

	private static String menuProperty(String destination, String id, String name) throws Exception {
		return gdbus("call", "--session", "--dest", destination, "--object-path",
				Tray.MENU_PATH, "--method", "com.canonical.dbusmenu.GetProperty", id, name);
	}

	/** Calls GetLayout with the protocol's signed-int32 parent id, without gdbus introspection help. */
	private static String rawMenuLayout(String destination) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("dbus-send");
		command.add("--session");
		command.add("--print-reply");
		command.add("--dest=" + destination);
		command.add(Tray.MENU_PATH);
		command.add("com.canonical.dbusmenu.GetLayout");
		command.add("int32:" + TrayMenu.ROOT_ID);
		command.add("int32:-1");
		command.add("array:string:");
		ProcessResult result = ProcessRunner.of(command).timeout(Duration.ofSeconds(30)).run();
		assertTrue(result.succeeded(), "dbus-send GetLayout failed: " + result.stderr());
		return result.stdout();
	}

	private static void call(String destination, String objectPath, String method, String... arguments)
			throws Exception {
		List<String> command = new ArrayList<>(List.of("call", "--session", "--dest", destination,
				"--object-path", objectPath, "--method", method));
		command.addAll(Arrays.asList(arguments));
		gdbus(command.toArray(String[]::new));
	}

	private static String gdbus(String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("gdbus");
		command.addAll(Arrays.asList(arguments));
		ProcessResult result = ProcessRunner.of(command).timeout(Duration.ofSeconds(30)).run();
		assertTrue(result.succeeded(), "gdbus failed: " + result.stderr());
		return result.stdout();
	}
}
