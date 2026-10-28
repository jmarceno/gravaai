package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the elapsed/format maths in useRecordingTimer.ts (used by the HUD recording state). */
class RecordingTimerTest {

	private final AtomicLong now = new AtomicLong(1_000);
	private final RecordingTimer timer = new RecordingTimer(now::get);

	@DisplayName("formatTime pads minutes and seconds to two digits (mm:ss)")
	@Test
	void formats() {
		assertEquals("00:00", RecordingTimer.format(0));
		assertEquals("00:09", RecordingTimer.format(9));
		assertEquals("01:05", RecordingTimer.format(65));
		assertEquals("59:59", RecordingTimer.format(3599));
		assertEquals("100:00", RecordingTimer.format(6000));
	}

	@DisplayName("elapsed seconds count up while recording and stop while paused")
	@Test
	void pauseAccounting() {
		timer.setRecording(true);
		assertEquals(0, timer.elapsedSeconds());
		now.addAndGet(5_000);
		assertEquals(5, timer.elapsedSeconds());
		timer.setPaused(true);
		now.addAndGet(3_000);
		assertEquals(5, timer.elapsedSeconds());
		timer.setPaused(false);
		now.addAndGet(2_000);
		assertEquals(7, timer.elapsedSeconds());
	}

	@DisplayName("stopping the recording resets the timer to zero")
	@Test
	void resets() {
		timer.setRecording(true);
		now.addAndGet(4_200);
		assertEquals(4, timer.elapsedSeconds());
		timer.setRecording(false);
		assertEquals(0, timer.elapsedSeconds());
		timer.setRecording(true);
		assertEquals(0, timer.elapsedSeconds());
	}
}
