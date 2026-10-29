package dev.gravaai.core.i18n;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where {@link I18n} reads locale bundles from (plan §5.12).
 *
 * <p>The locale JSON files are copied unchanged from {@code src/i18n/locales/**} into
 * {@code gravaai-ui/src/main/resources/i18n/}; at runtime they are on the classpath as
 * {@code i18n/<locale>/<namespace>.json}. Tests and tools can point at the directory instead. Core
 * stays free of a compile-time dependency on gravaai-ui: the UI module supplies the classpath.
 */
@FunctionalInterface
public interface LocaleSource {

	/**
	 * @return the UTF-8 JSON text of one namespace file, or {@code null} when it does not exist
	 */
	@Nullable String read(String locale, String namespace) throws IOException;

	/** Reads from the context class loader (the app's classpath, i.e. gravaai-ui resources). */
	static @Nullable LocaleSource classpath() {
		return classpath(Thread.currentThread().getContextClassLoader());
	}

	static @Nullable LocaleSource classpath(ClassLoader loader) {
		return (locale, namespace) -> {
			try (InputStream input = loader.getResourceAsStream(
					"i18n/" + locale + "/" + namespace + ".json")) {
				return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}
		};
	}

	/** Reads {@code <root>/<locale>/<namespace>.json} (the copied resources during tests). */
	static @Nullable LocaleSource directory(Path localesRoot) {
		return (locale, namespace) -> {
			Path file = localesRoot.resolve(locale).resolve(namespace + ".json");
			return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		};
	}
}
