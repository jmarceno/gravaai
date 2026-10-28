package dev.gravaai.platform.filemanager;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.gravaai.platform.dbus.SessionBus;

@Timeout(30)
class FileManagerTest {

	@Test
	@DisplayName("turns a path into a percent-encoded file URI")
	void buildsFileUri() {
		String uri = FileManager.toFileUri(Path.of("/tmp/a b/héllo.txt"));

		assertTrue(uri.startsWith("file:///tmp/a%20b/"), uri);
		assertTrue(uri.contains("h%C3%A9llo"), uri);
	}

	@Test
	@DisplayName("creates the client when FileManager1 runs")
	void createsClientWhenFileManagerRuns() throws Exception {
		try (SessionBus bus = SessionBus.connect()) {
			Assumptions.assumeTrue(FileManager.isAvailable(bus), "no org.freedesktop.FileManager1 on the bus");

			new FileManager(bus);
		}
	}
}
