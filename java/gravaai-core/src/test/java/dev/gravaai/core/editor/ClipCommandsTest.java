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
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.ZoomRegion;

/** Port of {@code hooks/useClipRegionCommands.test.ts} (React hook adapted to {@link EditorStore}). */
class ClipCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	@Test
	@DisplayName("selects the middle section after two cuts so Delete removes it")
	void selectsMiddleSectionAfterTwoCuts() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"original","startMs":0,"endMs":3000,"speed":1}]}
				"""));

		store.apply(new ClipCommands.Split(1_000));
		assertEquals(store.state().clipRegions().get(0).id(), store.selectedClipId());
		store.setSelectedClipId(null);
		store.apply(new ClipCommands.Split(2_000));
		assertEquals(store.state().clipRegions().get(1).id(), store.selectedClipId());
		store.apply(new ClipCommands.Delete(store.selectedClipId()));

		assertEquals(2, store.state().clipRegions().size());
		assertEquals(0, store.state().clipRegions().get(0).startMs());
		assertEquals(1_000, store.state().clipRegions().get(0).endMs());
		assertEquals(1_000, store.state().clipRegions().get(1).startMs());
		assertEquals(2_000, store.state().clipRegions().get(1).endMs());
		assertEquals(2_000L, store.state().clipRegions().get(1).sourceStartMs());
	}

	@Test
	@DisplayName("packs the sequence after a cut and ripples the zoom regions that follow it")
	void packsSequenceAfterCut() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":3000,"speed":1},
				{"id":"b","startMs":3000,"endMs":6000,"speed":1}],
				"zoomRegions":[{"id":"zoom","startMs":4000,"endMs":5000,"depth":3,
				"focus":{"cx":0.5,"cy":0.5}}]}
				"""));
		store.setSelectedClipId("a");

		assertTrue(store.apply(new ClipCommands.CutSourceRanges(
				List.of(new TranscriptCut.SourceRange(1_000, 2_000)))));

		List<ClipRegion> clips = store.state().clipRegions();
		assertEquals(3, clips.size());
		assertEquals(new ClipRegion("a", 0, 1_000, 0L, null, null, 1, false, false, null, null),
				clips.get(0));
		assertEquals(1_000, clips.get(1).startMs());
		assertEquals(2_000, clips.get(1).endMs());
		assertEquals(2_000L, clips.get(1).sourceStartMs());
		assertEquals(new ClipRegion("b", 2_000, 5_000, 3000L, null, null, 1, false, false, null, null),
				clips.get(2));
		assertNull(store.selectedClipId());

		List<ZoomRegion> zooms = store.state().zoomRegions();
		assertEquals(1, zooms.size());
		assertEquals(3_000, zooms.get(0).startMs());
		assertEquals(4_000, zooms.get(0).endMs());
	}

	@Test
	@DisplayName("refuses a cut that would delete the whole sequence")
	void refusesFullCut() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":3000,"speed":1}]}
				"""));

		assertFalse(store.apply(new ClipCommands.CutSourceRanges(
				List.of(new TranscriptCut.SourceRange(0, 3_000)))));
		assertEquals(1, store.state().clipRegions().size());
	}

	@Test
	@DisplayName("changes speed without moving the source window")
	void changesSpeed() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":2000,"speed":1}]}
				"""));

		store.apply(new ClipCommands.Select("a"));
		store.apply(new ClipCommands.ChangeSpeed(2));

		ClipRegion clip = store.state().clipRegions().get(0);
		assertEquals(1_000, clip.endMs());
		assertEquals(2, clip.speed());
		assertEquals(0L, clip.sourceStartMs());
	}

	@Test
	@DisplayName("toggles muted / camera layout / transition / source audio on the selected clip")
	void togglesClipFlags() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":2000,"speed":1}]}
				"""));

		store.apply(new ClipCommands.Select("a"));
		store.apply(new ClipCommands.SetMuted(true));
		store.apply(new ClipCommands.SetCameraLayout(
				dev.gravaai.core.project.ClipCameraLayout.CAMERA_ONLY));
		store.apply(new ClipCommands.SetTransition(
				new dev.gravaai.core.project.ClipTransition(
						dev.gravaai.core.project.ClipTransitionKind.FADE_BLACK, 500)));
		store.apply(new ClipCommands.SetShowSourceAudio(true));

		ClipRegion clip = store.state().clipRegions().get(0);
		assertEquals(true, clip.muted());
		assertEquals(dev.gravaai.core.project.ClipCameraLayout.CAMERA_ONLY, clip.cameraLayout());
		assertEquals(500, clip.transitionOut().durationMs());
		assertEquals(true, clip.showSourceAudio());
	}
}
