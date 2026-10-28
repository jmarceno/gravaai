package dev.gravaai.ui.editor;

import java.util.function.Consumer;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies settings-panel edits to the shared {@link EditorStore} as undoable commands.
 *
 * <p>The store's canonical path is project JSON: every edit mutates the serialised editor object
 * for one field and normalises it back, so the exact same clamps/defaults run as when a project is
 * loaded, and {@code EditorHistory} records the change.
 */
public final class EditorMutations {

	private EditorMutations() {}

	/** Runs one editor-JSON mutation as a command; returns false when nothing changed. */
	public static boolean update(EditorStore store, Consumer<ObjectNode> mutation) {
		return store.apply(editor -> {
			ObjectNode before = editor.editorJson();
			ObjectNode json = before.deepCopy();
			mutation.accept(json);
			var next = ProjectPersistence.normalizeProjectEditor(json);
			editor.replaceState(next);
			return !ProjectPersistence.normalizeProjectEditor(before).equals(next);
		});
	}
}
