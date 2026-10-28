package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;

class CursorAssetsTest {

	@Test
	@DisplayName("every cursor set loads a visible, anchored sprite for every supported key")
	void everyStyleAndKeyLoads() {
		CursorAssets assets = CursorAssets.get();
		for (CursorStyle style : CursorStyle.values()) {
			for (String key : CursorAssets.SUPPORTED_CURSOR_KEYS) {
				CursorAssets.Asset asset = assets.resolve(style, key);
				String label = style.wire() + "/" + key;
				assertTrue(asset.anchorX() >= 0 && asset.anchorX() <= 1, label);
				assertTrue(asset.anchorY() >= 0 && asset.anchorY() <= 1, label);
				assertTrue(asset.aspectRatio() > 0, label);
				assertTrue(visiblePixels(asset) > 100, label + " is blank");
			}
		}
	}

	@Test
	@DisplayName("unknown cursor types fall back to the arrow sprite")
	void unknownTypeFallsBackToArrow() {
		CursorAssets assets = CursorAssets.get();
		assertSame(assets.resolve(CursorStyle.TAHOE, "arrow"),
				assets.resolve(CursorStyle.TAHOE, "wait"));
		assertSame(assets.resolve(CursorStyle.TAHOE, "arrow"),
				assets.resolve(CursorStyle.CUSTOM, null));
	}

	@Test
	@DisplayName("the dot style asset is the 112px canvas anchored at its centre")
	void dotAsset() {
		CursorAssets.Asset dot = CursorAssets.get().resolve(CursorStyle.DOT, "pointer");
		assertEquals(112, dot.image().getWidth());
		assertEquals(0.5, dot.anchorX());
		assertEquals(0.5, dot.anchorY());
	}

	@Test
	@DisplayName("natural size follows width/height (px, pt) and falls back to the viewBox")
	void naturalSize() {
		assertArrayEquals(new double[] {618, 958},
				CursorAssets.naturalSize("<svg width=\"618\" height=\"958\" viewBox=\"0 0 1 1\">"));
		assertArrayEquals(new double[] {32 * 96 / 72.0, 32 * 96 / 72.0},
				CursorAssets.naturalSize(
						"<svg width=\"32pt\" height=\"32pt\" viewBox=\"0 0 32 32\">"));
		assertArrayEquals(new double[] {32, 32},
				CursorAssets.naturalSize("<svg x=\"0px\" viewBox=\"0 0 32 32\">"));
	}

	private static int visiblePixels(CursorAssets.Asset asset) {
		int width = asset.image().getWidth();
		int height = asset.image().getHeight();
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(new ImageInfo(width, height, ColorType.RGBA_8888,
					ColorAlphaType.UNPREMUL));
			asset.image().readPixels(bitmap, 0, 0);
			byte[] rgba = bitmap.readPixels();
			int count = 0;
			for (int index = 3; index < rgba.length; index += 4) {
				if ((rgba[index] & 0xFF) > 32) {
					count++;
				}
			}
			return count;
		}
	}
}
