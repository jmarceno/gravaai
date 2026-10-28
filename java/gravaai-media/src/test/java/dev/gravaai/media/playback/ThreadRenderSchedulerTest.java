package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Smoke test for the production 60 Hz render scheduler. */
class ThreadRenderSchedulerTest {

	@Test
	@DisplayName("ticks on its own thread and stops")
	void ticksAndStops() throws InterruptedException {
		ThreadRenderScheduler scheduler = new ThreadRenderScheduler(200);
		AtomicInteger ticks = new AtomicInteger();
		scheduler.start(ticks::incrementAndGet);
		long deadline = System.currentTimeMillis() + 2_000;
		while (ticks.get() < 3 && System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		scheduler.stop();
		assertTrue(ticks.get() >= 3);
		int afterStop = ticks.get();
		Thread.sleep(20);
		assertEquals(afterStop, ticks.get());
	}

	@Test
	@DisplayName("rejects an invalid frame rate")
	void rejectsInvalidFrameRate() {
		assertThrows(IllegalArgumentException.class, () -> new ThreadRenderScheduler(0));
		assertThrows(IllegalArgumentException.class, () -> new ThreadRenderScheduler(Double.NaN));
	}
}
