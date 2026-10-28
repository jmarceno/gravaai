package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javafx.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The durations/easings copied from the React CSS must not drift (plan §5.12 Animations). */
class MotionTest {

	@DisplayName("durations match the HeroUI CSS and motion/react values")
	@Test
	void durations() {
		assertEquals(250, Motion.BUTTON_PRESS.toMillis(), 0.001);
		assertEquals(100, Motion.BUTTON_COLOR.toMillis(), 0.001);
		assertEquals(300, Motion.SWITCH_THUMB.toMillis(), 0.001);
		assertEquals(250, Motion.SWITCH_TRACK.toMillis(), 0.001);
		assertEquals(300, Motion.METER_FILL.toMillis(), 0.001);
		assertEquals(150, Motion.POPOVER_ENTER.toMillis(), 0.001);
		assertEquals(100, Motion.POPOVER_EXIT.toMillis(), 0.001);
		assertEquals(250, Motion.DIALOG_ENTER.toMillis(), 0.001);
		assertEquals(250, Motion.TABS_INDICATOR.toMillis(), 0.001);
		assertEquals(200, Motion.ACCORDION.toMillis(), 0.001);
		assertEquals(2000, Motion.SKELETON_SHIMMER.toMillis(), 0.001);
		assertEquals(350, Motion.TOAST_ENTER.toMillis(), 0.001);
		assertEquals(250, Motion.TOAST_EXIT.toMillis(), 0.001);
		assertEquals(150, Motion.TOAST_OPACITY.toMillis(), 0.001);
		assertEquals(10000, Motion.MARQUEE.toMillis(), 0.001);
		assertEquals(1500, Motion.TOOLTIP_DELAY.toMillis(), 0.001);
		assertEquals(300, Motion.HUD_TOOLTIP_DELAY.toMillis(), 0.001);
	}

	@DisplayName("every easing curve is a real interpolator")
	@Test
	void easings() {
		for (var interpolator : new javafx.animation.Interpolator[] {
				Motion.LINEAR, Motion.EASE_SMOOTH, Motion.EASE_OUT, Motion.EASE_OUT_QUAD,
				Motion.EASE_OUT_QUART, Motion.EASE_OUT_FLUID, Motion.EASE_IN_OUT_QUAD,
				Motion.EASE_MOTION_OUT}) {
			assertNotNull(interpolator);
		}
		assertEquals(0.5, Motion.LINEAR.interpolate(0.0, 1.0, 0.5), 1e-9);
	}

	@DisplayName("the motion/react HUD transition keeps its 240 ms duration")
	@Test
	void hudMotion() {
		assertEquals(240, Motion.MENU_CARD_IN.toMillis(), 0.001);
		assertEquals(Duration.millis(900), Motion.SPIN);
	}
}
