package dev.gravaai.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;

/**
 * Tests for {@link I18n}, ported from the behaviour of {@code translateForLocale},
 * {@code interpolate}, {@code parseKey} and {@code normalizeLocale} in
 * {@code src/contexts/I18nContext.tsx} (plan §5.12).
 */
class I18nTest {

	@Test
	@DisplayName("translates a namespaced key path")
	void translatesNamespacedKey() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common", "{\"app\":{\"name\":\"Gravaai\"}}"))));

		assertEquals("Gravaai", i18n.t("common:app.name"));
	}

	@Test
	@DisplayName("defaults to the common namespace when the first segment is not a namespace")
	void defaultsToCommonNamespace() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common", "{\"app\":{\"name\":\"Gravaai\"}}"))));

		assertEquals("Gravaai", i18n.t("app.name"));
	}

	@Test
	@DisplayName("falls back to English when the active locale lacks the key")
	void fallsBackToEnglish() {
		I18n i18n = new I18n(source(Map.of(
				"en", Map.of("common", "{\"app\":{\"name\":\"Gravaai\",\"language\":\"Language\"}}"),
				"de", Map.of("common", "{\"app\":{\"name\":\"Gravaai\"}}"))));
		i18n.setLocale("de");

		assertEquals("Gravaai", i18n.t("common:app.name"));
		assertEquals("Language", i18n.t("common:app.language"));
	}

	@Test
	@DisplayName("falls back to the caller fallback and then the key")
	void fallsBackToFallbackThenKey() {
		I18n i18n = new I18n(source(Map.of("en", Map.of("common", "{}"))));

		assertEquals("Fallback", i18n.t("common:missing.key", "Fallback"));
		assertEquals("common:other.missing", i18n.t("common:other.missing"));
	}

	@Test
	@DisplayName("interpolates variables and renders integral numbers like JavaScript")
	void interpolatesVariables() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common", "{\"greet\":{\"hi\":\"Hi {{name}}, {{ count }} left\"}}"))));

		assertEquals("Hi Ada, 3 left",
				i18n.t("common:greet.hi", null, Map.of("name", "Ada", "count", 3.0)));
		assertEquals("Hi ,  left", i18n.t("common:greet.hi", null, Map.of()));
	}

	@Test
	@DisplayName("leaves placeholders untouched when no variables are supplied")
	void leavesPlaceholdersWithoutVariables() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common", "{\"greet\":{\"hi\":\"Hi {{name}}\"}}"))));

		assertEquals("Hi {{name}}", i18n.t("common:greet.hi"));
	}

	@Test
	@DisplayName("uses i18next plural suffixes when the bundle carries them")
	void usesPluralSuffixes() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common",
				"{\"items_one\":\"{{count}} item\",\"items_other\":\"{{count}} items\"}"))));

		assertEquals("1 item", i18n.t("common:items", null, Map.of("count", 1)));
		assertEquals("2 items", i18n.t("common:items", null, Map.of("count", 2)));
	}

	@Test
	@DisplayName("falls back to the other plural suffix when only one is translated")
	void fallsBackToOtherPluralSuffix() {
		I18n i18n = new I18n(source(Map.of("en", Map.of(
				"common", "{\"items_other\":\"{{count}} items\"}"))));

		assertEquals("1 items", i18n.t("common:items", null, Map.of("count", 1)));
	}

	@Test
	@DisplayName("falls back to the key when no bundle loads")
	void fallsBackToKeyWithoutBundle() {
		I18n i18n = new I18n(source(Map.of()));

		assertEquals("common:app.name", i18n.t("common:app.name"));
		assertTrue(i18n.bundle("en").isEmpty());
	}

	@Test
	@DisplayName("normalizes locale tags like the TS provider")
	void normalizesLocaleTags() {
		assertEquals("en", I18n.normalizeLocale(null));
		assertEquals("en", I18n.normalizeLocale(""));
		assertEquals("de", I18n.normalizeLocale("de"));
		assertEquals("zh-CN", I18n.normalizeLocale("zh-cn"));
		assertEquals("zh-CN", I18n.normalizeLocale("zh-Hans-CN"));
		assertEquals("zh-CN", I18n.normalizeLocale("zh"));
		assertEquals("pt-BR", I18n.normalizeLocale("pt-br"));
		assertEquals("en", I18n.normalizeLocale("xx-YY"));
		assertEquals("en", I18n.normalizeLocale("en-US"));
	}

	@Test
	@DisplayName("tracks the active locale")
	void tracksActiveLocale() {
		I18n i18n = new I18n(source(Map.of(
				"en", Map.of("common", "{\"app\":{\"name\":\"Gravaai\"}}"),
				"de", Map.of("common", "{\"app\":{\"name\":\"Gravaai DE\"}}"))));
		assertEquals("en", i18n.locale());
		i18n.setLocale("de");
		assertEquals("de", i18n.locale());
		assertEquals("Gravaai DE", i18n.t("common:app.name"));
		i18n.setLocale("fr");
		assertEquals("fr", i18n.locale());
		assertEquals("Gravaai", i18n.t("common:app.name"));
	}

	@Test
	@DisplayName("loads a locale that the UI cannot select yet (ru ships with the resources)")
	void loadsLocaleOutsideSupportedList() {
		I18n i18n = new I18n(source(Map.of("ru", Map.of(
				"common", "{\"app\":{\"name\":\"Gravaai (ru)\"}}"))));

		assertEquals(1, i18n.bundle("ru").size());
		assertEquals("en", I18n.normalizeLocale("ru"));
	}

	@Test
	@DisplayName("reads locale files from the classpath")
	void readsFromClasspath(@TempDir Path directory) throws IOException {
		Path en = directory.resolve("classpath/i18n/en");
		Files.createDirectories(en);
		String json = "{\"app\":{\"name\":\"Gravaai\"}}";
		Files.writeString(en.resolve("common.json"), json, StandardCharsets.UTF_8);

		try (URLClassLoader loader = new URLClassLoader(new URL[] {
				directory.resolve("classpath").toUri().toURL()})) {
			LocaleSource classpath = LocaleSource.classpath(loader);
			assertEquals(json, classpath.read("en", "common"));
			assertNull(classpath.read("de", "common"));
		}
	}

	private static LocaleSource source(Map<String, Map<String, String>> locales) {
		return (locale, namespace) -> {
			Map<String, String> namespaces = locales.get(locale);
			return namespaces == null ? null : namespaces.get(namespace);
		};
	}
}
