package dev.gravaai.ui.i18n;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.i18n.Locales;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build gate for the wired surfaces (card "i18n: wire the top surfaces"): every key literal
 * handed to {@link UiI18n#t} in gravaai-ui and gravaai-app sources must exist in the English
 * bundle. The runtime lookup silently falls back to the key itself, so without this check a typo
 * ships as untranslated text. Key parity across the shipped locales is {@code I18nCheckTest}'s
 * job; this test owns the code side.
 */
@NullMarked
class UiI18nKeysCheckTest {

	/** First argument of {@code UiI18n.t("...")} / {@code dev.gravaai.ui.i18n.UiI18n.t("...")}. */
	private static final Pattern T_KEY = Pattern.compile("UiI18n\\.t\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	@Test
	@DisplayName("every UiI18n.t key literal resolves in the en bundle")
	void everyWiredKeyExists() throws IOException {
		Set<String> keys = new LinkedHashSet<>();
		for (Path sourceRoot : sourceRoots()) {
			try (Stream<Path> files = Files.walk(sourceRoot)) {
				files.filter(file -> file.toString().endsWith(".java"))
						.forEach(file -> collectKeys(file, keys));
			}
		}
		assertTrue(!keys.isEmpty(), "no UiI18n.t call sites found; the scan is broken");

		Map<String, JsonNode> english = new dev.gravaai.core.i18n.I18n(
				dev.gravaai.core.i18n.LocaleSource.classpath(UiI18n.class.getClassLoader()))
				.bundle("en");
		List<String> missing = new ArrayList<>();
		for (String key : keys) {
			int colon = key.indexOf(':');
			String namespace = colon > 0 ? key.substring(0, colon) : "";
			String path = colon > 0 ? key.substring(colon + 1) : "";
			if (!Locales.I18N_NAMESPACES.contains(namespace)) {
				missing.add(key + " (unknown namespace)");
				continue;
			}
			JsonNode bundle = english.get(namespace);
			String value = bundle == null ? null : messageValue(bundle, path);
			if (value == null) {
				missing.add(key);
			}
		}
		assertTrue(missing.isEmpty(), "UiI18n keys missing from the en bundle: " + missing);
	}

	private static void collectKeys(Path file, Set<String> keys) {
		String source;
		try {
			source = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError("could not read " + file, error);
		}
		Matcher matcher = T_KEY.matcher(source);
		while (matcher.find()) {
			keys.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
		}
	}

	/** Walks {@code a.b.c} like I18n's dot-path lookup; returns string leaves only. */
	private static String messageValue(JsonNode namespace, String path) {
		JsonNode current = namespace;
		for (String part : path.split("\\.", -1)) {
			if (current == null || !current.isObject()) {
				return null;
			}
			current = current.get(part);
		}
		return current != null && current.isString() ? current.asString() : null;
	}

	/**
	 * The main source roots of the UI and app modules, anchored on the module layout like
	 * {@code I18nCheckTest.localesRoot()} (Maven runs this test with user.dir = gravaai-ui).
	 */
	private static List<Path> sourceRoots() {
		List<Path> roots = new ArrayList<>();
		for (Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
				candidate != null; candidate = candidate.getParent()) {
			if (Files.isDirectory(candidate.resolve("gravaai-core"))) {
				Path ui = candidate.resolve("gravaai-ui/src/main/java");
				Path app = candidate.resolve("gravaai-app/src/main/java");
				if (Files.isDirectory(ui)) {
					roots.add(ui);
				}
				if (Files.isDirectory(app)) {
					roots.add(app);
				}
				if (!roots.isEmpty()) {
					return List.copyOf(roots);
				}
			}
			Path nested = candidate.resolve("java");
			if (Files.isDirectory(nested.resolve("gravaai-core"))) {
				Path ui = nested.resolve("gravaai-ui/src/main/java");
				Path app = nested.resolve("gravaai-app/src/main/java");
				if (Files.isDirectory(ui)) {
					roots.add(ui);
				}
				if (Files.isDirectory(app)) {
					roots.add(app);
				}
				if (!roots.isEmpty()) {
					return List.copyOf(roots);
				}
			}
		}
		throw new AssertionError("Could not locate the source roots from "
				+ System.getProperty("user.dir"));
	}
}
