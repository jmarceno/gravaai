package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.GradeRegion;
import dev.gravaai.core.project.GradeTarget;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Behaviour tests for {@link GradeCommands} (the Color track). */
class GradeCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static final String REGIONS = """
			{"gradeRegions":[
				{"id":"grade-1","startMs":100,"endMs":1000,"brightness":10,"contrast":-20,
				 "saturation":30,"temperature":40,"gamma":1.5},
				{"id":"grade-2","startMs":2000,"endMs":2900,"brightness":0,"contrast":0,
				 "saturation":0,"temperature":0,"gamma":1}]}
			""";

	@Test
	@DisplayName("normalizes grade regions: clamps the sliders and drops invalid entries")
	void normalizesRegions() {
		ProjectEditorState normalized = state("""
				{"gradeRegions":[
					{"id":"grade-1","startMs":500,"endMs":1400,"brightness":500,"contrast":-500,
					 "saturation":12.5,"temperature":null,"gamma":99},
					{"id":"","startMs":0,"endMs":10},
					{"id":"grade-2","startMs":900,"endMs":100}]}
				""");

		assertEquals(2, normalized.gradeRegions().size());
		GradeRegion first = normalized.gradeRegions().get(0);
		assertEquals(GradeRegion.MAX_ADJUST, first.brightness());
		assertEquals(GradeRegion.MIN_ADJUST, first.contrast());
		assertEquals(12.5, first.saturation());
		assertEquals(0, first.temperature());
		assertEquals(GradeRegion.MAX_GAMMA, first.gamma());
		GradeRegion second = normalized.gradeRegions().get(1);
		// A reversed span is swapped (the shared normalizeSpan rule), never negative.
		assertEquals(100, second.startMs());
		assertEquals(101, second.endMs());
		assertEquals("grade-2", second.id());
	}

	@Test
	@DisplayName("adding a grade selects it, clearing the other selections")
	void addsAndSelects() {
		EditorStore store = new EditorStore(state(REGIONS));
		store.apply(new ZoomCommands.Add(0, 500));
		assertEquals(store.state().zoomRegions().get(0).id(), store.selectedZoomId());

		store.apply(new GradeCommands.Add(1000, 2000));

		List<GradeRegion> grades = store.state().gradeRegions();
		assertEquals(3, grades.size());
		GradeRegion added = grades.get(2);
		assertEquals("grade-3", added.id());
		assertEquals(1000, added.startMs());
		assertEquals(2000, added.endMs());
		assertTrue(added.isNeutral());
		assertEquals("grade-3", store.selectedGradeId());
		assertNull(store.selectedZoomId());
	}

	@Test
	@DisplayName("changing params keeps the span and clamps the values")
	void changesParams() {
		EditorStore store = new EditorStore(state(REGIONS));

		store.apply(new GradeCommands.ChangeParams("grade-1", -500, 20, -30, 40, 0.5));

		GradeRegion graded = store.state().gradeRegions().get(0);
		assertEquals(GradeRegion.MIN_ADJUST, graded.brightness());
		assertEquals(20, graded.contrast());
		assertEquals(-30, graded.saturation());
		assertEquals(40, graded.temperature());
		// 0.5 is inside the range, so it survives as given.
		assertEquals(0.5, graded.gamma());
		assertEquals(100, graded.startMs());
		assertEquals(1000, graded.endMs());
		assertFalse(graded.isNeutral());
	}

	@Test
	@DisplayName("moving a grade keeps the grading params")
	void changesSpan() {
		EditorStore store = new EditorStore(state(REGIONS));

		store.apply(new GradeCommands.ChangeSpan("grade-1", 1500, 2500));

		GradeRegion moved = store.state().gradeRegions().get(0);
		assertEquals(1500, moved.startMs());
		assertEquals(2500, moved.endMs());
		assertEquals(10, moved.brightness());
		assertEquals(1.5, moved.gamma());
	}

	@Test
	@DisplayName("deleting a grade clears its selection and undo restores both")
	void deletesAndUndoes() {
		EditorStore store = new EditorStore(state(REGIONS));
		store.apply(new GradeCommands.Select("grade-1"));
		int before = store.state().gradeRegions().size();

		store.apply(new GradeCommands.Delete("grade-1"));

		assertEquals(before - 1, store.state().gradeRegions().size());
		assertNull(store.selectedGradeId());

		store.undo();

		assertEquals(before, store.state().gradeRegions().size());
		assertEquals("grade-1", store.selectedGradeId());
	}

	@Test
	@DisplayName("grade regions survive a project save/load round trip")
	void roundTripsThroughProjectDocument() {
		ProjectEditorState state = state(REGIONS);
		ProjectDocument document = ProjectDocument.create("video.mp4", state);
		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				document.toJson().get("editor"));

		assertEquals(2, reloaded.gradeRegions().size());
		GradeRegion first = reloaded.gradeRegions().get(0);
		assertEquals("grade-1", first.id());
		assertEquals(10, first.brightness());
		assertEquals(-20, first.contrast());
		assertEquals(30, first.saturation());
		assertEquals(40, first.temperature());
		assertEquals(1.5, first.gamma());
	}

	@Test
	@DisplayName("a state without grades writes no gradeRegions key (old files round-trip as-is)")
	void writesNoKeyWithoutGrades() {
		ProjectEditorState state = state("{}");
		ProjectDocument document = ProjectDocument.create("video.mp4", state);

		assertTrue(document.toJson().get("editor").get("gradeRegions") == null);
	}

	@Test
	@DisplayName("labels name the moved sliders")
	void labelsTheGrades() {
		assertEquals("Grade", state(REGIONS).gradeRegions().get(1).label());
		assertEquals("+10 light warm +30 sat", new GradeRegion("g", 0, 1000, 10, 0, 30, 40, 1)
				.label());
		assertEquals("Grade", new GradeRegion("g", 0, 1000, 0, 0, 0, 0, 1).label());
	}

	@Test
	@DisplayName("normalizes the layer choice: camera and both survive, unknown reads as main")
	void normalizesTargets() {
		ProjectEditorState normalized = state("""
				{"gradeRegions":[
					{"id":"grade-1","startMs":0,"endMs":100,"targets":"camera"},
					{"id":"grade-2","startMs":0,"endMs":100,"targets":"both"},
					{"id":"grade-3","startMs":0,"endMs":100,"targets":"vibes"},
					{"id":"grade-4","startMs":0,"endMs":100}]}
				""");

		assertEquals(GradeTarget.CAMERA, normalized.gradeRegions().get(0).targets());
		assertEquals(GradeTarget.BOTH, normalized.gradeRegions().get(1).targets());
		assertEquals(GradeTarget.MAIN, normalized.gradeRegions().get(2).targets());
		assertEquals(GradeTarget.MAIN, normalized.gradeRegions().get(3).targets());
	}

	@Test
	@DisplayName("changing the target keeps the span and sliders; undo restores it")
	void changesTarget() {
		EditorStore store = new EditorStore(state(REGIONS));

		store.apply(new GradeCommands.ChangeTarget("grade-1", GradeTarget.CAMERA));

		GradeRegion retargeted = store.state().gradeRegions().get(0);
		assertEquals(GradeTarget.CAMERA, retargeted.targets());
		assertEquals(10, retargeted.brightness());
		assertEquals(100, retargeted.startMs());
		// An unknown id changes nothing.
		assertFalse(store.apply(new GradeCommands.ChangeTarget("nope", GradeTarget.BOTH)));

		store.undo();

		assertEquals(GradeTarget.MAIN, store.state().gradeRegions().get(0).targets());
	}

	@Test
	@DisplayName("the layer choice survives a project save/load round trip; main stays omitted")
	void roundTripsTargets() {
		ProjectEditorState graded = state("""
				{"gradeRegions":[
					{"id":"grade-1","startMs":0,"endMs":100,"brightness":5},
					{"id":"grade-2","startMs":200,"endMs":300,"targets":"camera"},
					{"id":"grade-3","startMs":400,"endMs":500,"targets":"both"}]}
				""");
		ProjectDocument document = ProjectDocument.create("video.mp4", graded);

		// The default target is omitted so grades that never left it keep the saved bytes.
		assertTrue(document.toJson().get("editor").get("gradeRegions").get(0)
				.get("targets") == null);
		assertEquals("camera", document.toJson().get("editor").get("gradeRegions").get(1)
				.get("targets").asString());
		ProjectEditorState reloaded = ProjectPersistence.normalizeProjectEditor(
				document.toJson().get("editor"));
		assertEquals(GradeTarget.MAIN, reloaded.gradeRegions().get(0).targets());
		assertEquals(GradeTarget.CAMERA, reloaded.gradeRegions().get(1).targets());
		assertEquals(GradeTarget.BOTH, reloaded.gradeRegions().get(2).targets());
	}

	@Test
	@DisplayName("labels name the layers a non-default grade shades")
	void labelsTheTargets() {
		assertEquals("Cam +10 light", new GradeRegion("g", 0, 1000, 10, 0, 0, 0, 1,
				GradeTarget.CAMERA).label());
		assertEquals("Both warm", new GradeRegion("g", 0, 1000, 0, 0, 0, 20, 1,
				GradeTarget.BOTH).label());
		assertEquals("Cam Grade", new GradeRegion("g", 0, 1000, 0, 0, 0, 0, 1,
				GradeTarget.CAMERA).label());
		assertEquals("Both Grade", new GradeRegion("g", 0, 1000, 0, 0, 0, 0, 1,
				GradeTarget.BOTH).label());
	}
}
