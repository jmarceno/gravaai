package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSS wallpaper parser ported from {@code modernFrameRenderer.setupBackground}, including
 * the export-side quirks (plan §8.5: no cleanup while porting).
 */
class BackgroundTest {

	@Test
	@DisplayName("parses flat CSS colours")
	void parsesFlatColours() {
		assertEquals(new Background.Colour(0xFFFF0000), Background.parse("#ff0000"));
		assertEquals(new Background.Colour(0xFF00FF00), Background.parse("#0f0"));
		assertEquals(new Background.Colour(0x80010203), Background.parse("#80010203"));
		assertEquals(new Background.Colour(0xFFFF0000), Background.parse("rgb(255, 0, 0)"));
		assertEquals(new Background.Colour(0x80FF0000),
				Background.parse("rgba(255, 0, 0, 0.5)"));
		assertEquals(new Background.Colour(0x00000000), Background.parse("transparent"));
	}

	@Test
	@DisplayName("unknown colours fall back to black like an untouched canvas")
	void unknownColourFallsBackToBlack() {
		assertEquals(Background.black(), Background.parse("#zzz"));
		assertEquals(Background.black(), Background.parse(""));
	}

	@Test
	@DisplayName("parses a two-stop linear gradient without a direction token")
	void parsesPlainLinearGradient() {
		Background background = Background.parse("linear-gradient(#ff0000, #0000ff)");
		Background.LinearGradient gradient =
				assertInstanceOf(Background.LinearGradient.class, background);
		assertArrayEquals(new int[] {0xFFFF0000, 0xFF0000FF}, gradient.argbColours());
		assertArrayEquals(new float[] {0.0f, 1.0f}, gradient.positions());
	}

	@Test
	@DisplayName("counts the skipped direction token in the stop positions (export quirk)")
	void directionTokenCountsForPositions() {
		Background plain = Background.parse("linear-gradient(120deg, #d4fc79 0%, #96e6a1 100%)");
		Background.LinearGradient directioned =
				assertInstanceOf(Background.LinearGradient.class, plain);
		assertArrayEquals(new float[] {0.5f, 1.0f}, directioned.positions());
		assertArrayEquals(new int[] {0xFFD4FC79, 0xFF96E6A1}, directioned.argbColours());

		Background direction = Background.parse("linear-gradient(to top, #a18cd1 0%, #fbc2eb 100%)");
		assertArrayEquals(new float[] {0.5f, 1.0f},
				assertInstanceOf(Background.LinearGradient.class, direction).positions());
	}

	@Test
	@DisplayName("rgba() stops split on commas and fall back to black, like the browser throw")
	void rgbaStopsFallBackToBlack() {
		// The export parser splits the gradient arguments on every comma; the pieces of an
		// rgba(...) stop then match only "rgba", which is not a CSS colour. The browser throws,
		// the export catches and paints black -> parity requires the same fallback.
		assertEquals(Background.black(), Background.parse(
				"linear-gradient(111.6deg, rgba(114,167,232,1) 9.4%,"
						+ " rgba(253,129,82,1) 43.9%)"));
	}

	@Test
	@DisplayName("radial gradients are black because their first part is the shape token")
	void radialGradientsFallBackToBlack() {
		assertEquals(Background.black(), Background.parse(
				"radial-gradient( circle farthest-corner at 3.2% 49.6%,"
						+ " rgba(80,12,139,0.87) 0%, rgba(161,10,144,0.72) 83.6% )"));
	}

	@Test
	@DisplayName("a single-stop gradient collapses to its colour")
	void singleStopCollapses() {
		assertEquals(new Background.Colour(0xFFAABBCC),
				Background.parse("linear-gradient(#aabbcc)"));
	}

	@Test
	@DisplayName("image wallpapers must be resolved to Background.Image by the caller")
	void imageSourcesAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> Background.parse("/wallpapers/tahoe-light.jpg"));
		assertThrows(IllegalArgumentException.class,
				() -> Background.parse("file:///tmp/wallpaper.png"));
	}

	@Test
	@DisplayName("fromWallpaper is the public UI entry point for CSS wallpapers")
	void fromWallpaperParsesCss() {
		assertEquals(new Background.Colour(0xFF112233), Background.fromWallpaper("#112233"));
		assertInstanceOf(Background.LinearGradient.class,
				Background.fromWallpaper("linear-gradient(#ff0000, #0000ff)"));
		assertThrows(IllegalArgumentException.class,
				() -> Background.fromWallpaper("/wallpapers/tahoe-light.jpg"));
	}

	@Test
	@DisplayName("Background.Image requires non-empty bytes")
	void imageRequiresBytes() {
		assertThrows(IllegalArgumentException.class, () -> new Background.Image(new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> new Background.Image(null));
	}
}
