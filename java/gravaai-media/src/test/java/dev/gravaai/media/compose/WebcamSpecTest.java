package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WebcamSpec visibility contracts (stage 7). */
class WebcamSpecTest {

	private static WebcamSpec spec(boolean enabled, List<WebcamSpec.VisibleRange> ranges) {
		return new WebcamSpec(enabled, "bottom-right", "bottom-right", 1, 1, 40, 40, 40, true,
				100, 0.3, 24, true, LayoutCrop.FULL, ranges, 0);
	}

	@Test
	@DisplayName("a disabled webcam is never visible")
	void disabledIsHidden() {
		assertFalse(spec(false, List.of()).isVisibleAt(0));
	}

	@Test
	@DisplayName("an enabled webcam without ranges is always visible")
	void enabledWithoutRangesIsVisible() {
		assertTrue(spec(true, List.of()).isVisibleAt(0));
	}

	@Test
	@DisplayName("visible ranges gate the webcam inclusively at the start and exclusively at the end")
	void rangesGateVisibility() {
		WebcamSpec spec = spec(true, List.of(new WebcamSpec.VisibleRange(500, 1500)));
		assertFalse(spec.isVisibleAt(499));
		assertTrue(spec.isVisibleAt(500));
		assertTrue(spec.isVisibleAt(1499));
		assertFalse(spec.isVisibleAt(1500));
	}
}
