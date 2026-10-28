package dev.gravaai.core.project;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dirty-state comparison (port of {@code src/components/video-editor/projectDirtyState.ts}, plan
 * §5.11).
 *
 * <p>Two snapshots are compared as JSON trees: legacy app preferences and the transient webcam
 * media attachment ({@code enabled}, {@code sourcePath}, {@code timeOffsetMs}) are not project
 * edits. Jackson tree equality is used for the TS {@code areDeepEqual} on JSON data. Callers must
 * pass snapshots parsed by the same reader, so integer node types agree.
 */
public final class ProjectDirtyState {

	private ProjectDirtyState() {}

	/** {@code hasUnsavedProjectChanges}. */
	public static boolean hasUnsavedProjectChanges(JsonNode currentProject, JsonNode lastSavedProject) {
		JsonNode comparableCurrent = omitTransientWebcamMediaFields(currentProject);
		JsonNode comparableLastSaved = omitTransientWebcamMediaFields(lastSavedProject);

		return comparableCurrent != null
				&& (comparableLastSaved == null || !comparableCurrent.equals(comparableLastSaved));
	}

	private static JsonNode omitTransientWebcamMediaFields(JsonNode project) {
		if (project == null || !project.isObject()) {
			return project;
		}
		JsonNode editorNode = project.get("editor");
		if (editorNode == null || !editorNode.isObject()) {
			return project;
		}

		ObjectNode editor = ProjectPersistence.stripProjectUserPreferences((ObjectNode) editorNode);
		JsonNode webcam = editor.get("webcam");
		if (webcam == null || !webcam.isObject()) {
			ObjectNode result = ((ObjectNode) project).deepCopy();
			result.set("editor", editor);
			return result;
		}

		ObjectNode persistentWebcamFields = ((ObjectNode) webcam).deepCopy();
		persistentWebcamFields.remove("enabled");
		persistentWebcamFields.remove("sourcePath");
		persistentWebcamFields.remove("timeOffsetMs");

		editor.set("webcam", persistentWebcamFields);
		ObjectNode result = ((ObjectNode) project).deepCopy();
		result.set("editor", editor);
		return result;
	}
}
