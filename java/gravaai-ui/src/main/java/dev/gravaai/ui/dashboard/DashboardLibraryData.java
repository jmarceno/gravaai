package dev.gravaai.ui.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four dashboard preferences that React keeps in {@code localStorage} (see
 * {@link DashboardLibraryStore} for the storage decision).
 *
 * @param folders {@code gravaai.project-folders.v1}
 * @param hiddenRawPaths {@code gravaai.raw-hidden.v1}: raw files removed from the library
 * @param rawNames {@code gravaai.raw-names.v1}: user names for raw files by path
 * @param colors {@code gravaai.dashboard-metadata.v1}: extra custom folder colours
 */
public record DashboardLibraryData(
		List<ProjectFolder> folders,
		List<String> hiddenRawPaths,
		Map<String, String> rawNames,
		List<String> colors) {

	public DashboardLibraryData {
		folders = List.copyOf(folders);
		hiddenRawPaths = List.copyOf(hiddenRawPaths);
		rawNames = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rawNames));
		colors = List.copyOf(colors);
	}

	public static DashboardLibraryData empty() {
		return new DashboardLibraryData(List.of(), List.of(), Map.of(), List.of());
	}
}
