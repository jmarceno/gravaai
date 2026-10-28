package dev.gravaai.core.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code scripts/i18n-check.mjs} (plan §5.12): every key of the base locale {@code en} must
 * exist in every other locale and vice versa, per namespace file. Key comparison is exact, so
 * i18next plural suffixed keys ({@code _one}, {@code _other}) have to match one to one.
 *
 * <p>{@link #check(Path)} returns the warning/error lines the script prints; the JUnit parity test
 * asserts that the list is empty for the shipped resources.
 */
public final class LocaleParityCheck {

	private static final String BASE_LOCALE = "en";
	private static final String ERROR_PREFIX = "i18n-check: ";

	private LocaleParityCheck() {}

	public static List<String> check(Path localesRoot) {
		List<String> errors = new ArrayList<>();
		List<String> locales;
		try {
			locales = new ArrayList<>(listDirectoryNames(localesRoot));
		} catch (IOException error) {
			errors.add(ERROR_PREFIX + "failed to list " + localesRoot + ": " + error);
			return errors;
		}
		locales.sort(String::compareTo);

		if (!locales.contains(BASE_LOCALE)) {
			errors.add(ERROR_PREFIX + "expected base locale directory \"" + BASE_LOCALE + "\"");
			return errors;
		}

		List<String> namespaceFiles;
		try {
			namespaceFiles = listJsonFiles(localesRoot.resolve(BASE_LOCALE));
		} catch (IOException error) {
			errors.add(ERROR_PREFIX + "failed to list " + localesRoot.resolve(BASE_LOCALE) + ": " + error);
			return errors;
		}

		for (String namespaceFile : namespaceFiles) {
			JsonNode baseData;
			try {
				baseData = load(localesRoot.resolve(BASE_LOCALE).resolve(namespaceFile));
			} catch (IOException | RuntimeException error) {
				errors.add(ERROR_PREFIX + "failed to load "
						+ relative(localesRoot, localesRoot.resolve(BASE_LOCALE).resolve(namespaceFile))
						+ ": " + error.getMessage());
				continue;
			}
			Set<String> baseKeys = new LinkedHashSet<>(collectKeyPaths(baseData));

			for (String locale : locales) {
				if (locale.equals(BASE_LOCALE)) {
					continue;
				}

				Path localeFile = localesRoot.resolve(locale).resolve(namespaceFile);
				if (!Files.exists(localeFile)) {
					errors.add(ERROR_PREFIX + "missing namespace file " + locale + "/" + namespaceFile);
					continue;
				}

				JsonNode localeData;
				try {
					localeData = load(localeFile);
				} catch (IOException | RuntimeException error) {
					errors.add(ERROR_PREFIX + "failed to load " + relative(localesRoot, localeFile)
							+ ": " + error.getMessage());
					continue;
				}
				Set<String> localeKeys = new LinkedHashSet<>(collectKeyPaths(localeData));

				for (String key : baseKeys) {
					if (!localeKeys.contains(key)) {
						errors.add(ERROR_PREFIX + "missing key " + locale + "/" + namespaceFile + ":" + key);
					}
				}
				for (String key : localeKeys) {
					if (!baseKeys.contains(key)) {
						errors.add(ERROR_PREFIX + "extra key " + locale + "/" + namespaceFile + ":" + key);
					}
				}
			}
		}

		return errors;
	}

	/**
	 * Port of {@code collectKeyPaths}: leaf paths with dots. Arrays and empty objects are leaves;
	 * nested objects are flattened (so {@code {"a":{"b":"x"}}} yields {@code ["a.b"]}).
	 */
	public static List<String> collectKeyPaths(JsonNode value) {
		return collectKeyPaths(value, "");
	}

	private static List<String> collectKeyPaths(JsonNode value, String prefix) {
		if (value == null || !value.isObject()) {
			return prefix.isEmpty() ? List.of() : List.of(prefix);
		}

		List<String> paths = new ArrayList<>();
		boolean empty = true;
		for (var entry : value.properties()) {
			empty = false;
			String nextPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			JsonNode child = entry.getValue();
			if (child.isObject()) {
				paths.addAll(collectKeyPaths(child, nextPrefix));
			} else {
				paths.add(nextPrefix);
			}
		}
		if (empty) {
			return prefix.isEmpty() ? List.of() : List.of(prefix);
		}
		return paths;
	}

	private static JsonNode load(Path file) throws IOException {
		return Json.parseJsonWithByteOrderMark(Files.readString(file, StandardCharsets.UTF_8));
	}

	private static List<String> listDirectoryNames(Path root) throws IOException {
		try (Stream<Path> entries = Files.list(root)) {
			return entries.filter(Files::isDirectory).map(entry -> entry.getFileName().toString()).toList();
		}
	}

	private static List<String> listJsonFiles(Path directory) throws IOException {
		try (Stream<Path> entries = Files.list(directory)) {
			return entries
					.filter(Files::isRegularFile)
					.map(entry -> entry.getFileName().toString())
					.filter(name -> name.endsWith(".json"))
					.sorted()
					.toList();
		}
	}

	private static String relative(Path root, Path file) {
		try {
			return root.relativize(file).toString();
		} catch (IllegalArgumentException error) {
			return file.toString();
		}
	}
}
