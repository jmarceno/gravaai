package dev.gravaai.services.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.extensions.canvas.CanvasColor;

/** CSS colour parsing used by the extension canvas ({@code fillStyle}/{@code strokeStyle}). */
class CanvasColorTest {

	@Test
	@DisplayName("parse reads three and six digit hex colours")
	void hexColors() {
		assertEquals(0xFFFF0000, CanvasColor.parse("#f00"));
		assertEquals(0xFFFF0000, CanvasColor.parse("#FF0000"));
		assertEquals(0xFF00FF00, CanvasColor.parse("#0f0"));
		assertEquals(0xFF123456, CanvasColor.parse("#123456"));
	}

	@Test
	@DisplayName("parse reads four and eight digit hex colours with alpha")
	void hexAlphaColors() {
		assertEquals(0x88FF0000, CanvasColor.parse("#f008"));
		assertEquals(0x80FF0000, CanvasColor.parse("#ff000080"));
	}

	@Test
	@DisplayName("parse reads rgb() and rgba() in comma and space syntax")
	void rgbColors() {
		assertEquals(0xFFFF0000, CanvasColor.parse("rgb(255, 0, 0)"));
		assertEquals(0xFF0000FF, CanvasColor.parse("rgba(0, 0, 255, 1)"));
		assertEquals(0x80FF0000, CanvasColor.parse("rgba(255, 0, 0, 0.5)"));
		assertEquals(0xFFFF0000, CanvasColor.parse("rgb(100% 0% 0%)"));
		assertEquals(0x800000FF, CanvasColor.parse("rgb(0 0 255 / 50%)"));
	}

	@Test
	@DisplayName("parse reads the documented named colours")
	void namedColors() {
		assertEquals(0xFFFF0000, CanvasColor.parse("red"));
		assertEquals(0xFFFFFFFF, CanvasColor.parse("WHITE"));
		assertEquals(0x00000000, CanvasColor.parse("transparent"));
	}

	@Test
	@DisplayName("parse returns null for anything that is not a colour")
	void invalidColors() {
		for (String value : new String[] {
				null, "", "notacolor", "#12", "#12345", "#1234567", "rgb(1,2)", "rgb(a,b,c)", "rgb(1,2,3,4,5)"}) {
			assertNull(CanvasColor.parse(value), String.valueOf(value));
		}
	}

	@Test
	@DisplayName("withAlpha replaces the alpha channel and clamps it")
	void withAlpha() {
		assertEquals(0x80FF0000, CanvasColor.withAlpha(0xFFFF0000, 0.5));
		assertEquals(0xFFFF0000, CanvasColor.withAlpha(0xFFFF0000, 1.5));
		assertEquals(0x00FF0000, CanvasColor.withAlpha(0xFFFF0000, -1));
		assertEquals(0.5, CanvasColor.alpha(0x80FF0000), 0.002);
	}

	@Test
	@DisplayName("toCss renders rgba() with the alpha channel")
	void toCss() {
		assertEquals("rgba(255, 0, 0, 0.502)", CanvasColor.toCss(0x80FF0000));
		assertEquals("rgba(18, 52, 86, 1.0)", CanvasColor.toCss(0xFF123456));
		assertTrue(CanvasColor.toCss(0x00000000).startsWith("rgba(0, 0, 0, 0"));
	}
}
