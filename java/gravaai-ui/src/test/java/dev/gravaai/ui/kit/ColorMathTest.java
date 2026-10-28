package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Colour conversions for the ColorPicker (color-picker.tsx uses HSB + hex strings). */
class ColorMathTest {

	@DisplayName("hex round trip keeps the app's accent colour")
	@Test
	void hexRoundTrip() {
		assertEquals("#2fb3a3", ColorMath.toHex(ColorMath.fromHex("#2fb3a3")));
		assertEquals("#2fb3a3", ColorMath.toHex(ColorMath.fromHex("2fb3a3")));
		assertEquals("#ffffff", ColorMath.toHex(ColorMath.fromHex("#fff")));
	}

	@DisplayName("translucent colours serialise as #rrggbbaa (Color.toString(\"hex\"))")
	@Test
	void alphaHex() {
		Color translucent = ColorMath.fromHex("#11223344");
		assertEquals(0x44 / 255.0, translucent.getOpacity(), 1e-4);
		assertEquals("#11223344", ColorMath.toHex(translucent));
	}

	@DisplayName("HSB conversions round trip within one channel step")
	@Test
	void hsbRoundTrip() {
		Color color = Color.hsb(200, 0.5, 0.25);
		ColorMath.Hsba hsb = ColorMath.toHsb(color);
		assertEquals(200, hsb.hue(), 1e-4);
		assertEquals(0.5, hsb.saturation(), 1e-4);
		assertEquals(0.25, hsb.brightness(), 1e-4);
		Color roundTrip = ColorMath.fromHsb(hsb);
		assertEquals(color.getRed(), roundTrip.getRed(), 1e-4);
		assertEquals(color.getGreen(), roundTrip.getGreen(), 1e-4);
		assertEquals(color.getBlue(), roundTrip.getBlue(), 1e-4);
	}

	@DisplayName("hue wraps and channels clamp")
	@Test
	void clampsHsb() {
		ColorMath.Hsba over = new ColorMath.Hsba(400, 2, -1, 2);
		Color wrapped = ColorMath.fromHsb(over);
		assertEquals(Color.hsb(40, 1, 0), wrapped);
	}

	@DisplayName("transparent parses, css output keeps alpha")
	@Test
	void cssOutput() {
		Color transparent = ColorMath.fromHex("transparent");
		assertEquals(0, transparent.getOpacity(), 1e-9);
		assertTrue(ColorMath.toCss(Color.web("#2fb3a3", 0.5)).startsWith("rgba(47, 179, 163"));
		assertEquals("#2fb3a3", ColorMath.toCss(Color.web("#2fb3a3")));
	}

	@DisplayName("invalid hex text is rejected")
	@Test
	void rejectsInvalid() {
		assertThrows(IllegalArgumentException.class, () -> ColorMath.fromHex("#zzzzzz"));
		assertThrows(IllegalArgumentException.class, () -> ColorMath.fromHex(null));
	}
}
