package dev.gravaai.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * DM Sans for JavaFX (plan §5.12).
 *
 * <p>JavaFX does not apply variable-font axes, so the app bundles static instances generated once
 * from {@code src/assets/fonts/dm-sans} with {@code fonttools varLib.instancer}
 * ({@code java/tools/fonts/generate-fonts.py}). Call {@link #load()} once at startup, before any
 * {@code Scene} exists: {@link Font#loadFont} registers the families, and CSS can then use
 * {@code -fx-font-family: "DM Sans"} with numeric weights.
 */
public final class AppFonts {

	/** Family name used by {@code kit/gravaai.css}. */
	public static final String FAMILY = "DM Sans";

	private static final List<String> FILES = List.of(
			"DMSans-Regular.ttf",
			"DMSans-Italic.ttf",
			"DMSans-Medium.ttf",
			"DMSans-MediumItalic.ttf",
			"DMSans-SemiBold.ttf",
			"DMSans-SemiBoldItalic.ttf",
			"DMSans-Bold.ttf",
			"DMSans-BoldItalic.ttf");

	private static final System.Logger LOG = System.getLogger("gravaai.ui");

	private static boolean loaded;

	private AppFonts() {}

	/**
	 * Loads the eight bundled faces. Idempotent; throws when a resource is missing or JavaFX
	 * rejects a face, so a broken install fails fast instead of silently falling back to a system
	 * font.
	 */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		for (String file : FILES) {
			String resource = "/kit/fonts/" + file;
			try (InputStream in = AppFonts.class.getResourceAsStream(resource)) {
				if (in == null) {
					throw new IllegalStateException("Bundled font not found on the classpath: " + resource);
				}
				Font font = Font.loadFont(in, 12);
				if (font == null) {
					throw new IllegalStateException("JavaFX rejected the bundled font: " + resource);
				}
				LOG.log(System.Logger.Level.DEBUG, "font {0}: family={1} name={2}", file,
						font.getFamily(), font.getName());
			} catch (IOException error) {
				throw new UncheckedIOException("Could not read " + resource, error);
			}
		}
		loaded = true;
		LOG.log(System.Logger.Level.INFO, "loaded " + FILES.size() + " DM Sans faces, family="
				+ findFamily());
	}

	/** {@code DM Sans} regular at {@code size} px. */
	public static Font font(double size) {
		return Font.font(FAMILY, size);
	}

	/** {@code DM Sans} at the given weight and {@code size} px. */
	public static Font font(FontWeight weight, double size) {
		return Font.font(FAMILY, weight, size);
	}

	/** True when the family is registered under the expected name. */
	public static boolean isLoaded() {
		return loaded && findFamily().equals(FAMILY);
	}

	/** The registered family name; useful in the startup log and in tests. */
	public static String findFamily() {
		for (String name : Font.getFamilies()) {
			if (name.equals(FAMILY)) {
				return FAMILY;
			}
		}
		return "";
	}
}
