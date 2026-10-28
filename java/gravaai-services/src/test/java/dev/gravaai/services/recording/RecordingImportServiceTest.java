package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.RecordingImportService.Format;
import dev.gravaai.services.recording.RecordingImportService.MediaMeta;
import dev.gravaai.services.recording.RecordingImportService.NormalizePlan;
import dev.gravaai.services.recording.RecordingImportService.NormalizeSources;
import dev.gravaai.services.recording.RecordingImportService.WebcamSegmentPlan;

/** Tests the pure argument/merge helpers of the import pipeline (ffmpeg runs are a manual gate). */
class RecordingImportServiceTest {

	private static final MediaMeta BASE_META = new MediaMeta(1920, 1080, 30, 10, true);
	private static final Format FORMAT = new Format(1920, 1080, 30);

	@Test
	@DisplayName("lists the files one import produces and can discard")
	void listsImportOutputs() {
		assertEquals(
				List.of(
						"/rec/.gravaai-media/id-webcam.mp4",
						"/rec/.gravaai-media/id.gravaai-session.json",
						"/rec/.gravaai-media/id.mp4.webcam-ranges.json"),
				RecordingImportService.sequenceWebcamOutputs("/rec/.gravaai-media/id.mp4"));
		List<String> outputs = RecordingImportService.recordingImportOutputs("/rec/.gravaai-media/id.mp4");
		assertTrue(outputs.contains("/rec/.gravaai-media/id.mp4"));
		assertTrue(outputs.contains("/rec/.gravaai-media/id.system.wav"));
		assertTrue(outputs.contains("/rec/.gravaai-media/id.mic.wav"));
		assertTrue(outputs.contains("/rec/.gravaai-media/id.mp4.cursor.json"));
		assertTrue(outputs.contains("/rec/.gravaai-media/id.mp4.keys.json"));
	}

	@Test
	@DisplayName("normalizes an input to the sequence format with system and mic tracks")
	void buildsTheNormalizePlan() {
		NormalizePlan plan = RecordingImportService.planNormalize(
				BASE_META,
				new NormalizeSources(null, "/rec/added.mic.wav", Map.of("/rec/added.mic.wav", 250L)),
				FORMAT,
				false);
		assertEquals("[0:a:0]", plan.filterComplex().substring(0, "[0:a:0]".length()));
		assertTrue(plan.filterComplex().contains("[1:a:0]"));
		assertTrue(plan.filterComplex().contains("adelay=250:all=1"));
		assertTrue(plan.filterComplex().contains("atrim=duration=10[mic]"));
		assertTrue(plan.filterComplex().contains("scale=1920:1080"));
		assertTrue(plan.filterComplex().contains("fps=30"));
		assertEquals("[v]", plan.videoMap());
		int mapIndex = plan.args().indexOf("-map");
		assertEquals("[v]", plan.args().get(mapIndex + 1));
		assertTrue(plan.args().contains("pcm_s16le"));
		assertTrue(plan.args().contains("libx264"));
	}

	@Test
	@DisplayName("copies the existing sequence video instead of re-encoding it")
	void copiesSequenceVideo() {
		NormalizePlan plan = RecordingImportService.planNormalize(
				new MediaMeta(1920, 1080, 30, 10, false),
				new NormalizeSources(null, null, Map.of()),
				FORMAT,
				true);
		assertEquals("0:v:0", plan.videoMap());
		assertTrue(!plan.args().contains("libx264"));
		assertTrue(plan.args().contains("anullsrc=r=48000:cl=stereo"));
	}

	@Test
	@DisplayName("plans webcam segments with black spans for missing sides")
	void plansWebcamSegments() {
		WebcamSegmentPlan black = RecordingImportService.planWebcamSegment(
				null, 1280, 720, 0, 1.5, 1000, 1500, 0);
		assertTrue(black.args().contains("lavfi"));
		assertTrue(black.filter().contains("scale=1280:720"));
		assertTrue(black.args().contains("1.5"));

		WebcamSegmentPlan withSource = RecordingImportService.planWebcamSegment(
				"/rec/cam.mkv", 1280, 720, 200, 1.0, 0, 1000, 0.8);
		assertEquals("-i", withSource.args().get(0));
		assertEquals("/rec/cam.mkv", withSource.args().get(1));
		assertTrue(withSource.filter().contains("tpad=start_mode=add:start_duration=0.2"));
		assertTrue(withSource.filter().contains("trim=start=0,"));
	}

	@Test
	@DisplayName("rescales cursor samples into the combined format")
	void mergesCursorSamples() {
		List<CursorTelemetryPoint> base = List.of(
				new CursorTelemetryPoint(100, 0.25, 0.25, null, null));
		List<CursorTelemetryPoint> added = List.of(
				new CursorTelemetryPoint(200, 0, 0, CursorInteractionType.CLICK, null));
		List<CursorTelemetryPoint> merged = RecordingImportService.mergeCursorSamples(
				List.of(base, added),
				List.of(0L, 1000L),
				List.of(
						new MediaMeta(1920, 1080, 30, 1, true),
						new MediaMeta(1000, 1000, 30, 1, false)),
				FORMAT);
		assertEquals(2, merged.size());
		assertEquals(100.0, merged.get(0).timeMs(), 1e-9);
		assertEquals(0.25, merged.get(0).cx(), 1e-9);
		assertEquals(1200.0, merged.get(1).timeMs(), 1e-9);
		assertEquals(420.0 / 1920.0, merged.get(1).cx(), 1e-9);
		assertEquals(0.0, merged.get(1).cy(), 1e-9);
		assertEquals(CursorInteractionType.CLICK, merged.get(1).interactionType());
	}

	@Test
	@DisplayName("shifts key presses onto the combined clock")
	void mergesKeystrokes() {
		List<KeystrokeEvent> merged = RecordingImportService.mergeKeystrokeEvents(
				List.of(
						List.of(new KeystrokeEvent(300, "S", List.of(KeyModifier.CTRL))),
						List.of(new KeystrokeEvent(100, "Enter", List.of()))),
				List.of(0L, 1000L));
		assertEquals(300, merged.get(0).timeMs());
		assertEquals(1100, merged.get(1).timeMs());
		assertEquals("Enter", merged.get(1).key());
	}
}
