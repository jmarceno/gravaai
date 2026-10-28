package dev.gravaai.ui.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.ui.editor.EditorWallpapers;

/**
 * Regression gate for the Electron assets migrated into {@code gravaai-ui} resources (P3.6).
 *
 * <p>Every path here was a {@code public/} file in the Electron tree. The Java app loads it from
 * the classpath, so a missing resource is a silent visual regression (tray recording icon,
 * announcement banner) or a black export background.
 */
class BundledAssetsTest {

	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

	private static byte[] bytes(String resource) throws IOException {
		try (InputStream stream = BundledAssetsTest.class.getResourceAsStream(resource)) {
			assertNotNull(stream, "missing bundled resource " + resource);
			return stream.readAllBytes();
		}
	}

	private static String text(String resource) throws IOException {
		return new String(bytes(resource), StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("the tray recording icon (public/rec-button.png) is packaged for AppIcons")
	void trayRecordingIconIsBundled() throws IOException {
		byte[] png = bytes("/app-icons/rec-button.png");
		assertArrayEquals(PNG_MAGIC, java.util.Arrays.copyOf(png, 4));
		assertTrue(png.length > 0);
	}

	@Test
	@DisplayName("the default tray icon is packaged")
	void trayDefaultIconIsBundled() throws IOException {
		byte[] png = bytes("/app-icons/gravaai-32.png");
		assertArrayEquals(PNG_MAGIC, java.util.Arrays.copyOf(png, 4));
	}

	@Test
	@DisplayName("the announcement banner placeholders (public/announcements/*.svg) are packaged")
	void announcementPlaceholdersAreBundled() throws IOException {
		for (String name : new String[] {"placeholder-1.svg", "placeholder-2.svg"}) {
			String svg = text("/ui/dashboard/announcements/" + name);
			assertTrue(svg.startsWith("<svg "), name + " is not an SVG document");
		}
	}

	@Test
	@DisplayName("every built-in image wallpaper has its full-size, thumbnail and preview copy")
	void imageWallpapersAreBundled() throws IOException {
		for (String file : EditorWallpapers.IMAGE_FILES) {
			assertTrue(bytes("/ui/editor/wallpapers/" + file).length > 0, "full-size " + file);
			assertTrue(bytes("/ui/editor/wallpapers/thumbs/" + file).length > 0, "thumb " + file);
			assertTrue(bytes("/ui/editor/wallpapers/previews/" + file).length > 0, "preview " + file);
		}
	}

	@Test
	@DisplayName("the video wallpaper has its clip and its poster thumb/preview")
	void videoWallpaperIsBundled() throws IOException {
		for (EditorWallpapers.Tile tile : EditorWallpapers.VIDEO_TILES) {
			String name = tile.value().substring(tile.value().lastIndexOf('/') + 1);
			assertTrue(bytes("/ui/editor/wallpapers/" + name).length > 0, "clip " + name);
			String poster = name.replaceFirst("\\.[^.]+$", ".jpg");
			assertTrue(bytes("/ui/editor/wallpapers/thumbs/" + poster).length > 0, "thumb " + poster);
			assertTrue(bytes("/ui/editor/wallpapers/previews/" + poster).length > 0,
					"preview " + poster);
		}
	}
}
