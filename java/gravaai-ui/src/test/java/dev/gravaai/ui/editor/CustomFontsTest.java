package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.settings.AppSettingsStore;

/** Port of {@code src/lib/customFonts.ts} parsing/rules + the AppSettingsStore replacement. */
class CustomFontsTest {

	private static CustomFonts.CustomFont font(String id, String name, String family) {
		return new CustomFonts.CustomFont(id, name, family,
				"https://fonts.googleapis.com/css2?family=Roboto&display=swap");
	}

	@Test
	@DisplayName("generates a slug plus timestamp font id")
	void generatesFontId() {
		String id = CustomFonts.generateFontId("Open Sans");
		assertTrue(id.startsWith("open-sans-"));
		assertTrue(id.substring("open-sans-".length()).matches("\\d+"));
		assertTrue(CustomFonts.generateFontId("  My   Font ").startsWith("-my-font-"));
	}

	@Test
	@DisplayName("parses the font family from a Google Fonts import URL")
	void parsesFontFamily() {
		assertEquals("Roboto", CustomFonts.parseFontFamilyFromImport(
				"https://fonts.googleapis.com/css2?family=Roboto:wght@400;700&display=swap"));
		assertEquals("Open Sans", CustomFonts.parseFontFamilyFromImport(
				"https://fonts.googleapis.com/css2?family=Open+Sans&display=swap"));
		assertNull(CustomFonts.parseFontFamilyFromImport("not a url"));
		assertNull(CustomFonts.parseFontFamilyFromImport(
				"https://fonts.googleapis.com/css2?display=swap"));
	}

	@Test
	@DisplayName("validates Google Fonts import URLs")
	void validatesGoogleFontsUrls() {
		assertTrue(CustomFonts.isValidGoogleFontsUrl(
				"https://fonts.googleapis.com/css2?family=Roboto&display=swap"));
		assertTrue(CustomFonts.isValidGoogleFontsUrl(
				"https://fonts.googleapis.com/css?family=Roboto"));
		assertFalse(CustomFonts.isValidGoogleFontsUrl(
				"http://fonts.googleapis.com/css2?family=Roboto"));
		assertFalse(CustomFonts.isValidGoogleFontsUrl(
				"https://example.com/css2?family=Roboto"));
		assertFalse(CustomFonts.isValidGoogleFontsUrl(
				"https://fonts.googleapis.com/css2?display=swap"));
		assertFalse(CustomFonts.isValidGoogleFontsUrl("garbage"));
	}

	@Test
	@DisplayName("adds a font after the loader verifies it")
	void addsFont() throws Exception {
		List<CustomFonts.CustomFont> fonts = CustomFonts.addCustomFont(List.of(),
				font("id", "Roboto", "Roboto"), url -> "Roboto");
		assertEquals(1, fonts.size());
		assertEquals("Roboto", fonts.get(0).fontFamily());
	}

	@Test
	@DisplayName("rejects duplicate ids and font families case-insensitively")
	void rejectsDuplicates() {
		List<CustomFonts.CustomFont> existing = List.of(font("id", "Roboto", "Roboto"));
		assertThrows(CustomFonts.DuplicateFontException.class,
				() -> CustomFonts.addCustomFont(existing, font("id", "Roboto", "Roboto"),
						url -> "Roboto"));
		assertThrows(CustomFonts.DuplicateFontException.class,
				() -> CustomFonts.addCustomFont(existing, font("other", "Roboto", " roboto "),
						url -> "Roboto"));
	}

	@Test
	@DisplayName("propagates a loader failure and does not add the font")
	void propagatesLoaderFailure() {
		assertThrows(Exception.class, () -> CustomFonts.addCustomFont(List.of(),
				font("id", "Roboto", "Roboto"), url -> {
					throw new IllegalStateException("Font load timeout");
				}));
	}

	@Test
	@DisplayName("removes a font by id")
	void removesFont() {
		List<CustomFonts.CustomFont> fonts = List.of(font("a", "A", "FamilyA"),
				font("b", "B", "FamilyB"));
		List<CustomFonts.CustomFont> next = CustomFonts.removeCustomFont(fonts, "a");
		assertEquals(List.of("b"), next.stream().map(CustomFonts.CustomFont::id).toList());
	}

	@Test
	@DisplayName("persists custom fonts through the app settings store")
	void persistsFonts(@TempDir Path directory) throws Exception {
		AppSettingsStore settings = new AppSettingsStore(directory.resolve("app-settings.json"));
		CustomFonts.Store store = new CustomFonts.Store(settings);
		List<CustomFonts.CustomFont> fonts = List.of(font("a", "Roboto", "Roboto"));

		store.save(fonts);
		assertEquals(fonts, store.load());
	}

	@Test
	@DisplayName("falls back to the legacy storage key and ignores invalid entries")
	void fallsBackToLegacyKey(@TempDir Path directory) throws Exception {
		AppSettingsStore settings = new AppSettingsStore(directory.resolve("app-settings.json"));
		settings.writeSetting(CustomFonts.LEGACY_STORAGE_KEY, dev.gravaai.core.json.Json
				.parseJsonWithByteOrderMark("""
						[{"id":"a","name":"Roboto","fontFamily":"Roboto",
						"importUrl":"https://fonts.googleapis.com/css2?family=Roboto"},
						{"id":"bad"}]
						"""));

		List<CustomFonts.CustomFont> loaded = new CustomFonts.Store(settings).load();
		assertEquals(1, loaded.size());
		assertEquals("Roboto", loaded.get(0).name());
	}
}
