package dev.gravaai.app.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ElementMotion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SceneElement;
import tools.jackson.databind.node.ObjectNode;

/**
 * P3.2b acceptance-adjacent unit tests for the agent editor port's pure halves: the inspect views
 * ({@code views.ts} port) and the export option validation that guards {@code job start}.
 *
 * <p>These run headless; the real {@code edit}/{@code frames}/{@code job} execution is covered by
 * {@code docs/java-rewrite/phase-3-skill-loop-evidence.md} against the packaged app.
 */
class AgentEditorViewsTest {

	@Test
	@DisplayName("overview reports the project, source/edited durations, counts and rev")
	void overviewReportsCountsAndRev() throws Exception {
		ProjectEditorState state = load("03-zoom-motion-blur.gravaai");
		String text = AgentEditorViews.overview(state, meta(7));
		assertTrue(text.contains("project: 03-zoom-motion-blur"), text);
		assertTrue(text.contains("source: ") && text.contains("(2.00s)"), text);
		assertTrue(text.contains("edited: 2.00s"), text);
		assertTrue(text.contains("aspect 16:9"), text);
		assertTrue(text.contains("zooms 2"), text);
		assertTrue(text.contains("rev 7"), text);
	}

	@Test
	@DisplayName("timeline names the short ids the edit batch has to use")
	void timelineNamesShortIds() throws Exception {
		ProjectEditorState state = load("03-zoom-motion-blur.gravaai");
		String text = AgentEditorViews.timeline(state, 2000, null, null, 11);
		assertTrue(text.contains("clips:"), text);
		assertTrue(text.contains("c1 0.00"), text);
		assertTrue(text.contains("zooms:"), text);
		assertTrue(text.contains(" z1 "), text);
		assertTrue(text.contains(" z2 "), text);
		assertTrue(text.contains("captions: 1 cues"), text);
		assertTrue(text.contains("rev 11"), text);
	}

	@Test
	@DisplayName("timeline honours the from/to window")
	void timelineHonoursWindow() throws Exception {
		ProjectEditorState state = load("03-zoom-motion-blur.gravaai");
		String text = AgentEditorViews.timeline(state, 2000, 1.5, 1.9, 3);
		assertTrue(text.contains("window 1.50\u20131.90s of 2.00s"), text);
		assertTrue(text.contains("(none in window)"), text);
	}

	@Test
	@DisplayName("annotations appear with normalized centre/size in the timeline view")
	void timelineShowsAnnotations() throws Exception {
		ProjectEditorState state = load("09-annotations.gravaai");
		String text = AgentEditorViews.timeline(state, 2000, null, null, 5);
		assertTrue(text.contains(" a1 "), text);
		assertTrue(text.contains("annotations:"), text);
	}

	@Test
	@DisplayName("transcript view lists cue words with short ids and the rev")
	void transcriptListsWords() throws Exception {
		ProjectEditorState state = load("03-zoom-motion-blur.gravaai");
		String text = AgentEditorViews.transcript(state, 2000, null, null, 9);
		assertTrue(text.contains("words 0.00\u20132.00s of 2.00s"), text);
		assertTrue(text.contains("q1 0.00\u20132.00"), text);
		assertTrue(text.contains("w1 0.00"), text);
		assertTrue(text.contains("rev 9"), text);
	}

	@Test
	@DisplayName("settings view is a flat key=value list with hints, skipping timeline arrays")
	void settingsIsFlatKeyValue() throws Exception {
		ProjectEditorState state = load("03-zoom-motion-blur.gravaai");
		String text = AgentEditorViews.settings(state, "zoom", 2);
		assertTrue(text.contains("zoomMotionBlur="), text);
		assertTrue(text.contains("zoomInDurationMs="), text);
		assertFalse(text.contains("zoomRegions="), text);
		assertTrue(text.contains("rev 2"), text);
	}

	@Test
	@DisplayName("export destination must be an absolute mp4/gif path")
	void exportDestinationValidation() {
		assertEquals("/tmp/out.mp4", AgentExportRunner.destination(options("/tmp/out.mp4", null))
				.toString());
		assertThrows(IllegalArgumentException.class,
				() -> AgentExportRunner.destination(options("relative/out.mp4", null)));
		assertThrows(IllegalArgumentException.class,
				() -> AgentExportRunner.destination(options("/tmp/out.mkv", null)));
		assertThrows(IllegalArgumentException.class,
				() -> AgentExportRunner.destination(options("/tmp/out.gif", "mp4")));
		assertThrows(IllegalArgumentException.class,
				() -> AgentExportRunner.destination(options("/tmp/out.mp4", "gif")));
		assertEquals("gif", AgentExportRunner.format(options("/tmp/out.gif", "gif")));
	}

	@Test
	@DisplayName("overview warns when a full-frame screen-space element overlaps scene elements")
	void overviewWarnsAboutHiddenSceneElements() throws Exception {
		ProjectEditorState base = load("03-zoom-motion-blur.gravaai");
		SceneElement backdrop = new SceneElement("element-a", "gravaai/shader-backdrop", 0, 4000,
				SceneElement.Box.FULL, Json.nodeFactory().objectNode(), 0, SceneElement.Space.SCREEN,
				List.of(), ElementMotion.Blur.NONE);
		SceneElement callout = new SceneElement("element-b", "gravaai/callout", 1000, 2000,
				new SceneElement.Box(30, 30, 40, 30), Json.nodeFactory().objectNode(), 0,
				SceneElement.Space.SCENE, List.of(), ElementMotion.Blur.NONE);
		ProjectEditorState state = base.toBuilder().elements(List.of(backdrop, callout)).build();
		assertEquals("e1 (screen, full-frame) may hide scene elements e2",
				AgentEditorViews.fullFrameHidesWarning(state));
		assertTrue(AgentEditorViews.overview(state, meta(7))
				.contains("e1 (screen, full-frame) may hide scene elements e2"));

		ProjectEditorState small = base.toBuilder().elements(List.of(new SceneElement("element-a",
				"gravaai/shader-backdrop", 0, 4000, new SceneElement.Box(10, 10, 80, 80),
				Json.nodeFactory().objectNode(), 0))).build();
		assertNull(AgentEditorViews.fullFrameHidesWarning(small));

		SceneElement disjointCallout = new SceneElement("element-b", "gravaai/callout", 4000, 5000,
				new SceneElement.Box(30, 30, 40, 30), Json.nodeFactory().objectNode(), 0,
				SceneElement.Space.SCENE, List.of(), ElementMotion.Blur.NONE);
		assertNull(AgentEditorViews.fullFrameHidesWarning(
				base.toBuilder().elements(List.of(backdrop, disjointCallout)).build()));
	}

	private static ObjectNode options(String path, String format) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("path", path);
		if (format != null) {
			node.put("format", format);
		}
		return node;
	}

	private static AgentEditorViews.Meta meta(long rev) throws Exception {
		return new AgentEditorViews.Meta("03-zoom-motion-blur",
				fixtures().resolve("03-zoom-motion-blur.gravaai").toString(),
				fixtures().getParent().resolve("media").resolve("h264-1080p60.mp4").toString(), 2000, false, rev);
	}

	private static ProjectEditorState load(String name) throws Exception {
		return ProjectDocument.load(fixtures().resolve(name)).editor();
	}

	/** {@code java/parity/fixtures/projects} resolved from the module working directory. */
	private static Path fixtures() throws Exception {
		Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		for (Path candidate = root; candidate != null; candidate = candidate.getParent()) {
			Path fixtures = candidate.resolve("parity").resolve("fixtures").resolve("projects");
			if (Files.isDirectory(fixtures)) {
				return fixtures;
			}
			Path javaFixtures = candidate.resolve("java").resolve("parity").resolve("fixtures")
					.resolve("projects");
			if (Files.isDirectory(javaFixtures)) {
				return javaFixtures;
			}
		}
		throw new IllegalStateException("could not locate java/parity/fixtures/projects from " + root);
	}
}
