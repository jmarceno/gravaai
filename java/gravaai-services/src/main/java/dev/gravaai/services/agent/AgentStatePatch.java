package dev.gravaai.services.agent;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.JsonNode;

/** Immutable record patching for {@link ProjectEditorState} (the agent planner's state updates). */
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

	/** Rebuilds the state with one component replaced. */
	static ProjectEditorState with(ProjectEditorState state, String component, Object value) {
		return withAll(state, Map.of(component, value));
	}

	/** Rebuilds the state with the given components replaced. */
	static ProjectEditorState withAll(ProjectEditorState state, Map<String, Object> updates) {
		RecordComponent[] components = ProjectEditorState.class.getRecordComponents();
		Object[] arguments = new Object[components.length];
		try {
			for (int index = 0; index < components.length; index++) {
				RecordComponent component = components[index];
				if (updates.containsKey(component.getName())) {
					arguments[index] = updates.get(component.getName());
				} else {
					arguments[index] = component.getAccessor().invoke(state);
				}
			}
			return ProjectEditorState.class.getDeclaredConstructor(
					java.util.Arrays.stream(components)
							.map(RecordComponent::getType).toArray(Class<?>[]::new))
					.newInstance(arguments);
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("could not patch the editor state", error);
		}
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
