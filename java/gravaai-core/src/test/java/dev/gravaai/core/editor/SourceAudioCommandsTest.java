package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SourceAudioTrackSettings;

/** Source-audio store command (P2.7): merge semantics, clamping and undo. */
class SourceAudioCommandsTest {

	private static final String EDITOR = """
			{"sourceAudioTrackSettingsByClip":{"clip-1":{"mic":{"volume":0.5,"extra":"keep"},
			 "unknownTrack":{"x":1}}},"defaultSourceAudioTrackSettings":{}}
			""";

	private static EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(EDITOR)));
	}

	@Test
	@DisplayName("sets volume/normalize for a track and preserves unknown keys")
	void setsTrack() {
		EditorStore store = store();
		assertTrue(store.apply(new SourceAudioCommands.SetTrackSettings("clip-1", "mixed", 1.5, true)));
		SourceAudioTrackSettings settings = store.state().sourceAudioTrackSettings().get("clip-1");
		assertEquals(new SourceAudioTrackSettings.Track(1.5, true), settings.track("mixed"));
		assertEquals(0.5, settings.track("mic").volume(), 0.001);
		assertEquals("keep",
				store.state().sourceAudioTrackSettingsByClip().path("clip-1").path("mic")
						.path("extra").asString());
		assertEquals(1, store.state().sourceAudioTrackSettingsByClip().path("clip-1")
				.path("unknownTrack").path("x").intValue());
	}

	@Test
	@DisplayName("undo restores the previous source-audio map")
	void clampsAndUndoes() {
		EditorStore store = store();
		store.apply(new SourceAudioCommands.SetTrackSettings("clip-1", "system", 9, false));
		assertEquals(2.0, store.state().sourceAudioTrackSettings().get("clip-1").track("system")
				.volume(), 0.001);
		assertFalse(store.apply(new SourceAudioCommands.SetTrackSettings(null, "mic", 1, false)));
		assertFalse(store.apply(new SourceAudioCommands.SetTrackSettings("clip-1", "", 1, false)));

	}
}
