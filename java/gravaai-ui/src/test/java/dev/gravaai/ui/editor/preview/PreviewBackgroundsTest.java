package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gravaai.media.compose.Background;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wallpaper string -> compositor Background resolution (P2.5). */
class PreviewBackgroundsTest {

	@DisplayName("bundled wallpapers load the 1280x720 preview bytes and are cached")
	@Test
	void bundledImage() {
		Background background = PreviewBackgrounds.of("/wallpapers/morning-tide.jpg");
		assertInstanceOf(Background.Image.class, background);
		assertTrue(((Background.Image) background).encodedImage().length > 1000);
		assertSame(background, PreviewBackgrounds.of("/wallpapers/morning-tide.jpg"));
	}

	@DisplayName("hex colours map to the compositor ARGB order")
	@Test
	void hexColour() {
		Background background = PreviewBackgrounds.of("#2fb3a3");
		assertInstanceOf(Background.Colour.class, background);
		assertEquals(0xFF2FB3A3, ((Background.Colour) background).argb());
	}

	@DisplayName("the editor gradient list maps to gradient stops")
	@Test
	void gradient() {
		Background background = PreviewBackgrounds.of(
				"linear-gradient(120deg, #d4fc79 0%, #96e6a1 100%)");
		assertInstanceOf(Background.LinearGradient.class, background);
		assertEquals(2, ((Background.LinearGradient) background).argbColours().length);
	}

	@DisplayName("unknown values and missing files fall back to the default wallpaper")
	@Test
	void fallbacks() {
		Background fallback = PreviewBackgrounds.of("/wallpapers/morning-tide.jpg");
		assertInstanceOf(Background.Image.class, fallback);
		byte[] expected = ((Background.Image) fallback).encodedImage();
		for (String value : new String[] {
				"/wallpapers/does-not-exist.jpg", "", "/tmp/no-such-file.jpg"}) {
			Background actual = PreviewBackgrounds.of(value);
			assertInstanceOf(Background.Image.class, actual, value);
			assertArrayEquals(expected, ((Background.Image) actual).encodedImage(), value);
		}
	}
}
