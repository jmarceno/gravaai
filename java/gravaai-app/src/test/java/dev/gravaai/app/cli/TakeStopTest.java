package dev.gravaai.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The stop state machine of a headless take. */
class TakeStopTest {

	@Test
	@DisplayName("an elapsed duration stops the take as a duration stop")
	void durationStops() {
		try (TakeStop stop = TakeStop.unhooked(Duration.ofSeconds(5))) {
			assertEquals(TakeStop.Cause.DURATION, stop.awaitStop(Optional.of(0L)));
			assertEquals(TakeStop.Phase.STOPPING_ON_DURATION, stop.phase());
		}
	}

	@Test
	@DisplayName("a signal stops the take and holds the JVM until the take is finalised")
	void signalWaitsForFinalisation() throws Exception {
		TakeStop stop = TakeStop.unhooked(Duration.ofSeconds(10));
		CountDownLatch hookReturned = new CountDownLatch(1);
		Thread hook = Thread.ofPlatform().start(() -> {
			stop.onSignal();
			hookReturned.countDown();
		});

		assertEquals(TakeStop.Cause.SIGNAL, stop.awaitStop(Optional.empty()));
		assertEquals(TakeStop.Phase.STOPPING_ON_SIGNAL, stop.phase());
		// The hook must not return while the file is still being written.
		assertFalse(hookReturned.await(200, TimeUnit.MILLISECONDS));

		stop.close();
		assertTrue(hookReturned.await(5, TimeUnit.SECONDS));
		assertEquals(TakeStop.Phase.FINISHED, stop.phase());
		hook.join(5_000);
	}

	@Test
	@DisplayName("a signal after the duration elapsed does not change the cause")
	void lateSignalKeepsDuration() throws Exception {
		TakeStop stop = TakeStop.unhooked(Duration.ofMillis(100));
		assertEquals(TakeStop.Cause.DURATION, stop.awaitStop(Optional.of(0L)));
		stop.onSignal();
		assertEquals(TakeStop.Phase.STOPPING_ON_DURATION, stop.phase());
		stop.close();
	}
}
