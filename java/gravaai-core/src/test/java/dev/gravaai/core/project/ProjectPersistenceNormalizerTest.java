package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.EditorHistory;
import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code src/components/video-editor/projectPersistence.test.ts} (the
 * {@code normalizeProjectEditor} part; the Electron media-server cases are N/A in the Java app,
 * plan §5.5, and the local file-URL conversion is covered separately).
 */
class ProjectPersistenceNormalizerTest {

	private static JsonNode editor(String json) {
		return Json.parseJsonWithByteOrderMark(json);
	}

	private static ProjectEditorState normalize(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(editor(editorJson));
	}

	@Test
	@DisplayName("ignores retired temporal blur fields when opening an older project")
	void ignoresRetiredTemporalBlurFields() {
		ProjectEditorState normalized = normalize("""
				{"zoomMotionBlur":0.6,"zoomTemporalMotionBlur":0.35,
				"zoomMotionBlurSampleCount":13,"zoomMotionBlurShutterFraction":0.94}
				""");
		assertEquals(0.6, normalized.zoomMotionBlur());
	}

	@Test
	@DisplayName("defaults to 8% on macOS and square corners elsewhere")
	void defaultsBorderRadiusPerPlatform() {
		assertEquals(8, ProjectPersistence.getDefaultBorderRadiusPercent("MacIntel"));
		assertEquals(0, ProjectPersistence.getDefaultBorderRadiusPercent("Win32"));
		assertEquals(0, ProjectPersistence.getDefaultBorderRadiusPercent("Linux x86_64"));
	}

	@Test
	@DisplayName("clamps radius percentages")
	void clampsRadiusPercentages() {
		assertEquals(50, normalize("{\"borderRadius\":75}").borderRadius());
	}

	@Test
	@DisplayName("converts legacy 1080p-relative radius pixels to percentages")
	void convertsLegacyRadiusPixels() {
		assertEquals(5, ProjectPersistence.legacyBorderRadiusPixelsToPercent(54));
	}

	@Test
	@DisplayName("preserves the extended advanced vertical padding range")
	void preservesAdvancedVerticalPadding() {
		ProjectEditorState state = normalize("""
				{"padding":{"top":240,"bottom":250,"left":22,"right":22,"linked":false}}
				""");
		assertEquals(new Padding(240, 250, 22, 22, false), state.padding());
	}

	@Test
	@DisplayName("keeps linked padding clamped to the original range")
	void keepsLinkedPaddingClamped() {
		ProjectEditorState state = normalize("""
				{"padding":{"top":250,"bottom":250,"left":250,"right":250,"linked":true}}
				""");
		assertEquals(new Padding(100, 100, 100, 100, true), state.padding());
	}

	@Test
	@DisplayName("migrates legacy webcam radius pixels to percentage roundness")
	void migratesLegacyWebcamRadius() {
		ProjectEditorState state = normalize("{\"webcam\":{\"cornerRadius\":90,\"width\":40,\"height\":40}}");
		assertEquals(17.36, state.webcam().roundness(), 0.1);
		assertNull(state.webcam().cornerRadius());
	}

	@Test
	@DisplayName("uses the legacy webcam size when migrating radius pixels")
	void usesLegacyWebcamSizeWhenMigratingRadius() {
		ProjectEditorState state = normalize("{\"webcam\":{\"cornerRadius\":90,\"size\":80}}");
		assertEquals(80, state.webcam().width());
		assertEquals(80, state.webcam().height());
		assertEquals(4.34, state.webcam().roundness(), 0.1);
	}

	private static final String SAVED_SEQUENCE = """
			{"clipRegions":[
				{"id":"a","startMs":0,"endMs":2000,"speed":1},
				{"id":"b","startMs":3000,"endMs":6000,"sourceStartMs":5000,"speed":1}],
			"zoomRegions":[{"id":"zoom","startMs":3500,"endMs":4500,"depth":2}],
			"annotationRegions":[{"id":"annotation","startMs":3500,"endMs":4500,"content":"Keep","type":"text"}],
			"audioRegions":[{"id":"music","startMs":3500,"endMs":7500,"audioPath":"/music.wav","volume":0.7}],
			"autoCaptions":[{"id":"cue","startMs":5500,"endMs":6000,"text":"Source timed"}],
			"sourceAudioTrackSettingsByClip":{"b":{"system":{"volume":0.4,"muted":false}}}}
			""";

	@Test
	@DisplayName("migrates clips and connected tracks before they become editor state")
	void migratesClipsAndConnectedTracks() {
		ProjectEditorState state = normalize(SAVED_SEQUENCE);

		assertEquals(new ClipRegion("b", 2000, 5000, 5000L, null, null, 1, false, false, null, null),
				state.clipRegions().get(1));
		assertEquals(new ZoomRegion("zoom", 2500, 3500, ZoomDepth.TWO,
				new ZoomFocus(0.5, 0.5), null), state.zoomRegions().get(0));
		assertEquals(2500, state.annotationRegions().get(0).startMs());
		assertEquals(3500, state.annotationRegions().get(0).endMs());
		assertEquals(2500, state.audioRegions().get(0).startMs());
		assertEquals(6500, state.audioRegions().get(0).endMs());
		assertEquals(List.of(new CaptionCue("cue", 5500, 6000, "Source timed", null)),
				state.autoCaptions());
		assertEquals(0.4, state.sourceAudioTrackSettingsByClip()
				.get("b").get("system").get("volume").doubleValue());

		ProjectEditorState reapplied = normalize(SAVED_SEQUENCE);
		assertEquals(state.clipRegions(), reapplied.clipRegions());
		assertEquals(state.zoomRegions(), reapplied.zoomRegions());
		assertEquals(state.annotationRegions(), reapplied.annotationRegions());
		assertEquals(state.audioRegions(), reapplied.audioRegions());
		assertEquals(state.autoCaptions(), reapplied.autoCaptions());
	}

	@Test
	@DisplayName("starts history at migrated footage, with only real edits to undo")
	void startsHistoryAtMigratedFootage() {
		ProjectEditorState state = normalize(SAVED_SEQUENCE);
		EditorHistory.Snapshot snapshot = new EditorHistory.Snapshot(List.of(), state.clipRegions(),
				state.speedRegions(), state.annotationRegions(), state.audioRegions(),
				state.autoCaptions(), null, null, null, null);
		ProjectEditorState reapplied = normalize(SAVED_SEQUENCE);
		EditorHistory.Snapshot reappliedSnapshot = new EditorHistory.Snapshot(List.of(),
				reapplied.clipRegions(), reapplied.speedRegions(), reapplied.annotationRegions(),
				reapplied.audioRegions(), reapplied.autoCaptions(), null, null, null, null);

		EditorHistory.Stack history = EditorHistory.createStack();
		assertEquals(EditorHistory.RecordResult.INITIALIZED, EditorHistory.record(history, snapshot));
		assertEquals(EditorHistory.RecordResult.UNCHANGED,
				EditorHistory.record(history, reappliedSnapshot));
		assertEquals(List.of(), history.past());

		EditorHistory.Snapshot edited = new EditorHistory.Snapshot(List.of(),
				List.of(state.clipRegions().get(0)), state.speedRegions(),
				state.annotationRegions(), state.audioRegions(), state.autoCaptions(),
				null, null, null, null);
		assertEquals(EditorHistory.RecordResult.RECORDED, EditorHistory.record(history, edited));
		assertEquals(state.clipRegions(),
				EditorHistory.undo(history, edited).clipRegions());
		assertEquals(List.of(), history.past());
	}

	@Test
	@DisplayName("preserves an explicit empty timeline and independent audio")
	void preservesExplicitEmptyTimeline() {
		ProjectEditorState state = normalize("""
				{"clipRegions":[],
				"audioRegions":[{"id":"music","startMs":3500,"endMs":7500,"audioPath":"/music.wav","volume":0.7}]}
				""");
		assertEquals(List.of(), state.clipRegions());
		assertEquals(3500, state.audioRegions().get(0).startMs());
		assertEquals(7500, state.audioRegions().get(0).endMs());
	}

	@Test
	@DisplayName("preserves annotations outside the recording rectangle and webcam visibility when a project is reopened")
	void preservesOutsideAnnotationsAndWebcamRanges() {
		ProjectEditorState state = normalize("""
				{"annotationRegions":[{"id":"outside","startMs":0,"endMs":1000,"type":"text",
				"position":{"x":-12,"y":110},"size":{"width":140,"height":20}}],
				"webcam":{"sourcePath":"/sequence-webcam.mp4","visibleRanges":[{"startMs":1200,"endMs":2000}]}}
				""");
		assertEquals(new AnnotationPosition(-12, 110), state.annotationRegions().get(0).position());
		assertEquals(140, state.annotationRegions().get(0).size().width());
		assertEquals(List.of(new WebcamOverlaySettings.TimeRange(1200, 2000)),
				state.webcam().visibleRanges());
	}

	@Test
	@DisplayName("discards inverted saved source bounds while preserving the in-point")
	void discardsInvertedSourceBounds() {
		ProjectEditorState state = normalize("""
				{"clipRegions":[{"id":"clip","startMs":0,"endMs":1000,"sourceStartMs":5000,
				"sourceMinMs":6000,"sourceMaxMs":4000,"speed":0}]}
				""");
		ClipRegion clip = state.clipRegions().get(0);
		assertEquals(5000L, clip.sourceStartMs());
		assertEquals(1, clip.speed());
		assertNull(clip.sourceMinMs());
		assertNull(clip.sourceMaxMs());
	}

	@Test
	@DisplayName("ignores legacy export preferences when opening and resaving a project")
	void ignoresLegacyExportPreferences() {
		String legacy = """
				{"wallpaper":"#112233","exportQuality":"high","exportFormat":"gif",
				"mp4FrameRate":60,"publishDestination":"local","whisperModelPath":"/machine/model.bin"}
				""";
		ProjectEditorState loaded = normalize(legacy);
		assertEquals("#112233", loaded.wallpaper());

		tools.jackson.databind.node.ObjectNode rawEditor =
				(tools.jackson.databind.node.ObjectNode) editor(legacy);
		tools.jackson.databind.node.ObjectNode data =
				ProjectPersistence.createProjectData("/clip.mp4", rawEditor, null);
		assertFalse(data.get("editor").has("exportQuality"));
		assertFalse(data.get("editor").has("exportFormat"));
		assertFalse(data.get("editor").has("mp4FrameRate"));
		assertFalse(data.get("editor").has("publishDestination"));
		assertFalse(data.get("editor").has("whisperModelPath"));
		assertEquals("high", rawEditor.get("exportQuality").asText());
	}

	@Test
	@DisplayName("keeps known camera layouts and drops unknown or default ones")
	void keepsKnownCameraLayouts() {
		ProjectEditorState state = normalize("""
				{"clipRegions":[
				{"id":"a","startMs":0,"endMs":1000,"speed":1,"cameraLayout":"camera-only"},
				{"id":"b","startMs":1000,"endMs":2000,"speed":1,"cameraLayout":"split"},
				{"id":"c","startMs":2000,"endMs":3000,"speed":1,"cameraLayout":"default"},
				{"id":"d","startMs":3000,"endMs":4000,"speed":1}]}
				""");
		assertEquals(java.util.Arrays.asList(ClipCameraLayout.CAMERA_ONLY, null, null, null),
				state.clipRegions().stream().map(ClipRegion::cameraLayout).toList());
	}

	@Test
	@DisplayName("keeps valid fades, clamps their duration and drops anything else")
	void keepsValidFades() {
		ProjectEditorState state = normalize("""
				{"clipRegions":[
				{"id":"a","startMs":0,"endMs":1000,"speed":1,
					"transitionOut":{"kind":"fade-black","durationMs":750}},
				{"id":"b","startMs":1000,"endMs":2000,"speed":1,
					"transitionOut":{"kind":"fade-black","durationMs":50}},
				{"id":"c","startMs":2000,"endMs":3000,"speed":1,
					"transitionOut":{"kind":"wipe","durationMs":500}},
				{"id":"d","startMs":3000,"endMs":4000,"speed":1}]}
				""");
		assertEquals(java.util.Arrays.asList(
				new ClipTransition(ClipTransitionKind.FADE_BLACK, 750),
				new ClipTransition(ClipTransitionKind.FADE_BLACK, 200),
				null,
				null),
				state.clipRegions().stream().map(ClipRegion::transitionOut).toList());
	}

	@Test
	@DisplayName("converts local paths to and from file URLs")
	void convertsFileUrls() {
		assertEquals("file:///Users/demo/My%20Clip.mp4",
				ProjectPersistence.toFileUrl("/Users/demo/My Clip.mp4"));
		assertEquals("/Users/demo/My Clip.mp4",
				ProjectPersistence.fromFileUrl("file:///Users/demo/My%20Clip.mp4"));
		assertEquals("/tmp/clip.mp4", ProjectPersistence.fromFileUrl("file:///tmp/clip.mp4"));
		assertEquals("http://127.0.0.1:1234/video?path=test",
				ProjectPersistence.fromFileUrl("http://127.0.0.1:1234/video?path=test"));
	}

	@Test
	@DisplayName("validates project data before opening a file")
	void validatesProjectData() {
		assertTrue(ProjectPersistence.validateProjectData(
				editor("{\"version\":2,\"videoPath\":\"/tmp/a.mp4\",\"editor\":{}}")));
		assertFalse(ProjectPersistence.validateProjectData(
				editor("{\"version\":2,\"videoPath\":\"\",\"editor\":{}}")));
		assertFalse(ProjectPersistence.validateProjectData(
				editor("{\"version\":2,\"videoPath\":\"/tmp/a.mp4\"}")));
		assertFalse(ProjectPersistence.validateProjectData(editor("[]")));
	}
}
