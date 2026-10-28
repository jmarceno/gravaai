package dev.gravaai.ui.dashboard;

import java.util.List;

/**
 * A dashboard project folder (port of {@code ProjectFolder} in
 * {@code src/components/video-editor/dashboard/useProjectFolders.ts}).
 *
 * <p>The React app keeps folders in {@code localStorage["gravaai.project-folders.v1"]}; Java cannot
 * read Chromium localStorage without a LevelDB parser (no library allowed), so the values move to
 * {@code <userData>/dashboard-library.json} under the same key (Lead decision, task-9; documented
 * as a known Electron -> Java metadata gap).
 *
 * @param id a stable id ({@code crypto.randomUUID()} in the React app)
 * @param name the folder name, editable in the sidebar
 * @param color the folder colour, {@code #rrggbb}
 * @param paths the project paths assigned to this folder
 */
public record ProjectFolder(String id, String name, String color, List<String> paths) {

	/** {@code FOLDER_COLORS} in {@code useProjectFolders.ts}. */
	public static final List<String> COLORS = List.of(
			"#929292", "#de85ac", "#d6ad58", "#70ad8a", "#759bd2", "#a68ccc");

	public ProjectFolder {
		paths = List.copyOf(paths);
	}
}
