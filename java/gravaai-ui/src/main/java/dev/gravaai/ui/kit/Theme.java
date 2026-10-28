package dev.gravaai.ui.kit;

import java.net.URL;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

/**
 * The Gravaai dark theme (plan §5.12), the same palette as the React app's only theme.
 *
 * <p>Colours live in {@code resources/kit/gravaai.css} as looked-up colours so CSS can use them;
 * these constants are the programmatic mirror for nodes that paint from code (canvas, gradients,
 * swatches). Keep the two in sync: the CSS token and the constant carry the same source name.
 */
public final class Theme {

	/** {@code src/index.css} {@code .dark} — base palette. */
	public static final Color BG = Color.web("#121418");
	public static final Color FG = Color.web("#ffffff");
	public static final Color SURFACE = Color.web("#1b1e25");
	public static final Color SURFACE_SECONDARY = Color.web("#232833");
	public static final Color SURFACE_TERTIARY = Color.web("#2a303b");
	public static final Color OVERLAY = Color.web("#1b1e25");
	public static final Color MUTED = Color.web("#8b929a");
	public static final Color DEFAULT = Color.web("#232833");
	public static final Color DEFAULT_FOREGROUND = Color.web("#ffffff");
	public static final Color SEGMENT = Color.web("#343b48");
	public static final Color SEGMENT_FOREGROUND = Color.web("#ffffff");
	public static final Color FIELD = Color.web("#12151b");
	public static final Color FIELD_FOREGROUND = Color.web("#ffffff");
	public static final Color FIELD_PLACEHOLDER = Color.web("#6b7280");
	public static final Color BORDER = Color.web("#2a303b");
	public static final Color SEPARATOR = Color.web("#2a303b");
	public static final Color ACCENT = Color.web("#2fb3a3");
	public static final Color ACCENT_FOREGROUND = Color.web("#0b0d10");
	public static final Color ACCENT_HOVER = Color.web("#3fd4c1");
	public static final Color ACCENT_SOFT = Color.web("#2fb3a3", 0.13);
	public static final Color ACCENT_SOFT_HOVER = Color.web("#2fb3a3", 0.2);
	public static final Color ACCENT_SOFT_FOREGROUND = Color.web("#3fd4c1");
	public static final Color SUCCESS = Color.web("#3ecf8e");
	public static final Color SUCCESS_SOFT = Color.web("#3ecf8e", 0.13);
	public static final Color SUCCESS_SOFT_FOREGROUND = Color.web("#82ddac");
	public static final Color WARNING = Color.web("#e8b339");
	public static final Color WARNING_SOFT = Color.web("#e8b339", 0.15);
	public static final Color WARNING_SOFT_FOREGROUND = Color.web("#efc87b");
	public static final Color DANGER = Color.web("#e5656e");
	public static final Color DANGER_HOVER = Color.web("#f07880");
	public static final Color DANGER_SOFT = Color.web("#e5656e", 0.15);
	public static final Color DANGER_SOFT_FOREGROUND = Color.web("#f19194");
	public static final Color DANGER_FOREGROUND = Color.web("#fcfcfc");
	public static final Color FOCUS = Color.web("#2fb3a3");
	public static final Color BACKDROP = Color.web("#000000", 0.6);
	public static final Color SCROLLBAR_THUMB = Color.web("#2a2f38");

	/** Derived colours (@heroui/styles variables.css dark block, color-mix resolved). */
	public static final Color SURFACE_HOVER = Color.web("#2a2d34");
	public static final Color SURFACE_SECONDARY_HOVER = Color.web("#323741");
	public static final Color BG_SECONDARY = Color.web("#191b1f");
	public static final Color BG_TERTIARY = Color.web("#212327");
	public static final Color DEFAULT_HOVER = Color.web("#2a2f3a");
	public static final Color FIELD_HOVER = Color.web("#16191f");
	public static final Color SEPARATOR_SECONDARY = Color.web("#383a41");
	public static final Color SEPARATOR_TERTIARY = Color.web("#404249");
	public static final Color BORDER_SECONDARY = Color.web("#46494f");
	public static final Color BORDER_TERTIARY = Color.web("#5f6267");

	/** Radius scale: @heroui/styles {@code --radius: 0.5rem}. */
	public static final double RADIUS_XS = 2;
	public static final double RADIUS_SM = 4;
	public static final double RADIUS_MD = 6;
	public static final double RADIUS_LG = 8;
	public static final double RADIUS_XL = 12;
	public static final double RADIUS_2XL = 16;
	public static final double RADIUS_3XL = 24;
	public static final double RADIUS_4XL = 32;

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
