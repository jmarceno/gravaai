package dev.gravaai.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.storage.UserData;
import javafx.scene.image.Image;

/**
 * The packaged icon lookup used by the tray (plan §5.5, P3.6 asset migration).
 *
 * <p>The recording variant used to be {@code public/rec-button.png}; it now has to resolve from
 * the {@code app-icons/} resources of {@code gravaai-ui}, otherwise the tray silently keeps the
 * idle icon while recording.
 */
class AppIconsTest {

	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("both tray icons resolve and extract from the packaged resources")
	void trayIconsResolve() throws Exception {
		AppIcons icons = new AppIcons(new UserData(tempDir));
		for (String name : new String[] {"gravaai-32.png", "rec-button.png"}) {
			Path extracted = icons.loadOrNull(name);
			assertNotNull(extracted, "missing packaged icon " + name);
			assertTrue(Files.isRegularFile(extracted));
			assertArrayEquals(PNG_MAGIC,
					java.util.Arrays.copyOf(Files.readAllBytes(extracted), 4), name + " is not a PNG");
		}
	}

	@Test
	@DisplayName("a build without the resource reports null instead of failing")
	void missingIconIsNull() {
		AppIcons icons = new AppIcons(new UserData(tempDir.resolve("profile")));
		assertNull(icons.loadOrNull("does-not-exist.png"));
		assertNull(icons.loadImageOrNull("does-not-exist.png"));
	}

	@Test
	@DisplayName("stage/taskbar images load from the packaged resources at all preferred sizes")
	void stageImagesLoad() {
		AppIcons icons = new AppIcons(new UserData(tempDir.resolve("profile")));
		java.util.List<Image> images = icons.loadImages(
				"gravaai-32.png", "gravaai-128.png", "gravaai-256.png");
		assertEquals(3, images.size());
		assertEquals(32, images.get(0).getWidth());
		assertEquals(128, images.get(1).getWidth());
		assertEquals(256, images.get(2).getWidth());
	}
}
