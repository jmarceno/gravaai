package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.types.Rect;

/** The cover-fit layout maths (plan §5.8 stage 2). */
class LayoutMathTest {

	@Test
	@DisplayName("a wide source covers a square destination and overflows horizontally")
	void wideSourceIntoSquare() {
		LayoutMath.CoverFit fit = LayoutMath.coverFit(1920, 1080, Rect.makeXYWH(0, 0, 1000, 1000));
		assertEquals(1000.0 / 1080.0, fit.scale(), 1e-9);
		assertEquals(1000.0, fit.destinationHeight(), 1e-9);
		assertEquals(1777.7777777, fit.destinationWidth(), 1e-6);
		assertEquals((1000.0 - 1777.7777777) / 2.0, fit.destinationX(), 1e-6);
		assertEquals(0.0, fit.destinationY(), 1e-9);
	}

	@Test
	@DisplayName("a tall source covers a wide destination and overflows vertically")
	void tallSourceIntoWide() {
		LayoutMath.CoverFit fit = LayoutMath.coverFit(100, 200, Rect.makeXYWH(0, 0, 300, 100));
		assertEquals(3.0, fit.scale(), 1e-9);
		assertEquals(300.0, fit.destinationWidth(), 1e-9);
		assertEquals(600.0, fit.destinationHeight(), 1e-9);
		assertEquals(0.0, fit.destinationX(), 1e-9);
		assertEquals((100.0 - 600.0) / 2.0, fit.destinationY(), 1e-9);
	}

	@Test
	@DisplayName("a matching aspect ratio fills the destination exactly")
	void matchingAspectFillsExactly() {
		LayoutMath.CoverFit fit = LayoutMath.coverFit(1920, 1080, Rect.makeXYWH(0, 0, 960, 540));
		assertEquals(0.5, fit.scale(), 1e-9);
		assertEquals(0.0, fit.destinationX(), 1e-9);
		assertEquals(0.0, fit.destinationY(), 1e-9);
		assertEquals(960.0, fit.destinationWidth(), 1e-9);
		assertEquals(540.0, fit.destinationHeight(), 1e-9);
	}

	@Test
	@DisplayName("an offset destination rectangle keeps its origin")
	void offsetDestination() {
		LayoutMath.CoverFit fit = LayoutMath.coverFit(100, 100, Rect.makeXYWH(40, 20, 200, 100));
		assertEquals(2.0, fit.scale(), 1e-9);
		assertEquals(40.0, fit.destinationX(), 1e-9);
		assertEquals(-30.0, fit.destinationY(), 1e-9);
	}

	@Test
	@DisplayName("invalid sizes are rejected")
	void invalidSizesRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> LayoutMath.coverFit(0, 100, Rect.makeXYWH(0, 0, 10, 10)));
		assertThrows(IllegalArgumentException.class,
				() -> LayoutMath.coverFit(100, 100, Rect.makeXYWH(0, 0, 0, 10)));
		assertThrows(IllegalArgumentException.class, () -> LayoutMath.coverFit(100, 100, null));
	}
}
