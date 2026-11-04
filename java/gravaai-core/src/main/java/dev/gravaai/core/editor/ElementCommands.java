package dev.gravaai.core.editor;

import java.util.List;
import java.util.Optional;

import dev.gravaai.core.project.SceneElement;

/** Elements-lane edits made from the timeline: select, move/resize and delete. */
public final class ElementCommands {

	private ElementCommands() {}

	private static Optional<SceneElement> find(EditorStore store, String id) {
		return store.state().elements().stream().filter(element -> element.id().equals(id))
				.findFirst();
	}

	/** Selects one element (clears every other timeline selection). */
	public record Select(String id) implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			store.clearSelectionsExcept(null, null, null, null, null, null);
			store.setSelectedElementId(Optional.of(id));
			return true;
		}
	}

	/** Moves or resizes an element on the timeline. */
	public record ChangeSpan(String id, double startMs, double endMs) implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			Optional<SceneElement> current = find(store, id);
			if (current.isEmpty()) {
				return false;
			}
			long start = Math.max(0, Math.round(startMs));
			long end = Math.max(start + SceneElement.MIN_DURATION_MS, Math.round(endMs));
			if (start == current.get().startMs() && end == current.get().endMs()) {
				return false;
			}
			store.setElements(store.state().elements().stream()
					.map(element -> element.id().equals(id) ? element.withSpan(start, end) : element)
					.toList());
			return true;
		}
	}

	/**
	 * Replaces one element with an edited copy (inspector edits: props, motion, space, blur). The
	 * id is kept; a copy equal to the current element is a no-op, so no empty undo step is made.
	 */
	public record Replace(SceneElement next) implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			Optional<SceneElement> current = find(store, next.id());
			if (current.isEmpty() || current.get().equals(next)) {
				return false;
			}
			store.setElements(store.state().elements().stream()
					.map(element -> element.id().equals(next.id()) ? next : element).toList());
			return true;
		}
	}

	/** Removes an element. */
	public record Delete(String id) implements EditorCommand {
		@Override
		public boolean apply(EditorStore store) {
			List<SceneElement> next = store.state().elements().stream()
					.filter(element -> !element.id().equals(id)).toList();
			if (next.size() == store.state().elements().size()) {
				return false;
			}
			store.setElements(next);
			if (store.selectedElementId().equals(Optional.of(id))) {
				store.setSelectedElementId(Optional.empty());
			}
			return true;
		}
	}
}
