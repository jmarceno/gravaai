package dev.gravaai.services.shortcuts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stored global shortcuts setting (port of {@code GlobalShortcutsConfig}).
 *
 * <p>{@code bindings} keeps one entry per action, in action order. A {@code null} value means the
 * action is explicitly unbound; a missing entry is normalised away (missing or invalid parts fall
 * back to the defaults, an explicit {@code null} stays unbound).
 */
public record GlobalShortcutsConfig(boolean enabled, Map<GlobalShortcutAction, GlobalShortcutBinding> bindings) {

	public GlobalShortcutsConfig {
		Map<GlobalShortcutAction, GlobalShortcutBinding> copy = new LinkedHashMap<>();
		if (bindings != null) {
			copy.putAll(bindings);
		}
		bindings = Collections.unmodifiableMap(copy);
	}
}
