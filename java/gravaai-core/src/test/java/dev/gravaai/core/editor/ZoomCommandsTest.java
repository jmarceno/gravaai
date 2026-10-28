package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;

/** Behaviour tests for {@link ZoomCommands} derived from {@code hooks/useZoomRegionCommands.ts}. */
class ZoomCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	@Test
	@DisplayName("adds a region, selects it and clears the other selections")
	void addsAndSelectsZoom() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new ZoomCommands.Add(1_000, 2_000));

		assertEquals(1, store.state().zoomRegions().size());
		ZoomRegion region = store.state().zoomRegions().get(0);
		assertEquals(1_000, region.startMs());
		assertEquals(2_000, region.endMs());
		assertEquals(ZoomDepth.THREE, region.depth());
		assertEquals(new ZoomFocus(0.5, 0.5), region.focus());
		assertEquals(ZoomMode.AUTO, region.mode());
		assertEquals(region.id(), store.selectedZoomId());
		// handleZoomAdded only changes the selection, not the active effect section.
		assertEquals(EditorStore.SECTION_SCENE, store.activeEffectSection());
	}

	@Test
	@DisplayName("adds a suggestion without stealing the selection")
	void addsSuggestion() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new ZoomCommands.AddSuggested(500, 1_500, new ZoomFocus(0.2, 0.8)));

		ZoomRegion region = store.state().zoomRegions().get(0);
		assertEquals(new ZoomFocus(0.2, 0.8), region.focus());
		assertEquals(ZoomMode.AUTO, region.mode());
		assertNull(store.selectedZoomId());
	}

	@Test
	@DisplayName("clamps the focus to the region depth and the frame")
	void clampsFocus() {
		EditorStore store = new EditorStore(state("""
				{"zoomRegions":[{"id":"z","startMs":0,"endMs":1000,"depth":2,
				"focus":{"cx":0.5,"cy":0.5}}]}
				"""));
		store.apply(new ZoomCommands.ChangeFocus("z", new ZoomFocus(2, -1)));

		assertEquals(new ZoomFocus(1, 0), store.state().zoomRegions().get(0).focus());
	}

	@Test
	@DisplayName("changes span, depth and mode of a region")
	void changesRegionProperties() {
		EditorStore store = new EditorStore(state("""
				{"zoomRegions":[{"id":"z","startMs":0,"endMs":1000,"depth":2,
				"focus":{"cx":0.5,"cy":0.5}}]}
				"""));
		store.apply(new ZoomCommands.ChangeSpan("z", 200, 900));
		store.apply(new ZoomCommands.Select("z"));
		store.apply(new ZoomCommands.ChangeDepth(ZoomDepth.FIVE));
		store.apply(new ZoomCommands.ChangeMode(ZoomMode.MANUAL));

		ZoomRegion region = store.state().zoomRegions().get(0);
		assertEquals(200, region.startMs());
		assertEquals(900, region.endMs());
		assertEquals(ZoomDepth.FIVE, region.depth());
		assertEquals(ZoomMode.MANUAL, region.mode());
	}

	@Test
	@DisplayName("deletes a zoom region and clears its selection")
	void deletesZoom() {
		EditorStore store = new EditorStore(state("""
				{"zoomRegions":[{"id":"z","startMs":0,"endMs":1000,"depth":2,
				"focus":{"cx":0.5,"cy":0.5}}]}
				"""));
		store.apply(new ZoomCommands.Select("z"));
		store.apply(new ZoomCommands.Delete("z"));

		assertTrue(store.state().zoomRegions().isEmpty());
		assertNull(store.selectedZoomId());
	}

	@Test
	@DisplayName("ignores depth/mode changes without a selected region")
	void ignoresChangesWithoutSelection() {
		EditorStore store = new EditorStore(state("{}"));
		assertFalse(store.apply(new ZoomCommands.ChangeDepth(ZoomDepth.SIX)));
		assertFalse(store.apply(new ZoomCommands.ChangeMode(ZoomMode.MANUAL)));
	}
}
