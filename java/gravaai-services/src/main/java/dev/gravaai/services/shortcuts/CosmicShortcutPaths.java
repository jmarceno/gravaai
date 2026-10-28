package dev.gravaai.services.shortcuts;

import java.nio.file.Path;
import java.util.List;

/**
 * Where COSMIC keeps its shortcut config (port of {@code CosmicShortcutPaths}).
 *
 * @param custom the user's custom shortcuts, the only file Gravaai writes
 * @param userDefaults the user's override of the system defaults
 * @param systemDefaults the system defaults, in XDG data directory order
 */
public record CosmicShortcutPaths(Path custom, Path userDefaults, List<Path> systemDefaults) {

	public CosmicShortcutPaths {
		systemDefaults = systemDefaults == null ? List.of() : List.copyOf(systemDefaults);
	}
}
