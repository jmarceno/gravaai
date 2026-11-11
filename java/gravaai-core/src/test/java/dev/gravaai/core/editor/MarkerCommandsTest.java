package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.MarkerTimeline;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.TimelineMarker;

/** Behaviour tests for {@link MarkerCommands}: add, rename, retime, promote and delete. */
class MarkerCommandsTest {

	private static ProjectEditorState state(List<ClipRegion> clips, List<TimelineMarker> markers) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark("{}"))
				.toBuilder().clipRegions(clips).markers(markers).build();
	}

	private static ClipRegion clip(String id, long start, long end, long sourceStart, double speed) {
		return new ClipRegion(id, start, end, sourceStart, null, null, speed, null, null, null, null);
	}

	/** One clip at 2x: source 0–2000 shows as timeline 0–1000. */
	private static EditorStore store() {
		return new EditorStore(state(List.of(clip("clip-1", 0, 1000, 0L, 2)), List.of()));
	}

	@Test
	@DisplayName("adding a marker stores source time and names it from the marker count")
	void addStoresSourceTime() {
		EditorStore store = store();
		store.apply(new MarkerCommands.Add(250, null));
		TimelineMarker marker = store.state().markers().get(0);
		// Timeline 250 at 2x is source 500.
		assertEquals(500, marker.startMs());
		assertEquals("Marker 1", marker.name());
		assertFalse(marker.chapter());
		assertEquals(marker.id(), store.selectedMarkerId());
		assertEquals(250L, MarkerTimeline.timelineMs(marker, store.state().clipRegions()));

		store.apply(new MarkerCommands.Add(0, "  My   Chapter "));
		assertEquals("My Chapter", store.state().markers().get(0).name());
	}

	@Test
	@DisplayName("renaming keeps the position; a blank name falls back to Marker")
	void rename() {
		EditorStore store = new EditorStore(state(List.of(clip("clip-1", 0, 1000, 0L, 2)),
				List.of(new TimelineMarker("marker-1", 400, "Old", false))));
		assertTrue(store.apply(new MarkerCommands.Rename("marker-1", "New name")));
		assertEquals(new TimelineMarker("marker-1", 400, "New name", false),
				store.state().markers().get(0));
		store.apply(new MarkerCommands.Rename("marker-1", "   "));
		assertEquals("Marker", store.state().markers().get(0).name());
		assertFalse(store.apply(new MarkerCommands.Rename("missing", "x")));
	}

	@Test
	@DisplayName("a ruler drag maps the timeline position back to source time")
	void moveToTimelineMs() {
		EditorStore store = store();
		store.apply(new MarkerCommands.Add(0, null));
		String id = store.state().markers().get(0).id();
		assertTrue(store.apply(new MarkerCommands.MoveToTimelineMs(id, 900)));
		assertEquals(1800, store.state().markers().get(0).startMs());
		assertEquals(900L, MarkerTimeline.timelineMs(store.state().markers().get(0),
				store.state().clipRegions()));
	}

	@Test
	@DisplayName("a marker promoted to a chapter keeps its name; demoting clears the flag")
	void toggleChapter() {
		EditorStore store = store();
		store.apply(new MarkerCommands.Add(0, "Intro"));
		String id = store.state().markers().get(0).id();
		store.apply(new MarkerCommands.SetChapter(id, true));
		assertTrue(store.state().markers().get(0).chapter());
		assertEquals("Intro", store.state().markers().get(0).name());
		store.apply(new MarkerCommands.SetChapter(id, false));
		assertFalse(store.state().markers().get(0).chapter());
	}

	@Test
	@DisplayName("deleting removes the marker and clears its selection")
	void delete() {
		EditorStore store = store();
		store.apply(new MarkerCommands.Add(0, null));
		String id = store.state().markers().get(0).id();
		assertTrue(store.apply(new MarkerCommands.Delete(id)));
		assertEquals(List.of(), store.state().markers());
		assertNull(store.selectedMarkerId());
		assertFalse(store.apply(new MarkerCommands.Delete(id)));
	}

	@Test
	@DisplayName("every marker edit is one undoable step, and undo restores the list")
	void undoable() {
		EditorStore store = store();
		store.apply(new MarkerCommands.Add(100, null));
		store.apply(new MarkerCommands.Add(200, null));
		assertEquals(2, store.state().markers().size());
		assertTrue(store.undo());
		assertEquals(1, store.state().markers().size());
		assertTrue(store.undo());
		assertEquals(List.of(), store.state().markers());
		assertTrue(store.redo());
		assertEquals(1, store.state().markers().size());
	}

	@Test
	@DisplayName("markers survive a project round trip through JSON")
	void roundTrip() {
		ProjectEditorState state = state(List.of(clip("clip-1", 0, 1000, 0L, 2)),
				List.of(new TimelineMarker("marker-1", 400, "Chapter one", true),
						new TimelineMarker("marker-2", 900, "Note", false)));
		var document = ProjectDocument.create("/tmp/recording.mp4", state);
		var json = document.toJson();
		assertEquals(2, json.get("editor").get("markers").size());
		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				json.get("editor"));
		assertEquals(state.markers(), reloaded.markers());
	}

	@Test
	@DisplayName("the ids of markers generated by the store do not collide with loaded ones")
	void idSequence() {
		EditorStore store = new EditorStore(state(List.of(clip("clip-1", 0, 1000, 0L, 2)),
				List.of(new TimelineMarker("marker-4", 100, "Four", false))));
		store.apply(new MarkerCommands.Add(0, null));
		assertEquals("marker-5", store.state().markers().stream()
				.filter(marker -> marker.name().equals("Marker 2") || marker.startMs() == 0)
				.findFirst().orElseThrow().id());
	}
}
