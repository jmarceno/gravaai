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
import dev.gravaai.core.os.HostOs;
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
		assertEquals(8, ProjectPersistence.getDefaultBorderRadiusPercent(HostOs.MACOS));
		assertEquals(0, ProjectPersistence.getDefaultBorderRadiusPercent(HostOs.WINDOWS));
		assertEquals(0, ProjectPersistence.getDefaultBorderRadiusPercent(HostOs.LINUX));
	}

	@Test
	@DisplayName("classifies the os.name values a JVM reports")
	void classifiesHostOs() {
		assertEquals(HostOs.MACOS, HostOs.detect("Mac OS X"));
		assertEquals(HostOs.MACOS, HostOs.detect("Darwin"));
		assertEquals(HostOs.WINDOWS, HostOs.detect("Windows 11"));
		assertEquals(HostOs.LINUX, HostOs.detect("Linux"));
		assertEquals(HostOs.OTHER, HostOs.detect(null));
		assertEquals(HostOs.OTHER, HostOs.detect("FreeBSD"));
	}

	@Test
	@DisplayName("clamps radius percentages")
	void clampsRadiusPercentages() {
		assertEquals(50, normalize("{\"borderRadius\":75}").borderRadius());
	}

	@Test
	@DisplayName("reads loupe annotations with the lens magnification and defaults it")
	void readsLoupeAnnotations() {
		ProjectEditorState normalized = normalize("""
				{"annotationRegions":[
					{"id":"a1","startMs":100,"endMs":900,"type":"loupe","content":"",
						"loupeMagnification":3},
					{"id":"a2","startMs":1000,"endMs":1500,"type":"loupe","content":""}]}
				""");
		assertEquals(2, normalized.annotationRegions().size());
		assertEquals(AnnotationType.LOUPE, normalized.annotationRegions().get(0).type());
		assertEquals(3, normalized.annotationRegions().get(0).loupeMagnificationOrDefault());
		assertEquals(AnnotationRegion.LOUPE_DEFAULT_MAGNIFICATION,
				normalized.annotationRegions().get(1).loupeMagnificationOrDefault());
	}

	@Test
	@DisplayName("round-trips a loupe annotation through the project document writer")
	void roundTripsLoupeAnnotations() {
		AnnotationRegion loupe = new AnnotationRegion("a1", 100, 900, AnnotationType.LOUPE, "",
				null, null, AnnotationPosition.DEFAULT,
				new AnnotationSize(AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT,
						AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT),
				AnnotationTextStyle.defaultStyle(), 1, 0, null, null, null, 2.5,
				SpotlightData.DEFAULT);
		String json = ProjectDocument.writeAnnotationRegions(List.of(loupe)).toString();

		ProjectEditorState normalized = normalize("{\"annotationRegions\":" + json + "}");
		// The writer always persists blurIntensity (the loupe ignores it), matching every type.
		assertEquals(List.of(new AnnotationRegion("a1", 100, 900, AnnotationType.LOUPE, "",
				null, null, AnnotationPosition.DEFAULT,
				new AnnotationSize(AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT,
						AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT),
				AnnotationTextStyle.defaultStyle(), 1, 0, null,
				(double) AnnotationRegion.BLUR_ANNOTATION_STRENGTH, null, 2.5,
				SpotlightData.DEFAULT)),
				normalized.annotationRegions());
	}

	@Test
	@DisplayName("reads spotlight annotations with their payload and defaults it")
	void readsSpotlightAnnotations() {
		ProjectEditorState normalized = normalize("""
				{"annotationRegions":[
					{"id":"a1","startMs":100,"endMs":900,"type":"spotlight","content":"",
						"spotlightData":{"dimPercent":45,"featherPercent":10}},
					{"id":"a2","startMs":1000,"endMs":1500,"type":"spotlight","content":""}]}
				""");
		assertEquals(2, normalized.annotationRegions().size());
		assertEquals(new SpotlightData(45, 10),
				normalized.annotationRegions().get(0).spotlightData());
		assertEquals(SpotlightData.DEFAULT,
				normalized.annotationRegions().get(1).spotlightData());
	}

	@Test
	@DisplayName("keeps the spotlight payload only on spotlights and round-trips it")
	void spotlightPayloadStaysOnSpotlights() {
		AnnotationRegion spotlight = new AnnotationRegion("a1", 100, 900, AnnotationType.SPOTLIGHT,
				"", null, null, AnnotationPosition.DEFAULT, AnnotationSize.DEFAULT,
				AnnotationTextStyle.defaultStyle(), 1, 0, null, null, null, null,
				new SpotlightData(150, -5));
		String json = ProjectDocument.writeAnnotationRegions(List.of(spotlight)).toString();

		ProjectEditorState normalized = normalize("{\"annotationRegions\":" + json + "}");
		// Out-of-range values clamp at write time through the record, and the payload survives.
		assertEquals(List.of(new AnnotationRegion("a1", 100, 900, AnnotationType.SPOTLIGHT, "",
				null, null, AnnotationPosition.DEFAULT, AnnotationSize.DEFAULT,
				AnnotationTextStyle.defaultStyle(), 1, 0, null,
				(double) AnnotationRegion.BLUR_ANNOTATION_STRENGTH, null, null,
				new SpotlightData(100, 0))),
				normalized.annotationRegions());

		// A non-spotlight annotation never persists the payload, and reads back the defaults.
		AnnotationRegion text = new AnnotationRegion("a2", 100, 900, AnnotationType.TEXT, "Hi",
				"Hi", null, AnnotationPosition.DEFAULT, AnnotationSize.DEFAULT,
				AnnotationTextStyle.defaultStyle(), 1, 0, null, null, null, null,
				new SpotlightData(10, 10));
		String textJson = ProjectDocument.writeAnnotationRegions(List.of(text)).toString();
		assertFalse(textJson.contains("spotlightData"));
		ProjectEditorState textNormalized = normalize("{\"annotationRegions\":" + textJson + "}");
		assertEquals(SpotlightData.DEFAULT,
				textNormalized.annotationRegions().get(0).spotlightData());
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
	@DisplayName("defaults the webcam background effect to none and round-trips it")
	void webcamBackgroundEffectDefaultsOffAndRoundTrips() {
		// A project saved before the effect existed: nothing happens to the room behind the person.
		assertEquals(WebcamBackgroundEffect.NONE,
				normalize("{\"webcam\":{\"enabled\":true}}").webcam().backgroundEffect());
		// Explicit values round-trip.
		assertEquals(WebcamBackgroundEffect.BLUR,
				normalize("{\"webcam\":{\"backgroundEffect\":\"blur\"}}").webcam()
						.backgroundEffect());
		assertEquals(WebcamBackgroundEffect.REMOVE,
				normalize("{\"webcam\":{\"backgroundEffect\":\"remove\"}}").webcam()
						.backgroundEffect());
		// Unknown junk falls back to none.
		assertEquals(WebcamBackgroundEffect.NONE,
				normalize("{\"webcam\":{\"backgroundEffect\":\"pixelate\"}}").webcam()
						.backgroundEffect());
	}

	@Test
	@DisplayName("migrates the legacy webcam backgroundBlur boolean to the effect mode")
	void webcamBackgroundBlurMigratesToEffect() {
		// Projects written before the effect mode carry only the boolean; true used to mean blur.
		assertEquals(WebcamBackgroundEffect.BLUR,
				normalize("{\"webcam\":{\"backgroundBlur\":true}}").webcam().backgroundEffect());
		assertEquals(WebcamBackgroundEffect.NONE,
				normalize("{\"webcam\":{\"backgroundBlur\":false}}").webcam().backgroundEffect());
		// Non-boolean junk in the legacy key falls back to the default.
		assertEquals(WebcamBackgroundEffect.NONE,
				normalize("{\"webcam\":{\"backgroundBlur\":\"yes\"}}").webcam()
						.backgroundEffect());
		// An explicit effect wins over a stale legacy boolean.
		assertEquals(WebcamBackgroundEffect.REMOVE,
				normalize("{\"webcam\":{\"backgroundEffect\":\"remove\",\"backgroundBlur\":true}}")
						.webcam().backgroundEffect());
		assertEquals(WebcamBackgroundEffect.NONE,
				normalize("{\"webcam\":{\"backgroundEffect\":\"none\",\"backgroundBlur\":true}}")
						.webcam().backgroundEffect());
	}

	@Test
	@DisplayName("defaults the high-quality-mask-while-playing switch to off and round-trips it")
	void webcamHighQualityMaskWhilePlayingDefaultsOff() {
		// Off by default: playback uses the fast model, only stops and exports use the precise one.
		assertFalse(normalize("{\"webcam\":{\"enabled\":true}}").webcam()
				.highQualityMaskWhilePlaying());
		assertTrue(normalize("{\"webcam\":{\"highQualityMaskWhilePlaying\":true}}").webcam()
				.highQualityMaskWhilePlaying());
		assertFalse(normalize("{\"webcam\":{\"highQualityMaskWhilePlaying\":\"yes\"}}").webcam()
				.highQualityMaskWhilePlaying());
	}

	@Test
	@DisplayName("defaults smart framing to off and round-trips it (opt-in per project)")
	void webcamSmartFramingDefaultsOff() {
		// Off by default: the camera only dodges the cursor when the project asks for it.
		assertFalse(normalize("{\"webcam\":{\"enabled\":true}}").webcam().smartFraming());
		assertTrue(normalize("{\"webcam\":{\"smartFraming\":true}}").webcam().smartFraming());
		assertFalse(normalize("{\"webcam\":{\"smartFraming\":\"yes\"}}").webcam().smartFraming());
	}

	@Test
	@DisplayName("defaults eye contact to off at 15 degrees, round-trips it and clamps the angle")
	void webcamEyeContactDefaultsAndClamp() {
		WebcamOverlaySettings untouched = normalize("{\"webcam\":{\"enabled\":true}}").webcam();
		assertFalse(untouched.eyeContact());
		assertEquals(15, untouched.eyeContactAngle(), 1e-9);
		WebcamOverlaySettings on = normalize(
				"{\"webcam\":{\"eyeContact\":true,\"eyeContactAngle\":20}}").webcam();
		assertTrue(on.eyeContact());
		assertEquals(20, on.eyeContactAngle(), 1e-9);
		assertEquals(WebcamOverlaySettings.MAX_EYE_CONTACT_ANGLE, normalize(
				"{\"webcam\":{\"eyeContactAngle\":90}}").webcam().eyeContactAngle(), 1e-9);
		assertEquals(0, normalize("{\"webcam\":{\"eyeContactAngle\":-4}}").webcam()
				.eyeContactAngle(), 1e-9);
		assertEquals(15, normalize("{\"webcam\":{\"eyeContactAngle\":\"x\"}}").webcam()
				.eyeContactAngle(), 1e-9);
		assertFalse(normalize("{\"webcam\":{\"eyeContact\":\"yes\"}}").webcam().eyeContact());
	}

	@Test
	@DisplayName("defaults the webcam blur mask controls and clamps them to their slider ranges")
	void webcamMaskControlsDefaultAndClamp() {
		// A project saved before the controls existed keeps the shipped edge shape.
		WebcamOverlaySettings untouched = normalize("{\"webcam\":{\"enabled\":true}}").webcam();
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_PRECISION, untouched.maskPrecision(), 1e-9);
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_OFFSET, untouched.maskOffset(), 1e-9);

		// Explicit values round-trip.
		WebcamOverlaySettings shaped = normalize(
				"{\"webcam\":{\"maskPrecision\":0.75,\"maskOffset\":-2.5}}").webcam();
		assertEquals(0.75, shaped.maskPrecision(), 1e-9);
		assertEquals(-2.5, shaped.maskOffset(), 1e-9);

		// Out-of-range numbers clamp to the slider ends, and junk falls back to the defaults.
		WebcamOverlaySettings clamped = normalize(
				"{\"webcam\":{\"maskPrecision\":9,\"maskOffset\":-99}}").webcam();
		assertEquals(1, clamped.maskPrecision(), 1e-9);
		assertEquals(-10, clamped.maskOffset(), 1e-9);
		WebcamOverlaySettings junk = normalize(
				"{\"webcam\":{\"maskPrecision\":\"soft\",\"maskOffset\":\"in\"}}").webcam();
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_PRECISION, junk.maskPrecision(), 1e-9);
		assertEquals(WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_OFFSET, junk.maskOffset(), 1e-9);
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
		assertEquals("high", rawEditor.get("exportQuality").asString());
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
		// Known non-default layouts move onto the Camera layout track; the rest drop.
		assertEquals(java.util.Arrays.asList(null, null, null, null),
				state.clipRegions().stream().map(ClipRegion::cameraLayout).toList());
		assertEquals(List.of(new CameraLayoutSegment("camera-a", 0, 1000,
				new CameraLayout.CameraOnly())), state.cameraLayoutSegments());
	}

	@Test
	@DisplayName("reads the camera layout switch wires; anything else is a cut")
	void keepsCameraLayoutTransitions() {
		ProjectEditorState state = normalize("""
				{"clipRegions":[{"id":"a","startMs":0,"endMs":4000,"speed":1}],
				 "cameraLayoutSegments":[
					{"id":"camera-1","startMs":0,"endMs":1000,"layout":"split-right",
					 "transition":"smooth","transitionOut":"smooth"},
					{"id":"camera-2","startMs":1000,"endMs":2000,"layout":"bottom-right",
					 "transition":"bogus","transitionOut":"instant"},
					{"id":"camera-3","startMs":2000,"endMs":3000,"layout":"top-left"}]}
				""");
		assertEquals(List.of(
				new CameraLayoutSegment("camera-1", 0, 1000,
						new CameraLayout.Position(WebcamPositionPreset.SPLIT_RIGHT),
						CameraLayoutStyle.INHERIT,
						CameraLayoutSegment.Transition.SMOOTH,
						CameraLayoutSegment.Transition.SMOOTH),
				new CameraLayoutSegment("camera-2", 1000, 2000,
						new CameraLayout.Position(WebcamPositionPreset.BOTTOM_RIGHT)),
				new CameraLayoutSegment("camera-3", 2000, 3000,
						new CameraLayout.Position(WebcamPositionPreset.TOP_LEFT))),
				state.cameraLayoutSegments());
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

	@Test
	@DisplayName("clickSound defaults to off / crisp / 0.8 when missing")
	void clickSoundDefaults() {
		assertEquals(ClickSoundSettings.DEFAULT, normalize("{}").clickSound());
		assertEquals(new ClickSoundSettings(false, ClickSoundStyle.CRISP, 0.8),
				normalize("{\"clickSound\":\"not-an-object\"}").clickSound());
	}

	@Test
	@DisplayName("clickSound normalises an unknown sound wire to crisp")
	void clickSoundUnknownWireBecomesCrisp() {
		ProjectEditorState state = normalize("{\"clickSound\":{\"sound\":\"whoosh\"}}");
		assertEquals(ClickSoundStyle.CRISP, state.clickSound().sound());
		assertEquals(ClickSoundStyle.SOFT,
				normalize("{\"clickSound\":{\"sound\":\"soft\"}}").clickSound().sound());
	}

	@Test
	@DisplayName("clickSound clamps volume to 0..1")
	void clickSoundVolumeClamp() {
		assertEquals(1, normalize("{\"clickSound\":{\"volume\":3}}").clickSound().volume());
		assertEquals(0, normalize("{\"clickSound\":{\"volume\":-2}}").clickSound().volume());
		assertEquals(0.8, normalize("{\"clickSound\":{\"volume\":\"loud\"}}")
				.clickSound().volume());
	}

	@Test
	@DisplayName("round-trips the nested click sound through the project document writer")
	void roundTripsClickSound() {
		ClickSoundSettings settings = new ClickSoundSettings(true, ClickSoundStyle.TICK, 0.4);
		String json = ProjectDocument.writeClickSound(settings).toString();
		ProjectEditorState state = normalize("{\"clickSound\":" + json + "}");
		assertEquals(settings, state.clickSound());
	}

	@Test
	@DisplayName("keySound defaults, unknown wire, volume clamp and round trip")
	void keySoundNormalisation() {
		assertEquals(KeySoundSettings.DEFAULT, normalize("{}").keySound());
		assertEquals(KeySoundStyle.CLACK,
				normalize("{\"keySound\":{\"sound\":\"whoosh\"}}").keySound().sound());
		assertEquals(KeySoundStyle.TAP,
				normalize("{\"keySound\":{\"sound\":\"tap\"}}").keySound().sound());
		assertEquals(1, normalize("{\"keySound\":{\"volume\":3}}").keySound().volume());
		KeySoundSettings settings = new KeySoundSettings(true, KeySoundStyle.SOFT, 0.3);
		String json = ProjectDocument.writeKeySound(settings).toString();
		assertEquals(settings, normalize("{\"keySound\":" + json + "}").keySound());
	}
}
