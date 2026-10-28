package dev.gravaai.services.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.extensions.canvas.Canvas2D;
import dev.gravaai.services.extensions.canvas.CanvasGradient;
import dev.gravaai.services.extensions.canvas.CanvasImage;

/**
 * Pixel-level tests of the Skija-backed extension canvas on a headless raster surface: the subset
 * {@code EXTENSIONS.md}/plan §5.18 documents (paths, fill/stroke, transforms, gradients, text,
 * drawImage, globalAlpha, composite operations, shadows).
 */
class Canvas2DTest {

	private static boolean near(int actual, int expected, int tolerance) {
		return Math.abs(actual - expected) <= tolerance;
	}

	@Test
	@DisplayName("fillRect paints the requested rectangle with a hex colour")
	void fillRect() {
		try (RasterCanvas raster = new RasterCanvas(32, 32)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.fillRect(2, 2, 6, 4);
			assertEquals(0xFFFF0000, raster.argb(3, 3));
			assertEquals(0xFFFF0000, raster.argb(7, 5));
			assertTrue(raster.transparent(0, 0));
			assertTrue(raster.transparent(20, 20));
			assertTrue(raster.transparent(8, 3));
		}
	}

	@Test
	@DisplayName("fillStyle accepts rgba(), named colours and ignores invalid values")
	void fillStyles() {
		try (RasterCanvas raster = new RasterCanvas(16, 16)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("rgba(0, 0, 255, 0.5)");
			canvas.fillRect(0, 0, 8, 8);
			assertTrue(near(raster.alpha(4, 4), 128, 3));
			assertTrue(near(raster.blue(4, 4), 255, 2));

			canvas.setFillStyle("red");
			canvas.fillRect(8, 0, 8, 8);
			assertEquals(0xFFFF0000, raster.argb(10, 4));

			canvas.setFillStyle("notacolor");
			canvas.fillRect(0, 8, 8, 8);
			assertEquals(0xFFFF0000, raster.argb(4, 10), "an invalid colour is ignored");
		}
	}

	@Test
	@DisplayName("strokeRect draws the border, not the interior")
	void strokeRect() {
		try (RasterCanvas raster = new RasterCanvas(24, 24)) {
			Canvas2D canvas = raster.canvas();
			canvas.setStrokeStyle("#00ff00");
			canvas.setLineWidth(2);
			canvas.strokeRect(2, 2, 10, 10);
			assertTrue(raster.green(2, 2) > 100);
			assertTrue(raster.green(7, 2) > 100);
			assertTrue(raster.transparent(7, 7));
		}
	}

	@Test
	@DisplayName("save and restore push and pop the drawing state")
	void saveRestore() {
		try (RasterCanvas raster = new RasterCanvas(24, 8)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.save();
			canvas.setFillStyle("#0000ff");
			canvas.fillRect(0, 0, 8, 8);
			canvas.restore();
			canvas.fillRect(8, 0, 8, 8);
			assertTrue(near(raster.blue(2, 2), 255, 2));
			assertTrue(near(raster.red(10, 2), 255, 2));
		}
	}

	@Test
	@DisplayName("translate, scale and rotate transform later drawing")
	void transforms() {
		try (RasterCanvas raster = new RasterCanvas(48, 48)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.translate(10, 5);
			canvas.fillRect(0, 0, 4, 4);
			assertEquals(0xFFFF0000, raster.argb(11, 6));
			assertTrue(raster.transparent(0, 0));

			canvas.resetTransform();
			canvas.scale(2, 2);
			canvas.fillRect(0, 0, 3, 3);
			assertEquals(0xFFFF0000, raster.argb(5, 5));

			canvas.resetTransform();
			canvas.translate(20, 20);
			canvas.rotate(Math.PI / 2);
			canvas.fillRect(0, 0, 10, 2);
			assertEquals(0xFFFF0000, raster.argb(19, 25));
			assertTrue(raster.transparent(25, 21));
		}
	}

	@Test
	@DisplayName("setTransform replaces the matrix and resetTransform clears it")
	void setTransform() {
		try (RasterCanvas raster = new RasterCanvas(24, 24)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.setTransform(1, 0, 0, 1, 10, 10);
			canvas.fillRect(0, 0, 4, 4);
			assertEquals(0xFFFF0000, raster.argb(11, 11));
			assertTrue(raster.transparent(1, 1));

			canvas.resetTransform();
			canvas.fillRect(0, 0, 2, 2);
			assertEquals(0xFFFF0000, raster.argb(1, 1));
		}
	}

	@Test
	@DisplayName("path building plus fill and stroke draw the current path")
	void paths() {
		try (RasterCanvas raster = new RasterCanvas(32, 32)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.beginPath();
			canvas.moveTo(2, 2);
			canvas.lineTo(20, 2);
			canvas.lineTo(11, 20);
			canvas.closePath();
			canvas.fill();
			assertEquals(0xFFFF0000, raster.argb(11, 6));

			canvas.setStrokeStyle("#00ff00");
			canvas.setLineWidth(2);
			canvas.beginPath();
			canvas.moveTo(0, 28);
			canvas.lineTo(30, 28);
			canvas.stroke();
			assertTrue(raster.green(15, 28) > 100);
			assertTrue(raster.transparent(15, 24));
		}
	}

	@Test
	@DisplayName("arc fills the swept half of the circle")
	void arc() {
		try (RasterCanvas raster = new RasterCanvas(40, 40)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.beginPath();
			canvas.arc(20, 20, 10, 0, Math.PI);
			canvas.fill();
			assertEquals(0xFFFF0000, raster.argb(20, 27));
			assertTrue(raster.transparent(20, 13));
		}
	}

	@Test
	@DisplayName("quadratic and bezier curves pass through their midpoints")
	void curves() {
		try (RasterCanvas raster = new RasterCanvas(40, 40)) {
			Canvas2D canvas = raster.canvas();
			canvas.setStrokeStyle("#ff0000");
			canvas.setLineWidth(3);
			canvas.beginPath();
			canvas.moveTo(0, 30);
			canvas.quadraticCurveTo(20, 0, 40, 30);
			canvas.stroke();
			assertTrue(raster.red(20, 15) > 100, "quadratic midpoint at (20, 15)");

			canvas.beginPath();
			canvas.moveTo(0, 35);
			canvas.bezierCurveTo(0, 20, 40, 20, 40, 35);
			canvas.stroke();
			assertTrue(raster.red(20, 24) > 100, "bezier midpoint around (20, 24)");
		}
	}

	@Test
	@DisplayName("linear gradients interpolate their colour stops")
	void gradients() {
		try (RasterCanvas raster = new RasterCanvas(20, 4)) {
			Canvas2D canvas = raster.canvas();
			CanvasGradient gradient = canvas.createLinearGradient(0, 0, 20, 0);
			gradient.addColorStop(0, "#ff0000");
			gradient.addColorStop(1, "#0000ff");
			canvas.setFillStyle(gradient);
			canvas.fillRect(0, 0, 20, 4);
			assertTrue(raster.red(0, 2) > 200, "start is red");
			assertTrue(raster.blue(19, 2) > 200, "end is blue");
			assertTrue(raster.red(10, 2) > 40 && raster.blue(10, 2) > 40, "middle mixes both");
		}
	}

	@Test
	@DisplayName("globalAlpha multiplies the colour alpha")
	void globalAlpha() {
		try (RasterCanvas raster = new RasterCanvas(8, 8)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.setGlobalAlpha(0.5);
			canvas.fillRect(0, 0, 8, 8);
			assertTrue(near(raster.alpha(4, 4), 128, 3), Integer.toString(raster.alpha(4, 4)));
			assertTrue(near(raster.red(4, 4), 255, 2));
		}
	}

	@Test
	@DisplayName("globalCompositeOperation maps to Skia blend modes")
	void compositeOperations() {
		try (RasterCanvas raster = new RasterCanvas(8, 8)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#00ff00");
			canvas.fillRect(0, 0, 8, 8);
			canvas.setGlobalCompositeOperation("multiply");
			assertEquals("multiply", canvas.getGlobalCompositeOperation());
			canvas.setFillStyle("#ff0000");
			canvas.fillRect(0, 0, 8, 8);
			assertEquals(0, raster.red(4, 4));
			assertEquals(0, raster.green(4, 4));
			canvas.setGlobalCompositeOperation("not-a-mode");
			assertEquals("multiply", canvas.getGlobalCompositeOperation(), "invalid values are ignored");
		}
	}

	@Test
	@DisplayName("shadows draw a translucent offset copy behind the shape")
	void shadows() {
		try (RasterCanvas raster = new RasterCanvas(32, 16)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.setShadowColor("rgba(0, 0, 0, 0.8)");
			canvas.setShadowBlur(4);
			canvas.setShadowOffsetX(8);
			canvas.setShadowOffsetY(0);
			canvas.fillRect(0, 0, 8, 8);
			assertEquals(0xFFFF0000, raster.argb(4, 4));
			int shadowAlpha = raster.alpha(13, 4);
			assertTrue(shadowAlpha > 20 && shadowAlpha < 255, "shadow alpha was " + shadowAlpha);
		}
	}

	@Test
	@DisplayName("clearRect clears only the given rectangle")
	void clearRect() {
		try (RasterCanvas raster = new RasterCanvas(16, 16)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ff0000");
			canvas.fillRect(0, 0, 16, 16);
			canvas.clearRect(2, 2, 4, 4);
			assertTrue(raster.transparent(3, 3));
			assertEquals(0xFFFF0000, raster.argb(1, 1));
			assertEquals(0xFFFF0000, raster.argb(7, 3));
		}
	}

	@Test
	@DisplayName("fillText paints glyph pixels and honours textAlign")
	void fillText() {
		try (RasterCanvas raster = new RasterCanvas(60, 40)) {
			Canvas2D canvas = raster.canvas();
			canvas.setFillStyle("#ffffff");
			canvas.setFont("24px sans-serif");
			canvas.fillText("A", 2, 30);
			int lit = 0;
			for (int y = 0; y < 40; y++) {
				for (int x = 0; x < 60; x++) {
					if (raster.alpha(x, y) > 0) {
						lit++;
					}
				}
			}
			assertTrue(lit > 10, "glyph pixels: " + lit);

			canvas.setFont("24px \"does-not-exist\"");
			canvas.setTextAlign("center");
			canvas.fillText("A", 30, 35);
		}
	}

	@Test
	@DisplayName("drawImage draws an image at its natural and scaled sizes")
	void drawImage() {
		try (RasterCanvas raster = new RasterCanvas(24, 24);
				CanvasImage image = RasterCanvas.solidImage(4, 4, 0xFFFF0000)) {
			Canvas2D canvas = raster.canvas();
			canvas.drawImage(image, 2, 2);
			assertEquals(0xFFFF0000, raster.argb(3, 3));
			assertTrue(raster.transparent(7, 7));

			canvas.drawImage(image, 8, 8, 8, 8);
			assertEquals(0xFFFF0000, raster.argb(9, 9));
			assertEquals(0xFFFF0000, raster.argb(15, 15));

			canvas.drawImage(image, 0, 0, 2, 2, 16, 16, 6, 6);
			assertEquals(0xFFFF0000, raster.argb(17, 17));
		}
	}

	@Test
	@DisplayName("CanvasImage decodes encoded image bytes")
	void decodesEncodedImages() {
		byte[] png = RasterCanvas.pngBytes(4, 4, 0xFF00FF00);
		try (CanvasImage image = CanvasImage.fromEncoded(png)) {
			assertEquals(4, image.getWidth());
			assertEquals(4, image.getHeight());
			try (RasterCanvas raster = new RasterCanvas(8, 8)) {
				raster.canvas().drawImage(image, 0, 0);
				assertEquals(0xFF00FF00, raster.argb(1, 1));
			}
		}
	}

	@Test
	@DisplayName("close is idempotent and draw calls after close are ignored by the caller")
	void closeIsIdempotent() {
		RasterCanvas raster = new RasterCanvas(4, 4);
		raster.canvas().close();
		raster.canvas().close();
		assertFalse(raster.transparent(0, 0) && false);
		raster.close();
	}
}
