package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Behaviour tests for {@link CaptionCommands} derived from {@code useCaptionCommands.ts}. */
class CaptionCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static EditorStore storeWithClip() {
		return new EditorStore(state("""
				{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":5000,"speed":1}],
				"autoCaptions":[{"id":"a","startMs":0,"endMs":1000,"text":"one two",
				"words":[{"text":"one","startMs":0,"endMs":500},
				{"text":"two","startMs":500,"endMs":1000,"leadingSpace":true}]}]}
				"""));
	}

	@Test
	@DisplayName("adds a cue from the clip under the timeline span and selects it")
	void addsCueFromClip() {
		EditorStore store = storeWithClip();
		store.apply(new CaptionCommands.Add(1_000, 2_000));

		assertEquals(2, store.state().autoCaptions().size());
		CaptionCue cue = store.state().autoCaptions().get(1);
		assertEquals(1_000, cue.startMs());
		assertEquals(2_000, cue.endMs());
		assertTrue(cue.id().startsWith("caption-"));
		assertEquals(cue.id(), store.selectedCaptionId());
		assertEquals(CaptionCommands.SECTION_CAPTION, store.activeEffectSection());
	}

	@Test
	@DisplayName("does not add a cue when no clip covers the span")
	void refusesCueWithoutClip() {
		EditorStore store = new EditorStore(state("""
				{"clipRegions":[{"id":"clip-1","startMs":0,"endMs":1000,"speed":1}]}
				"""));
		assertFalse(store.apply(new CaptionCommands.Add(2_000, 3_000)));
		assertTrue(store.state().autoCaptions().isEmpty());
	}

	@Test
	@DisplayName("selects and clears captions")
	void selectsCaptions() {
		EditorStore store = storeWithClip();
		store.apply(new CaptionCommands.Select("a"));
		assertEquals("a", store.selectedCaptionId());
		assertEquals(CaptionCommands.SECTION_CAPTION, store.activeEffectSection());
		assertNull(store.selectedZoomId());

		store.apply(new CaptionCommands.Select(null));
		assertNull(store.selectedCaptionId());
		assertEquals(EditorStore.SECTION_SCENE, store.activeEffectSection());
	}

	@Test
	@DisplayName("edits cue text through the word-aware editor")
	void editsCueText() {
		EditorStore store = storeWithClip();
		store.apply(new CaptionCommands.TextEdit("a", "uno dos"));

		CaptionCue cue = store.state().autoCaptions().get(0);
		assertEquals("uno dos", cue.text());
		assertEquals(2, cue.words().size());
	}

	@Test
	@DisplayName("retimes, splits, merges and deletes cues")
	void retimesSplitsMergesDeletes() {
		EditorStore store = storeWithClip();
		store.apply(new CaptionCommands.Retime("a", new dev.gravaai.core.project.CaptionOps.RetimeSpan(0, 2_000)));
		assertEquals(2_000, store.state().autoCaptions().get(0).endMs());

		store.apply(new CaptionCommands.Split("a", 1_000));
		assertEquals(2, store.state().autoCaptions().size());

		EditorStore mergeStore = storeWithClip();
		mergeStore.apply(new CaptionCommands.Add(2_000, 3_000));
		String secondId = mergeStore.state().autoCaptions().get(1).id();
		mergeStore.apply(new CaptionCommands.Merge("a", secondId));
		assertEquals(1, mergeStore.state().autoCaptions().size());

		store.apply(new CaptionCommands.Delete("a"));
		assertEquals(1, store.state().autoCaptions().size());
	}

	@Test
	@DisplayName("clears all captions and disables the auto-caption setting")
	void clearsAllCaptions() {
		EditorStore store = storeWithClip();
		store.apply(new CaptionCommands.Select("a"));
		store.apply(new CaptionCommands.ClearAll());

		assertTrue(store.state().autoCaptions().isEmpty());
		assertEquals(false, store.state().autoCaptionSettings().enabled());
		// handleClearAutoCaptions does not touch the selection.
		assertEquals("a", store.selectedCaptionId());
	}
}
