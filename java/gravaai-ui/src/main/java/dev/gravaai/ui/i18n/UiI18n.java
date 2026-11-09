package dev.gravaai.ui.i18n;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.gravaai.core.i18n.I18n;
import dev.gravaai.core.i18n.Locales;
import dev.gravaai.core.i18n.LocaleSource;
import dev.gravaai.core.settings.AppSettingsStore;

/**
 * The app-wide translation lookup over the locale bundles shipped in
 * {@code gravaai-ui/src/main/resources/i18n/}. The app configures it once at startup
 * ({@link #init}); every surface then reads its strings through {@link #t}.
 *
 * <p>The selected language is stored in {@code app-settings.json} under {@link #SETTING}
 * (the localStorage key the React app used). The stored value is either {@link #SYSTEM}
 * — resolve the system locale at startup, the default — or one of
 * {@link Locales#SUPPORTED_LOCALES}. {@link #select} persists a new choice and notifies the
 * listeners, which rebuild the visible surfaces (dashboard scene, HUD bar).
 *
 * <p>Before {@link #init} runs (unit tests, screenshot harnesses) the holder serves English from
 * the classpath and persists nothing, so views and their tests stay deterministic.
 */
public final class UiI18n {

	/** The {@code app-settings.json} key carrying the language preference. */
	public static final String SETTING = "gravaai.locale";

	/** Stored-preference value: follow the system locale, never persist a concrete one. */
	public static final String SYSTEM = "system";

	/** One entry of the settings language picker; the label is the language's own name. */
	public record LanguageOption(String value, String label) {}

	private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile I18n i18n = defaultI18n();
	private static volatile @Nullable AppSettingsStore settings;
	private static volatile String selected = SYSTEM;

	private UiI18n() {}

	/** Classpath bundles of the loader that can see gravaai-ui's resources. */
	private static I18n defaultI18n() {
		return new I18n(java.util.Objects.requireNonNull(
				LocaleSource.classpath(UiI18n.class.getClassLoader()),
				"locale bundles missing from the classpath"));
	}

	/**
	 * App startup: read the stored preference (default: the system locale) into the lookup and
	 * remember the store so {@link #select} can persist later choices. Classpath bundles come from
	 * the loader that can see {@code gravaai-ui} resources, not the caller's TCCL.
	 */
	public static void init(AppSettingsStore store) {
		settings = store;
		i18n = new I18n(java.util.Objects.requireNonNull(
				LocaleSource.classpath(UiI18n.class.getClassLoader()),
				"locale bundles missing from the classpath"), resolve(store));
	}

	/** The stored preference ({@link #SYSTEM} or a supported locale), not the resolved locale. */
	public static String selected() {
		return selected;
	}

	/** The resolved active locale (a {@link Locales#SUPPORTED_LOCALES} entry). */
	public static String locale() {
		return i18n.locale();
	}

	/** {@link I18n#t(String)}: no variables, so {@code {{var}}} placeholders stay untouched. */
	public static String t(String key) {
		return i18n.t(key);
	}

	/** {@link I18n#t(String, String)}: {@code fallback} when no bundle carries the key. */
	public static String t(String key, String fallback) {
		return i18n.t(key, fallback);
	}

	/** {@link I18n#t(String, Map)}: {@code {{var}}} interpolation. */
	public static String t(String key, Map<String, ?> vars) {
		return i18n.t(key, vars);
	}

	/** {@link I18n#t(String, String, Map)}: fallback plus interpolation. */
	public static String t(String key, @Nullable String fallback, Map<String, ?> vars) {
		return i18n.t(key, fallback, vars);
	}

	/**
	 * Applies a language choice ({@link #SYSTEM} or a supported locale): normalizes it, persists it
	 * when it changed and notifies the listeners (on the calling thread; the app calls this on the
	 * FX thread and the listeners rebuild the scenes there).
	 *
	 * @return {@code true} when the resolved locale changed
	 */
	public static boolean select(String preference) {
		String next = preference == null || preference.isBlank() || SYSTEM.equals(preference)
				? SYSTEM
				: I18n.normalizeLocale(preference);
		String current = selected;
		if (next.equals(current)) {
			return false;
		}
		selected = next;
		if (settings != null) {
			try {
				settings.writeSetting(SETTING,
						dev.gravaai.core.json.Json.nodeFactory().stringNode(next));
			} catch (RuntimeException | java.io.IOException error) {
				System.getLogger("gravaai.ui.i18n").log(System.Logger.Level.WARNING,
						"could not save the language preference: " + error);
			}
		}
		i18n.setLocale(resolve(next));
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
		return true;
	}

	/** Registers a locale-change listener ({@link #select} fires it). */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	/** The picker entries: the system option first, then the supported locales in their own name. */
	public static List<LanguageOption> languageOptions() {
		List<LanguageOption> options = new ArrayList<>();
		options.add(new LanguageOption(SYSTEM, t("dashboard:settings.general.languageSystem")));
		for (String locale : Locales.SUPPORTED_LOCALES) {
			options.add(new LanguageOption(locale, NATIVE_NAMES.getOrDefault(locale, locale)));
		}
		return List.copyOf(options);
	}

	/** Native language names for the picker; languages identify themselves, they are not translated. */
	private static final Map<String, String> NATIVE_NAMES = Map.of(
			"en", "English",
			"es", "Español",
			"fr", "Français",
			"de", "Deutsch",
			"it", "Italiano",
			"nl", "Nederlands",
			"ko", "한국어",
			"pt-BR", "Português (Brasil)",
			"zh-CN", "简体中文",
			"zh-TW", "繁體中文");

	/** Tests: back to the deterministic pre-init state (English, no persistence). */
	public static void resetForTests() {
		settings = null;
		selected = SYSTEM;
		i18n = defaultI18n();
	}

	private static String resolve(@Nullable AppSettingsStore store) {
		return resolve(store == null ? null : readSetting(store));
	}

	private static @Nullable String readSetting(AppSettingsStore store) {
		try {
			Object value = store.readSetting(SETTING);
			return value instanceof tools.jackson.databind.JsonNode node && node.isString()
					? node.asString()
					: null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** {@code system} (or anything unusable) resolves to the system locale, normalized. */
	private static String resolve(@Nullable String preference) {
		if (preference == null || preference.isBlank() || SYSTEM.equals(preference)) {
			return I18n.normalizeLocale(Locale.getDefault().toLanguageTag());
		}
		return I18n.normalizeLocale(preference);
	}
}
