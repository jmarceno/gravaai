package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CameraLayout;
import dev.gravaai.core.project.CameraLayoutSegment;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.WebcamPositionPreset;

/** Behaviour tests for {@link CameraLayoutCommands} (the Camera layout track). */
class CameraLayoutCommandsTest {

	private static final CameraLayout SPLIT =
			new CameraLayout.Position(WebcamPositionPreset.SPLIT_RIGHT);
	private static final CameraLayout CORNER =
			new CameraLayout.Position(WebcamPositionPreset.BOTTOM_RIGHT);

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static EditorStore store() {
		return new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":20000,"speed":1}]}
				"""));
	}

	private static List<String> layouts(EditorStore store) {
		return store.state().cameraLayoutSegments().stream()
				.map(segment -> segment.startMs() + "-" + segment.endMs() + ":"
						+ segment.layout().wire())
				.toList();
	}

	@Test
	@DisplayName("split screen first, a corner camera ten seconds later")
	void switchesSplitThenCorner() {
		EditorStore store = store();

		assertTrue(store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, SPLIT)));
		assertTrue(store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, CORNER)));

		assertEquals(List.of("0-10000:split-right", "10000-20000:bottom-right"), layouts(store));
		assertEquals(store.state().cameraLayoutSegments().get(1).id(),
				store.selectedCameraLayoutId());
	}

	@Test
	@DisplayName("switching at a segment's start relabels it instead of splitting")
	void switchAtStartRelabels() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, SPLIT));

		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, new CameraLayout.CameraOnly()));

		assertEquals(List.of("0-20000:camera-only"), layouts(store));
	}

	@Test
	@DisplayName("in a gap the new segment stops at the next one")
	void gapStopsAtNextSegment() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(12_000, 20_000, SPLIT));

		store.apply(new CameraLayoutCommands.SwitchAt(4_000, 20_000, CORNER));

		assertEquals(List.of("4000-12000:bottom-right", "12000-20000:split-right"),
				layouts(store));
	}

	@Test
	@DisplayName("dragging an edge stops at the neighbour")
	void spanClampsToNeighbours() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, SPLIT));
		store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, CORNER));
		String second = store.state().cameraLayoutSegments().get(1).id();

		store.apply(new CameraLayoutCommands.ChangeSpan(second, 6_000, 20_000));

		assertEquals(List.of("0-10000:split-right", "10000-20000:bottom-right"), layouts(store));
	}

	@Test
	@DisplayName("deleting falls back to the project layout and undo restores it")
	void deleteAndUndo() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, SPLIT));
		String id = store.state().cameraLayoutSegments().get(0).id();

		assertTrue(store.apply(new CameraLayoutCommands.Delete(id)));
		assertEquals(List.of(), layouts(store));
		assertNull(store.selectedCameraLayoutId());

		assertTrue(store.undo());
		assertEquals(List.of("0-20000:split-right"), layouts(store));
		assertFalse(store.apply(new CameraLayoutCommands.Delete("missing")));
	}

	@Test
	@DisplayName("drawing on the empty lane adds a block on the project layout")
	void addsOnTheProjectLayout() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":20000,"speed":1}],
				 "webcam":{"positionPreset":"top-left"}}
				"""));

		assertTrue(store.apply(new CameraLayoutCommands.Add(4_000, 9_000)));

		CameraLayoutSegment added = store.state().cameraLayoutSegments().get(0);
		assertEquals(4_000, added.startMs());
		assertEquals(9_000, added.endMs());
		// A fresh block changes nothing on screen: same preset as the project, smooth switch in.
		assertEquals(new CameraLayout.Position(WebcamPositionPreset.TOP_LEFT), added.layout());
		assertEquals(CameraLayoutSegment.Transition.SMOOTH, added.transition());
		assertTrue(added.style().isInherit());
		assertEquals(added.id(), store.selectedCameraLayoutId());
	}

	@Test
	@DisplayName("a drawn block is trimmed against the blocks beside it")
	void drawnBlockStopsAtNeighbours() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, SPLIT));

		assertTrue(store.apply(new CameraLayoutCommands.Add(4_000, 14_000)));

		assertEquals(List.of("4000-10000:bottom-right", "10000-20000:split-right"), layouts(store));
	}

	@Test
	@DisplayName("a drawn block too short to keep is rejected")
	void drawnBlockNeedsRoom() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, SPLIT));

		assertFalse(store.apply(new CameraLayoutCommands.Add(9_950, 14_000)));
		assertEquals(1, store.state().cameraLayoutSegments().size());
	}

	@Test
	@DisplayName("clearing a block's style restores inheritance in one undoable step")
	void clearStyleRestoresInheritance() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, CORNER));
		String id = store.state().cameraLayoutSegments().get(0).id();
		store.apply(new CameraLayoutCommands.SetStyle(id, "margin", OptionalDouble.of(12)));
		store.apply(new CameraLayoutCommands.SetStyle(id, "size", OptionalDouble.of(30)));

		assertTrue(store.apply(new CameraLayoutCommands.ClearStyle(id)));
		assertTrue(store.state().cameraLayoutSegments().get(0).style().isInherit());
		// Nothing left to clear the second time.
		assertFalse(store.apply(new CameraLayoutCommands.ClearStyle(id)));

		assertTrue(store.undo());
		assertEquals(OptionalDouble.of(12),
				store.state().cameraLayoutSegments().get(0).style().margin());
	}

	@Test
	@DisplayName("selecting another element clears the camera layout selection")
	void otherSelectionClears() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, SPLIT));

		store.apply(new ZoomCommands.Add(0, 500));

		assertNull(store.selectedCameraLayoutId());
	}

	@Test
	@DisplayName("cutting footage ripples the segments with it")
	void ripplesWithClipEdits() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, CORNER));
		store.apply(new ClipCommands.Split(5_000));
		String first = store.state().clipRegions().get(0).id();

		store.apply(new ClipCommands.Delete(first));

		assertEquals(List.of("5000-15000:bottom-right"), layouts(store));
	}

	@Test
	@DisplayName("speeding a clip up squeezes the segments over it")
	void ripplesWithSpeedChanges() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(10_000, 20_000, CORNER));
		store.apply(new ClipCommands.Select(store.state().clipRegions().get(0).id()));

		store.apply(new ClipCommands.ChangeSpeed(2));

		assertEquals(List.of("5000-10000:bottom-right"), layouts(store));
	}

	@Test
	@DisplayName("style overrides and the smooth switch persist; clearing a field inherits again")
	void styleAndTransition() {
		EditorStore store = store();
		store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, CORNER));
		String id = store.state().cameraLayoutSegments().get(0).id();

		store.apply(new CameraLayoutCommands.SetStyle(id, "size", OptionalDouble.of(250)));
		store.apply(new CameraLayoutCommands.SetStyle(id, "shadow", OptionalDouble.of(0.5)));
		store.apply(new CameraLayoutCommands.SetTransition(id,
				CameraLayoutSegment.Transition.SMOOTH));

		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				ProjectDocument.writeEditor(store.state(), Json.nodeFactory().objectNode()));
		CameraLayoutSegment segment = reloaded.cameraLayoutSegments().get(0);
		assertEquals(OptionalDouble.of(100), segment.style().size());
		assertEquals(OptionalDouble.of(0.5), segment.style().shadow());
		assertEquals(CameraLayoutSegment.Transition.SMOOTH, segment.transition());

		store.apply(new CameraLayoutCommands.SetStyle(id, "size", OptionalDouble.empty()));
		assertEquals(OptionalDouble.empty(),
				store.state().cameraLayoutSegments().get(0).style().size());
	}

	@Test
	@DisplayName("the switch-out toggles and survives a save and load")
	void styleAndTransitionOut() {
		EditorStore store = store();
		assertTrue(store.apply(new CameraLayoutCommands.SwitchAt(0, 20_000, CORNER)));
		String id = store.state().cameraLayoutSegments().get(0).id();

		assertEquals(CameraLayoutSegment.Transition.CUT,
				store.state().cameraLayoutSegments().get(0).transitionOut());
		assertTrue(store.apply(new CameraLayoutCommands.SetTransitionOut(id,
				CameraLayoutSegment.Transition.SMOOTH)));
		assertEquals(CameraLayoutSegment.Transition.SMOOTH,
				store.state().cameraLayoutSegments().get(0).transitionOut());
		// Setting the same value again changes nothing.
		assertFalse(store.apply(new CameraLayoutCommands.SetTransitionOut(id,
				CameraLayoutSegment.Transition.SMOOTH)));

		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				ProjectDocument.writeEditor(store.state(), Json.nodeFactory().objectNode()));
		assertEquals(CameraLayoutSegment.Transition.SMOOTH,
				reloaded.cameraLayoutSegments().get(0).transitionOut());
		// The switch-in keeps its default through the round trip.
		assertEquals(CameraLayoutSegment.Transition.CUT,
				reloaded.cameraLayoutSegments().get(0).transition());
	}

	@Test
	@DisplayName("older per-clip camera layouts move onto the Camera layout track")
	void migratesClipLayouts() {
		ProjectEditorState loaded = state("""
				{"clipRegions":[
					{"id":"a","startMs":0,"endMs":4000,"speed":1,"cameraLayout":"camera-only"},
					{"id":"b","startMs":4000,"endMs":9000,"speed":1}]}
				""");

		assertEquals(List.of(new CameraLayoutSegment("camera-a", 0, 4000,
				new CameraLayout.CameraOnly())), loaded.cameraLayoutSegments());
		assertNull(loaded.clipRegions().get(0).cameraLayout());
	}

	@Test
	@DisplayName("segments survive a save and load; unknown layouts and overlaps are repaired")
	void persistsAndNormalizes() {
		ProjectEditorState loaded = state("""
				{"cameraLayoutSegments":[
					{"id":"camera-2","startMs":5000,"endMs":9000,"layout":"camera-only"},
					{"id":"camera-1","startMs":0,"endMs":6000,"layout":"split-left"},
					{"id":"camera-3","startMs":9000,"endMs":9500,"layout":"bogus"}]}
				""");

		assertEquals(List.of(
				new CameraLayoutSegment("camera-1", 0, 6000,
						new CameraLayout.Position(WebcamPositionPreset.SPLIT_LEFT)),
				new CameraLayoutSegment("camera-2", 6000, 9000, new CameraLayout.CameraOnly())),
				loaded.cameraLayoutSegments());
		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				ProjectDocument.writeEditor(loaded, Json.nodeFactory().objectNode()));
		assertEquals(loaded.cameraLayoutSegments(), reloaded.cameraLayoutSegments());
	}
}
