package dev.gravaai.services.shortcuts;

import java.util.List;

/**
 * One entry of COSMIC's shortcut map, in the shape the parser understands (port of
 * {@code CosmicShortcutEntry}).
 *
 * @param modifiers as written, e.g. {@code ["Super", "Alt"]}
 * @param key null when the entry only has a {@code keycode} or no key at all
 * @param description null when the entry has no description
 * @param action raw action text, e.g. {@code Spawn("…")}, {@code Terminate}, {@code System(Terminal)}
 * @param spawnCommand unescaped command when the action is {@code Spawn("…")}, else null
 * @param start offset of the whole entry including its line's indentation, trailing comma and newline
 * @param end offset just past the entry (including the trailing comma and newline when present)
 * @param valueEnd offset just past the action value
 * @param hasTrailingComma whether a comma follows the action value
 */
public record CosmicShortcutEntry(
		List<String> modifiers,
		String key,
		String description,
		String action,
		String spawnCommand,
		int start,
		int end,
		int valueEnd,
		boolean hasTrailingComma) {

	public CosmicShortcutEntry {
		modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
	}
}
