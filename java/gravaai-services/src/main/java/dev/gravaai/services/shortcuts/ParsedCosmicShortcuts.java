package dev.gravaai.services.shortcuts;

import java.util.List;

/** A parsed COSMIC shortcut map: every entry plus the two brace offsets (port of
 * {@code ParsedCosmicShortcuts}). */
public record ParsedCosmicShortcuts(List<CosmicShortcutEntry> entries, int openBrace, int closeBrace) {

	public ParsedCosmicShortcuts {
		entries = entries == null ? List.of() : List.copyOf(entries);
	}
}
