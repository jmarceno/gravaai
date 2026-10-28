package dev.gravaai.ui.editor.transcript;

import java.util.List;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ProjectPersistence;

/** Deterministic transcript fixtures for the panel/gallery tests (real editor store). */
final class TranscriptFixtures {

	static final String EDITOR = """
			{
			  "clipRegions": [
			    {"id":"clip-1","startMs":0,"endMs":6000,"speed":1}
			  ],
			  "autoCaptions": [
			    {"id":"cue-1","startMs":500,"endMs":4000,"text":"one um three four",
			     "words":[
			       {"text":"one","startMs":500,"endMs":1000},
			       {"text":"um","startMs":1500,"endMs":1700,"leadingSpace":true},
			       {"text":"three","startMs":2500,"endMs":3000,"leadingSpace":true},
			       {"text":"four","startMs":3500,"endMs":4000,"leadingSpace":true}
			     ]}
			  ],
			  "autoCaptionSettings": {"enabled":true,"language":"en"}
			}
			""";

	static final String EMPTY_EDITOR = """
			{
			  "clipRegions": [
			    {"id":"clip-1","startMs":0,"endMs":6000,"speed":1}
			  ],
			  "autoCaptions": [],
			  "autoCaptionSettings": {"enabled":true,"language":"en"}
			}
			""";

	/** Speed-clip fixture for the caption-speed mapping tests (1x, 2x, 4x use the same source). */
	static String speedEditor(double speed) {
		return "{\"clipRegions\":[{\"id\":\"clip-1\",\"startMs\":0,\"endMs\":"
				+ Math.round(6000 / speed) + ",\"speed\":" + speed + "}],\"autoCaptions\":[]}";
	}

	private TranscriptFixtures() {
	}

	static EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(EDITOR)));
	}

	static EditorStore emptyStore() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(EMPTY_EDITOR)));
	}

	/** The generated cues used by the fake caption service (same words as {@link #EDITOR}). */
	static List<CaptionCue> generatedCues() {
		return List.of(new CaptionCue("cue-1", 500, 4_000, "one um three four", List.of(
				new CaptionCueWord("one", 500, 1_000, null),
				new CaptionCueWord("um", 1_500, 1_700, true),
				new CaptionCueWord("three", 2_500, 3_000, true),
				new CaptionCueWord("four", 3_500, 4_000, true))));
	}
}
