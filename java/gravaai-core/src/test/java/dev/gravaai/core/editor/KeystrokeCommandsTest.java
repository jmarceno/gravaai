package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.KeystrokeElement;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Behaviour tests for {@link KeystrokeCommands} (the editable Keystrokes track). */
class KeystrokeCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static final String ELEMENTS = """
			{"keystrokeElements":[
				{"id":"key-1","startMs":100,"endMs":1000,"key":"S","modifiers":["ctrl"]},
				{"id":"key-2","startMs":2000,"endMs":2900,"key":"A","modifiers":[]}]}
			""";

	@Test
	@DisplayName("normalizes modifiers into the canonical order and drops blank keys")
	void normalizesElements() {
		ProjectEditorState normalized = state("""
				{"keystrokeElements":[
					{"id":"key-1","startMs":500,"endMs":1400,"key":" a ","modifiers":["super","CTRL","bogus"]},
					{"id":"","startMs":0,"endMs":10,"key":"X","modifiers":[]},
					{"id":"key-2","startMs":0,"endMs":10,"key":"  ","modifiers":[]}]}
				""");

		assertEquals(1, normalized.keystrokeElements().size());
		KeystrokeElement element = normalized.keystrokeElements().get(0);
		assertEquals("key-1", element.id());
		assertEquals("a", element.key());
		// Unknown wires drop; known ones canonicalize into chip order regardless of stored order.
		assertEquals(List.of("ctrl", "super"), element.modifiers());
		assertEquals(List.of("Ctrl", "Super", "a"), element.chips());
	}

	@Test
	@DisplayName("selecting a press clears the other selections")
	void selectsAndClearsOthers() {
		EditorStore store = new EditorStore(state(ELEMENTS));
		store.apply(new ZoomCommands.Add(0, 500));
		assertEquals(store.state().zoomRegions().get(0).id(), store.selectedZoomId());

		store.apply(new KeystrokeCommands.Select("key-2"));

		assertEquals("key-2", store.selectedKeystrokeId());
		assertNull(store.selectedZoomId());
		assertNull(store.selectedClipId());
	}

	@Test
	@DisplayName("moving a press keeps its key and modifiers")
	void movesPress() {
		EditorStore store = new EditorStore(state(ELEMENTS));

		store.apply(new KeystrokeCommands.ChangeSpan("key-1", 1500, 2400));

		KeystrokeElement element = store.state().keystrokeElements().get(0);
		assertEquals(1500, element.startMs());
		assertEquals(2400, element.endMs());
		assertEquals("S", element.key());
		assertEquals(List.of("ctrl"), element.modifiers());
	}

	@Test
	@DisplayName("editing the label replaces the key text and modifiers")
	void editsLabel() {
		EditorStore store = new EditorStore(state(ELEMENTS));

		store.apply(new KeystrokeCommands.ChangeLabel("key-1", "s",
				List.of("shift", "ctrl", "unknown")));

		KeystrokeElement element = store.state().keystrokeElements().get(0);
		assertEquals("s", element.key());
		assertEquals(List.of("ctrl", "shift"), element.orderedModifiers());
	}

	@Test
	@DisplayName("deleting a press clears its selection and undoes the deletion")
	void deletesAndUndoes() {
		EditorStore store = new EditorStore(state(ELEMENTS));
		store.apply(new KeystrokeCommands.Select("key-1"));

		assertTrue(store.apply(new KeystrokeCommands.Delete("key-1")));
		assertEquals(1, store.state().keystrokeElements().size());
		assertNull(store.selectedKeystrokeId());

		assertTrue(store.undo());
		assertEquals(2, store.state().keystrokeElements().size());
		assertEquals("key-1", store.state().keystrokeElements().get(0).id());
	}

	@Test
	@DisplayName("deleting an unknown press is a no-op")
	void deleteUnknownIsNoop() {
		EditorStore store = new EditorStore(state(ELEMENTS));

		assertFalse(store.apply(new KeystrokeCommands.Delete("missing")));
		assertEquals(2, store.state().keystrokeElements().size());
	}
}
