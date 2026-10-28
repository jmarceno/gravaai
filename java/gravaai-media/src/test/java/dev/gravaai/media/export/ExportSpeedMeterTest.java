package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportSpeedMeterTest {

	@Test
	@DisplayName("publishes within a quarter second on a long export, not only every 1%")
	void publishesOnTime() {
		AtomicLong now = new AtomicLong();
		// 10 min at 60 fps: 1% is 360 frames, ~27 s at 13 fps.
		ExportSpeedMeter meter = new ExportSpeedMeter(36_000, now::get);
		assertNotNull(meter.frameWritten(0), "first frame publishes");
		now.addAndGet(77_000_000L); // ~13 fps
		assertNull(meter.frameWritten(1), "too soon");
		now.addAndGet(77_000_000L);
		assertNull(meter.frameWritten(2));
		now.addAndGet(77_000_000L);
		assertNull(meter.frameWritten(3));
		now.addAndGet(77_000_000L);
		ExportSpeedMeter.Reading reading = meter.frameWritten(4);
		assertNotNull(reading, "published after 250 ms");
		assertEquals(5, reading.framesDone());
	}

	@Test
	@DisplayName("measures the recent speed and the time left")
	void measuresRecentSpeed() {
		AtomicLong now = new AtomicLong();
		ExportSpeedMeter meter = new ExportSpeedMeter(1_000, now::get);
		ExportSpeedMeter.Reading reading = null;
		// 10 s at 10 fps, then 10 s at 50 fps: the reading follows the recent 50 fps.
		long frame = 0;
		for (int i = 0; i < 100; i++, frame++) {
			now.addAndGet(100_000_000L);
			meter.frameWritten(frame);
		}
		for (int i = 0; i < 500; i++, frame++) {
			now.addAndGet(20_000_000L);
			ExportSpeedMeter.Reading next = meter.frameWritten(frame);
			if (next != null) {
				reading = next;
			}
		}
		assertNotNull(reading);
		assertEquals(591, reading.framesDone(), "last 1% boundary before frame 600");
		assertEquals(50, reading.fps(), 0.5);
		assertEquals((1_000 - 591) / 50.0, reading.secondsRemaining(), 0.2);
	}
}
