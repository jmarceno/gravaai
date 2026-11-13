package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.InkTelemetry.InkPoint;
import dev.gravaai.services.recording.InkTelemetry.InkRecording;
import dev.gravaai.services.recording.InkTelemetry.InkSession;
import dev.gravaai.services.recording.InkTelemetry.InkStroke;
import dev.gravaai.services.recording.InkTelemetry.InkTool;

/** The {@code .ink.json} sidecar: its shape, its guards and what the editor does with it. */
class InkTelemetryTest {

	@TempDir
	Path dir;

	private static InkRecording sample() {
		InkStroke stroke = new InkStroke("s1", InkTool.PEN, "#ff3b30", 0.0074, 1200, 1500, 4000,
				List.of(new InkPoint(1200, 0.25, 0.5), new InkPoint(1350, 0.3, 0.55),
						new InkPoint(1500, 0.123456789, 0.987654321)));
		return new InkRecording(List.of(new InkSession(1000, 4000)), List.of(stroke));
	}

	@Test
	@DisplayName("a written sidecar reads back the same sessions and strokes")
	void roundTrip() throws IOException {
		String video = dir.resolve("take.mp4").toString();
		InkTelemetry.write(video, sample());
		assertTrue(Files.isRegularFile(dir.resolve("take.mp4.ink.json")));
		InkRecording read = InkTelemetry.read(video);
		assertEquals(sample().sessions(), read.sessions());
		InkStroke stroke = read.strokes().getFirst();
		assertEquals("s1", stroke.id());
		assertEquals(InkTool.PEN, stroke.tool());
		assertEquals(4000, stroke.clearedAtMs());
		assertEquals(3, stroke.points().size());
		// Five decimals are kept.
		assertEquals(0.12346, stroke.points().getLast().x(), 1e-9);
	}

	@Test
	@DisplayName("a take where the pen was never used leaves no file behind")
	void emptyRemovesTheFile() throws IOException {
		String video = dir.resolve("take.mp4").toString();
		InkTelemetry.write(video, sample());
		InkTelemetry.write(video, InkRecording.EMPTY);
		assertFalse(Files.exists(dir.resolve("take.mp4.ink.json")));
		assertTrue(InkTelemetry.read(video).isEmpty());
	}

	@Test
	@DisplayName("unusable entries are dropped and a future version reads as no ink")
	void guards() {
		var root = Json.parseJsonWithByteOrderMark("""
				{"version": 1,
				 "sessions": [{"startMs": 10, "endMs": 5}, {"startMs": 100, "endMs": 900}, "x"],
				 "strokes": [
				   {"id": "a", "tool": "pen", "color": "red", "width": 0.01, "startMs": 1, "endMs": 2,
				    "clearedAtMs": 3, "points": [[1, 0.1, 0.1]]},
				   {"id": "b", "tool": "laser", "color": "#ffffff", "width": 0.01, "startMs": 1,
				    "endMs": 2, "clearedAtMs": 3, "points": [[1, 0.1, 0.1]]},
				   {"id": "c", "tool": "pen", "color": "#FFFFFF", "width": 0.01, "startMs": 200,
				    "endMs": 300, "clearedAtMs": 900, "points": [[200, 1.7, -2], [250, "x", 1], [260]]}
				 ]}
				""");
		InkRecording parsed = InkTelemetry.parse(root);
		assertEquals(List.of(new InkSession(100, 900)), parsed.sessions());
		assertEquals(1, parsed.strokes().size());
		InkStroke kept = parsed.strokes().getFirst();
		assertEquals("#ffffff", kept.color());
		assertEquals(List.of(new InkPoint(200, 1, 0)), kept.points());

		var future = Json.parseJsonWithByteOrderMark("{\"version\": 2, \"sessions\": [{\"startMs\": 1, \"endMs\": 2}]}");
		assertTrue(InkTelemetry.parse(future).isEmpty());
	}

	@Test
	@DisplayName("shifting moves every time onto the video clock")
	void shifted() {
		InkRecording moved = sample().shifted(250);
		assertEquals(new InkSession(1250, 4250), moved.sessions().getFirst());
		InkStroke stroke = moved.strokes().getFirst();
		assertEquals(1450, stroke.startMs());
		assertEquals(4250, stroke.clearedAtMs());
		assertEquals(1450, stroke.points().getFirst().timeMs());
		assertEquals(1, sample().strokeCount(sample().sessions().getFirst()));
	}

	@Test
	@DisplayName("clicks inside a draw session become moves; clicks outside stay clicks")
	void clicksInSessionsAreMoves() {
		List<CursorTelemetryPoint> samples = List.of(
				new CursorTelemetryPoint(500, 0.1, 0.1, CursorInteractionType.CLICK, null),
				new CursorTelemetryPoint(1500, 0.2, 0.2, CursorInteractionType.CLICK, null),
				new CursorTelemetryPoint(1600, 0.2, 0.2, CursorInteractionType.MOUSEUP, null),
				new CursorTelemetryPoint(1700, 0.3, 0.3, CursorInteractionType.MOVE, null),
				new CursorTelemetryPoint(5000, 0.4, 0.4, CursorInteractionType.DOUBLE_CLICK, null));
		List<CursorTelemetryPoint> filtered = InkTelemetry.withoutClicksInSessions(samples,
				List.of(new InkSession(1000, 4000)));
		assertEquals(CursorInteractionType.CLICK, filtered.get(0).interactionType());
		assertEquals(CursorInteractionType.MOVE, filtered.get(1).interactionType());
		assertEquals(CursorInteractionType.MOVE, filtered.get(2).interactionType());
		assertEquals(0.2, filtered.get(1).cx());
		assertEquals(CursorInteractionType.DOUBLE_CLICK, filtered.get(4).interactionType());
		assertEquals(samples, InkTelemetry.withoutClicksInSessions(samples, List.of()));
	}
}
