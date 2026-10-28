package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Behaviour tests for {@link AudioCommands} derived from {@code useAudioRegionCommands.ts}. */
class AudioCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	@Test
	@DisplayName("adds an audio region at volume 1, selects it and shows the audio section")
	void addsAudioRegion() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AudioCommands.Add(0, 2_000, "/music.wav", 1));

		AudioRegion region = store.state().audioRegions().get(0);
		assertEquals("audio-1", region.id());
		assertEquals("/music.wav", region.audioPath());
		assertEquals(1, region.volume());
		assertEquals(false, region.normalize());
		assertEquals(1, region.trackIndex());
		assertEquals(region.id(), store.selectedAudioId());
		assertEquals(EditorStore.SECTION_AUDIO, store.activeEffectSection());
	}

	@Test
	@DisplayName("changes span, track, volume and normalisation on the selected region")
	void changesAudioFields() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AudioCommands.Add(0, 2_000, "/music.wav", null));
		String id = store.selectedAudioId();

		store.apply(new AudioCommands.ChangeSpan(id, 500, 1_500, 2));
		store.apply(new AudioCommands.ChangeVolume(0.4));
		store.apply(new AudioCommands.SetNormalize(true));

		AudioRegion region = store.state().audioRegions().get(0);
		assertEquals(500, region.startMs());
		assertEquals(1_500, region.endMs());
		assertEquals(2, region.trackIndex());
		assertEquals(0.4, region.volume());
		assertEquals(true, region.normalize());
	}

	@Test
	@DisplayName("clamps volume and ignores updates without a selection")
	void clampsVolume() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AudioCommands.Add(0, 1_000, "/music.wav", null));
		store.apply(new AudioCommands.ChangeVolume(9));
		assertEquals(1, store.state().audioRegions().get(0).volume());

		store.setSelectedAudioId(null);
		assertTrue(!store.apply(new AudioCommands.ChangeVolume(0.5)));
		assertTrue(!store.apply(new AudioCommands.SetNormalize(true)));
	}

	@Test
	@DisplayName("deletes the region and clears its selection")
	void deletesAudioRegion() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AudioCommands.Add(0, 1_000, "/music.wav", null));
		String id = store.selectedAudioId();
		store.apply(new AudioCommands.Delete(id));

		assertTrue(store.state().audioRegions().isEmpty());
		assertNull(store.selectedAudioId());
	}
}
