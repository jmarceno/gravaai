package dev.gravaai.ui.editor.captions;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;

/** Deterministic caption fixtures for the panel/gallery tests (real editor store). */
final class CaptionFixtures {

	static final String FIXTURE = """
			{
			  "clipRegions": [
			    {"id":"clip-1","startMs":0,"endMs":6000,"speed":1}
			  ],
			  "autoCaptions": [
			    {"id":"cap-a","startMs":1000,"endMs":2000,"text":"Hello Hello",
			     "words":[{"text":"Hello","startMs":1000,"endMs":1500},
			              {"text":"Hello","startMs":1500,"endMs":2000,"leadingSpace":true}]},
			    {"id":"cap-b","startMs":2300,"endMs":3000,"text":"Second caption"}
			  ]
			}
			""";

	private CaptionFixtures() {
	}

	static EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark(FIXTURE)));
	}
}
