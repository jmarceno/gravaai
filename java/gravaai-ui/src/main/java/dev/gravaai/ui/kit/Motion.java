package dev.gravaai.ui.kit;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Animation timings and easing curves (plan §5.12 "Animations").
 *
 * <p>Every duration and curve is read from the React source: CSS transitions in
 * {@code @heroui/styles} and the React CSS modules, plus the one {@code motion/react} transition in
 * {@code launch/LaunchWindow.tsx} (0.24 s, ease [0.22, 1, 0.36, 1]). Cubic-bezier curves map
 * directly to {@link Interpolator#ofSpline(double, double, double, double)}.
 */
public final class Motion {

	/** Linear, for the 2 s skeleton shimmer and the 10 s marquee. */
	@SuppressWarnings("deprecation")
	public static final Interpolator LINEAR = Interpolator.LINEAR;

	/** CSS {@code ease} — {@code --ease-smooth}. */
	public static final Interpolator EASE_SMOOTH = Interpolator.ofSpline(0.25, 0.1, 0.25, 1);
	/** Tailwind {@code --ease-out}: cubic-bezier(0, 0, 0.2, 1). */
	public static final Interpolator EASE_OUT = Interpolator.ofSpline(0, 0, 0.2, 1);
	/** {@code --ease-out-quad}: cubic-bezier(0.25, 0.46, 0.45, 0.94). */
	public static final Interpolator EASE_OUT_QUAD = Interpolator.ofSpline(0.25, 0.46, 0.45, 0.94);
	/** {@code --ease-out-quart}: cubic-bezier(0.165, 0.84, 0.44, 1). */
	public static final Interpolator EASE_OUT_QUART = Interpolator.ofSpline(0.165, 0.84, 0.44, 1);
	/** {@code --ease-out-fluid}: cubic-bezier(0.32, 0.72, 0, 1). */
	public static final Interpolator EASE_OUT_FLUID = Interpolator.ofSpline(0.32, 0.72, 0, 1);
	/** {@code --ease-in-out-quad}: cubic-bezier(0.455, 0.03, 0.515, 0.955). */
	public static final Interpolator EASE_IN_OUT_QUAD = Interpolator.ofSpline(0.455, 0.03, 0.515, 0.955);
	/** {@code launch/LaunchWindow.tsx}: motion ease [0.22, 1, 0.36, 1]. */
	public static final Interpolator EASE_MOTION_OUT = Interpolator.ofSpline(0.22, 1, 0.36, 1);

	/* Durations, all read from the source CSS/motion code. */
	public static final Duration BUTTON_PRESS = Duration.millis(250); // transition: transform 250ms
	public static final Duration BUTTON_COLOR = Duration.millis(100); // background-color 100ms
	public static final Duration SWITCH_TRACK = Duration.millis(250);
	public static final Duration SWITCH_TRACK_HOVER = Duration.millis(150);
	public static final Duration SWITCH_THUMB = Duration.millis(300); // margin, ease-out-fluid
	public static final Duration SWITCH_THUMB_COLOR = Duration.millis(200);
	public static final Duration SLIDER_THUMB = Duration.millis(250);
	public static final Duration SLIDER_SHADOW = Duration.millis(150);
	public static final Duration METER_FILL = Duration.millis(300); // width 300ms ease-out
	public static final Duration POPOVER_ENTER = Duration.millis(150); // animate-in duration-150
	public static final Duration POPOVER_EXIT = Duration.millis(100);
	public static final Duration TOOLTIP_CLOSE_DELAY = Duration.millis(500);
	public static final Duration DIALOG_ENTER = Duration.millis(250); // duration-250 ease-out-quad
	public static final Duration DIALOG_EXIT = Duration.millis(100);
	public static final Duration BACKDROP_ENTER = Duration.millis(150);
	public static final Duration BACKDROP_EXIT = Duration.millis(100);
	public static final Duration TABS_INDICATOR = Duration.millis(250); // ease-out-fluid
	public static final Duration ACCORDION = Duration.millis(200); // height+opacity 200ms
	public static final Duration SKELETON_SHIMMER = Duration.millis(2000); // 2s linear infinite
	public static final Duration TOAST_ENTER = Duration.millis(350); // ease-out-fluid
	public static final Duration TOAST_EXIT = Duration.millis(250);
	public static final Duration TOAST_OPACITY = Duration.millis(150);
	public static final Duration MENU_ITEM_HOVER = Duration.millis(250);
	public static final Duration MARQUEE = Duration.millis(10000); // 10s linear infinite
	public static final Duration MENU_CARD_IN = Duration.millis(240); // motion 0.24s
	public static final Duration SPIN = Duration.millis(900); // finalizingSpin 0.9s linear
	public static final Duration REC_BLINK = Duration.millis(1200); // blink 1.2s ease-in-out

	/** HeroUI default tooltip delay ({@code --tooltip-delay: 1500ms}). */
	public static final Duration TOOLTIP_DELAY = Duration.millis(1500);
	/** HudTooltip delay (300 ms). */
	public static final Duration HUD_TOOLTIP_DELAY = Duration.millis(300);

	private Motion() {}

	/** Fades a node in; no-op when it is already visible. */
	public static FadeTransition fadeIn(Node node, Duration duration, Interpolator easing) {
		FadeTransition transition = new FadeTransition(duration, node);
		transition.setFromValue(0);
		transition.setToValue(1);
		transition.setInterpolator(easing);
		return transition;
	}

	/** Fades a node out to zero opacity. */
	public static FadeTransition fadeOut(Node node, Duration duration, Interpolator easing) {
		FadeTransition transition = new FadeTransition(duration, node);
		transition.setFromValue(node.getOpacity());
		transition.setToValue(0);
		transition.setInterpolator(easing);
		return transition;
	}

	/** Translates a node to the given coordinates. */
	public static TranslateTransition translateTo(Node node, double x, double y, Duration duration,
			Interpolator easing) {
		TranslateTransition transition = new TranslateTransition(duration, node);
		transition.setToX(x);
		transition.setToY(y);
		transition.setInterpolator(easing);
		return transition;
	}

	/** Scales a node uniformly. */
	public static ScaleTransition scaleTo(Node node, double scale, Duration duration, Interpolator easing) {
		ScaleTransition transition = new ScaleTransition(duration, node);
		transition.setToX(scale);
		transition.setToY(scale);
		transition.setInterpolator(easing);
		return transition;
	}
}
