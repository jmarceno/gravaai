package dev.gravaai.platform.portal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.gravaai.platform.dbus.SessionBus;

@Timeout(30)
class PortalTest {

	@Test
	@DisplayName("builds FileChooser options with filters, current folder and current name")
	void buildsFileChooserOptions() {
		Portal.FileDialogRequest request = new Portal.FileDialogRequest("Open video", true, false,
				List.of(new Portal.FileFilter("Videos", List.of("*.mp4", "*.mkv"))),
				Path.of("/home/user/Videos"), "clip.mp4");

		Map<String, Variant<?>> options = Portal.fileDialogOptions(request, "gravaai_1_2");

		assertEquals("gravaai_1_2", options.get("handle_token").getValue());
		assertEquals(Boolean.TRUE, options.get("multiple").getValue());
		assertFalse(options.containsKey("directory"));
		assertEquals("a(sa(us))", options.get("filters").getSig());
		byte[] currentFolder = (byte[]) options.get("current_folder").getValue();
		assertEquals(0, currentFolder[currentFolder.length - 1]);
		assertEquals("/home/user/Videos", new String(currentFolder, 0, currentFolder.length - 1, UTF_8));
		assertEquals("clip.mp4", options.get("current_name").getValue());
	}

	@Test
	@DisplayName("directory dialogs set the directory option")
	void directoryOptions() {
		Map<String, Variant<?>> options = Portal.fileDialogOptions(Portal.FileDialogRequest.directory("Pick"),
				"token");

		assertEquals(Boolean.TRUE, options.get("directory").getValue());
		assertFalse(options.containsKey("multiple"));
		assertFalse(options.containsKey("filters"));
	}

	@Test
	@DisplayName("predicts the portal request object path")
	void predictsRequestPath() {
		assertEquals("/org/freedesktop/portal/desktop/request/1_42/gravaai_1",
				Portal.predictedRequestPath("1_42", "gravaai_1"));
	}

	@Test
	@DisplayName("creates the portal client when xdg-desktop-portal runs")
	void createsClientWhenPortalRuns() throws Exception {
		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(Portal.isAvailable(bus), "no xdg-desktop-portal on the bus");

			try (Portal portal = new Portal(bus)) {
				assertTrue(portal.predictedRequestPath("token").endsWith("/token"));
			}
		}
	}
}
