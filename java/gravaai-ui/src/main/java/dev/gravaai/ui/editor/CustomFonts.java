package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import javafx.scene.text.Font;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Custom annotation fonts (port of {@code src/lib/customFonts.ts}, plan §5.12).
 *
 * <p>The React app injects a Google Fonts CSS import and waits for the browser font loader. JavaFX
 * cannot fetch webfonts, so {@link #defaultLoader()} downloads the CSS, resolves the served truetype
 * URL and registers it with {@link Font#loadFont}. The parsing/validation helpers and the
 * insert/remove rules are ported one-to-one; persistence moves from {@code localStorage} to
 * {@link AppSettingsStore} (single-process narrowing).
 */
public final class CustomFonts {

	public static final String STORAGE_KEY = "gravaai_custom_fonts";
	public static final String LEGACY_STORAGE_KEY = "openscreen_custom_fonts";

	/** {@code CustomFont}. */
	public record CustomFont(String id, String name, String fontFamily, String importUrl) {}

	/** Thrown when the same id/family is added twice ({@code DuplicateFontError}). */
	public static final class DuplicateFontException extends RuntimeException {
		public DuplicateFontException(String fontName) {
			super("Font \"" + fontName + "\" has already been added");
		}
	}

	/** Loads a font from its import URL; returns the registered font family. */
	@FunctionalInterface
	public interface FontLoader {
		String load(String importUrl) throws Exception;
	}

	private static final Pattern CSS_URL = Pattern.compile("url\\(([^)]+)\\)");
	private static final int MAX_FONT_BYTES = 5 * 1024 * 1024;

	private CustomFonts() {}

	/** {@code generateFontId}: {@code <slug>-<timestamp>}. */
	public static String generateFontId(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-") + "-"
				+ System.currentTimeMillis();
	}

	/** {@code parseFontFamilyFromImport}. */
	public static @Nullable String parseFontFamilyFromImport(String importUrl) {
		try {
			URI uri = URI.create(importUrl);
			for (String pair : uri.getQuery().split("&")) {
				String[] parts = pair.split("=", 2);
				if (parts.length == 2 && "family".equals(parts[0])) {
					String family = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
					return family.split(":")[0].replace("+", " ");
				}
			}
			return null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** {@code isValidGoogleFontsUrl}. */
	public static boolean isValidGoogleFontsUrl(String url) {
		try {
			URI uri = URI.create(url);
			return "https".equalsIgnoreCase(uri.getScheme())
					&& "fonts.googleapis.com".equals(uri.getHost())
					&& (uri.getPath().equals("/css") || uri.getPath().equals("/css2"))
					&& uri.getQuery() != null && uri.getQuery().contains("family=");
		} catch (RuntimeException error) {
			return false;
		}
	}

	/** {@code addCustomFont}: duplicate check, load verification, then append. */
	public static List<CustomFont> addCustomFont(
			List<CustomFont> existing, CustomFont font, FontLoader loader) throws Exception {
		String normalizedFamily = font.fontFamily().trim().toLowerCase(Locale.ROOT);
		for (CustomFont candidate : existing) {
			if (candidate.id().equals(font.id()) || candidate.fontFamily().trim()
					.toLowerCase(Locale.ROOT).equals(normalizedFamily)) {
				throw new DuplicateFontException(font.name());
			}
		}
		String loadedFamily = loader.load(font.importUrl());
		if (loadedFamily == null || loadedFamily.isBlank()) {
			throw new IOException("Font \"" + font.fontFamily() + "\" failed to load");
		}
		List<CustomFont> next = new ArrayList<>(existing);
		next.add(font);
		return List.copyOf(next);
	}

	/** {@code removeCustomFont}. */
	public static List<CustomFont> removeCustomFont(List<CustomFont> fonts, String fontId) {
		List<CustomFont> next = new ArrayList<>();
		for (CustomFont font : fonts) {
			if (!font.id().equals(fontId)) {
				next.add(font);
			}
		}
		return List.copyOf(next);
	}

	/**
	 * Production loader: fetches the Google Fonts CSS with a truetype user agent, resolves the
	 * served font file, downloads it and registers it with JavaFX.
	 */
	public static FontLoader defaultLoader() {
		return importUrl -> {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(10)).build();
			HttpRequest cssRequest = HttpRequest.newBuilder(URI.create(importUrl))
					.timeout(Duration.ofSeconds(15))
					.header("User-Agent",
							"Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)")
					.GET().build();
			HttpResponse<String> css = client.send(cssRequest,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (css.statusCode() != 200) {
				throw new IOException("Font stylesheet returned HTTP " + css.statusCode());
			}
			Matcher matcher = CSS_URL.matcher(css.body());
			if (!matcher.find()) {
				throw new IOException("Font stylesheet has no font file URL");
			}
			String fontUrl = matcher.group(1).trim().replace("\"", "").replace("'", "");
			HttpResponse<byte[]> font = client.send(
					HttpRequest.newBuilder(URI.create(fontUrl)).timeout(Duration.ofSeconds(20))
							.GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (font.statusCode() != 200 || font.body().length == 0
					|| font.body().length > MAX_FONT_BYTES) {
				throw new IOException("Could not download the font file");
			}
			Font loaded = Font.loadFont(new ByteArrayInputStream(font.body()), 12);
			if (loaded == null) {
				throw new IOException("JavaFX rejected the downloaded font");
			}
			return loaded.getFamily();
		};
	}

	/** Persists custom fonts in {@code app-settings.json} (localStorage replacement). */
	public static final class Store {

		private final AppSettingsStore settings;

		public Store(AppSettingsStore settings) {
			this.settings = settings;
		}

		/** {@code getCustomFonts}: legacy key fallback, invalid JSON reads empty. */
		public List<CustomFont> load() {
			JsonNode stored = settings.readSetting(STORAGE_KEY);
			if (stored == null) {
				stored = settings.readSetting(LEGACY_STORAGE_KEY);
			}
			if (stored == null || !stored.isArray()) {
				return List.of();
			}
			List<CustomFont> fonts = new ArrayList<>();
			for (JsonNode item : stored) {
				if (item.isObject() && item.path("id").isString() && item.path("name").isString()
						&& item.path("fontFamily").isString()
						&& item.path("importUrl").isString()) {
					fonts.add(new CustomFont(item.get("id").asString(), item.get("name").asString(),
							item.get("fontFamily").asString(), item.get("importUrl").asString()));
				}
			}
			return List.copyOf(fonts);
		}

		/** {@code saveCustomFonts}. */
		public void save(List<CustomFont> fonts) throws IOException {
			var list = Json.nodeFactory().arrayNode();
			for (CustomFont font : fonts) {
				var item = Json.nodeFactory().objectNode();
				item.put("id", font.id());
				item.put("name", font.name());
				item.put("fontFamily", font.fontFamily());
				item.put("importUrl", font.importUrl());
				list.add(item);
			}
			settings.writeSetting(STORAGE_KEY, list);
			settings.writeSetting(LEGACY_STORAGE_KEY, list);
		}
	}
}
