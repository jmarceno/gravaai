package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Ported from {@code electron/ipc/cursor/telemetry.test.ts}. */
class CursorTelemetryTest {

	private static final long START = 1_000;

	private CursorCaptureClock clock;

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		clock = new CursorCaptureClock();
		clock.setStartTimeMs(START);
		clock.reset();
	}

	@Test
	@DisplayName("subtracts paused time from elapsed cursor timestamps")
	void subtractsPausedTimeFromElapsedTimestamps() {
		assertEquals(120, clock.getCursorCaptureElapsedMs(1_120));

		clock.pauseCursorCapture(1_200);
		assertEquals(200, clock.getCursorCaptureElapsedMs(1_450));

		clock.resumeCursorCapture(1_700);
		assertEquals(400, clock.getCursorCaptureElapsedMs(1_900));
	}

	@Test
	@DisplayName("ignores duplicate pause or resume transitions")
	void ignoresDuplicatePauseOrResumeTransitions() {
		clock.pauseCursorCapture(1_150);
		clock.pauseCursorCapture(1_250);
		clock.resumeCursorCapture(1_500);
		clock.resumeCursorCapture(1_650);

		assertEquals(550, clock.getCursorCaptureElapsedMs(1_900));
	}

	@Test
	@DisplayName("drops cursor samples captured after the renderer pause boundary")
	void dropsSamplesAfterThePauseBoundary() {
		List<CursorTelemetryPoint> samples = new ArrayList<>();
		CursorTelemetry.pushSample(samples, 0.1, 0.1, 120, CursorInteractionType.MOVE, null);
		CursorTelemetry.pushSample(samples, 0.2, 0.2, 205, CursorInteractionType.MOVE, null);
		CursorTelemetry.pushSample(samples, 0.3, 0.3, 260, CursorInteractionType.MOVE, null);

		List<CursorTelemetryPoint> kept = clock.pauseCursorCaptureAtBoundary(1_200, samples);

		assertEquals(200, clock.getCursorCaptureElapsedMs(1_500));
		assertEquals(List.of(120.0), kept.stream().map(CursorTelemetryPoint::timeMs).toList());

		clock.resumeCursorCapture(1_700);
		assertEquals(400, clock.getCursorCaptureElapsedMs(1_900));
	}

	@Test
	@DisplayName("normalizes cursor telemetry samples before persisting them")
	void normalizesSamplesBeforePersisting() throws IOException {
		JsonNode raw = Json.parseJsonWithByteOrderMark("""
				[
				  {"timeMs": 30, "cx": 2, "cy": -1, "interactionType": "click", "cursorType": "pointer"},
				  {"timeMs": -10, "cx": null, "cy": 0.2, "interactionType": "drag", "cursorType": "ibeam"},
				  {"timeMs": 10, "cx": 0.25, "cy": 0.75, "interactionType": "move", "cursorType": "text"}
				]
				""");

		List<CursorTelemetryPoint> samples = CursorTelemetry.normalizeCursorTelemetrySamples(raw);

		assertEquals(
				List.of(
						new CursorTelemetryPoint(0, 0.5, 0.2, null, null),
						new CursorTelemetryPoint(10, 0.25, 0.75, CursorInteractionType.MOVE, CursorVisualType.TEXT),
						new CursorTelemetryPoint(30, 1, 0, CursorInteractionType.CLICK, CursorVisualType.POINTER)),
				samples);

		String videoPath = dir.resolve("recording.mp4").toString();
		List<CursorTelemetryPoint> written = CursorTelemetry.writeCursorTelemetry(videoPath, samples);

		assertEquals(samples, written);
		String telemetryPath = RecordingPaths.getTelemetryPathForVideo(videoPath);
		assertEquals(videoPath + ".cursor.json", telemetryPath);
		JsonNode writtenJson = Json.parseJsonWithByteOrderMark(
				Files.readString(Path.of(telemetryPath), StandardCharsets.UTF_8));
		assertEquals(CursorTelemetry.CURSOR_TELEMETRY_VERSION, writtenJson.get("version").asInt());
		assertEquals(
				Json.stringify(CursorTelemetry.samplesToJson(samples)),
				Json.stringify(writtenJson.get("samples")));
		// Unset markers are omitted, like JSON.stringify does for undefined.
		assertFalse(writtenJson.get("samples").get(0).has("interactionType"));
		assertFalse(writtenJson.get("samples").get(0).has("cursorType"));
	}

	@Test
	@DisplayName("removes the sidecar when saving an empty cursor telemetry payload")
	void removesTheSidecarForEmptyPayload() throws IOException {
		String videoPath = dir.resolve("recording.mp4").toString();
		Path telemetryPath = Path.of(RecordingPaths.getTelemetryPathForVideo(videoPath));
		Files.writeString(telemetryPath, "{}", StandardCharsets.UTF_8);

		CursorTelemetry.writeCursorTelemetry(videoPath, List.of());

		assertFalse(Files.exists(telemetryPath));
	}

	@Test
	@DisplayName("merges fresh samples after the pending snapshot without duplicating times")
	void mergesFreshSamplesAfterThePendingSnapshot() {
		List<CursorTelemetryPoint> pending = List.of(new CursorTelemetryPoint(100, 0.1, 0.1, null, null));
		List<CursorTelemetryPoint> active = List.of(
				new CursorTelemetryPoint(100, 0.2, 0.2, null, null),
				new CursorTelemetryPoint(160, 0.3, 0.3, null, null));

		List<CursorTelemetryPoint> merged = CursorTelemetry.mergeForPersistence(active, pending);

		assertEquals(List.of(100.0, 160.0), merged.stream().map(CursorTelemetryPoint::timeMs).toList());
		assertEquals(0.3, merged.get(1).cx());
	}
}
