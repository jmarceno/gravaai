package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.svg.SVGDOM;

/**
 * Port of the cursor asset pipeline in {@code cursorRenderer.ts} + {@code uploadedCursorAssets.ts}:
 * the committed SVG cursor sets are rasterised to at least {@code MIN_RASTERIZED_CURSOR_HEIGHT},
 * trimmed to their alpha bounds (unless {@code preserveCanvas}) with the hotspot carried into the
 * anchor, {@code tahoe-inverted} inverts the Tahoe set, and {@code dot}/{@code figma} build their
 * single style asset. Assets are decoded once per process and shared (raster images, read-only).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class CursorAssets {

	/** One loaded cursor image: {@code LoadedCursorAsset}. */
	record Asset(Image image, double aspectRatio, double anchorX, double anchorY) {
	}

	/** {@code MIN_RASTERIZED_CURSOR_HEIGHT}. */
	private static final int MIN_RASTERIZED_CURSOR_HEIGHT = 512;

	/** {@code WINDOWS11_CURSOR_VIEWBOX_HEIGHT}. */
	private static final double WINDOWS11_CURSOR_VIEWBOX_HEIGHT = 32;

	/** {@code SUPPORTED_CURSOR_KEYS}. */
	static final List<String> SUPPORTED_CURSOR_KEYS = List.of("arrow", "text", "pointer",
			"crosshair", "open-hand", "closed-hand", "resize-ew", "resize-ns", "not-allowed");

	private record Source(String path, double anchorX, double anchorY, boolean preserveCanvas) {
	}

	private static final Pattern ATTRIBUTE = Pattern.compile(
			"\\s(width|height|viewBox)\\s*=\\s*\"([^\"]*)\"");

	private static volatile CursorAssets instance;

	private final Map<CursorStyle, Map<String, Asset>> sets = new EnumMap<>(CursorStyle.class);
	private final Map<CursorStyle, Asset> styleAssets = new EnumMap<>(CursorStyle.class);

	private CursorAssets() {
		sets.put(CursorStyle.MACOS, loadSet(macosSources()));
		Map<String, Asset> tahoe = loadSet(tahoeSources());
		sets.put(CursorStyle.TAHOE, tahoe);
		sets.put(CursorStyle.WINDOWS11, loadSet(windows11Sources()));
		Map<String, Asset> inverted = new HashMap<>();
		tahoe.forEach((key, asset) -> inverted.put(key, invert(asset)));
		sets.put(CursorStyle.TAHOE_INVERTED, Map.copyOf(inverted));
		styleAssets.put(CursorStyle.DOT, dotAsset());
		styleAssets.put(CursorStyle.FIGMA, figmaAsset());
	}

	/** The shared, lazily loaded asset table ({@code preloadCursorAssets}). */
	static CursorAssets get() {
		CursorAssets current = instance;
		if (current == null) {
			synchronized (CursorAssets.class) {
				current = instance;
				if (current == null) {
					current = new CursorAssets();
					instance = current;
				}
			}
		}
		return current;
	}

	/** Whether {@code style} draws per-cursor-type sprites ({@code isStatefulCursorStyle}). */
	static @Nullable boolean isStateful(CursorStyle style) {
		return style == CursorStyle.MACOS || style == CursorStyle.TAHOE
				|| style == CursorStyle.TAHOE_INVERTED || style == CursorStyle.WINDOWS11;
	}

	/**
	 * The asset for a style and telemetry cursor type: stateful sets fall back to {@code arrow}
	 * (the TS {@code spriteKey}), single styles use their style asset, and cursor-pack styles
	 * without loaded packs fall back to the Tahoe set (the TS warning path).
	 */
	@Nullable Asset resolve(CursorStyle style, String cursorType) {
		if (style.isSingleCursorStyle()) {
			return styleAssets.get(style);
		}
		Map<String, Asset> set = sets.get(isStateful(style) ? style : CursorStyle.TAHOE);
		String key = cursorType != null && SUPPORTED_CURSOR_KEYS.contains(cursorType)
				? cursorType
				: "arrow";
		Asset asset = set.get(key);
		return asset != null ? asset : set.get("arrow");
	}

	// ---- cursorSetAssets ------------------------------------------------------------------

	private static Source asset(String path, double hotspotX, double hotspotY) {
		return new Source(path, hotspotX / 100, hotspotY / 100, false);
	}

	private static Source windowsSystemAsset(String path, double hotspotX, double hotspotY) {
		return new Source(path, hotspotX / WINDOWS11_CURSOR_VIEWBOX_HEIGHT,
				hotspotY / WINDOWS11_CURSOR_VIEWBOX_HEIGHT, true);
	}

	private static Map<String, Source> macosSources() {
		return Map.of(
				"arrow", asset("macos/pointer-1__34-24.svg", 34, 24),
				"text", asset("macos/ibeamstroke-1__50-50.svg", 50, 50),
				"pointer", asset("macos/pointinghand-1__39-26.svg", 39, 26),
				"crosshair", asset("macos/crosshair-1__50-50.svg", 50, 50),
				"open-hand", asset("macos/openhand-1__50-50.svg", 50, 50),
				"closed-hand", asset("macos/closedhand-1__50-50.svg", 50, 50),
				"resize-ew", asset("macos/resizeeastwest-1__50-50.svg", 50, 50),
				"resize-ns", asset("macos/resizenorthsouth-1__50-50.svg", 50, 50),
				"not-allowed", asset("macos/notallowed-1__23-0.svg", 23, 0));
	}

	/** The legacy {@code tahoe} id draws the Golden Gate pack for hands. */
	private static Map<String, Source> tahoeSources() {
		return Map.of(
				"arrow", asset("tahoe/pointer-1__14-6.svg", 14, 6),
				"text", asset("tahoe/ibeam-1__50-44.svg", 50, 44),
				"pointer", asset("golden-gate/pointinghand.svg", 37.5, 25),
				"crosshair", asset("tahoe/crosshair-1__50-50.svg", 50, 50),
				"open-hand", asset("golden-gate/openhand.svg", 46.875, 50),
				"closed-hand", asset("golden-gate/closedhand.svg", 46.875, 50),
				"resize-ew", asset("tahoe/resizeeastwest-1__50-50.svg", 50, 50),
				"resize-ns", asset("tahoe/resizenorthsouth-1__50-49.svg", 50, 49),
				"not-allowed", asset("tahoe/notallowed-1__23-0.svg", 23, 0));
	}

	/** Hotspots are from the 32px frames embedded in the matching Windows .cur files. */
	private static Map<String, Source> windows11Sources() {
		return Map.of(
				"arrow", windowsSystemAsset("windows11/arrow__31-22.svg", 0, 0),
				"text", windowsSystemAsset("windows11/text__50-50.svg", 16, 16),
				"pointer", windowsSystemAsset("windows11/pointer__54-35.svg", 6, 0),
				"crosshair", windowsSystemAsset("windows11/cross__50-50.svg", 16, 16),
				"open-hand", windowsSystemAsset("windows11/grab__50-50.svg", 16, 16),
				"closed-hand", windowsSystemAsset("windows11/grabbing__50-50.svg", 16, 16),
				"resize-ew", windowsSystemAsset("windows11/resize-horizontal__51-49.svg", 11, 4),
				"resize-ns", windowsSystemAsset("windows11/resize-vertical__48-50.svg", 4, 11),
				"not-allowed", windowsSystemAsset("windows11/block__50-50.svg", 8, 8));
	}

	private static Map<String, Asset> loadSet(Map<String, Source> sources) {
		Map<String, Asset> loaded = new HashMap<>();
		sources.forEach((key, source) -> loaded.put(key, rasterize(source)));
		return Map.copyOf(loaded);
	}

	// ---- createRasterizedCursorAsset ------------------------------------------------------

	/** Unpremultiplied RGBA pixels of one rasterised cursor. */
	private record Pixels(int width, int height, @Nullable byte[] rgba) {
	}

	private static Asset rasterize(Source source) {
		Pixels pixels = renderSvg(readResource(source.path()), MIN_RASTERIZED_CURSOR_HEIGHT);
		double anchorX = clamp(source.anchorX(), 0, 1);
		double anchorY = clamp(source.anchorY(), 0, 1);
		if (source.preserveCanvas()) {
			return toAsset(pixels, anchorX, anchorY);
		}
		return trimToAlpha(pixels, pixels.width() * anchorX, pixels.height() * anchorY,
				anchorX, anchorY);
	}

	/**
	 * Rasterises an SVG at its natural (browser {@code naturalWidth/Height}) size scaled by
	 * {@code max(1, minHeight / naturalHeight)}.
	 */
	private static Pixels renderSvg(byte[] svg, int minHeight) {
		String text = new String(svg, StandardCharsets.UTF_8);
		double[] natural = naturalSize(text);
		double rasterScale = natural[1] > 0 ? Math.max(1, minHeight / natural[1]) : 1;
		int width = Math.max(1, (int) Math.round(natural[0] * rasterScale));
		int height = Math.max(1, (int) Math.round(natural[1] * rasterScale));
		try (Surface surface = Surface.makeRaster(ImageInfo.makeN32Premul(width, height))) {
			Canvas canvas = surface.getCanvas();
			canvas.clear(0);
			canvas.scale((float) (width / natural[0]), (float) (height / natural[1]));
			if (text.contains("data:image/")) {
				renderEmbeddedRaster(canvas, text);
			} else {
				try (Data data = Data.makeFromBytes(svg); SVGDOM dom = new SVGDOM(data)) {
					dom.setContainerSize((float) natural[0], (float) natural[1]);
					dom.render(canvas);
				}
			}
			return readUnpremul(surface, width, height);
		}
	}

	private static final Pattern EMBEDDED_IMAGE = Pattern.compile(
			"<image\\s+id=\"([^\"]+)\"\\s+x=\"([^\"]+)\"\\s+y=\"([^\"]+)\"\\s+"
					+ "width=\"([^\"]+)\"\\s+height=\"([^\"]+)\"\\s+"
					+ "xlink:href=\"data:image/[a-z]+;base64,([^\"]+)\"");

	private static final Pattern MASK_USE = Pattern.compile(
			"<mask[^>]*>.*?xlink:href=\"#([^\"]+)\"", Pattern.DOTALL);

	private static final Pattern CLIP_ZERO = Pattern.compile(
			"<clipPath id=\"clip-0\">\\s*<path[^>]*d=\"M ([0-9.]+) ([0-9.]+) L ([0-9.]+) [0-9.]+ "
					+ "L [0-9.]+ ([0-9.]+)");

	/**
	 * The macOS/Tahoe raster cursors are SVG wrappers around one embedded PNG ({@code <image>} +
	 * {@code <use>}); Skia's SVG module has no data-URI image provider, so they are drawn here.
	 * The {@code notallowed} variant masks the cursor PNG with a second PNG's alpha (its
	 * {@code filter-remove-color} keeps alpha only) and clips to {@code clip-0}; its ±18 px
	 * {@code <use>} translations cancel out, so both images draw at their own origin.
	 */
	private static void renderEmbeddedRaster(Canvas canvas, String text) {
		Map<String, Image> images = new HashMap<>();
		Map<String, float[]> bounds = new HashMap<>();
		Matcher matcher = EMBEDDED_IMAGE.matcher(text);
		while (matcher.find()) {
			byte[] png = java.util.Base64.getMimeDecoder().decode(matcher.group(6));
			images.put(matcher.group(1), Image.makeDeferredFromEncodedBytes(png));
			bounds.put(matcher.group(1), new float[] {Float.parseFloat(matcher.group(2)),
					Float.parseFloat(matcher.group(3)), Float.parseFloat(matcher.group(4)),
					Float.parseFloat(matcher.group(5))});
		}
		try {
			Matcher mask = MASK_USE.matcher(text);
			String maskId = mask.find() ? mask.group(1) : null;
			String contentId = images.keySet().stream()
					.filter(id -> !id.equals(maskId))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("no embedded cursor image"));
			canvas.save();
			Matcher clip = CLIP_ZERO.matcher(text);
			if (clip.find()) {
				canvas.clipRect(io.github.humbleui.types.Rect.makeLTRB(
						Float.parseFloat(clip.group(1)), Float.parseFloat(clip.group(2)),
						Float.parseFloat(clip.group(3)), Float.parseFloat(clip.group(4))));
			}
			if (maskId != null && images.containsKey(maskId)) {
				canvas.saveLayer(null, null);
				drawEmbedded(canvas, images.get(contentId), bounds.get(contentId), null);
				try (Paint maskPaint = new Paint()) {
					maskPaint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
					drawEmbedded(canvas, images.get(maskId), bounds.get(maskId), maskPaint);
				}
				canvas.restore();
			} else {
				drawEmbedded(canvas, images.get(contentId), bounds.get(contentId), null);
			}
			canvas.restore();
		} finally {
			images.values().forEach(Image::close);
		}
	}

	private static void drawEmbedded(Canvas canvas, @Nullable Image image, @Nullable float[] box, @Nullable Paint paint) {
		canvas.drawImageRect(image, io.github.humbleui.types.Rect.makeWH(image.getWidth(),
				image.getHeight()), io.github.humbleui.types.Rect.makeXYWH(box[0], box[1], box[2],
				box[3]), new io.github.humbleui.skija.FilterMipmap(
						io.github.humbleui.skija.FilterMode.LINEAR,
						io.github.humbleui.skija.MipmapMode.NONE), paint, true);
	}

	/**
	 * The browser natural size: explicit {@code width}/{@code height} (px or pt), else the
	 * {@code viewBox} size (Chrome keeps the viewBox aspect ratio for size-less SVG images).
	 */
	static double[] naturalSize(String svgText) {
		int rootStart = svgText.indexOf("<svg");
		int rootEnd = rootStart < 0 ? -1 : svgText.indexOf('>', rootStart);
		String root = rootStart < 0 || rootEnd < 0 ? svgText : svgText.substring(rootStart, rootEnd);
		Double width = null;
		Double height = null;
		double[] viewBox = null;
		Matcher matcher = ATTRIBUTE.matcher(root);
		while (matcher.find()) {
			switch (matcher.group(1)) {
				case "width" -> width = cssLength(matcher.group(2));
				case "height" -> height = cssLength(matcher.group(2));
				default -> {
					String[] parts = matcher.group(2).trim().split("[\\s,]+");
					if (parts.length == 4) {
						viewBox = new double[] {Double.parseDouble(parts[2]),
								Double.parseDouble(parts[3])};
					}
				}
			}
		}
		if (width != null && height != null) {
			return new double[] {width, height};
		}
		if (viewBox != null) {
			return viewBox;
		}
		return new double[] {300, 150};
	}

	private static @Nullable Double cssLength(String value) {
		String trimmed = value.trim();
		try {
			if (trimmed.endsWith("pt")) {
				return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2)) * 96 / 72;
			}
			if (trimmed.endsWith("px")) {
				return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2));
			}
			return Double.parseDouble(trimmed);
		} catch (NumberFormatException notAbsolute) {
			return null;
		}
	}

	/** {@code trimCanvasToAlpha}: crops to the non-zero alpha bounds and shifts the hotspot. */
	private static Asset trimToAlpha(Pixels pixels, double hotspotX, double hotspotY,
			double fallbackAnchorX, double fallbackAnchorY) {
		int width = pixels.width();
		int height = pixels.height();
		byte[] rgba = pixels.rgba();
		int minX = width;
		int minY = height;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (rgba[(y * width + x) * 4 + 3] == 0) {
					continue;
				}
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}
		}
		if (maxX < minX || maxY < minY) {
			return toAsset(pixels, fallbackAnchorX, fallbackAnchorY);
		}
		int croppedWidth = maxX - minX + 1;
		int croppedHeight = maxY - minY + 1;
		byte[] cropped = new byte[croppedWidth * croppedHeight * 4];
		for (int y = 0; y < croppedHeight; y++) {
			System.arraycopy(rgba, ((y + minY) * width + minX) * 4, cropped, y * croppedWidth * 4,
					croppedWidth * 4);
		}
		double anchorX = croppedWidth > 0
				? clamp((hotspotX - minX) / croppedWidth, 0, 1)
				: fallbackAnchorX;
		double anchorY = croppedHeight > 0
				? clamp((hotspotY - minY) / croppedHeight, 0, 1)
				: fallbackAnchorY;
		return toAsset(new Pixels(croppedWidth, croppedHeight, cropped), anchorX, anchorY);
	}

	/** {@code createInvertedCursorAsset}: inverts RGB of every non-transparent pixel. */
	private static Asset invert(Asset asset) {
		Image image = asset.image();
		int width = image.getWidth();
		int height = image.getHeight();
		byte[] rgba;
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(unpremulInfo(width, height));
			image.readPixels(bitmap, 0, 0);
			rgba = bitmap.readPixels();
		}
		for (int index = 0; index < rgba.length; index += 4) {
			if (rgba[index + 3] == 0) {
				continue;
			}
			rgba[index] = (byte) (255 - (rgba[index] & 0xFF));
			rgba[index + 1] = (byte) (255 - (rgba[index + 1] & 0xFF));
			rgba[index + 2] = (byte) (255 - (rgba[index + 2] & 0xFF));
		}
		return new Asset(Image.makeRasterFromBytes(unpremulInfo(width, height), rgba, width * 4L),
				asset.aspectRatio(), asset.anchorX(), asset.anchorY());
	}

	// ---- createCursorStyleAsset ----------------------------------------------------------

	/** The 112px dot canvas: white disc, radius 26, 10px {@code rgba(15, 23, 42, 0.88)} ring. */
	private static Asset dotAsset() {
		int size = 112;
		try (Surface surface = Surface.makeRaster(ImageInfo.makeN32Premul(size, size));
				Paint fill = new Paint(); Paint stroke = new Paint()) {
			Canvas canvas = surface.getCanvas();
			canvas.clear(0);
			fill.setColor(0xFFFFFFFF);
			stroke.setMode(PaintMode.STROKE);
			stroke.setStrokeWidth(10);
			stroke.setColor((int) Math.round(0.88 * 255) << 24 | 0x0F172A);
			canvas.drawCircle(size / 2f, size / 2f, 26, fill);
			canvas.drawCircle(size / 2f, size / 2f, 26, stroke);
			return toAsset(readUnpremul(surface, size, size), 0.5, 0.5);
		}
	}

	/** The minimal Figma-style cursor, trimmed around its (40, 22) hotspot. */
	private static Asset figmaAsset() {
		Pixels pixels = renderSvg(readResource("custom/minimal-cursor.svg"), 0);
		return trimToAlpha(pixels, 40, 22, 0, 0);
	}

	// ---- helpers --------------------------------------------------------------------------

	private static Asset toAsset(Pixels pixels, double anchorX, double anchorY) {
		Image image = Image.makeRasterFromBytes(unpremulInfo(pixels.width(), pixels.height()),
				pixels.rgba(), pixels.width() * 4L);
		double aspectRatio = pixels.height() > 0 ? pixels.width() / (double) pixels.height() : 1;
		return new Asset(image, aspectRatio, anchorX, anchorY);
	}

	private static Pixels readUnpremul(Surface surface, int width, int height) {
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(unpremulInfo(width, height));
			surface.readPixels(bitmap, 0, 0);
			return new Pixels(width, height, bitmap.readPixels());
		}
	}

	private static ImageInfo unpremulInfo(int width, int height) {
		return new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
	}

	private static byte[] readResource(String path) {
		String resource = "/dev/gravaai/media/cursors/" + path;
		try (InputStream stream = CursorAssets.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("missing cursor asset " + resource);
			}
			return stream.readAllBytes();
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
