package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The caption band height overlays stack against so keystrokes clear the subtitles. */
class CaptionRendererBandTest {

	private static CaptionSpec captions(double fontSize, double bottomOffset, int maxRows) {
		return new CaptionSpec(true, CaptionSpec.DEFAULT_FONT_FAMILY, fontSize, bottomOffset, 62,
				maxRows, "none", 17.5, "#FFFFFF", "#A3A3A3", 0.9,
				List.of(new CaptionSpec.Cue("c1", 0, 10_000, "hello world")));
	}

	@Test
	@DisplayName("is zero without captions or without cues")
	void zeroWhenAbsent() {
		assertEquals(0, CaptionRenderer.bottomBandHeight(CaptionSpec.DISABLED, 1920, 1080));
		assertEquals(0, CaptionRenderer.bottomBandHeight(
				new CaptionSpec(true, CaptionSpec.DEFAULT_FONT_FAMILY, 30, 3, 62, 1, "none", 17.5,
						"#FFFFFF", "#A3A3A3", 0.9, List.of()),
				1920, 1080));
	}

	@Test
	@DisplayName("covers the bottom offset plus the full caption box")
	void coversOffsetAndBox() {
		// 1080p default settings: font 30, one row → 30 * 1.32 + 2 * 30 * 0.78 = 86.4 px box.
		double band = CaptionRenderer.bottomBandHeight(captions(30, 3, 1), 1920, 1080);
		assertEquals(1080 * 0.03 + 86.4, band, 0.01);

		// Two rows double the text block, and small frame widths clamp the font at 14 px.
		double twoRows = CaptionRenderer.bottomBandHeight(captions(30, 3, 2), 1920, 1080);
		assertEquals(1080 * 0.03 + 2 * 30 * 1.32 + 2 * 30 * 0.78, twoRows, 0.01);

		double smallFrame = CaptionRenderer.bottomBandHeight(captions(30, 3, 1), 400, 200);
		assertEquals(200 * 0.03 + 14 * 1.32 + 2 * 14 * 0.78, smallFrame, 0.01);
	}
}
