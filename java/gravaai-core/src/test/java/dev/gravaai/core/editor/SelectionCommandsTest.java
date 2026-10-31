package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;

/** Multi-selection commands: {@link SelectionCommands}, {@link BatchCommand} and batched edits. */
class SelectionCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static EditorStore zoomStore() {
		return new EditorStore(state("""
				{"zoomRegions":[
				{"id":"zoom-1","startMs":0,"endMs":500,"depth":3,"focus":{"cx":0.5,"cy":0.5},"mode":"manual"},
				{"id":"zoom-2","startMs":600,"endMs":1000,"depth":2,"focus":{"cx":0.5,"cy":0.5},"mode":"auto"},
				{"id":"zoom-3","startMs":1100,"endMs":1500,"depth":3,"focus":{"cx":0.5,"cy":0.5},"mode":"auto"}]}
				"""));
	}

	@Test
	@DisplayName("SelectMany keeps the primary zoom, the zoom section and every multi id")
	void selectsManyZooms() {
		EditorStore store = zoomStore();
		store.apply(new ClipCommands.Select("clip-1"));

		assertTrue(store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.ZOOM, "zoom-2", List.of("zoom-1", "zoom-2"))));

		assertEquals("zoom-2", store.selectedZoomId());
		assertNull(store.selectedClipId());
		assertEquals(Set.of("zoom-1", "zoom-2"), store.multiSelection());
		assertEquals(EditorStore.SECTION_ZOOM, store.activeEffectSection());
	}

	@Test
	@DisplayName("a plain single select ends the multi-selection")
	void plainSelectClearsMulti() {
		EditorStore store = zoomStore();
		store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.ZOOM, "zoom-1", List.of("zoom-1", "zoom-2")));

		store.apply(new ZoomCommands.Select("zoom-3"));

		assertEquals("zoom-3", store.selectedZoomId());
		assertTrue(store.multiSelection().isEmpty());
	}

	@Test
	@DisplayName("SelectMany rejects unknown kinds and single-kind selection still works for audio")
	void selectManyKinds() {
		EditorStore store = zoomStore();
		assertFalse(store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.CLIP, "clip-1", List.of("clip-1", "clip-2"))));
		assertTrue(store.multiSelection().isEmpty());

		assertTrue(store.apply(new AudioCommands.Add(0, 2_000, "/a.wav", null)));
		String audioId = store.selectedAudioId();
		store.apply(new AudioCommands.Add(2_000, 4_000, "/b.wav", null));
		String audioId2 = store.selectedAudioId();
		assertTrue(store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.AUDIO, audioId, List.of(audioId, audioId2))));
		assertEquals(audioId, store.selectedAudioId());
		assertEquals(Set.of(audioId, audioId2), store.multiSelection());
		assertEquals(EditorStore.SECTION_AUDIO, store.activeEffectSection());
	}

	@Test
	@DisplayName("annotating through the preview Select ends the multi-selection")
	void annotationSelectClearsMulti() {
		EditorStore store = new EditorStore(state("""
				{"annotationRegions":[{"id":"annotation-1","startMs":0,"endMs":500,"type":"text",
				"content":"hi","position":{"x":10,"y":10},"size":{"width":20,"height":10}}]}
				"""));
		store.apply(new ZoomCommands.Add(0, 500));
		String zoomId = store.selectedZoomId();
		store.apply(new AnnotationCommands.Select("annotation-1"));
		store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.ANNOTATION, "annotation-1", List.of("annotation-1")));

		// The preview overlay's plain Select clears the Ctrl/Cmd multi-selection.
		store.apply(new ZoomCommands.Select(zoomId));
		store.apply(new SelectionCommands.SelectMany(
				SelectionCommands.TimelineSelections.ANNOTATION, "annotation-1", List.of("annotation-1")));
		assertEquals(Set.of("annotation-1"), store.multiSelection());
	}

	@Test
	@DisplayName("a batched multi-delete removes every selected zoom in one undo step")
	void batchDeleteZooms() {
		EditorStore store = zoomStore();
		store.apply(new SelectionCommands.SelectMany(SelectionCommands.TimelineSelections.ZOOM,
				"zoom-1", List.of("zoom-1", "zoom-2", "zoom-3")));
		int undoStepsBefore = store.history().past().size();

		assertTrue(store.apply(new BatchCommand(List.of(
				new ZoomCommands.Delete("zoom-1"),
				new ZoomCommands.Delete("zoom-2"),
				new ZoomCommands.Delete("zoom-3")))));

		assertTrue(store.state().zoomRegions().isEmpty());
		assertNull(store.selectedZoomId());
		assertEquals(undoStepsBefore + 1, store.history().past().size());
		assertTrue(store.undo());
		assertEquals(3, store.state().zoomRegions().size());
	}

	@Test
	@DisplayName("id-parameterized ChangeDepth/ChangeMode apply to the given zoom, not the selection")
	void changeDepthAndModeForId() {
		EditorStore store = zoomStore();
		store.apply(new ZoomCommands.Select("zoom-1"));

		assertTrue(store.apply(new ZoomCommands.ChangeDepth("zoom-2", ZoomDepth.FIVE)));
		assertTrue(store.apply(new ZoomCommands.ChangeMode("zoom-3", ZoomMode.MANUAL)));

		ZoomRegion zoom2 = region(store, "zoom-2");
		ZoomRegion zoom3 = region(store, "zoom-3");
		assertEquals(ZoomDepth.FIVE, zoom2.depth());
		assertEquals(ZoomMode.MANUAL, zoom3.mode());
		assertEquals(ZoomDepth.THREE, region(store, "zoom-1").depth());
		assertEquals(ZoomMode.MANUAL, region(store, "zoom-1").mode());
	}

	@Test
	@DisplayName("id-parameterized ChangeVolume/SetNormalize apply to the given audio region")
	void changeVolumeForId() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AudioCommands.Add(0, 2_000, "/a.wav", null));
		String first = store.selectedAudioId();
		store.apply(new AudioCommands.Add(2_000, 4_000, "/b.wav", null));
		String second = store.selectedAudioId();

		assertTrue(store.apply(new AudioCommands.ChangeVolume(first, 0.25)));
		assertTrue(store.apply(new AudioCommands.SetNormalize(second, true)));

		assertEquals(0.25, audio(store, first).volume());
		assertEquals(1, audio(store, second).volume());
		assertEquals(true, audio(store, second).normalize());
		assertEquals(false, audio(store, first).normalize());
	}

	@Test
	@DisplayName("undo/redo restores the multi-selection")
	void undoRestoresMultiSelection() {
		EditorStore store = zoomStore();
		store.apply(new SelectionCommands.SelectMany(SelectionCommands.TimelineSelections.ZOOM,
				"zoom-2", List.of("zoom-1", "zoom-2")));

		assertTrue(store.undo());
		assertTrue(store.multiSelection().isEmpty());
		assertTrue(store.redo());
		assertEquals(Set.of("zoom-1", "zoom-2"), store.multiSelection());
	}

	@Test
	@DisplayName("toggling updates the multi-selection and reports the new state")
	void togglesMultiSelection() {
		EditorStore store = zoomStore();
		assertTrue(store.toggleMultiSelection("zoom-1"));
		assertEquals(Set.of("zoom-1"), store.multiSelection());
		assertFalse(store.toggleMultiSelection("zoom-1"));
		assertTrue(store.multiSelection().isEmpty());
	}

	private static ZoomRegion region(EditorStore store, String id) {
		for (ZoomRegion region : store.state().zoomRegions()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		throw new AssertionError("missing zoom " + id);
	}

	private static AudioRegion audio(EditorStore store, String id) {
		for (AudioRegion region : store.state().audioRegions()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		throw new AssertionError("missing audio " + id);
	}
}
