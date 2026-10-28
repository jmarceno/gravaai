package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;

/** Tests for {@link EditorStore}: history integration, ids and change publication. */
class EditorStoreTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	@Test
	@DisplayName("records an undo step for every applied command and restores it on undo/redo")
	void recordsUndoSteps() {
		EditorStore store = new EditorStore(state("{}"));

		assertEquals(0, store.state().zoomRegions().size());
		assertTrue(store.apply(new ZoomCommands.Add(1_000, 2_000)));
		assertEquals(1, store.state().zoomRegions().size());
		assertEquals(1, store.history().past().size());

		assertTrue(store.undo());
		assertEquals(0, store.state().zoomRegions().size());
		assertTrue(store.redo());
		assertEquals(1, store.state().zoomRegions().size());
	}

	@Test
	@DisplayName("derives generated ids from the ids already in the state")
	void derivesNextIds() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":1000,"speed":1},
				{"id":"clip-5","startMs":1000,"endMs":2000,"speed":1}]}
				"""));

		List<String> ids = store.state().clipRegions().stream().map(ClipRegion::id).toList();
		assertEquals("clip-6", store.nextId("clip", ids));
		assertEquals("clip-7", store.nextId("clip", ids));
		assertEquals(1, store.nextIndex("annotation-zIndex"));
		assertEquals(2, store.nextIndex("annotation-zIndex"));
	}

	@Test
	@DisplayName("publishes every state change to JavaFX-free listeners")
	void publishesChanges() {
		EditorStore store = new EditorStore(state("{}"));
		AtomicInteger events = new AtomicInteger();
		var subscription = store.onChange(changed -> events.incrementAndGet());

		store.apply(new ZoomCommands.Add(0, 500));
		store.apply(new ClipCommands.Select(null));

		assertEquals(2, events.get());
		subscription.close();
		store.apply(new ZoomCommands.Add(500, 1_000));
		assertEquals(2, events.get());
	}

	@Test
	@DisplayName("initialises the history at the initial snapshot")
	void initialisesHistory() {
		EditorStore store = new EditorStore(state("{}"));
		assertEquals(List.of(), store.history().past());
		assertEquals(List.of(), store.history().future());
		assertTrue(store.snapshot() != null);
	}

	@Test
	@DisplayName("applyImportBatch writes regions and the clip selection as one undoable command")
	void appliesImportBatch() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":1000,"speed":1}],
				"zoomRegions":[{"id":"zoom-1","startMs":0,"endMs":500,"depth":3,
				"focus":{"cx":0.5,"cy":0.5},"mode":"manual"}]}
				"""));

		assertTrue(store.applyImportBatch(json -> {
			json.set("clipRegions", ProjectDocument.writeClipRegions(List.of(
					new ClipRegion("clip-1", 0, 1000, 0L, 0L, 1000L, 1.0, null, null, null, null),
					new ClipRegion("clip-2", 1000, 2000, 1000L, 1000L, 2000L, 1.0,
							null, null, null, null))));
			json.set("zoomRegions", ProjectDocument.writeZoomRegions(List.of(
					new ZoomRegion("zoom-1", 0, 500, ZoomDepth.THREE,
							new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL),
					new ZoomRegion("zoom-2", 1200, 1700, ZoomDepth.DEFAULT_AUTO,
							new ZoomFocus(0.5, 0.5), ZoomMode.AUTO))));
		}, "clip-2"));

		assertEquals(2, store.state().clipRegions().size());
		assertEquals(2, store.state().zoomRegions().size());
		assertEquals("clip-2", store.selectedClipId());
		assertEquals(1, store.history().past().size());
		assertTrue(store.undo());
		assertEquals(1, store.state().clipRegions().size());
		assertEquals(1, store.state().zoomRegions().size());
	}

	@Test
	@DisplayName("applyImportBatch reports no change for an identical batch")
	void appliesImportBatchNoChange() {
		EditorStore store = new EditorStore(state(
				"""
				{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":1000,"speed":1}]}
				"""));

		assertFalse(store.applyImportBatch(json -> { }, null));
		assertEquals(0, store.history().past().size());
	}
}
