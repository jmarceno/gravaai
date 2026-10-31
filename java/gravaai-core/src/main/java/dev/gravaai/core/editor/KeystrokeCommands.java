package dev.gravaai.core.editor;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.KeystrokeElement;

/**
 * The Keystrokes-track operations (one class per command, like {@link ZoomCommands}): selecting,
 * moving and deleting single presses and editing a press's key or modifiers. Keystrokes stay
 * single-selection (like captions); the multi-selection batch path does not include them.
 */
public final class KeystrokeCommands {

	private KeystrokeCommands() {}

	private static List<String> ids(List<KeystrokeElement> elements) {
		List<String> ids = new ArrayList<>(elements.size());
		for (KeystrokeElement element : elements) {
			ids.add(element.id());
		}
		return ids;
	}

	/** Selects one press (or clears the keystroke selection with {@code id = null}). */
	public static final class Select implements EditorCommand {
		private final @Nullable String id;

		public Select(@Nullable String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			store.setSelectedKeystrokeId(id);
			if (id != null) {
				store.clearSelectionsExcept(null, null, null, null, null, id);
			}
			return true;
		}
	}

	/** Moves (or resizes the linger window of) one press. */
	public static final class ChangeSpan implements EditorCommand {
		private final String id;
		private final double startMs;
		private final double endMs;

		public ChangeSpan(String id, double startMs, double endMs) {
			this.id = id;
			this.startMs = startMs;
			this.endMs = endMs;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<KeystrokeElement> next = new ArrayList<>();
			boolean changed = false;
			for (KeystrokeElement element : store.state().keystrokeElements()) {
				if (element.id().equals(id)) {
					next.add(new KeystrokeElement(element.id(), Math.round(startMs),
							Math.round(endMs), element.key(), element.modifiers()));
					changed = true;
				} else {
					next.add(element);
				}
			}
			if (changed) {
				store.setKeystrokeElements(next);
			}
			return changed;
		}
	}

	/** Edits a press's key text and modifier set. */
	public static final class ChangeLabel implements EditorCommand {
		private final String id;
		private final String key;
		private final List<String> modifiers;

		public ChangeLabel(String id, String key, List<String> modifiers) {
			this.id = id;
			this.key = key;
			this.modifiers = List.copyOf(modifiers);
		}

		@Override
		public boolean apply(EditorStore store) {
			if (key == null || key.isBlank()) {
				return false;
			}
			List<KeystrokeElement> next = new ArrayList<>();
			boolean changed = false;
			for (KeystrokeElement element : store.state().keystrokeElements()) {
				if (element.id().equals(id)) {
					next.add(new KeystrokeElement(element.id(), element.startMs(), element.endMs(),
							key.trim(), modifiers));
					changed = true;
				} else {
					next.add(element);
				}
			}
			if (changed) {
				store.setKeystrokeElements(next);
			}
			return changed;
		}
	}

	/** Removes one press. */
	public static final class Delete implements EditorCommand {
		private final String id;

		public Delete(String id) {
			this.id = id;
		}

		@Override
		public boolean apply(EditorStore store) {
			List<KeystrokeElement> next = new ArrayList<>();
			for (KeystrokeElement element : store.state().keystrokeElements()) {
				if (!element.id().equals(id)) {
					next.add(element);
				}
			}
			if (next.size() == store.state().keystrokeElements().size()) {
				return false;
			}
			store.setKeystrokeElements(next);
			if (id.equals(store.selectedKeystrokeId())) {
				store.setSelectedKeystrokeId(null);
			}
			return true;
		}
	}
}
