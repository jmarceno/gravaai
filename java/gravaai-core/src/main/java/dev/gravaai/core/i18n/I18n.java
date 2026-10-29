package dev.gravaai.core.i18n;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Translation lookup (port of {@code src/contexts/I18nContext.tsx}, plan §5.12).
 *
 * <p>Semantics kept from the TS implementation:
 * <ul>
 *   <li>keys are {@code namespace:key.path} when the first segment is one of
 *       {@link Locales#I18N_NAMESPACES}, otherwise {@code common:key}</li>
 *   <li>lookup order: active locale, English, the caller's fallback, then the key itself</li>
 *   <li>{@code {{var}}} interpolation with optional whitespace; a missing variable becomes ""</li>
 *   <li>i18next plural suffixes: when a {@code count} variable is supplied and the bundle carries
 *       {@code key_one}/{@code key_other}, the suffixed key is used (plan §5.12)</li>
 * </ul>
 *
 * <p>The active locale is normalized like {@code normalizeLocale} in the TS source. Non-English
 * bundles are loaded lazily and cached; a locale that fails to load falls back to English, like
 * {@code getLocaleBundle} + the provider's fallback (the failed bundle is retried on the next
 * lookup, matching the cache deletion in {@code localeBundles.ts}).
 *
 * <p>Not ported here (UI concerns, P1.3): {@code getInitialLocale} (localStorage key
 * {@code gravaai.locale} plus {@code navigator.languages}) and the "wait for the first bundle"
 * gate.
 */
public final class I18n {

	private static final Pattern INTERPOLATION = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

	private final LocaleSource source;
	private final Map<String, Map<String, JsonNode>> bundles = new ConcurrentHashMap<>();
	private volatile String locale;

	public I18n(LocaleSource source) {
		this(source, Locales.DEFAULT_LOCALE);
	}

	public I18n(LocaleSource source, String locale) {
		this.source = source;
		this.locale = normalizeLocale(locale);
	}

	public String locale() {
		return locale;
	}

	public void setLocale(String locale) {
		this.locale = normalizeLocale(locale);
	}

	/** {@code t(key)}: no variables, so {@code {{var}}} placeholders stay untouched. */
	public String t(String key) {
		return translateForLocale(locale, bundleOrNull(locale), key, null, null);
	}

	/** {@code t(key, fallback)}: no variables, so placeholders stay untouched. */
	public String t(String key, String fallback) {
		return translateForLocale(locale, bundleOrNull(locale), key, fallback, null);
	}

	/** Plan §5.12 shorthand: {@code t("namespace:key.path", Map.of("count", 1))}. */
	public String t(String key, Map<String, ?> vars) {
		return t(key, null, vars);
	}

	/** @param fallback returned when no bundle carries the key (may be null) */
	public String t(String key, @Nullable String fallback, Map<String, ?> vars) {
		return translateForLocale(locale, bundleOrNull(locale), key, fallback, vars);
	}

	/**
	 * One locale's namespace trees, loaded and cached. Missing or broken files yield an empty map;
	 * the locale is retried on the next call.
	 */
	public Map<String, JsonNode> bundle(String locale) {
		Map<String, JsonNode> loaded = bundleOrNull(locale);
		return loaded == null ? Map.of() : loaded;
	}

	/** Port of {@code normalizeLocale}: exact, case-insensitive, extended subtags, language-only. */
	public static String normalizeLocale(@Nullable String locale) {
		if (locale == null || locale.isEmpty()) {
			return Locales.DEFAULT_LOCALE;
		}
		if (Locales.SUPPORTED_LOCALES.contains(locale)) {
			return locale;
		}

		for (String supported : Locales.SUPPORTED_LOCALES) {
			if (supported.toLowerCase(Locale.ROOT).equals(locale.toLowerCase(Locale.ROOT))) {
				return supported;
			}
		}

		String[] parts = locale.split("-", -1);
		if (parts.length >= 3) {
			String languageRegion = parts[0] + "-" + parts[parts.length - 1];
			if (Locales.SUPPORTED_LOCALES.contains(languageRegion)) {
				return languageRegion;
			}
		}

		String language = parts[0].toLowerCase(Locale.ROOT);
		for (String supported : Locales.SUPPORTED_LOCALES) {
			if (supported.split("-")[0].toLowerCase(Locale.ROOT).equals(language)) {
				return supported;
			}
		}

		return Locales.DEFAULT_LOCALE;
	}

	private @Nullable Map<String, JsonNode> bundleOrNull(String locale) {
		Map<String, JsonNode> cached = bundles.get(locale);
		if (cached != null) {
			return cached;
		}

		Map<String, JsonNode> loaded = loadBundle(locale);
		if (loaded == null) {
			return null;
		}
		Map<String, JsonNode> existing = bundles.putIfAbsent(locale, loaded);
		return existing != null ? existing : loaded;
	}

	private @Nullable Map<String, JsonNode> loadBundle(String locale) {
		Map<String, JsonNode> namespaces = new LinkedHashMap<>();
		for (String namespace : Locales.I18N_NAMESPACES) {
			String json;
			try {
				json = source.read(locale, namespace);
			} catch (IOException error) {
				return null;
			}
			if (json == null) {
				continue;
			}
			JsonNode parsed;
			try {
				parsed = Json.parseJsonWithByteOrderMark(json);
			} catch (RuntimeException error) {
				return null;
			}
			if (!parsed.isObject()) {
				return null;
			}
			namespaces.put(namespace, parsed);
		}
		return namespaces;
	}

	private String translateForLocale(
			String locale,
			@Nullable Map<String, JsonNode> loadedBundle,
			String key,
			@Nullable String fallback,
			@Nullable Map<String, ?> vars) {
		ParsedKey parsed = parseKey(key);
		Map<String, JsonNode> preferred =
				locale.equals(Locales.DEFAULT_LOCALE) ? bundleOrNull(Locales.DEFAULT_LOCALE) : loadedBundle;
		Map<String, JsonNode> english = bundleOrNull(Locales.DEFAULT_LOCALE);

		String rawValue = null;
		if (vars != null && vars.get("count") instanceof Number count) {
			// i18next chooses a suffix from the count; the data decides which suffixes exist. No
			// shipped locale carries plural keys today (the parity test would show them).
			String chosen = count.doubleValue() == 1.0 ? "_one" : "_other";
			String alternative = chosen.equals("_one") ? "_other" : "_one";
			rawValue = firstMessage(preferred, english, parsed, parsed.path() + chosen);
			if (rawValue == null) {
				rawValue = firstMessage(preferred, english, parsed, parsed.path() + alternative);
			}
		}

		if (rawValue == null) {
			rawValue = firstMessage(preferred, english, parsed, parsed.path());
		}
		if (rawValue == null) {
			rawValue = fallback;
		}
		if (rawValue == null) {
			rawValue = key;
		}
		return interpolate(rawValue, vars);
	}

	/** First hit in the active bundle, then English, exactly like translateForLocale in the TS. */
	private static @Nullable String firstMessage(
			@Nullable Map<String, JsonNode> preferred,
			@Nullable Map<String, JsonNode> english,
			ParsedKey parsed,
			String path) {
		String value = getMessageValue(preferred == null ? null : preferred.get(parsed.namespace()), path);
		if (value == null) {
			value = getMessageValue(english == null ? null : english.get(parsed.namespace()), path);
		}
		return value;
	}

	private static @Nullable String getMessageValue(@Nullable JsonNode namespace, String path) {
		if (namespace == null) {
			return null;
		}
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
	 * Port of {@code parseKey}: the TS keys are dot separated ({@code common.app.name}). The plan
	 * (§5.12) writes {@code namespace:key.path}; that documented short form is accepted too.
	 */
	private static ParsedKey parseKey(String key) {
		int colon = key.indexOf(':');
		if (colon > 0 && colon < key.length() - 1
				&& Locales.I18N_NAMESPACES.contains(key.substring(0, colon))) {
			return new ParsedKey(key.substring(0, colon), key.substring(colon + 1));
		}

		String[] parts = key.split("\\.", -1);
		if (parts.length > 1 && Locales.I18N_NAMESPACES.contains(parts[0])) {
			return new ParsedKey(parts[0], String.join(".", Arrays.copyOfRange(parts, 1, parts.length)));
		}
		return new ParsedKey("common", key);
	}

	private static String interpolate(String template, @Nullable Map<String, ?> vars) {
		if (vars == null) {
			return template;
		}
		Matcher matcher = INTERPOLATION.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			Object value = vars.get(matcher.group(1));
			matcher.appendReplacement(
					result, Matcher.quoteReplacement(value == null ? "" : stringifyVar(value)));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** {@code String(value)} for the JS side: integral numbers print without a trailing ".0". */
	private static String stringifyVar(Object value) {
		if (value instanceof Number number) {
			double asDouble = number.doubleValue();
			if (!Double.isInfinite(asDouble) && asDouble == Math.rint(asDouble)) {
				return Long.toString((long) asDouble);
			}
		}
		return String.valueOf(value);
	}

	private record ParsedKey(String namespace, String path) {}
}
