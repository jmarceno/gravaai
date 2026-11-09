package dev.gravaai.core.i18n;

import java.util.List;

/**
 * Locale configuration (port of {@code src/i18n/config.ts}, plan §5.12).
 *
 * <p>{@link #SUPPORTED_LOCALES} mirrors the TS list, which does not include {@code ru} even though
 * {@code src/i18n/locales/ru/} exists: the bundle is shipped and key-checked, but the app cannot
 * select it until the TS list does. The parity test discovers directories (11) like
 * {@code scripts/i18n-check.mjs} does.
 *
 * <p>{@code dashboard} is the one namespace without an upstream file: it carries the strings of the
 * Java-only surfaces (the library dashboard, the app settings pages and the language picker) that
 * the Electron app never had, translated in this repository like every other namespace.
 */
public final class Locales {

	public static final String DEFAULT_LOCALE = "en";

	public static final List<String> SUPPORTED_LOCALES =
			List.of("en", "es", "fr", "de", "it", "nl", "ko", "pt-BR", "zh-CN", "zh-TW");

	public static final List<String> I18N_NAMESPACES =
			List.of("common", "launch", "editor", "timeline", "settings", "dialogs", "shortcuts",
					"dashboard");

	private Locales() {}
}
