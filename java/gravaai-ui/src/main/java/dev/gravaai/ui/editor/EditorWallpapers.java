package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.project.EditorDefaults;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Built-in wallpapers, colours and gradients of the editor background (port of
 * {@code src/lib/wallpapers.ts} + the palette constants in {@code SettingsPanel.tsx}).
 *
 * <p>Wallpaper values are clean {@code /wallpapers/<name>} identifiers. Values that do not
 * resolve to a bundled image fall back to {@link EditorDefaults#DEFAULT_WALLPAPER_PATH} at
 * render time. The files are the CC0 wallpapers documented in {@code ASSETS.md}; the thumbnails
 * live under {@code resources/ui/editor/wallpapers/} (a 160x120 {@code thumbs/} and a 1280x720
 * {@code previews/} copy of each built-in image).
 */
public final class EditorWallpapers {

	/** One wallpaper tile. */
	public record Tile(String value, String label) {}

	/** Built-in image wallpapers, in menu order. */
	public static final List<String> IMAGE_FILES = List.of(
			"morning-tide.jpg", "midnight-silk.jpg", "onyx-wave.jpg", "neon-bloom.jpg",
			"dune-ribbons.jpg", "blue-flame.jpg", "solar-drift.jpg", "velvet-current.jpg",
			"verdant-mist.jpg", "sea-glass.jpg", "aurora-dust.jpg", "prism-glass.jpg",
			"rose-quartz.jpg", "ember-silk.jpg", "coral-tide.jpg", "magenta-rush.jpg",
			"cloudline-city.jpg", "white-atlas.jpg", "stormlight.jpg", "solar-vortex.jpg",
			"harvest-dusk.jpg", "fogline.jpg", "opal-sky.jpg", "rainbow-fold.jpg",
			"blue-rays.jpeg", "ink-bloom.jpg", "terrace-fields.jpg", "citrus-glass.jpeg",
			"forest-light.jpg", "alpine-lake.jpg", "crimson-silk.jpg", "prism-streak.jpg",
			"painted-current.jpg", "emerald-flow.jpg", "amber-vortex.jpg", "blue-orbit.jpg",
			"dusk-lake.jpg", "golden-horizon.jpg");

	/** Built-in video wallpapers. */
	public static final List<Tile> VIDEO_TILES =
			List.of(new Tile("/wallpapers/wispy-sky.mp4", "Wispy Sky"));

	/** Poster/thumbnail name used when a project names a wallpaper that is not bundled. */
	private static final String DEFAULT_IMAGE =
			EditorDefaults.DEFAULT_WALLPAPER_PATH.replaceFirst("^.*/", "");

	/** Colour swatches ({@code colorPalette} in SettingsPanel.tsx). */
	public static final List<String> COLOR_PALETTE = List.of(
			"#FF0000", "#FFD700", "#00FF00", "#FFFFFF", "#0000FF", "#FF6B00", "#9B59B6",
			"#E91E63", "#00BCD4", "#FF5722", "#8BC34A", "#FFC107", "#2563EB", "#000000",
			"#607D8B", "#795548");

	/** Gradients ({@code GRADIENTS} in SettingsPanel.tsx, verbatim CSS strings). */
	public static final List<String> GRADIENTS = List.of(
			"linear-gradient( 111.6deg,  rgba(114,167,232,1) 9.4%, rgba(253,129,82,1) 43.9%, rgba(253,129,82,1) 54.8%, rgba(249,202,86,1) 86.3% )",
			"linear-gradient(120deg, #d4fc79 0%, #96e6a1 100%)",
			"radial-gradient( circle farthest-corner at 3.2% 49.6%,  rgba(80,12,139,0.87) 0%, rgba(161,10,144,0.72) 83.6% )",
			"linear-gradient( 111.6deg,  rgba(0,56,68,1) 0%, rgba(163,217,185,1) 51.5%, rgba(231, 148, 6, 1) 88.6% )",
			"linear-gradient( 107.7deg,  rgba(235,230,44,0.55) 8.4%, rgba(252,152,15,1) 90.3% )",
			"linear-gradient( 91deg,  rgba(72,154,78,1) 5.2%, rgba(251,206,70,1) 95.9% )",
			"radial-gradient( circle farthest-corner at 10% 20%,  rgba(2,37,78,1) 0%, rgba(4,56,126,1) 19.7%, rgba(85,245,221,1) 100.2% )",
			"linear-gradient( 109.6deg,  rgba(15,2,2,1) 11.2%, rgba(36,163,190,1) 91.1% )",
			"linear-gradient(135deg, #FBC8B4, #2447B1)",
			"linear-gradient(109.6deg, #F635A6, #36D860)",
			"linear-gradient(90deg, #FF0101, #4DFF01)",
			"linear-gradient(315deg, #EC0101, #5044A9)",
			"linear-gradient(45deg, #ff9a9e 0%, #fad0c4 99%, #fad0c4 100%)",
			"linear-gradient(to top, #a18cd1 0%, #fbc2eb 100%)",
			"linear-gradient(to right, #ff8177 0%, #ff867a 0%, #ff8c7f 21%, #f99185 52%, #cf556c 78%, #b12a5b 100%)",
			"linear-gradient(120deg, #84fab0 0%, #8fd3f4 100%)",
			"linear-gradient(to right, #4facfe 0%, #00f2fe 100%)",
			"linear-gradient(to top, #fcc5e4 0%, #fda34b 15%, #ff7882 35%, #c8699e 52%, #7046aa 71%, #0c1db8 87%, #020f75 100%)",
			"linear-gradient(to right, #fa709a 0%, #fee140 100%)",
			"linear-gradient(to top, #30cfd0 0%, #330867 100%)",
			"linear-gradient(to top, #c471f5 0%, #fa71cd 100%)",
			"linear-gradient(to right, #f78ca0 0%, #f9748f 19%, #fd868c 60%, #fe9a8b 100%)",
			"linear-gradient(to top, #48c6ef 0%, #6f86d6 100%)",
			"linear-gradient(to right, #0acffe 0%, #495aff 100%)");

	private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");
	private static final Pattern RGBA = Pattern.compile(
			"rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)");

	private EditorWallpapers() {}

	/** Image wallpaper tiles in menu order. */
	public static List<Tile> imageTiles() {
		List<Tile> tiles = new ArrayList<>(IMAGE_FILES.size());
		for (String file : IMAGE_FILES) {
			tiles.add(new Tile("/wallpapers/" + file, labelFor(file)));
		}
		return List.copyOf(tiles);
	}

	/** Turns {@code morning-tide.jpg} into {@code Morning Tide}. */
	private static String labelFor(String file) {
		StringBuilder label = new StringBuilder();
		for (String word : file.replaceFirst("\\.[^.]+$", "").split("-")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!label.isEmpty()) {
				label.append(' ');
			}
			label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return label.toString();
	}

	/** {@code isVideoWallpaperSource}. */
	public static boolean isVideoWallpaper(@Nullable String value) {
		return value != null && dev.gravaai.media.compose.Background.isVideoWallpaper(value);
	}

	/** True for a bundled wallpaper path ({@code /wallpapers/...}). */
	public static boolean isBundledWallpaper(@Nullable String value) {
		return value != null && value.startsWith("/wallpapers/");
	}

	/** Hex colour values (the Color tab and the click-effect palette). */
	public static boolean isHexColor(@Nullable String value) {
		return value != null && HEX.matcher(value.trim()).matches();
	}

	/** The CSS gradient list contains this value. */
	public static boolean isGradient(@Nullable String value) {
		return value != null && GRADIENTS.contains(value);
	}

	/** Background tab for a wallpaper value. */
	public static String tabFor(@Nullable String value) {
		if (isGradient(value)) {
			return "gradient";
		}
		if (isHexColor(value)) {
			return "color";
		}
		if (isVideoWallpaper(value)) {
			return "video";
		}
		return "image";
	}

	/** Thumbnail image for a wallpapers/ path; null for colours/gradients/custom files. */
	public static @Nullable Image thumbnail(@Nullable String value) {
		if (value == null || !isBundledWallpaper(value)) {
			return null;
		}
		Image image = load("/ui/editor/wallpapers/thumbs/" + fileName(value));
		return image != null ? image : load("/ui/editor/wallpapers/thumbs/" + DEFAULT_IMAGE);
	}

	/** Preview-resolution image (1280x720) for the preview stage. */
	public static @Nullable Image preview(@Nullable String value) {
		if (value == null || !isBundledWallpaper(value)) {
			return null;
		}
		Image image = load("/ui/editor/wallpapers/previews/" + fileName(value));
		return image != null ? image
				: load("/ui/editor/wallpapers/previews/" + DEFAULT_IMAGE);
	}

	private static @Nullable String fileName(String value) {
		String[] parts = value.split("/");
		String name = parts[parts.length - 1];
		// Video wallpapers use a poster frame (WallpaperVideoPreview shows the first frame).
		return isVideoWallpaper(name) ? name.replaceFirst("\\.[^.]+$", ".jpg") : name;
	}

	private static @Nullable Image load(String resource) {
		try (InputStream in = EditorWallpapers.class.getResourceAsStream(resource)) {
			return in == null ? null : new Image(in);
		} catch (Exception error) {
			return null;
		}
	}

	/** Parses a colour or CSS gradient; falls back to the given colour for images/custom files. */
	public static Paint paintFor(@Nullable String value, Color fallback) {
		if (value == null) {
			return fallback;
		}
		if (isHexColor(value)) {
			return Color.web(value.trim());
		}
		if (isGradient(value)) {
			return parseGradient(value);
		}
		return fallback;
	}

	/** Minimal CSS gradient parser for the fixed {@link #GRADIENTS} list. */
	static Paint parseGradient(String css) {
		String body = css.substring(css.indexOf('(') + 1, css.lastIndexOf(')'));
		List<String> parts = splitTopLevel(body);
		if (parts.isEmpty()) {
			return Color.TRANSPARENT;
		}
		boolean radial = css.startsWith("radial-gradient");
		List<Stop> stops = new ArrayList<>();
		double directionAngle = 180;
		String keyword = null;
		int index = 0;
		if (!radial) {
			String first = parts.get(0).trim();
			Matcher angle = Pattern.compile("^([0-9.]+)deg").matcher(first);
			if (angle.find()) {
				directionAngle = Double.parseDouble(angle.group(1));
				index = 1;
			} else if (first.startsWith("to ")) {
				keyword = first.trim();
				index = 1;
			}
		}
		List<String> stopParts = parts.subList(index, parts.size());
		for (int i = 0; i < stopParts.size(); i++) {
			String part = stopParts.get(i).trim();
			Color color = parseColor(part);
			if (color == null) {
				continue;
			}
			double offset = stopParts.size() == 1 ? 0 : (double) i / (stopParts.size() - 1);
			Matcher percentage = Pattern.compile("([0-9.]+)%\\s*$").matcher(part);
			if (percentage.find()) {
				offset = Double.parseDouble(percentage.group(1)) / 100;
			}
			stops.add(new Stop(clamp01(offset), color));
		}
		if (stops.isEmpty()) {
			return Color.TRANSPARENT;
		}
		if (stops.size() == 1) {
			return stops.get(0).getColor();
		}
		if (radial) {
			double cx = 0.5;
			double cy = 0.5;
			Matcher at = Pattern.compile("at\\s+([0-9.]+)%\\s+([0-9.]+)%").matcher(body);
			if (at.find()) {
				cx = Double.parseDouble(at.group(1)) / 100;
				cy = Double.parseDouble(at.group(2)) / 100;
			}
			return new RadialGradient(0, 0, cx, cy, 1, true, CycleMethod.NO_CYCLE, stops);
		}
		double dx;
		double dy;
		if (keyword != null) {
			dx = keyword.contains("right") ? 1 : keyword.contains("left") ? -1 : 0;
			dy = keyword.contains("bottom") ? 1 : keyword.contains("top") ? -1 : 0;
		} else {
			double radians = Math.toRadians(directionAngle);
			dx = Math.sin(radians);
			dy = -Math.cos(radians);
		}
		return new LinearGradient(0.5 - dx / 2, 0.5 - dy / 2, 0.5 + dx / 2, 0.5 + dy / 2,
				true, CycleMethod.NO_CYCLE, stops);
	}

	private static List<String> splitTopLevel(String value) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		for (char character : value.toCharArray()) {
			if (character == '(') {
				depth++;
			} else if (character == ')') {
				depth--;
			}
			if (character == ',' && depth == 0) {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (!current.toString().isBlank()) {
			parts.add(current.toString());
		}
		return parts;
	}

	private static @Nullable Color parseColor(String value) {
		Matcher hex = HEX.matcher(value);
		if (hex.find()) {
			return Color.web(hex.group());
		}
		Matcher rgba = RGBA.matcher(value);
		if (rgba.find()) {
			double red = Double.parseDouble(rgba.group(1));
			double green = Double.parseDouble(rgba.group(2));
			double blue = Double.parseDouble(rgba.group(3));
			double alpha = rgba.group(4) == null ? 1 : Double.parseDouble(rgba.group(4));
			return Color.color(clamp01(red / 255), clamp01(green / 255), clamp01(blue / 255),
					clamp01(alpha));
		}
		return null;
	}

	private static double clamp01(double value) {
		return Math.min(1, Math.max(0, value));
	}
}
