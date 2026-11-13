package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.InkTelemetry.InkRecording;
import dev.gravaai.services.recording.InkTelemetry.InkSession;
import dev.gravaai.services.recording.InkTelemetry.InkStroke;
import dev.gravaai.services.recording.InkTelemetry.InkTool;
import dev.gravaai.services.recording.cursor.FrameBounds;

/** The live ink recorder: clock, normalization against the recorded frame, undo and clear. */
class InkLogTest {

	private final AtomicLong clock = new AtomicLong();
	/** This machine's layout: the landscape HDMI monitor at (0,635), the portrait one at (1920,0). */
	private final AtomicReference<Optional<FrameBounds>> frame =
			new AtomicReference<>(Optional.of(new FrameBounds(0, 635, 1920, 1080)));
	private final InkLog log = new InkLog(clock::get, frame::get);

	private String stroke(double x, double y) {
		return log.beginStroke(InkTool.PEN, "#FF3B30", 8, x, y).orElseThrow();
	}

	@Test
	@DisplayName("strokes are normalized to the recorded frame, not to the overlay window")
	void normalizesAgainstTheFrame() {
		log.beginSession();
		// The overlay was clamped below COSMIC's top panel; screen coordinates are unaffected.
		String id = stroke(960, 635 + 540);
		log.extendStroke(id, 1920, 635 + 1080);
		log.endStroke(id);
		InkStroke stroke = log.take().strokes().getFirst();
		assertEquals(0.5, stroke.points().get(0).x(), 1e-9);
		assertEquals(0.5, stroke.points().get(0).y(), 1e-9);
		assertEquals(1, stroke.points().get(1).x(), 1e-9);
		assertEquals(8.0 / 1080, stroke.width(), 1e-9);
		assertEquals("#ff3b30", stroke.color());
	}

	@Test
	@DisplayName("a portrait monitor and an area normalize against their own rectangle")
	void portraitAndArea() {
		frame.set(Optional.of(new FrameBounds(1920, 0, 1080, 1920)));
		log.beginSession();
		log.endStroke(stroke(1920 + 270, 480));
		frame.set(Optional.of(new FrameBounds(100, 200, 400, 300)));
		log.endStroke(stroke(300, 350));
		List<InkStroke> strokes = log.take().strokes();
		assertEquals(0.25, strokes.get(0).points().getFirst().x(), 1e-9);
		assertEquals(0.25, strokes.get(0).points().getFirst().y(), 1e-9);
		assertEquals(0.5, strokes.get(1).points().getFirst().x(), 1e-9);
		assertEquals(0.5, strokes.get(1).points().getFirst().y(), 1e-9);
	}

	@Test
	@DisplayName("undo, clear and leaving draw mode stamp when strokes left the screen")
	void clearedTimes() {
		clock.set(1000);
		log.beginSession();
		clock.set(1100);
		String undone = stroke(10, 700);
		clock.set(1200);
		log.endStroke(undone);
		clock.set(1300);
		log.removeStroke(undone);
		clock.set(1400);
		log.endStroke(stroke(20, 700));
		clock.set(1500);
		log.clearAll();
		clock.set(1600);
		log.endStroke(stroke(30, 700));
		clock.set(2000);
		log.endSession();
		InkRecording recording = log.take();
		assertEquals(List.of(new InkSession(1000, 2000)), recording.sessions());
		assertEquals(List.of(1300L, 1500L, 2000L),
				recording.strokes().stream().map(InkStroke::clearedAtMs).toList());
		assertEquals(1200, recording.strokes().getFirst().endMs());
	}

	@Test
	@DisplayName("no ink is recorded outside draw mode or without a frame, and take() starts over")
	void guards() {
		assertTrue(log.beginStroke(InkTool.PEN, "#ffffff", 4, 0, 700).isEmpty());
		log.beginSession();
		frame.set(Optional.empty());
		assertTrue(log.beginStroke(InkTool.PEN, "#ffffff", 4, 0, 700).isEmpty());
		// Stopping the recording mid-session closes the session at the current time.
		clock.set(500);
		InkRecording recording = log.take();
		assertEquals(List.of(new InkSession(0, 500)), recording.sessions());
		assertTrue(log.take().isEmpty());
	}
}
