package dev.gravaai.services.agent;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.JsonNode;

/** JSON views of {@link ProjectEditorState} for agent settings paths; edits go through {@link ProjectEditorState.Builder}. */
final class AgentStatePatch {

	private AgentStatePatch() {}

	/** Every component name of {@link ProjectEditorState} in constructor order. */
	static List<String> componentNames() {
		List<String> names = new ArrayList<>();
		for (RecordComponent component : ProjectEditorState.class.getRecordComponents()) {
			names.add(component.getName());
		}
		return List.copyOf(names);
	}

	/** The editor state as the project JSON (component names are the JSON field names). */
	static JsonNode toJson(ProjectEditorState state) {
		return Json.MAPPER.valueToTree(state);
	}

	/** Normalise editor JSON back into a state (the production project normaliser). */
	static ProjectEditorState normalize(JsonNode editorJson) {
		return ProjectPersistence.normalizeProjectEditor(editorJson);
	}
}
