package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.services.recording.InkTelemetry.InkPoint;
import dev.gravaai.services.recording.InkTelemetry.InkRecording;
import dev.gravaai.services.recording.InkTelemetry.InkSession;
import dev.gravaai.services.recording.InkTelemetry.InkStroke;
import dev.gravaai.services.recording.InkTelemetry.InkTool;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.CursorTelemetryPoint;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.InkFocus;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.SuggestionParams;
import dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils.SuggestedZoomRegion;

/** One zoom per draw session, framing the ink. */
class InkZoomSuggestionTest {

	private static InkStroke stroke(long start, double x0, double y0, double x1, double y1) {
		return new InkStroke("s" + start, InkTool.PEN, "#ffffff", 0.02, start, start + 100, start + 2000,
				List.of(new InkPoint(start, x0, y0), new InkPoint(start + 50, x1, y1)));
	}

	@Test
	@DisplayName("a session's strokes become one focus box, moved into the clip's time")
	void focusFromRecording() {
		InkRecording ink = new InkRecording(
				List.of(new InkSession(2000, 5000), new InkSession(9000, 9500)),
				List.of(stroke(2100, 0.2, 0.3, 0.4, 0.5), stroke(3000, 0.6, 0.1, 0.5, 0.2)));
		List<InkFocus> focus = ZoomSuggestionUtils.inkFocus(ink, 1000, 6000);
		// The second session holds no stroke: nothing to frame.
		assertEquals(1, focus.size());
		InkFocus box = focus.getFirst();
		assertEquals(1000, box.startMs());
		assertEquals(4000, box.endMs());
		assertEquals(0.19, box.minX(), 1e-9);
		assertEquals(0.09, box.minY(), 1e-9);
		assertEquals(0.61, box.maxX(), 1e-9);
		assertEquals(0.51, box.maxY(), 1e-9);
	}

	@Test
	@DisplayName("a draw session is one zoom on its ink, and clicks elsewhere still get theirs")
	void oneZoomPerSession() {
		List<CursorTelemetryPoint> telemetry = List.of(
				new CursorTelemetryPoint(0, 0.1, 0.1, null, null),
				new CursorTelemetryPoint(15_000, 0.8, 0.8, "click", null),
				new CursorTelemetryPoint(15_100, 0.8, 0.8, "mouseup", null));
		SuggestionParams params = new SuggestionParams(telemetry, 30_000, 1000, List.of())
				.withInk(List.of(new InkFocus(4000, 8000, 0.2, 0.2, 0.4, 0.6)));
		List<SuggestedZoomRegion> zooms = ZoomSuggestionUtils.buildInteractionZoomSuggestions(params).suggestions();
		assertEquals(2, zooms.size());
		SuggestedZoomRegion ink = zooms.getFirst();
		assertEquals(new ZoomFocus(0.30000000000000004, 0.4), ink.focus());
		assertEquals(3500, ink.startMs(), 1e-9);
		assertEquals(8500, ink.endMs(), 1e-9);
		assertEquals(0.8, zooms.get(1).focus().cx(), 1e-9);
	}

	@Test
	@DisplayName("ink alone is enough, and an existing zoom over the session wins")
	void inkWithoutClicksAndReserved() {
		List<InkFocus> ink = List.of(new InkFocus(4000, 8000, 0.2, 0.2, 0.4, 0.6));
		assertEquals(1, ZoomSuggestionUtils.buildInteractionZoomSuggestions(
				new SuggestionParams(List.of(), 30_000, 1000, List.of()).withInk(ink)).suggestions().size());
		assertEquals(0, ZoomSuggestionUtils.buildInteractionZoomSuggestions(
				new SuggestionParams(List.of(), 30_000, 1000, List.of(new Span(5000, 6000))).withInk(ink))
				.suggestions().size());
	}
}
