package dev.gravaai.platform.tray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrayIconTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("converts ARGB pixels into ARGB32 network byte order")
	void convertsToNetworkByteOrder() {
		TrayIcon icon = TrayIcon.fromArgb(2, 1, new int[] {0xFF112233, 0x80445566});

		assertArrayEquals(new byte[] {(byte) 0xFF, 0x11, 0x22, 0x33, (byte) 0x80, 0x44, 0x55, 0x66},
				icon.argb());
		assertEquals(2, icon.width());
		assertEquals(1, icon.height());
	}

	@Test
	@DisplayName("round-trips a PNG through ImageIO")
	void loadsPng() throws Exception {
		TrayIcon original = TrayIcon.fromArgb(3, 2, new int[] {
				0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
				0x01020304, 0x00000000, 0xFFFFFFFF});
		Path png = tempDir.resolve("icon.png");
		original.save(png);

		TrayIcon loaded = TrayIcon.load(png);

		assertEquals(original.width(), loaded.width());
		assertEquals(original.height(), loaded.height());
		assertArrayEquals(original.argb(), loaded.argb());
	}

	@Test
	@DisplayName("builds the StatusNotifierItem icon pixmap")
	void buildsPixmap() {
		TrayIcon icon = TrayIcon.fromArgb(1, 1, new int[] {0xFF010203});

		IconPixmap pixmap = icon.toPixmap();

		assertEquals(1, pixmap.width);
		assertEquals(1, pixmap.height);
		assertArrayEquals(new byte[] {(byte) 0xFF, 0x01, 0x02, 0x03}, pixmap.data);
	}

	@Test
	@DisplayName("rejects a wrong-sized pixel buffer and a non-image file")
	void rejectsInvalidInput() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> TrayIcon.fromArgb(2, 2, new int[] {1}));
		assertThrows(IllegalArgumentException.class,
				() -> new TrayIcon(1, 1, new byte[] {1, 2, 3}));

		Path notAnImage = tempDir.resolve("not-an-image.txt");
		Files.writeString(notAnImage, "nope");
		assertThrows(java.io.IOException.class, () -> TrayIcon.load(notAnImage));
	}

	@Test
	@DisplayName("keeps a defensive copy of the pixel bytes")
	void keepsDefensiveCopy() {
		byte[] bytes = {1, 2, 3, 4};
		TrayIcon icon = new TrayIcon(1, 1, bytes);

		bytes[0] = 99;

		assertEquals(1, icon.argb()[0]);
		byte[] copy = icon.argbCopy();
		copy[1] = 99;
		assertEquals(2, icon.argb()[1]);
	}
}
