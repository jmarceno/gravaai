package dev.gravaai.core.editor;

import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure parts of {@code project/useProjectSnapshotModel.ts}: the persisted editor snapshot, the
 * source path shown in the editor and the project display name.
 */
public final class ProjectSnapshotModel {

	private ProjectSnapshotModel() {}

	/** {@code buildPersistedEditorState}: full editor JSON without the dev-only motion-blur tuning. */
	public static ObjectNode buildPersistedEditorState(ProjectEditorState state) {
		return ProjectPersistence.stripPersistedDevMotionBlurSettings(
				ProjectDocument.writeEditor(state, dev.gravaai.core.json.Json.nodeFactory().objectNode()));
	}

	/** {@code currentSourcePath}: explicit source first, else the project path converted from file://. */
	public static String currentSourcePath(String videoSourcePath, String videoPath) {
		if (videoSourcePath != null) {
			return videoSourcePath;
		}
		return videoPath != null ? ProjectPersistence.fromFileUrl(videoPath) : null;
	}

	/**
	 * {@code projectDisplayName}: the project file name without its {@code .gravaai} extension
	 * (and without any other extension), falling back to the translated "Untitled Project".
	 */
	public static String projectDisplayName(String currentProjectPath, String untitledFallback) {
		String fileName = "Untitled Project";
		if (currentProjectPath != null) {
			String[] parts = currentProjectPath.split("[\\\\/]");
			fileName = parts.length == 0 ? "Untitled Project" : parts[parts.length - 1];
		}
		String withoutProjectExtension = fileName.replaceFirst("(?i)\\.gravaai$", "");
		String withoutAnyExtension = withoutProjectExtension.replaceFirst("\\.[^.]+$", "");
		return withoutAnyExtension.isEmpty() ? untitledFallback : withoutAnyExtension;
	}
}
