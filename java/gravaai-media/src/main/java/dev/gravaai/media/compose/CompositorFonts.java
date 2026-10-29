package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

/**
 * Bundled DM Sans faces for Skija text (plan §5.8 "Bundle DM Sans and load user custom fonts").
 *
 * <p>The golden references were captured with the Electron renderer's SF Pro/system stack, which
 * is not redistributable; DM Sans is the app's bundled substitute and the closest face shipping in
 * the build. Loading it gives captions/annotations/keystrokes identical metrics in preview and
 * export instead of whatever the host system resolves. If a face cannot be loaded the code falls
 * back to Skia's default font, so rendering never fails.
 */
final class CompositorFonts {

	enum Face {
		REGULAR("DMSans-Regular.ttf"),
		MEDIUM("DMSans-Medium.ttf"),
		SEMI_BOLD("DMSans-SemiBold.ttf"),
		BOLD("DMSans-Bold.ttf"),
		ITALIC("DMSans-Italic.ttf"),
		SEMI_BOLD_ITALIC("DMSans-SemiBoldItalic.ttf"),
		BOLD_ITALIC("DMSans-BoldItalic.ttf");

		private final String file;

		Face(String file) {
			this.file = file;
		}
	}

	private static final String RESOURCE_PREFIX = "/fonts/";
	private static final Map<Face, Typeface> TYPEFACES = new EnumMap<>(Face.class);
	private static final Map<Face, Data> DATA = new EnumMap<>(Face.class);

	@Nullable private CompositorFonts() {
	}

	/** The DM Sans typeface for a face, or null when the bundled resource is unavailable. */
	static synchronized @Nullable Typeface typeface(Face face) {
		Typeface cached = TYPEFACES.get(face);
		if (cached != null) {
			return cached;
		}
		String resource = RESOURCE_PREFIX + face.file;
		try (InputStream input = CompositorFonts.class.getResourceAsStream(resource)) {
			if (input == null) {
				return null;
			}
			Data data = Data.makeFromBytes(input.readAllBytes());
			Typeface typeface = FontMgr.getDefault().makeFromData(data);
			if (typeface == null) {
				data.close();
				return null;
			}
			DATA.put(face, data);
			TYPEFACES.put(face, typeface);
			return typeface;
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** A font for the face; the caller closes it. */
	static Font font(Face face, float size) {
		Typeface typeface = typeface(face);
		if (typeface == null) {
			Font fallback = new Font();
			fallback.setSize(size);
			return fallback;
		}
		return new Font(typeface, size);
	}
}
