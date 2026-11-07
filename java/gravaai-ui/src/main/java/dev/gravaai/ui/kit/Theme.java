package dev.gravaai.ui.kit;

import java.net.URL;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

/**
 * The Gravaai theme — the Instrument palette (docs/mocks/editor-instrument.html :root),
 * app-wide since the editor redesign. Same values as kit/gravaai.css.
 *
 * <p>Colours live in {@code resources/kit/gravaai.css} as looked-up colours so CSS can use them;
 * these constants are the programmatic mirror for nodes that paint from code (canvas, gradients,
 * swatches). Keep the two in sync: the CSS token and the constant carry the same source name.
 */
public final class Theme {

	/** Instrument palette — panel base, chrome surfaces, hairlines, cyan. */
	public static final Color BG = Color.web("#0e1317");
	public static final Color FG = Color.web("#e9f1f4");
	public static final Color SURFACE = Color.web("#0b0f12");
	public static final Color SURFACE_SECONDARY = Color.web("#141a1f");
	public static final Color SURFACE_TERTIARY = Color.web("#1a2127");
	public static final Color OVERLAY = Color.web("#141a1f");
	public static final Color MUTED = Color.web("#8794a0");
	public static final Color FAINT = Color.web("#5b6872");
	public static final Color DEFAULT = Color.web("#141a1f");
	public static final Color DEFAULT_FOREGROUND = Color.web("#e9f1f4");
	public static final Color SEGMENT = Color.web("#141a1f");
	public static final Color SEGMENT_FOREGROUND = Color.web("#e9f1f4");
	public static final Color FIELD = Color.web("#0a0e11");
	public static final Color FIELD_FOREGROUND = Color.web("#e9f1f4");
	public static final Color FIELD_PLACEHOLDER = Color.web("#5b6872");
	public static final Color BORDER = Color.web("#222b33");
	public static final Color SEPARATOR = Color.web("#171e24");
	public static final Color ACCENT = Color.web("#2fe6d0");
	public static final Color ACCENT_FOREGROUND = Color.web("#032522");
	public static final Color ACCENT_HOVER = Color.web("#57f0dd");
	public static final Color ACCENT_SOFT = Color.web("#2fe6d0", 0.1);
	public static final Color ACCENT_SOFT_HOVER = Color.web("#2fe6d0", 0.16);
	public static final Color ACCENT_SOFT_FOREGROUND = Color.web("#2fe6d0");
	public static final Color SUCCESS = Color.web("#3ecf8e");
	public static final Color SUCCESS_SOFT = Color.web("#3ecf8e", 0.13);
	public static final Color SUCCESS_SOFT_FOREGROUND = Color.web("#82ddac");
	public static final Color WARNING = Color.web("#f0b429");
	public static final Color WARNING_SOFT = Color.web("#f0b429", 0.14);
	public static final Color WARNING_SOFT_FOREGROUND = Color.web("#f0b429");
	public static final Color DANGER = Color.web("#e5656e");
	public static final Color DANGER_HOVER = Color.web("#f07880");
	public static final Color DANGER_SOFT = Color.web("#e5656e", 0.15);
	public static final Color DANGER_SOFT_FOREGROUND = Color.web("#f19194");
	public static final Color DANGER_FOREGROUND = Color.web("#fcfcfc");
	public static final Color FOCUS = Color.web("#2fe6d0");
	public static final Color BACKDROP = Color.web("#000000", 0.6);
	public static final Color SCROLLBAR_THUMB = Color.web("#222b33");

	/** Derived colours — raised/hover states onto the Instrument surfaces. */
	public static final Color SURFACE_HOVER = Color.web("#141a1f");
	public static final Color SURFACE_SECONDARY_HOVER = Color.web("#1a2127");
	public static final Color BG_SECONDARY = Color.web("#0b0f12");
	public static final Color BG_TERTIARY = Color.web("#10151a");
	public static final Color DEFAULT_HOVER = Color.web("#1a2127");
	public static final Color FIELD_HOVER = Color.web("#0c1116");
	public static final Color SEPARATOR_SECONDARY = Color.web("#222b33");
	public static final Color SEPARATOR_TERTIARY = Color.web("#2a343d");
	public static final Color BORDER_SECONDARY = Color.web("#28323a");
	public static final Color BORDER_TERTIARY = Color.web("#38424b");

	/** Radius scale — the Instrument shape language: 2px rails, 3px segments, 4px controls, 8px panels. */
	public static final double RADIUS_XS = 2;
	public static final double RADIUS_SM = 3;
	public static final double RADIUS_MD = 4;
	public static final double RADIUS_LG = 5;
	public static final double RADIUS_XL = 6;
	public static final double RADIUS_2XL = 8;
	public static final double RADIUS_3XL = 8;
	public static final double RADIUS_4XL = 8;

	/** {@code --disabled-opacity}. */
	public static final double DISABLED_OPACITY = 0.5;

	private static final String STYLESHEET = "/kit/gravaai.css";

	private Theme() {}

	/** Adds {@code kit/gravaai.css} to the scene if it is not there yet. */
	public static void install(Scene scene) {
		String url = stylesheetUrl();
		if (!scene.getStylesheets().contains(url)) {
			scene.getStylesheets().add(url);
		}
	}

	/** The stylesheet URL, for windows that build their scene themselves. */
	public static String stylesheetUrl() {
		URL resource = Theme.class.getResource(STYLESHEET);
		if (resource == null) {
			throw new IllegalStateException("Missing UI kit stylesheet on the classpath: " + STYLESHEET);
		}
		return resource.toExternalForm();
	}
}
