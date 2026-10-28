package dev.gravaai.media.compose;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The compositor background (plan §5.8 stage 1), resolved from the project's wallpaper setting.
 *
 * <p>Port of the export-side wallpaper handling in {@code modernFrameRenderer.setupBackground}
 * for the CSS cases (colour and {@code linear-/radial-gradient}), plus an already-decoded image
 * variant for bundled/user wallpapers: the media layer never resolves asset paths, the caller
 * (services/UI) supplies the image bytes.</p>
 *
 * <p><strong>Faithful TS quirks</strong> (ported on purpose, plan §8.5; goldens confirm them):
 * the export parser ignores the linear-gradient direction token and uses
 * {@code index / (parts.length - 1)} on the comma-split parts — including the skipped direction
 * token — so {@code linear-gradient(120deg, #a, #b)} produces stops at 0.5 and 1.0. The same
 * split cuts {@code rgba(...)} colours apart and matches only the {@code "rgba"} prefix, which
 * is not a valid CSS colour; the browser throws and the export falls back to black, so those
 * gradients are rendered black here as well (radial gradients always hit this because their
 * first part is the shape/size token).</p>
 */
public sealed interface Background {

	int BLACK = 0xFF000000;

	/** A flat colour. */
	record Colour(int argb) implements Background {
	}

	/**
	 * A vertical linear gradient: the TS export always creates it from (0, 0) to (0, height)
	 * regardless of the CSS angle.
	 */
	record LinearGradient(int[] argbColours, float[] positions) implements Background {
		public LinearGradient {
			argbColours = argbColours.clone();
			positions = positions.clone();
			validateStops(argbColours.length, positions.length);
		}
	}

	/** A radial gradient centred on the canvas with radius {@code max(width, height) / 2}. */
	record RadialGradient(int[] argbColours, float[] positions) implements Background {
		public RadialGradient {
			argbColours = argbColours.clone();
			positions = positions.clone();
			validateStops(argbColours.length, positions.length);
		}
	}

	/**
	 * An already encoded wallpaper image (PNG/JPEG bytes). Callers keep one instance per
	 * project so the compositor's pre-rendered background cache can key on identity.
	 */
	record Image(byte[] encodedImage) implements Background {
		public Image {
			if (encodedImage == null || encodedImage.length == 0) {
				throw new IllegalArgumentException("background image bytes must not be empty");
			}
		}
	}

	/**
	 * One decoded frame of a video wallpaper ({@code isVideoWallpaperSource}), drawn cover-fit like
	 * an image. A new instance per frame, so the compositor re-renders the background each frame.
	 */
	record VideoFrame(RgbaFrame frame) implements Background {
	}

	/** {@code isVideoWallpaperSource}: the wallpaper value names a video file. */
	static boolean isVideoWallpaper(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		String path = value.split("\\?", 2)[0].toLowerCase(java.util.Locale.ROOT);
		return path.matches(".*\\.(avi|m4v|mkv|mov|mp4|webm)$");
	}

	/** A black background, the canvas fallback. */
	static Background black() {
		return new Colour(BLACK);
	}

	private static void validateStops(int colourCount, int positionCount) {
		if (colourCount != positionCount) {
			throw new IllegalArgumentException("gradient colours/positions length mismatch: "
					+ colourCount + " vs " + positionCount);
		}
		if (colourCount == 0) {
			throw new IllegalArgumentException("gradient needs at least one colour stop");
		}
	}

	String GRADIENT = "^(linear|radial)-gradient\\((.+)\\)$";
	Pattern GRADIENT_PATTERN = Pattern.compile(GRADIENT);
	Pattern STOP_COLOUR = Pattern.compile("^(#[0-9a-fA-F]{3,8}|rgba?\\([^)]+\\)|[a-z]+)");

	/**
	 * Public entry point for the UI/services layer resolving a project wallpaper string
	 * (task-17 preview panel, project hover previews): CSS colours and gradients are parsed into
	 * a {@link Background}.
	 *
	 * <p>Image and video wallpapers are not loaded here — the media layer never resolves asset
	 * paths. Callers that know the wallpaper is a bundled/user image load its bytes and pass
	 * {@code new Background.Image(bytes)}; passing such a string to this method throws.</p>
	 *
	 * @throws IllegalArgumentException for image/video wallpapers (see above)
	 */
	public static Background fromWallpaper(String css) {
		return parse(css);
	}

	/**
	 * Resolves a project wallpaper string that is a CSS background (colour or gradient).
	 *
	 * @throws IllegalArgumentException for image/video wallpapers, which the caller must resolve
	 *         into {@link Image} instead
	 */
	static Background parse(String wallpaper) {
		String value = wallpaper == null ? "" : wallpaper;
		if (value.startsWith("#")) {
			int argb = CssColour.parse(value);
			return new Colour(argb != -1 ? argb : BLACK);
		}
		if (value.startsWith("linear-gradient") || value.startsWith("radial-gradient")) {
			return parseGradient(value);
		}
		if (!value.isBlank()) {
			int argb = CssColour.parse(value);
			if (argb != -1) {
				return new Colour(argb);
			}
			// A URL/file/video wallpaper: the caller must hand us the decoded image instead.
			boolean imageSource = value.startsWith("file://") || value.startsWith("data:")
					|| value.startsWith("/") || value.startsWith("http");
			if (imageSource) {
				throw new IllegalArgumentException(
						"wallpaper is an image source; resolve it to Background.Image: " + value);
			}
		}
		return black();
	}

	private static Background parseGradient(String value) {
		Matcher matcher = GRADIENT_PATTERN.matcher(value);
		if (!matcher.matches()) {
			return black();
		}
		boolean linear = matcher.group(1).equals("linear");
		String[] parts = matcher.group(2).split(",", -1);
		int[] colours = new int[parts.length];
		float[] positions = new float[parts.length];
		int count = 0;
		for (int index = 0; index < parts.length; index++) {
			String part = parts[index].strip();
			if (linear && (part.startsWith("to ") || part.contains("deg"))) {
				continue;
			}
			Matcher colourMatch = STOP_COLOUR.matcher(part);
			String token = colourMatch.find() ? colourMatch.group(1) : null;
			int argb = token == null ? -1 : CssColour.parse(token);
			if (argb == -1) {
				// The browser throws on an invalid gradient stop; the export catches it and
				// paints the fallback black background.
				return black();
			}
			colours[count] = argb;
			positions[count] = index / (float) Math.max(parts.length - 1, 1);
			count++;
		}
		if (count == 0) {
			return black();
		}
		int[] finalColours = java.util.Arrays.copyOf(colours, count);
		float[] finalPositions = java.util.Arrays.copyOf(positions, count);
		if (count == 1) {
			return new Colour(finalColours[0]);
		}
		return linear
				? new LinearGradient(finalColours, finalPositions)
				: new RadialGradient(finalColours, finalPositions);
	}
}
